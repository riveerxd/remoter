use std::net::SocketAddr;
use std::path::{Path, PathBuf};
use std::process::ExitCode;
use std::sync::atomic::AtomicBool;
use std::sync::{Arc, Mutex};

use remoter_auth::{Devices, Verifier};
use remoterd::agent::AgentClient;
use remoterd::audit::{Audit, ROTATE_BYTES};
use remoterd::config::Config;
use remoterd::state::{App, Settings};
use tokio::sync::{mpsc, watch};
use tracing_subscriber::layer::SubscriberExt;
use tracing_subscriber::util::SubscriberInitExt;

// nonces live 30s, 10 mutations a minute per device: never fills
const REPLAY_CAP: usize = 4096;

fn main() -> ExitCode {
    let args: Vec<String> = std::env::args().skip(1).collect();
    let config_path = match args.as_slice() {
        [flag, p] if flag == "--config" => PathBuf::from(p),
        _ => {
            eprintln!("usage: remoterd --config /etc/remoter/config.toml");
            return ExitCode::from(2);
        }
    };
    init_logging();
    #[cfg(feature = "e2e-test")]
    tracing::warn!("{}: test knobs on", remoterd::E2E_MARKER);
    match run(&config_path) {
        Ok(()) => ExitCode::SUCCESS,
        Err(e) => {
            tracing::error!("{e}");
            eprintln!("remoterd: {e}");
            ExitCode::FAILURE
        }
    }
}

fn init_logging() {
    let reg = tracing_subscriber::registry();
    // under systemd stderr already lands in the journal, so both would log every line twice
    if let Some(j) = std::env::var_os("JOURNAL_STREAM").and_then(|_| tracing_journald::layer().ok()) {
        reg.with(quiet(j)).init();
    } else {
        reg.with(quiet(tracing_subscriber::fmt::layer().with_writer(std::io::stderr).with_target(false))).init();
    }
}

fn quiet<S: tracing::Subscriber, L: tracing_subscriber::Layer<S>>(l: L) -> tracing_subscriber::filter::Filtered<L, tracing_subscriber::filter::LevelFilter, S> {
    l.with_filter(tracing_subscriber::filter::LevelFilter::INFO)
}

/// Root, unless an e2e build says otherwise.
fn trusted_owner() -> u32 {
    #[cfg(feature = "e2e-test")]
    if let Some(uid) = std::env::var("REMOTER_E2E_TRUSTED_OWNER").ok().and_then(|v| v.parse().ok()) {
        return uid;
    }
    0
}

/// Google's roots, plus the test root in e2e builds.
fn root_pins() -> Vec<[u8; 32]> {
    #[allow(unused_mut)]
    let mut pins = remoter_attest::GOOGLE_ROOT_SPKI_SHA256.to_vec();
    #[cfg(feature = "e2e-test")]
    if let Some(pin) = std::env::var("REMOTER_E2E_EXTRA_ROOT_PIN").ok().and_then(|h| remoterd::attest::parse_digest(&h).ok()) {
        pins.push(pin);
    }
    pins
}

fn admin_uid() -> u32 {
    #[cfg(feature = "e2e-test")]
    if let Some(uid) = std::env::var("REMOTER_E2E_ADMIN_UID").ok().and_then(|v| v.parse().ok()) {
        return uid;
    }
    0
}

fn run(config_path: &Path) -> Result<(), String> {
    let cfg = Config::load(config_path)?;
    let exe = std::env::current_exe().map_err(|e| e.to_string())?;
    let trust: Vec<&Path> = vec![config_path, &cfg.paths.devices, &cfg.paths.server_key, &cfg.paths.server_cert, &exe];
    let mut problems = remoter_auth::files::check_trust_files(&trust, trusted_owner());
    // our own state dir, so owned by us rather than root
    // SAFETY: geteuid has no preconditions.
    let me = unsafe { libc::geteuid() };
    problems.extend(remoter_auth::files::check_state_dir(&cfg.paths.state_dir, me, trusted_owner()));
    if !problems.is_empty() {
        return Err(format!("refusing to start, trust files are loose: {}", problems.join("; ")));
    }
    let agent_uid = remoterd::uid_of(&cfg.paths.agent_user).ok_or_else(|| format!("no user {}", cfg.paths.agent_user))?;
    let (chain, key) = remoterd::tls::load_pem(&cfg.paths.server_cert, &cfg.paths.server_key)?;
    let all = Devices::load(&cfg.paths.devices)?;
    let live = remoter_auth::unpaired::effective(&all, &cfg.unpaired_file());
    tracing::info!(devices = live.len(), "loaded devices");
    let (devices_tx, devices_rx) = watch::channel(Arc::new(live));
    let server_fp: [u8; 32] = remoterd::tls::spki_sha256(&chain[0]).ok_or("server certificate doesn't parse")?;
    let pairing_tls = remoterd::tls::pairing_config(chain.clone(), key.clone_key()).map_err(|e| e.to_string())?;
    let tls = remoterd::tls::server_config(devices_rx, chain, key).map_err(|e| e.to_string())?;
    let policy = cfg.attestation.policy()?;
    let audit = Audit::open(cfg.audit_file(), ROTATE_BYTES).map_err(|e| format!("audit: {e}"))?;
    let hostname = remoterd::hostname();
    let app = Arc::new(App {
        attest: Mutex::new(remoterd::attest::AttestState::open(&cfg.paths.state_dir, cfg.attestation.status_max_age_days, root_pins())),
        policy,
        fresh_ms: cfg.attestation.fresh_hours as i64 * 3_600_000,
        pair: Mutex::default(),
        pairing: remoterd::state::Pairing {
            device: cfg.net.listen_device.clone(),
            addr: SocketAddr::from((cfg.net.listen_addr, cfg.net.pair_port)),
            public_host: cfg.net.listen_addr,
            api_port: cfg.net.port,
            server_fp,
            tls: Arc::new(pairing_tls),
        },
        settings: Settings {
            devices_file: cfg.paths.devices.clone(),
            unpaired_file: cfg.unpaired_file(),
            locked_flag: cfg.locked_flag(),
            power_supply_dir: cfg.paths.power_supply_dir.clone(),
            hostname,
            clock_window_ms: cfg.net.clock_window_s as i64 * 1000,
        },
        devices: devices_tx,
        verifier: Verifier::new(cfg.net.clock_window_s as i64 * 1000, None, REPLAY_CAP),
        limits: Mutex::default(),
        autolock: Mutex::default(),
        locked: AtomicBool::new(cfg.locked_flag().exists()),
        audit: Mutex::new(audit),
        agent: AgentClient { socket: cfg.agent_socket.clone(), agent_uid },
        tails: Mutex::default(),
        handshake_log: Mutex::default(),
    });
    let rt = tokio::runtime::Builder::new_multi_thread().enable_all().build().map_err(|e| e.to_string())?;
    rt.block_on(async move {
        let (conn_tx, conn_rx) = mpsc::channel(64);
        let addr = SocketAddr::from((cfg.net.listen_addr, cfg.net.port));
        tokio::spawn(remoterd::listener::run(cfg.net.listen_device.clone(), addr, conn_tx));
        match remoterd::admin::bind(&cfg.paths.admin_socket) {
            Ok(l) => {
                tokio::spawn(remoterd::admin::serve(app.clone(), l, admin_uid()));
            }
            Err(e) => tracing::error!(%e, "admin socket unavailable, pairing and lock off won't work"),
        }
        let reload_app = app.clone();
        tokio::spawn(async move {
            let Ok(mut hup) = tokio::signal::unix::signal(tokio::signal::unix::SignalKind::hangup()) else { return };
            while hup.recv().await.is_some() {
                match reload_app.reload_devices() {
                    Ok(n) => tracing::info!(devices = n, "reloaded devices"),
                    Err(e) => tracing::error!(%e, "reload failed, keeping the old list"),
                }
            }
        });
        remoterd::serve::serve(app, Arc::new(tls), conn_rx).await;
    });
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::io::Write;

    #[derive(Clone, Default)]
    struct Buf(Arc<Mutex<Vec<u8>>>);

    impl Write for Buf {
        fn write(&mut self, b: &[u8]) -> std::io::Result<usize> {
            self.0.lock().expect("lock").extend_from_slice(b);
            Ok(b.len())
        }
        fn flush(&mut self) -> std::io::Result<()> {
            Ok(())
        }
    }

    // h2 logged every frame of the phone's traffic, about 8000 lines an hour
    #[test]
    fn library_chatter_stays_out() {
        let buf = Buf::default();
        let w = buf.clone();
        let sub = tracing_subscriber::registry().with(quiet(tracing_subscriber::fmt::layer().with_writer(move || w.clone())));
        tracing::subscriber::with_default(sub, || {
            tracing::trace!(target: "h2::codec", "encoding SETTINGS");
            tracing::debug!(target: "h2::proto", "Connection::poll");
            tracing::info!("listening");
        });
        let out = String::from_utf8(buf.0.lock().expect("lock").clone()).expect("utf8");
        assert!(out.contains("listening"), "{out}");
        assert!(!out.contains("SETTINGS") && !out.contains("Connection::poll"), "{out}");
    }
}
