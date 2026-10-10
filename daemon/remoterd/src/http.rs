//! `/v1`. Every response gets `Remoter-Request-Id`, every error is an `ErrorBody`.

use std::convert::Infallible;
use std::sync::Arc;
use std::time::Duration;

use axum::body::Bytes;
use axum::extract::rejection::BytesRejection;
use axum::extract::{DefaultBodyLimit, OriginalUri, Path, Request, State};
use axum::http::{HeaderMap, HeaderValue, Method, StatusCode, header};
use axum::middleware::{self, Next};
use axum::response::sse::{Event as SseEvent, KeepAlive, Sse};
use axum::response::{IntoResponse, Response};
use axum::routing::{delete, get, post};
use axum::{Extension, Json, Router};
use remoter_auth::{Admit, Cached, RawSigHeaders, Request as SigRequest};
use remoter_proto::api::{AttestChallenge, AttestRequest, AttestResponse, ErrorBody, Health, LiveSessions, LockResponse, Resources, SessionSummary};
use remoter_proto::canonical::{HDR_DEVICE, HDR_NONCE, HDR_SIGNATURE, HDR_TIMESTAMP};
use remoter_proto::ipc::{AgentRequest, EventsReply, LiveReply, MutateReply, TailReply};
use remoter_proto::{ErrorCode, b64, local};
use tokio::sync::{broadcast, mpsc};

use crate::agent::CallError;
use crate::limits::Kind;
use crate::state::{App, TailHub, lock};

pub const BODY_LIMIT: usize = remoter_proto::api::BODY_LIMIT;
const AGENT_READ: Duration = Duration::from_secs(10);
const AGENT_MUTATE: Duration = Duration::from_secs(60);
const EVENTS_WAIT_MS: u32 = 20_000;
const TAIL_EVERY: Duration = Duration::from_millis(500);
const LIVE_WAIT_MS: u32 = 20_000;
const LIVE_HEALTH_EVERY: Duration = Duration::from_secs(2);
// the phone calls a quiet stream dead after ~8s
const LIVE_PING_EVERY: Duration = Duration::from_secs(5);

/// Set once per connection from the mTLS cert.
#[derive(Clone, Debug)]
pub struct Peer {
    pub device: String,
    pub tls_hash: [u8; 32],
}

#[derive(Clone, Debug)]
pub struct Rid(pub String);

pub fn router(app: Arc<App>) -> Router {
    Router::new()
        .route("/v1/health", get(health))
        .route("/v1/live", get(live))
        .route("/v1/fs/list", get(fs_list))
        .route("/v1/fs/search", get(fs_search))
        .route("/v1/fs/recent", get(fs_recent))
        .route("/v1/fs/history", get(fs_history))
        .route("/v1/fs/mkdir", post(signed))
        .route("/v1/sessions", get(sessions).post(signed))
        .route("/v1/sessions/{id}", get(session).delete(signed))
        .route("/v1/sessions/{id}/events", get(events))
        .route("/v1/procs", get(procs))
        .route("/v1/procs/{pid}/signal", post(signed))
        .route("/v1/view-token", post(signed))
        .route("/v1/attest/challenge", get(attest_challenge))
        .route("/v1/attest", post(attest))
        .route("/v1/lock", post(lock_now))
        .route("/v1/audit", get(audit))
        .route("/v1/devices/self", delete(signed))
        .fallback(not_found)
        .method_not_allowed_fallback(not_found)
        .layer(DefaultBodyLimit::max(BODY_LIMIT))
        .layer(middleware::from_fn(request_id))
        .with_state(app)
}

async fn request_id(mut req: Request, next: Next) -> Response {
    let rid = ulid::Ulid::generate().to_string();
    req.extensions_mut().insert(Rid(rid.clone()));
    let mut res = next.run(req).await;
    if let Ok(v) = HeaderValue::from_str(&rid) {
        res.headers_mut().insert(remoter_proto::api::HDR_REQUEST_ID, v);
    }
    res
}

fn error_full(rid: &str, code: ErrorCode, message: impl Into<String>, retry_after_s: Option<u32>, server_time: Option<i64>) -> Response {
    let body = ErrorBody { code, message: message.into(), request_id: rid.into(), retry_after_s, sessions: None, server_time };
    let status = StatusCode::from_u16(code.http_status()).unwrap_or(StatusCode::INTERNAL_SERVER_ERROR);
    (status, Json(body)).into_response()
}

fn error(rid: &str, code: ErrorCode, message: impl Into<String>) -> Response {
    error_full(rid, code, message, None, None)
}

fn raw_json(status: u16, body: String) -> Response {
    let status = StatusCode::from_u16(status).unwrap_or(StatusCode::INTERNAL_SERVER_ERROR);
    (status, [(header::CONTENT_TYPE, "application/json")], body).into_response()
}

fn call_error(rid: &str, e: CallError) -> Response {
    match e {
        CallError::Down(m) => error(rid, ErrorCode::AgentDown, m),
        CallError::Failed(f) => error(rid, f.code, f.message),
    }
}

async fn not_found(Extension(rid): Extension<Rid>) -> Response {
    error(&rid.0, ErrorCode::NotFound, "no such endpoint")
}

/// The device may have been revoked since the connection opened.
fn gate(app: &App, peer: &Peer, rid: &Rid, kind: Kind) -> Result<(), Box<Response>> {
    if app.devices().get(&peer.device).is_none() {
        return Err(Box::new(error(&rid.0, ErrorCode::DeviceUnknown, "device no longer paired")));
    }
    app.take(&peer.device, kind)
        .map_err(|retry| Box::new(error_full(&rid.0, ErrorCode::RateLimited, "rate limited", Some(retry), None)))
}

/// Percent decoding only, `+` stays `+` (OkHttp sends `%20`). Bytes, so
/// non UTF-8 reaches the path guard as sent.
pub fn query_pairs(q: Option<&str>) -> Vec<(Vec<u8>, Vec<u8>)> {
    fn dec(s: &str) -> Vec<u8> {
        let b = s.as_bytes();
        let mut out = Vec::with_capacity(b.len());
        let mut i = 0;
        while i < b.len() {
            if b[i] == b'%'
                && i + 2 < b.len()
                && let (Some(h), Some(l)) = ((b[i + 1] as char).to_digit(16), (b[i + 2] as char).to_digit(16))
            {
                out.push((h * 16 + l) as u8);
                i += 3;
            } else {
                out.push(b[i]);
                i += 1;
            }
        }
        out
    }
    q.unwrap_or("")
        .split('&')
        .filter(|kv| !kv.is_empty())
        .map(|kv| match kv.split_once('=') {
            Some((k, v)) => (dec(k), dec(v)),
            None => (dec(kv), Vec::new()),
        })
        .collect()
}

fn param<'a>(pairs: &'a [(Vec<u8>, Vec<u8>)], key: &str) -> Option<&'a [u8]> {
    pairs.iter().find(|(k, _)| k == key.as_bytes()).map(|(_, v)| v.as_slice())
}

async fn health(State(app): State<Arc<App>>, Extension(peer): Extension<Peer>, Extension(rid): Extension<Rid>) -> Response {
    if let Err(r) = gate(&app, &peer, &rid, Kind::Read) {
        return *r;
    }
    Json(health_of(&app, &peer.device).await).into_response()
}

async fn health_of(app: &App, device: &str) -> Health {
    let sessions = app
        .agent
        .call_as::<remoter_proto::ipc::AgentStatus>(&AgentRequest::Status {}, Duration::from_secs(3))
        .await
        .map(|s| s.sessions)
        .unwrap_or(0);
    let (on_ac, battery_pct) = power(&app.settings.power_supply_dir);
    let dev = app.pairing.device.clone();
    let tunnel = tokio::task::spawn_blocking(move || crate::route::tunnel(&dev)).await.ok().flatten();
    Health {
        hostname: app.settings.hostname.clone(),
        version: env!("CARGO_PKG_VERSION").into(),
        server_time: local::now_ms(),
        locked: app.is_locked(),
        sessions,
        on_ac,
        battery_pct,
        fresh_until: lock(&app.attest).fresh_until(device),
        tunnel,
    }
}

pub fn power(dir: &std::path::Path) -> (Option<bool>, Option<u8>) {
    let read = |p: std::path::PathBuf| std::fs::read_to_string(p).ok().map(|s| s.trim().to_owned());
    let mut on_ac = None;
    let mut pct = None;
    let Ok(rd) = std::fs::read_dir(dir) else { return (None, None) };
    let mut entries: Vec<_> = rd.flatten().map(|e| e.path()).collect();
    entries.sort();
    for p in entries {
        match read(p.join("type")).as_deref() {
            Some("Mains") => on_ac = Some(on_ac.unwrap_or(false) || read(p.join("online")).as_deref() == Some("1")),
            Some("Battery") if pct.is_none() => pct = read(p.join("capacity")).and_then(|c| c.parse::<u8>().ok()).map(|c| c.min(100)),
            _ => {}
        }
    }
    (on_ac, pct)
}

async fn forward(app: &App, rid: &Rid, req: AgentRequest) -> Response {
    match app.agent.call(&req, AGENT_READ).await {
        Ok(v) => Json(v).into_response(),
        Err(e) => call_error(&rid.0, e),
    }
}

async fn fs_list(State(app): State<Arc<App>>, Extension(peer): Extension<Peer>, Extension(rid): Extension<Rid>, uri: OriginalUri) -> Response {
    if let Err(r) = gate(&app, &peer, &rid, Kind::Read) {
        return *r;
    }
    let q = query_pairs(uri.query());
    let path = b64::encode(param(&q, "path").unwrap_or(b""));
    let hidden = param(&q, "hidden") == Some(b"true");
    forward(&app, &rid, AgentRequest::List { path, hidden }).await
}

async fn fs_search(State(app): State<Arc<App>>, Extension(peer): Extension<Peer>, Extension(rid): Extension<Rid>, uri: OriginalUri) -> Response {
    if let Err(r) = gate(&app, &peer, &rid, Kind::Read) {
        return *r;
    }
    let q = query_pairs(uri.query());
    let Ok(text) = String::from_utf8(param(&q, "q").unwrap_or(b"").to_vec()) else {
        return error(&rid.0, ErrorCode::BadRequest, "query must be UTF-8");
    };
    let path = b64::encode(param(&q, "path").unwrap_or(b""));
    forward(&app, &rid, AgentRequest::Search { path, q: text }).await
}

async fn fs_recent(State(app): State<Arc<App>>, Extension(peer): Extension<Peer>, Extension(rid): Extension<Rid>) -> Response {
    if let Err(r) = gate(&app, &peer, &rid, Kind::Read) {
        return *r;
    }
    forward(&app, &rid, AgentRequest::Recent {}).await
}

/// Just a read, mTLS is enough. Older phones still send a view token, ignored.
async fn fs_history(State(app): State<Arc<App>>, Extension(peer): Extension<Peer>, Extension(rid): Extension<Rid>, uri: OriginalUri) -> Response {
    if let Err(r) = gate(&app, &peer, &rid, Kind::Read) {
        return *r;
    }
    let q = query_pairs(uri.query());
    let path = b64::encode(param(&q, "path").unwrap_or(b""));
    forward(&app, &rid, AgentRequest::History { path }).await
}

async fn sessions(State(app): State<Arc<App>>, Extension(peer): Extension<Peer>, Extension(rid): Extension<Rid>) -> Response {
    if let Err(r) = gate(&app, &peer, &rid, Kind::Read) {
        return *r;
    }
    forward(&app, &rid, AgentRequest::Sessions {}).await
}

async fn session(State(app): State<Arc<App>>, Extension(peer): Extension<Peer>, Extension(rid): Extension<Rid>, Path(id): Path<String>) -> Response {
    if let Err(r) = gate(&app, &peer, &rid, Kind::Read) {
        return *r;
    }
    forward(&app, &rid, AgentRequest::Session { id }).await
}

async fn procs(State(app): State<Arc<App>>, Extension(peer): Extension<Peer>, Extension(rid): Extension<Rid>) -> Response {
    if let Err(r) = gate(&app, &peer, &rid, Kind::Read) {
        return *r;
    }
    forward(&app, &rid, AgentRequest::Procs {}).await
}

async fn audit(State(app): State<Arc<App>>, Extension(peer): Extension<Peer>, Extension(rid): Extension<Rid>, uri: OriginalUri) -> Response {
    if let Err(r) = gate(&app, &peer, &rid, Kind::Read) {
        return *r;
    }
    let q = query_pairs(uri.query());
    let before = match param(&q, "before") {
        None => i64::MAX,
        Some(b) => match std::str::from_utf8(b).ok().and_then(|s| s.parse::<i64>().ok()) {
            Some(v) => v,
            None => return error(&rid.0, ErrorCode::BadRequest, "before must be a number"),
        },
    };
    Json(lock(&app.audit).page(&peer.device, before)).into_response()
}

async fn attest_challenge(State(app): State<Arc<App>>, Extension(peer): Extension<Peer>, Extension(rid): Extension<Rid>) -> Response {
    if let Err(r) = gate(&app, &peer, &rid, Kind::Read) {
        return *r;
    }
    match lock(&app.attest).new_challenge(&peer.device) {
        Ok((c, expires)) => Json(AttestChallenge { challenge: b64::encode(&c), expires }).into_response(),
        Err(e) => error(&rid.0, ErrorCode::Internal, e),
    }
}

/// Throwaway key attested over the challenge, same phone, still locked and verified.
async fn attest(
    State(app): State<Arc<App>>,
    Extension(peer): Extension<Peer>,
    Extension(rid): Extension<Rid>,
    body: Result<Bytes, BytesRejection>,
) -> Response {
    if let Err(r) = gate(&app, &peer, &rid, Kind::Read) {
        return *r;
    }
    let Ok(body) = body else { return error(&rid.0, ErrorCode::BadRequest, "body too large or unreadable") };
    let Ok(req) = serde_json::from_slice::<AttestRequest>(&body) else { return error(&rid.0, ErrorCode::BadRequest, "not an attestation request") };
    let Some(chain) = (req.chain.len() <= 8).then(|| req.chain.iter().map(|c| b64::decode(c)).collect::<Option<Vec<_>>>()).flatten() else {
        return error(&rid.0, ErrorCode::BadRequest, "chain encoding");
    };
    let (boot_key, paired_with) = app.devices().get(&peer.device).map(|d| (d.record.verified_boot_key.clone(), d.record.weaknesses.clone())).unwrap_or_default();
    let boot_key: Vec<u8> = (0..boot_key.len() / 2).filter_map(|i| u8::from_str_radix(boot_key.get(2 * i..2 * i + 2)?, 16).ok()).collect();
    let outcome = {
        let mut st = lock(&app.attest);
        let challenge = st.take_challenge(&peer.device);
        match (challenge, st.data()) {
            (None, _) => Err("no open challenge for this device".to_owned()),
            (_, Err(e)) => Err(e),
            (Some(c), Ok((roots, revoked))) => {
                remoter_attest::check_reattest(&chain, &c, &boot_key, &paired_with, &app.policy, roots, revoked, local::now_ms() / 1000).map(|_| ()).map_err(|e| e.to_string())
            }
        }
    };
    match outcome {
        Ok(()) => {
            let until = local::now_ms() + app.fresh_ms;
            lock(&app.attest).mark_fresh(&peer.device, until);
            app.audit(Some(&peer.device), "reattest", None, "ok", &rid.0);
            Json(AttestResponse { fresh_until: until }).into_response()
        }
        Err(e) => {
            app.audit(Some(&peer.device), "reattest", None, "refused", &rid.0);
            error(&rid.0, ErrorCode::ReattestRequired, e)
        }
    }
}

/// mTLS only, locking never needs a fingerprint.
async fn lock_now(State(app): State<Arc<App>>, Extension(peer): Extension<Peer>, Extension(rid): Extension<Rid>) -> Response {
    if let Err(r) = gate(&app, &peer, &rid, Kind::Read) {
        return *r;
    }
    app.set_locked(Some(&peer.device), "phone");
    Json(LockResponse { locked: true }).into_response()
}

fn sig_headers(h: &HeaderMap) -> RawSigHeaders {
    let get = |n: &str| h.get(n).and_then(|v| v.to_str().ok()).map(String::from);
    RawSigHeaders { device: get(HDR_DEVICE), timestamp: get(HDR_TIMESTAMP), nonce: get(HDR_NONCE), signature: get(HDR_SIGNATURE) }
}

fn action_of(method: &Method, path: &str) -> &'static str {
    match (method.as_str(), path) {
        ("POST", "/v1/fs/mkdir") => "mkdir",
        ("POST", "/v1/sessions") => "spawn",
        ("DELETE", p) if p.starts_with("/v1/sessions/") => "end",
        ("POST", p) if p.starts_with("/v1/procs/") => "signal",
        ("POST", "/v1/view-token") => "view_token",
        ("DELETE", "/v1/devices/self") => "unpair",
        _ => "unknown",
    }
}

/// Only call with a body whose signature passed.
fn audit_path(action: &str, path: &str, body: &[u8]) -> Option<String> {
    let v: serde_json::Value = serde_json::from_slice(body).unwrap_or_default();
    let s = |k: &str| v.get(k).and_then(|x| x.as_str()).map(String::from);
    match action {
        "spawn" => s("path"),
        "mkdir" => Some(format!("{}/{}", s("parent")?, s("name")?).trim_start_matches('/').to_owned()),
        "end" => path.strip_prefix("/v1/sessions/").map(String::from),
        "signal" => Some(format!("{} {}", path.strip_prefix("/v1/procs/")?.strip_suffix("/signal")?, s("signal")?)),
        _ => None,
    }
}

fn result_of(status: u16, body: &str) -> String {
    if status < 300 {
        return "ok".into();
    }
    serde_json::from_str::<ErrorBody>(body).map(|b| b.code.as_str().to_owned()).unwrap_or_else(|_| format!("http_{status}"))
}

fn verify(app: &App, peer: &Peer, rid: &Rid, method: &Method, target: &str, headers: &HeaderMap, body: &[u8]) -> Result<remoter_auth::Admitted, Box<Response>> {
    let path = target.split_once('?').map_or(target, |(p, _)| p);
    let action = action_of(method, path);
    let raw = sig_headers(headers);
    let devices = app.devices();
    let checked = app.verifier.check(
        &devices,
        &SigRequest { mtls_device: &peer.device, headers: &raw, method: method.as_str(), target, body, now_ms: local::now_ms() },
    );
    match checked {
        Ok(a) => Ok(a),
        Err(rej) => {
            app.count_failure(&peer.device, rej.lock_weight());
            app.audit(Some(&peer.device), action, None, rej.code().as_str(), &rid.0);
            Err(Box::new(error_full(&rid.0, rej.code(), format!("{rej:?}"), None, rej.server_time())))
        }
    }
}

async fn wait_cached(app: &App, a: &remoter_auth::Admitted, rid: &Rid) -> Response {
    let deadline = tokio::time::Instant::now() + AGENT_MUTATE;
    while tokio::time::Instant::now() < deadline {
        if let Some(c) = app.verifier.cached(a) {
            return raw_json(c.status, c.body);
        }
        tokio::time::sleep(Duration::from_millis(100)).await;
    }
    error(&rid.0, ErrorCode::Internal, "the first copy is still running")
}

#[allow(clippy::too_many_arguments)]
async fn signed(
    State(app): State<Arc<App>>,
    Extension(peer): Extension<Peer>,
    Extension(rid): Extension<Rid>,
    method: Method,
    uri: OriginalUri,
    headers: HeaderMap,
    body: Result<Bytes, BytesRejection>,
) -> Response {
    let Ok(body) = body else {
        return error(&rid.0, ErrorCode::BadRequest, "body too large or unreadable");
    };
    // charge before checking the signature, keeps the replay store bounded
    if let Err(r) = gate(&app, &peer, &rid, Kind::Mutation) {
        return *r;
    }
    let target = uri.path_and_query().map(|p| p.as_str().to_owned()).unwrap_or_else(|| uri.path().to_owned());
    let admitted = match verify(&app, &peer, &rid, &method, &target, &headers, &body) {
        Ok(a) => a,
        Err(r) => return *r,
    };
    match &admitted.admit {
        Admit::Replay(c) => return raw_json(c.status, c.body.clone()),
        Admit::InFlight => return wait_cached(&app, &admitted, &rid).await,
        Admit::Conflict => return error(&rid.0, ErrorCode::NonceReused, "nonce reused"),
        Admit::Fresh => {}
    }
    let path = target.split_once('?').map_or(target.as_str(), |(p, _)| p);
    let action = action_of(&method, path);
    // not cached as the answer: after re-attesting, the same bytes may run
    if action != "unpair" && !lock(&app.attest).is_fresh(&peer.device) {
        app.verifier.forget(&admitted);
        return error(&rid.0, ErrorCode::ReattestRequired, "re-attestation due");
    }
    let reply = if app.is_locked() && action != "view_token" && action != "unpair" {
        mutate_error(&rid, ErrorCode::Locked, "locked", None)
    } else if action == "spawn"
        && let Err(retry) = app.take(&peer.device, Kind::Spawn)
    {
        mutate_error(&rid, ErrorCode::RateLimited, "spawn rate limited", Some(retry))
    } else {
        let req = AgentRequest::Mutate {
            device: peer.device.clone(),
            request_id: rid.0.clone(),
            method: method.as_str().into(),
            target: target.clone(),
            headers: sig_headers(&headers),
            body: b64::encode(&body),
        };
        match app.agent.call_as::<MutateReply>(&req, AGENT_MUTATE).await {
            Ok(r) => r,
            Err(CallError::Down(m)) => {
                // forget it so a retry can run. if the agent did run it, its own store answers.
                app.verifier.forget(&admitted);
                app.audit(Some(&peer.device), action, audit_path(action, path, &body), "agent_down", &rid.0);
                return error(&rid.0, ErrorCode::AgentDown, m);
            }
            Err(CallError::Failed(f)) => mutate_error(&rid, f.code, &f.message, None),
        }
    };
    app.verifier.complete(&admitted, Cached { status: reply.status, body: reply.body.clone(), audit_path: reply.audit_path.clone() });
    // prefer the agent's canonical path
    let at = reply.audit_path.clone().or_else(|| audit_path(action, path, &body));
    app.audit(Some(&peer.device), action, at, &result_of(reply.status, &reply.body), &rid.0);
    if action == "unpair"
        && reply.status < 300
        && let Err(e) = app.unpair_later(&peer.device)
    {
        tracing::error!(%e, "unpair recorded by the agent but not by remoterd");
    }
    raw_json(reply.status, reply.body)
}

fn mutate_error(rid: &Rid, code: ErrorCode, message: &str, retry_after_s: Option<u32>) -> MutateReply {
    let body = ErrorBody { code, message: message.into(), request_id: rid.0.clone(), retry_after_s, sessions: None, server_time: None };
    MutateReply { status: code.http_status(), body: serde_json::to_string(&body).unwrap_or_default(), audit_path: None }
}

type SseItem = Result<SseEvent, Infallible>;

fn sse_of(e: &remoter_proto::api::Event, id: Option<u64>) -> SseEvent {
    let ev = SseEvent::default().event(e.name()).data(e.data_json());
    match id {
        Some(i) => ev.id(i.to_string()),
        None => ev,
    }
}

async fn events(
    State(app): State<Arc<App>>,
    Extension(peer): Extension<Peer>,
    Extension(rid): Extension<Rid>,
    Path(id): Path<String>,
    headers: HeaderMap,
) -> Response {
    if let Err(r) = gate(&app, &peer, &rid, Kind::Read) {
        return *r;
    }
    let after = headers.get("last-event-id").and_then(|v| v.to_str().ok()).and_then(|s| s.parse::<u64>().ok()).unwrap_or(0);
    let first = match app.agent.call_as::<EventsReply>(&AgentRequest::Events { id: id.clone(), after, wait_ms: 0 }, AGENT_READ).await {
        Ok(r) => r,
        Err(e) => return call_error(&rid.0, e),
    };
    let (tx, rx) = mpsc::channel::<SseItem>(64);
    // tail holds its own sender, so it has to die with the events pump or the stream never closes
    let tail = tokio::spawn(pump_tail(app.clone(), id.clone(), tx.clone()));
    tokio::spawn(async move {
        pump_events(app, id, after, first, tx).await;
        tail.abort();
    });
    let stream = futures_util::stream::unfold(rx, |mut rx| async move { rx.recv().await.map(|e| (e, rx)) });
    Sse::new(stream).keep_alive(KeepAlive::new().interval(LIVE_PING_EVERY).text("ping")).into_response()
}

async fn pump_events(app: Arc<App>, id: String, mut after: u64, first: EventsReply, tx: mpsc::Sender<SseItem>) {
    let mut reply = first;
    loop {
        for se in &reply.events {
            after = after.max(se.seq);
            if tx.send(Ok(sse_of(&se.event, Some(se.seq)))).await.is_err() {
                return;
            }
        }
        if reply.gone || tx.is_closed() {
            return;
        }
        let req = AgentRequest::Events { id: id.clone(), after, wait_ms: EVENTS_WAIT_MS };
        reply = tokio::select! {
            r = app.agent.call_as::<EventsReply>(&req, Duration::from_millis(EVENTS_WAIT_MS as u64 + 10_000)) => match r {
                Ok(r) => r,
                Err(CallError::Down(_)) => {
                    // agent restarting, session is fine
                    tokio::time::sleep(Duration::from_secs(1)).await;
                    EventsReply { events: Vec::new(), gone: false }
                }
                Err(CallError::Failed(_)) => return,
            },
            () = tx.closed() => return,
        };
    }
}

struct TailGuard {
    app: Arc<App>,
    id: String,
    sub: u64,
}

impl Drop for TailGuard {
    fn drop(&mut self) {
        let mut tails = lock(&self.app.tails);
        if let Some(h) = tails.get_mut(&self.id) {
            h.subs.remove(&self.sub);
            if h.subs.is_empty() {
                tails.remove(&self.id);
            }
        }
    }
}

/// First subscriber starts the poller.
fn subscribe(app: &Arc<App>, id: &str) -> (broadcast::Receiver<TailReply>, TailGuard) {
    let mut tails = lock(&app.tails);
    let fresh = !tails.contains_key(id);
    let hub = tails.entry(id.to_owned()).or_insert_with(|| TailHub { tx: broadcast::channel(8).0, subs: Default::default(), next: 0 });
    let sub = hub.next;
    hub.next += 1;
    hub.subs.insert(sub);
    let rx = hub.tx.subscribe();
    if fresh {
        tokio::spawn(poll_tail(app.clone(), id.to_owned()));
    }
    (rx, TailGuard { app: app.clone(), id: id.to_owned(), sub })
}

/// One screen read per session however many phones watch. Stops when nobody does.
async fn poll_tail(app: Arc<App>, id: String) {
    let mut last: Option<Vec<String>> = None;
    loop {
        let tx = {
            let tails = lock(&app.tails);
            match tails.get(&id) {
                Some(h) if !h.subs.is_empty() => h.tx.clone(),
                _ => return,
            }
        };
        if let Ok(t) = app.agent.call_as::<TailReply>(&AgentRequest::Tail { id: id.clone() }, AGENT_READ).await
            && last.as_ref() != Some(&t.lines)
        {
            last = Some(t.lines.clone());
            let _ = tx.send(t);
        }
        tokio::time::sleep(TAIL_EVERY).await;
    }
}

async fn pump_tail(app: Arc<App>, id: String, tx: mpsc::Sender<SseItem>) {
    let (mut rx, _guard) = subscribe(&app, &id);
    // the poller only sends changes, a new subscriber needs the current screen first
    if let Ok(t) = app.agent.call_as::<TailReply>(&AgentRequest::Tail { id: id.clone() }, AGENT_READ).await {
        let ev = remoter_proto::api::Event::Tail { lines: t.lines, at: t.at };
        if tx.send(Ok(sse_of(&ev, None))).await.is_err() {
            return;
        }
    }
    loop {
        let got = tokio::select! {
            r = rx.recv() => r,
            () = tx.closed() => return,
        };
        match got {
            Ok(t) => {
                let ev = remoter_proto::api::Event::Tail { lines: t.lines, at: t.at };
                if tx.send(Ok(sse_of(&ev, None))).await.is_err() {
                    return;
                }
            }
            Err(broadcast::error::RecvError::Lagged(_)) => {}
            Err(broadcast::error::RecvError::Closed) => return,
        }
    }
}

/// Full snapshot per event, no ids, a reconnect just starts over.
async fn live(State(app): State<Arc<App>>, Extension(peer): Extension<Peer>, Extension(rid): Extension<Rid>) -> Response {
    if let Err(r) = gate(&app, &peer, &rid, Kind::Read) {
        return *r;
    }
    let (tx, rx) = mpsc::channel::<SseItem>(16);
    tokio::spawn(pump_live_sessions(app.clone(), tx.clone()));
    tokio::spawn(pump_live_health(app.clone(), peer.device.clone(), tx.clone()));
    tokio::spawn(pump_live_resources(app.clone(), tx));
    let stream = futures_util::stream::unfold(rx, |mut rx| async move { rx.recv().await.map(|e| (e, rx)) });
    Sse::new(stream).keep_alive(KeepAlive::new().interval(LIVE_PING_EVERY).text("ping")).into_response()
}

fn json_event(name: &str, v: &impl serde::Serialize) -> SseEvent {
    SseEvent::default().event(name).data(serde_json::to_string(v).unwrap_or_else(|_| "{}".into()))
}

async fn pump_live_sessions(app: Arc<App>, tx: mpsc::Sender<SseItem>) {
    let mut after = 0;
    let mut last: Option<Vec<SessionSummary>> = None;
    loop {
        let req = AgentRequest::Live { after, wait_ms: LIVE_WAIT_MS };
        let got = tokio::select! {
            r = app.agent.call_as::<LiveReply>(&req, Duration::from_millis(LIVE_WAIT_MS as u64 + 10_000)) => r,
            () = tx.closed() => return,
        };
        match got {
            Ok(r) => {
                after = r.seq;
                // seq moves on phases too
                if last.as_ref() != Some(&r.sessions) {
                    if tx.send(Ok(json_event("sessions", &LiveSessions { sessions: r.sessions.clone() }))).await.is_err() {
                        return;
                    }
                    last = Some(r.sessions);
                }
            }
            // agent restarting, keep the stream
            Err(_) => tokio::select! {
                () = tokio::time::sleep(Duration::from_secs(1)) => {}
                () = tx.closed() => return,
            },
        }
    }
}

async fn pump_live_health(app: Arc<App>, device: String, tx: mpsc::Sender<SseItem>) {
    let mut last: Option<Health> = None;
    let mut every = tokio::time::interval(LIVE_HEALTH_EVERY);
    every.set_missed_tick_behavior(tokio::time::MissedTickBehavior::Delay);
    loop {
        tokio::select! {
            _ = every.tick() => {}
            () = tx.closed() => return,
        }
        let h = health_of(&app, &device).await;
        let same = last.as_ref().is_some_and(|l| Health { server_time: h.server_time, ..l.clone() } == h);
        if !same {
            if tx.send(Ok(json_event("health", &h))).await.is_err() {
                return;
            }
            last = Some(h);
        }
    }
}

/// Sent when it moved, which on a working laptop is nearly every tick. An agent too old to
/// know the request just never gets one in.
async fn pump_live_resources(app: Arc<App>, tx: mpsc::Sender<SseItem>) {
    let mut last: Option<Resources> = None;
    let mut every = tokio::time::interval(LIVE_HEALTH_EVERY);
    every.set_missed_tick_behavior(tokio::time::MissedTickBehavior::Delay);
    loop {
        tokio::select! {
            _ = every.tick() => {}
            () = tx.closed() => return,
        }
        match app.agent.call_as::<Resources>(&AgentRequest::Resources {}, Duration::from_secs(3)).await {
            Ok(r) if last.as_ref() != Some(&r) => {
                if tx.send(Ok(json_event("resources", &r))).await.is_err() {
                    return;
                }
                last = Some(r);
            }
            Ok(_) | Err(CallError::Down(_)) => {}
            Err(CallError::Failed(_)) => return,
        }
    }
}
