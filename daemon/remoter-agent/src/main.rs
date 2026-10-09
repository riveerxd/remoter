use std::os::unix::fs::{FileTypeExt, PermissionsExt};
use std::path::{Path, PathBuf};
use std::process::ExitCode;
use std::sync::{Arc, Mutex};
use std::time::Duration;

use remoter_agent::config::{AgentConfig, OpenIn};
use remoter_agent::guard::Home;
use remoter_agent::launcher::{Launcher, TmuxLauncher, WindowLauncher};
use remoter_agent::notify::{Notifier, NotifySend};
use remoter_agent::recent::History;
use remoter_agent::server::{AgentCtx, serve};
use remoter_agent::sessions::{Sessions, SessionsConfig};
use remoter_agent::transcripts::Transcripts;
use remoter_agent::trust::Trust;
use remoter_auth::Verifier;

// 10 mutations a minute per device, nonces live 30s: plenty
const REPLAY_CAP: usize = 4096;

fn main() -> ExitCode {
    let args: Vec<String> = std::env::args().skip(1).collect();
    let config_path = match args.as_slice() {
        [flag, p] if flag == "--config" => PathBuf::from(p),
        _ => {
            eprintln!("usage: remoter-agent --config /etc/remoter/config.toml");
            return ExitCode::from(2);
        }
    };
    #[cfg(feature = "e2e-test")]
    eprintln!("{}: test knobs on", remoter_agent::launcher::E2E_MARKER);
    match run(&config_path) {
        Ok(()) => ExitCode::SUCCESS,
        Err(e) => {
            eprintln!("remoter-agent: {e}");
            ExitCode::FAILURE
        }
    }
}

fn trusted_owner() -> u32 {
    #[cfg(feature = "e2e-test")]
    if let Some(uid) = std::env::var("REMOTER_E2E_TRUSTED_OWNER").ok().and_then(|v| v.parse().ok()) {
        return uid;
    }
    0
}

fn launcher(cfg: &AgentConfig) -> Box<dyn Launcher> {
    #[cfg(feature = "e2e-test")]
    if std::env::var_os("REMOTER_E2E_HEADLESS").is_some() {
        return Box::new(remoter_agent::launcher::HeadlessLauncher { exec_bin: cfg.exec_bin.clone() });
    }
    if cfg.open_in == OpenIn::Tmux {
        return Box::new(TmuxLauncher { tmux_bin: cfg.tmux_bin.clone(), socket: cfg.tmux_socket.clone(), exec_bin: cfg.exec_bin.clone() });
    }
    Box::new(WindowLauncher {
        terminal: cfg.terminal(),
        wm: cfg.wm(),
        exec_bin: cfg.exec_bin.clone(),
        workspace: cfg.workspace,
    })
}

fn run(config_path: &Path) -> Result<(), String> {
    let cfg = AgentConfig::load(config_path)?;
    let exe = std::env::current_exe().map_err(|e| e.to_string())?;
    let trust_files: Vec<&Path> = vec![config_path, &cfg.devices, &cfg.exec_bin, &exe];
    let problems = remoter_auth::files::check_trust_files(&trust_files, trusted_owner());
    if !problems.is_empty() {
        return Err(format!("refusing to start, trust files are loose: {}", problems.join("; ")));
    }
    let peer_uid = remoter_agent::peer::uid_of(&cfg.remoterd_user).ok_or_else(|| format!("no user {}", cfg.remoterd_user))?;
    let runtime = match &cfg.runtime_dir {
        Some(p) => p.clone(),
        None => PathBuf::from(std::env::var_os("XDG_RUNTIME_DIR").ok_or("XDG_RUNTIME_DIR not set")?).join("remoter"),
    };
    let history = Arc::new(History::open(
        cfg.history_file.clone().unwrap_or_else(|| cfg.home.join(".local/state/remoter/recent.json")),
    ));
    let claude_json = cfg.home.join(".claude.json");
    let transcripts = Arc::new(Transcripts::new(cfg.claude_dir.clone().unwrap_or_else(|| cfg.home.join(".claude"))));
    let open_home = || Home::open(&cfg.home).map_err(|e| format!("{}: {e}", cfg.home.display()));
    let sessions = Sessions::new(
        SessionsConfig {
            cwd_deny: cfg.cwd_deny.clone(),
            claude_bin: cfg.claude_bin.clone(),
            git_bin: cfg.git_bin.clone(),
            max_sessions: cfg.max_sessions,
            runtime_base: runtime,
            locked_flag: cfg.locked_flag(),
            ready_timeout: Duration::from_secs(cfg.ready_timeout_s),
            exited_ttl: Duration::from_secs(3600),
            history: Some(history.clone()),
            transcripts: Some(transcripts),
        },
        open_home()?,
        Trust::new(claude_json.clone()),
        launcher(&cfg),
    )
    .map_err(|e| e.to_string())?;
    let notifier: Box<dyn Notifier> = Box::new(NotifySend { bin: cfg.notify_bin.clone() });
    let ctx = Arc::new(AgentCtx {
        home: open_home()?,
        trust: Trust::new(claude_json),
        sessions,
        cwd_deny: cfg.cwd_deny.clone(),
        search_skip: cfg.search_skip.clone(),
        max_sessions: cfg.max_sessions,
        git_bin: cfg.git_bin.clone(),
        devices_file: cfg.devices.clone(),
        unpaired_file: cfg.unpaired_file(),
        locked_flag: cfg.locked_flag(),
        // refuse anything dated before our start, the fresh replay store knows nothing
        verifier: Verifier::for_agent(cfg.clock_window_s as i64 * 1000, remoter_proto::local::now_ms(), REPLAY_CAP),
        history: Some(history),
        notifier,
        peer_uid,
        agent_state: cfg.agent_state(),
        autolock: Mutex::default(),
        procs: remoter_agent::procs::Procs::system(),
    });
    watch_lock(cfg.locked_flag(), cfg.agent_state(), NotifySend { bin: cfg.notify_bin.clone() });
    let listener = bind_socket(&cfg.socket)?;
    serve(listener, ctx).map_err(|e| e.to_string())
}

/// remoterd has no desktop to notify on, so we watch its flag (and copy it
/// into our own sticky lock while at it).
fn watch_lock(flag: PathBuf, state: PathBuf, n: NotifySend) {
    std::thread::spawn(move || {
        let mut was = flag.exists();
        loop {
            remoter_agent::server::adopt_remoterd_lock(&state, &flag);
            std::thread::sleep(Duration::from_secs(2));
            let now = flag.exists();
            if now && !was {
                n.notify(true, "remoter locked this laptop. Run sudo remoterctl lock off once you know why.");
            }
            was = now;
        }
    });
}

fn bind_socket(path: &Path) -> Result<std::os::unix::net::UnixListener, String> {
    // only ever remove a stale socket
    if let Ok(m) = std::fs::symlink_metadata(path) {
        if !m.file_type().is_socket() {
            return Err(format!("{} exists and isn't a socket", path.display()));
        }
        std::fs::remove_file(path).map_err(|e| format!("{}: {e}", path.display()))?;
    }
    let l = std::os::unix::net::UnixListener::bind(path).map_err(|e| format!("{}: {e}", path.display()))?;
    // dir is setgid remoterd (tmpfiles 2750), so the group is already right
    std::fs::set_permissions(path, std::fs::Permissions::from_mode(0o660)).map_err(|e| e.to_string())?;
    Ok(l)
}
