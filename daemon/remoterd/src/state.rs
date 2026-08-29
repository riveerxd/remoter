use std::collections::HashMap;
use std::os::unix::fs::PermissionsExt;
use std::path::PathBuf;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex, MutexGuard};
use std::time::Instant;

use remoter_auth::{Devices, LockWeight, Verifier};
use remoter_proto::api::AuditEntry;
use tokio::sync::{broadcast, watch};

use crate::agent::AgentClient;
use crate::audit::Audit;
use crate::limits::{AutoLock, Kind, Limits};

pub fn lock<T>(m: &Mutex<T>) -> MutexGuard<'_, T> {
    m.lock().unwrap_or_else(|p| p.into_inner())
}

pub struct Settings {
    pub devices_file: PathBuf,
    pub unpaired_file: PathBuf,
    pub locked_flag: PathBuf,
    pub power_supply_dir: PathBuf,
    pub hostname: String,
    pub clock_window_ms: i64,
}

pub struct Pairing {
    pub device: String,
    /// bind address
    pub addr: std::net::SocketAddr,
    /// what the link tells the phone
    pub public_host: std::net::Ipv4Addr,
    pub api_port: u16,
    /// the link's `fp`
    pub server_fp: [u8; 32],
    pub tls: Arc<rustls::ServerConfig>,
}

pub struct App {
    pub settings: Settings,
    pub attest: Mutex<crate::attest::AttestState>,
    pub policy: remoter_attest::Policy,
    pub fresh_ms: i64,
    pub pair: Mutex<Option<crate::pair::PairSession>>,
    pub pairing: Pairing,
    /// device file minus the self unpaired ones. Live connections watch this.
    pub devices: watch::Sender<Arc<Devices>>,
    pub verifier: Verifier,
    pub limits: Mutex<Limits>,
    pub autolock: Mutex<AutoLock>,
    pub locked: AtomicBool,
    pub audit: Mutex<Audit>,
    pub agent: AgentClient,
    pub tails: Mutex<HashMap<String, TailHub>>,
    pub handshake_log: Mutex<HashMap<std::net::IpAddr, Instant>>,
}

pub struct TailHub {
    pub tx: broadcast::Sender<remoter_proto::ipc::TailReply>,
    pub subs: std::collections::HashSet<u64>,
    pub next: u64,
}

impl App {
    pub fn devices(&self) -> Arc<Devices> {
        self.devices.borrow().clone()
    }

    /// SIGHUP. Dropped devices lose their connections right away.
    pub fn reload_devices(&self) -> Result<usize, String> {
        let all = Devices::load(&self.settings.devices_file)?;
        let live = remoter_auth::unpaired::effective(&all, &self.settings.unpaired_file);
        let n = live.len();
        self.devices.send_replace(Arc::new(live));
        Ok(n)
    }

    pub fn attest_data_ok(&self) -> Result<(), String> {
        lock(&self.attest).data().map(|_| ())
    }

    /// `remoterctl lock off`, clears the bad signature counts too.
    pub fn unlock(&self) -> Result<(), String> {
        match std::fs::remove_file(&self.settings.locked_flag) {
            Err(e) if e.kind() != std::io::ErrorKind::NotFound => return Err(e.to_string()),
            _ => {}
        }
        self.locked.store(false, Ordering::SeqCst);
        *lock(&self.autolock) = AutoLock::default();
        self.audit(None, "unlock", None, "ok", "-");
        Ok(())
    }

    pub fn take(&self, device: &str, kind: Kind) -> Result<(), u32> {
        lock(&self.limits).take(device, kind, Instant::now())
    }

    pub fn is_locked(&self) -> bool {
        self.locked.load(Ordering::SeqCst) || self.settings.locked_flag.exists()
    }

    /// Locks on the third bad signature in ten minutes, or any reused nonce.
    pub fn count_failure(&self, device: &str, w: LockWeight) {
        if lock(&self.autolock).record(device, w, Instant::now()) {
            self.set_locked(Some(device), "auto");
        }
    }

    pub fn set_locked(&self, device: Option<&str>, why: &str) {
        self.locked.store(true, Ordering::SeqCst);
        // 0644: the agent has to read it
        if std::fs::write(&self.settings.locked_flag, why.as_bytes()).is_ok() {
            let _ = std::fs::set_permissions(&self.settings.locked_flag, std::fs::Permissions::from_mode(0o644));
        }
        self.audit(device, "lock", None, why, "-");
        tracing::warn!(?device, why, "locked");
    }

    pub fn audit(&self, device: Option<&str>, action: &str, path: Option<String>, result: &str, request_id: &str) {
        let e = AuditEntry {
            ts: remoter_proto::local::now_ms(),
            device: device.map(String::from),
            action: action.into(),
            path,
            result: result.into(),
            request_id: request_id.into(),
        };
        if let Err(err) = lock(&self.audit).append(e) {
            tracing::error!(%err, "audit write failed");
        }
    }

    /// Cut off a moment later so the reply to the unpair itself still gets through.
    pub fn unpair_later(self: &Arc<Self>, device: &str) -> Result<(), String> {
        self.write_unpaired(device)?;
        let app = self.clone();
        tokio::spawn(async move {
            tokio::time::sleep(std::time::Duration::from_millis(300)).await;
            if let Err(e) = app.reload_devices() {
                tracing::error!(%e, "reload after unpair failed");
            }
        });
        Ok(())
    }

    fn write_unpaired(&self, device: &str) -> Result<(), String> {
        let mut set = match remoter_auth::unpaired::read(&self.settings.unpaired_file) {
            remoter_auth::unpaired::Unpaired::Some(s) => s,
            // unreadable already refuses everyone
            remoter_auth::unpaired::Unpaired::Unreadable => Default::default(),
        };
        set.insert(device.to_owned());
        let mut v: Vec<_> = set.into_iter().collect();
        v.sort();
        let json = serde_json::to_vec(&remoter_auth::unpaired::UnpairedFile { devices: v }).map_err(|e| e.to_string())?;
        remoter_proto::local::write_atomic(&self.settings.unpaired_file, &json).map_err(|e| e.to_string())?;
        let _ = std::fs::set_permissions(&self.settings.unpaired_file, std::fs::Permissions::from_mode(0o644));
        Ok(())
    }
}
