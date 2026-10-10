//! Pairing listener: up to five minutes, no client auth, only `POST /pair`, one
//! attempt. The phone's request hangs until the code typed on the laptop matches or not.

use std::net::SocketAddr;
use std::sync::Arc;
use std::time::Duration;

use axum::body::Bytes;
use axum::extract::rejection::BytesRejection;
use axum::extract::{DefaultBodyLimit, State};
use axum::http::StatusCode;
use axum::response::{IntoResponse, Response};
use axum::routing::post;
use axum::{Json, Router};
use hyper_util::rt::{TokioExecutor, TokioIo};
use hyper_util::service::TowerToHyperService;
use remoter_proto::admin::{PairCandidate, PairStarted, PairStatus, Weakness};
use remoter_proto::api::{ErrorBody, PairRequest, PairResponse};
use remoter_proto::{ErrorCode, b64, local, pair};
use sha2::{Digest, Sha256};
use tokio::sync::{Notify, oneshot};

use crate::state::{App, lock};

pub const MAX_TTL_S: u32 = 300;
// two chains of up to eight certs, base64
pub const PAIR_BODY_LIMIT: usize = 64 * 1024;
const MAX_CONNECTIONS: usize = 16;

pub struct PairSession {
    pub secret: [u8; 32],
    pub challenge: [u8; 16],
    pub name: String,
    pub expires: i64,
    pub status: PairStatus,
    pub attempted: bool,
    pub confirm: Option<oneshot::Sender<Option<String>>>,
    pub closed: Arc<Notify>,
}

fn reject(message: &str) -> Response {
    let body = ErrorBody { code: ErrorCode::PairRejected, message: message.into(), request_id: ulid::Ulid::generate().to_string(), retry_after_s: None, sessions: None, server_time: None };
    (StatusCode::FORBIDDEN, Json(body)).into_response()
}

pub fn valid_device_name(n: &str) -> bool {
    !n.trim().is_empty() && n.chars().count() <= 64 && n.chars().all(|c| !c.is_control()) && !remoter_proto::names::is_unsupported_name(n.as_bytes())
}

/// Fails if a window is already open.
pub async fn start(app: &Arc<App>, name: &str, ttl_s: u32) -> Result<PairStarted, String> {
    if !valid_device_name(name) {
        return Err("device name must be 1 to 64 printable characters".into());
    }
    {
        let s = lock(&app.pair);
        if let Some(p) = s.as_ref()
            && p.expires > local::now_ms()
            && !matches!(p.status, PairStatus::Done {} | PairStatus::Failed { .. } | PairStatus::Expired {})
        {
            return Err("a pairing window is already open".into());
        }
    }
    app.attest_data_ok()?;
    let mut secret = [0u8; 32];
    let mut challenge = [0u8; 16];
    getrandom::fill(&mut secret).map_err(|e| e.to_string())?;
    getrandom::fill(&mut challenge).map_err(|e| e.to_string())?;
    let ttl = ttl_s.clamp(1, MAX_TTL_S) as i64;
    let expires = local::now_ms() + ttl * 1000;
    let std_listener = crate::listener::bind(&app.pairing.device, app.pairing.addr).map_err(|e| format!("pairing listener: {e}"))?;
    let bound = std_listener.local_addr().map_err(|e| e.to_string())?;
    let listener = tokio::net::TcpListener::from_std(std_listener).map_err(|e| e.to_string())?;
    let closed = Arc::new(Notify::new());
    *lock(&app.pair) = Some(PairSession { secret, challenge, name: name.to_owned(), expires, status: PairStatus::Waiting {}, attempted: false, confirm: None, closed: closed.clone() });
    tokio::spawn(serve(app.clone(), listener, expires, closed));
    let link = pair::Link {
        host: app.pairing.public_host,
        port: app.pairing.api_port,
        pair_port: bound.port(),
        server_fp: app.pairing.server_fp,
        secret,
        challenge,
        expires_unix: expires / 1000,
    };
    app.audit(None, "pair_open", Some(name.to_owned()), "ok", "-");
    Ok(PairStarted { link: link.to_uri(), expires })
}

async fn serve(app: Arc<App>, listener: tokio::net::TcpListener, expires: i64, closed: Arc<Notify>) {
    let router = Router::new().route("/pair", post(handle)).fallback(|| async { reject("only POST /pair") }).layer(DefaultBodyLimit::max(PAIR_BODY_LIMIT)).with_state(app.clone());
    let acceptor = tokio_rustls::TlsAcceptor::from(app.pairing.tls.clone());
    let deadline = tokio::time::Instant::now() + Duration::from_millis((expires - local::now_ms()).max(0) as u64);
    let mut conns = 0;
    loop {
        let (tcp, _addr): (tokio::net::TcpStream, SocketAddr) = tokio::select! {
            r = listener.accept() => match r { Ok(p) => p, Err(_) => continue },
            () = closed.notified() => break,
            () = tokio::time::sleep_until(deadline) => break,
        };
        conns += 1;
        if conns > MAX_CONNECTIONS {
            break;
        }
        let (acceptor, router) = (acceptor.clone(), router.clone());
        tokio::spawn(async move {
            let Ok(Ok(tls)) = tokio::time::timeout(Duration::from_secs(10), acceptor.accept(tcp)).await else { return };
            let _ = hyper::server::conn::http2::Builder::new(TokioExecutor::new())
                .serve_connection(TokioIo::new(tls), TowerToHyperService::new(router))
                .await;
        });
    }
    // dropping the listener closes the port, an attempt in flight keeps its connection
    drop(listener);
    let mut s = lock(&app.pair);
    if let Some(p) = s.as_mut()
        && matches!(p.status, PairStatus::Waiting {})
    {
        p.status = PairStatus::Expired {};
    }
}

fn leaf_spki(chain: &[Vec<u8>]) -> Option<Vec<u8>> {
    let (_, leaf) = x509_parser::parse_x509_certificate(chain.first()?).ok()?;
    Some(leaf.public_key().raw.to_vec())
}

fn decode_chain(c: &[String]) -> Option<Vec<Vec<u8>>> {
    if c.is_empty() || c.len() > 8 {
        return None;
    }
    c.iter().map(|x| b64::decode(x)).collect()
}

fn fail(app: &App, reason: &str) -> Response {
    if let Some(p) = lock(&app.pair).as_mut() {
        p.status = PairStatus::Failed { reason: reason.into() };
    }
    app.audit(None, "pair", None, reason, "-");
    reject("pairing refused")
}

async fn handle(State(app): State<Arc<App>>, body: Result<Bytes, BytesRejection>) -> Response {
    let (secret, challenge, name, expires) = {
        let mut s = lock(&app.pair);
        let Some(p) = s.as_mut() else { return reject("no pairing window") };
        // first post wins, good or bad, and the port closes behind it
        if p.attempted || p.expires <= local::now_ms() {
            return reject("this pairing window is used up");
        }
        p.attempted = true;
        p.closed.notify_one();
        (p.secret, p.challenge, p.name.clone(), p.expires)
    };
    let Ok(body) = body else { return fail(&app, "body too large or unreadable") };
    let Ok(req) = serde_json::from_slice::<PairRequest>(&body) else { return fail(&app, "not a pairing request") };
    if !valid_device_name(&req.device_name) {
        return fail(&app, "device name");
    }
    let (Some(tls_chain), Some(sig_chain)) = (decode_chain(&req.tls_chain), decode_chain(&req.sig_chain)) else {
        return fail(&app, "chains");
    };
    let (Some(tls_spki), Some(sig_spki)) = (leaf_spki(&tls_chain), leaf_spki(&sig_chain)) else { return fail(&app, "leaf certificates") };
    // SPKIs from the leaves themselves, never a separate field that could disagree
    let transcript = pair::Transcript { server_fp: &app.pairing.server_fp, tls_spki: &tls_spki, sig_spki: &sig_spki, device_name: &req.device_name };
    let mac_ok = b64::decode(&req.mac).is_some_and(|m| pair::mac_matches(&secret, &transcript, &m));
    if !mac_ok {
        return fail(&app, "mac");
    }
    let attested = {
        let st = lock(&app.attest);
        match st.data() {
            Ok((roots, revoked)) => remoter_attest::check_pair(&tls_chain, &sig_chain, &challenge, &app.policy, roots, revoked, local::now_ms() / 1000),
            Err(e) => return fail(&app, &e),
        }
    };
    let (tls, sig) = match attested {
        Ok(p) => p,
        Err(e) => return fail(&app, &format!("attestation: {e}")),
    };
    let hex = |b: &[u8]| b.iter().map(|x| format!("{x:02x}")).collect::<String>();
    let weaknesses = remoter_attest::weaknesses(&tls, &sig);
    let boot = |w| !weaknesses.contains(&w);
    let candidate = PairCandidate {
        name,
        manufacturer: sig.manufacturer.clone(),
        model: sig.model.clone(),
        sig_level: sig.attestation_security_level,
        tls_level: tls.attestation_security_level,
        boot_state: format!(
            "{}, {}",
            if boot(Weakness::BootNotVerified) { "verified" } else { "not verified" },
            if boot(Weakness::BootloaderUnlocked) { "locked" } else { "unlocked" }
        ),
        weaknesses,
        boot_key_prefix: hex(&sig.verified_boot_key).chars().take(8).collect(),
        code: pair::confirmation_code(&secret, &transcript),
        tls_spki_sha256: b64::encode(&Sha256::digest(&tls_spki)),
        sig_pub: b64::encode(&sig_spki),
        verified_boot_key: hex(&sig.verified_boot_key),
        attestation: serde_json::json!({ "tls": tls, "sig": sig }),
    };
    let (tx, rx) = oneshot::channel();
    {
        let mut s = lock(&app.pair);
        let Some(p) = s.as_mut() else { return reject("no pairing window") };
        p.status = PairStatus::Received { candidate: Box::new(candidate) };
        p.confirm = Some(tx);
    }
    let left = Duration::from_millis((expires - local::now_ms()).max(0) as u64);
    match tokio::time::timeout(left, rx).await {
        Ok(Ok(Some(device_id))) => Json(PairResponse { device_id, hostname: app.settings.hostname.clone() }).into_response(),
        _ => {
            let mut s = lock(&app.pair);
            if let Some(p) = s.as_mut()
                && !matches!(p.status, PairStatus::Failed { .. })
            {
                p.status = PairStatus::Failed { reason: "not confirmed on the laptop".into() };
            }
            reject("not confirmed on the laptop")
        }
    }
}

pub async fn wait(app: &App, wait_ms: u32) -> PairStatus {
    let deadline = tokio::time::Instant::now() + Duration::from_millis(wait_ms.min(60_000) as u64);
    loop {
        let st = lock(&app.pair).as_ref().map(|p| p.status.clone()).unwrap_or(PairStatus::Failed { reason: "no pairing window".into() });
        if !matches!(st, PairStatus::Waiting {}) || tokio::time::Instant::now() >= deadline {
            return st;
        }
        tokio::time::sleep(Duration::from_millis(100)).await;
    }
}

/// Called after the code matched and remoterctl wrote the device file.
pub fn confirm(app: &App, device_id: &str) -> Result<(), String> {
    if !remoter_proto::canonical::is_device_id(device_id) {
        return Err("not a device id".into());
    }
    app.reload_devices()?;
    if app.devices().get(device_id).is_none() {
        return Err("the device isn't in the device file".into());
    }
    let tx = {
        let mut s = lock(&app.pair);
        let p = s.as_mut().ok_or("no pairing window")?;
        if !matches!(p.status, PairStatus::Received { .. }) {
            return Err("nothing to confirm".into());
        }
        if p.expires <= local::now_ms() {
            p.status = PairStatus::Expired {};
            return Err("the pairing window expired".into());
        }
        p.status = PairStatus::Done {};
        p.confirm.take().ok_or("already answered")?
    };
    // only paired once the phone got its id. if it gave up waiting, remoterctl removes the record
    tx.send(Some(device_id.to_owned())).map_err(|_| "the phone gave up waiting".to_owned())?;
    // pairing counts as today's attestation
    lock(&app.attest).mark_fresh(device_id, local::now_ms() + app.fresh_ms);
    app.audit(Some(device_id), "pair", None, "ok", "-");
    Ok(())
}

pub fn reject_pending(app: &App) -> Result<(), String> {
    let tx = {
        let mut s = lock(&app.pair);
        let p = s.as_mut().ok_or("no pairing window")?;
        p.status = PairStatus::Failed { reason: "the code didn't match".into() };
        p.confirm.take()
    };
    app.audit(None, "pair", None, "code_mismatch", "-");
    if let Some(tx) = tx {
        let _ = tx.send(None);
    }
    Ok(())
}
