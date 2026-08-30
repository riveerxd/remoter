//! `/run/remoterd/admin.sock`, root only (`SO_PEERCRED`). Pairing, lock and
//! attestation data come in here, never over the network.

use std::os::unix::fs::{FileTypeExt, PermissionsExt};
use std::path::Path;
use std::sync::Arc;
use std::time::Duration;

use remoter_proto::admin::{AdminReply, AdminRequest};
use tokio::io::{AsyncReadExt, AsyncWriteExt};

use crate::state::{App, lock};

// revocation status lists run to hundreds of KB
pub const MAX_ADMIN_FRAME: usize = 8 * 1024 * 1024;
const READ_DEADLINE: Duration = Duration::from_secs(10);

pub fn bind(path: &Path) -> std::io::Result<tokio::net::UnixListener> {
    if let Ok(m) = std::fs::symlink_metadata(path) {
        if !m.file_type().is_socket() {
            return Err(std::io::Error::other(format!("{} exists and isn't a socket", path.display())));
        }
        std::fs::remove_file(path)?;
    }
    let l = tokio::net::UnixListener::bind(path)?;
    std::fs::set_permissions(path, std::fs::Permissions::from_mode(0o600))?;
    Ok(l)
}

pub async fn serve(app: Arc<App>, listener: tokio::net::UnixListener, admin_uid: u32) {
    loop {
        let Ok((mut s, _)) = listener.accept().await else { continue };
        match s.peer_cred() {
            Ok(c) if c.uid() == admin_uid => {}
            _ => continue,
        }
        let app = app.clone();
        tokio::spawn(async move {
            let mut buf = Vec::new();
            let read = tokio::time::timeout(READ_DEADLINE, (&mut s).take(MAX_ADMIN_FRAME as u64 + 1).read_to_end(&mut buf)).await;
            if !matches!(read, Ok(Ok(_))) || buf.len() > MAX_ADMIN_FRAME {
                return;
            }
            let reply = match serde_json::from_slice::<AdminRequest>(&buf) {
                Ok(r) => dispatch(&app, r).await,
                Err(e) => AdminReply::Err(format!("request: {e}")),
            };
            if let Ok(out) = serde_json::to_vec(&reply) {
                let _ = s.write_all(&out).await;
            }
        });
    }
}

fn ok<T: serde::Serialize>(v: T) -> AdminReply {
    serde_json::to_value(v).map(AdminReply::Ok).unwrap_or_else(|e| AdminReply::Err(e.to_string()))
}

fn result<T: serde::Serialize>(r: Result<T, String>) -> AdminReply {
    match r {
        Ok(v) => ok(v),
        Err(e) => AdminReply::Err(e),
    }
}

async fn dispatch(app: &Arc<App>, req: AdminRequest) -> AdminReply {
    match req {
        AdminRequest::SetAttestationData { roots, status } => {
            let r = lock(&app.attest).set_data(&roots, &status);
            if r.is_ok() {
                app.audit(None, "attestation_data", None, "ok", "-");
            }
            result(r.map(|n| serde_json::json!({ "roots": n })))
        }
        AdminRequest::PairStart { name, ttl_s } => result(crate::pair::start(app, &name, ttl_s).await),
        AdminRequest::PairWait { wait_ms } => ok(crate::pair::wait(app, wait_ms).await),
        AdminRequest::PairConfirm { device_id } => result(crate::pair::confirm(app, &device_id).map(|()| serde_json::json!({}))),
        AdminRequest::PairReject {} => result(crate::pair::reject_pending(app).map(|()| serde_json::json!({}))),
        AdminRequest::Lock {} => {
            app.set_locked(None, "remoterctl");
            ok(serde_json::json!({ "locked": true }))
        }
        AdminRequest::Unlock {} => result(app.unlock().map(|()| serde_json::json!({ "locked": false }))),
        AdminRequest::Reload {} => result(app.reload_devices().map(|n| serde_json::json!({ "devices": n }))),
        AdminRequest::AuditHead {} => ok(serde_json::json!({ "head": lock(&app.audit).head() })),
    }
}
