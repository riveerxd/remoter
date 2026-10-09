//! Agent end of `/run/remoter/agent.sock`. Only remoterd's uid gets an answer,
//! and every mutation's signature is checked again here with our own replay
//! store, so a compromised remoterd can't forge anything.

use std::io::{Read, Write};
use std::os::unix::net::{UnixListener, UnixStream};
use std::path::PathBuf;
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};

use remoter_auth::{Admit, Cached, Devices, Request, Verifier};
use remoter_proto::api::{ErrorBody, MkdirRequest, SessionDetail, SessionState, SessionsResponse, SignalRequest, SpawnRequest, SpawnResponse, ViewTokenResponse};
use remoter_proto::ipc::{
    AgentFailure, AgentReply, AgentRequest, AgentStatus, EventsReply, LiveReply, MAX_EVENTS_WAIT_MS, MAX_FRAME, MutateReply, SeqEvent,
    TailReply,
};
use remoter_proto::{ErrorCode, b64, local};

use crate::guard::{Home, parse_rel};
use crate::list::{Lister, STAT_BUDGET};
use crate::notify::Notifier;
use crate::procs::Procs;
use crate::recent::History;
use crate::search::{Searcher, TIME_BUDGET};
use crate::sessions::Sessions;
use crate::trust::Trust;
use crate::{AgentError, mkdir};

pub struct AgentCtx {
    pub home: Home,
    pub trust: Trust,
    pub sessions: Sessions,
    pub cwd_deny: Vec<String>,
    pub search_skip: Vec<String>,
    pub max_sessions: u32,
    pub git_bin: PathBuf,
    pub devices_file: PathBuf,
    /// written by remoterd until `remoterctl` drops them from the device file
    pub unpaired_file: PathBuf,
    pub locked_flag: PathBuf,
    pub verifier: Verifier,
    pub history: Option<Arc<History>>,
    pub notifier: Box<dyn Notifier>,
    pub peer_uid: u32,
    /// remoterd can't write here: our sticky lock and unpaired list
    pub agent_state: PathBuf,
    pub autolock: Mutex<remoter_auth::autolock::AutoLock>,
    pub procs: Procs,
}

pub const AGENT_LOCK_FILE: &str = "locked";
pub const AGENT_UNPAIRED_FILE: &str = "unpaired.json";

/// `remoterctl lock off`, agent half.
pub fn clear_agent_lock(state: &std::path::Path) -> std::io::Result<()> {
    match std::fs::remove_file(state.join(AGENT_LOCK_FILE)) {
        Err(e) if e.kind() != std::io::ErrorKind::NotFound => Err(e),
        _ => Ok(()),
    }
}

/// Copies remoterd's lock into ours, so a compromised remoterd deleting its
/// flag doesn't unlock anything. Runs on a timer, because while locked
/// remoterd never forwards a mutation and we'd never look otherwise.
pub fn adopt_remoterd_lock(state: &std::path::Path, remoterd_flag: &std::path::Path) -> bool {
    if !exists_or_unreadable(remoterd_flag) || exists_or_unreadable(&state.join(AGENT_LOCK_FILE)) {
        return false;
    }
    let _ = std::fs::create_dir_all(state);
    match local::write_atomic(&state.join(AGENT_LOCK_FILE), b"remoterd locked") {
        Ok(()) => true,
        Err(e) => {
            eprintln!("remoter-agent: can't write own lock flag: {e}");
            false
        }
    }
}

fn exists_or_unreadable(p: &std::path::Path) -> bool {
    !matches!(std::fs::symlink_metadata(p), Err(e) if e.kind() == std::io::ErrorKind::NotFound)
}

const READ_TIMEOUT: Duration = Duration::from_secs(5);
// one per open event stream plus whatever is in flight
pub const MAX_CONNECTIONS: usize = 64;
const IN_FLIGHT_WAIT: Duration = Duration::from_secs(25);

pub fn serve(listener: UnixListener, ctx: Arc<AgentCtx>) -> std::io::Result<()> {
    let active = Arc::new(std::sync::atomic::AtomicUsize::new(0));
    for conn in listener.incoming() {
        let Ok(stream) = conn else { continue };
        // before spawning a thread, a stranger costs one syscall
        if !matches!(crate::peer::peer_uid(&stream), Ok(uid) if uid == ctx.peer_uid) {
            continue;
        }
        if active.fetch_add(1, std::sync::atomic::Ordering::SeqCst) >= MAX_CONNECTIONS {
            active.fetch_sub(1, std::sync::atomic::Ordering::SeqCst);
            continue;
        }
        let (ctx, active) = (ctx.clone(), active.clone());
        std::thread::spawn(move || {
            handle(&ctx, stream);
            active.fetch_sub(1, std::sync::atomic::Ordering::SeqCst);
        });
    }
    Ok(())
}

/// One deadline for the whole request, a trickle can't keep it alive.
fn read_request(stream: &mut UnixStream) -> Option<Vec<u8>> {
    let deadline = Instant::now() + READ_TIMEOUT;
    let mut buf = Vec::new();
    let mut chunk = [0u8; 16 * 1024];
    loop {
        let left = deadline.checked_duration_since(Instant::now()).filter(|d| !d.is_zero())?;
        stream.set_read_timeout(Some(left)).ok()?;
        match stream.read(&mut chunk) {
            Ok(0) => return Some(buf),
            Ok(n) => {
                buf.extend_from_slice(&chunk[..n]);
                if buf.len() > MAX_FRAME {
                    return None;
                }
            }
            Err(e) if e.kind() == std::io::ErrorKind::Interrupted => {}
            Err(_) => return None,
        }
    }
}

pub fn handle(ctx: &AgentCtx, mut stream: UnixStream) {
    // again, for callers that pass a stream in directly
    match crate::peer::peer_uid(&stream) {
        Ok(uid) if uid == ctx.peer_uid => {}
        _ => return,
    }
    let Some(buf) = read_request(&mut stream) else { return };
    let reply = match serde_json::from_slice::<AgentRequest>(&buf) {
        Ok(req) => ctx.dispatch(req),
        Err(e) => fail(AgentError::new(ErrorCode::BadRequest, format!("request: {e}"))),
    };
    if let Ok(out) = serde_json::to_vec(&reply) {
        let _ = stream.set_write_timeout(Some(READ_TIMEOUT));
        let _ = stream.write_all(&out);
    }
}

fn ok<T: serde::Serialize>(v: T) -> AgentReply {
    match serde_json::to_value(v) {
        Ok(v) => AgentReply::Ok(v),
        Err(e) => fail(AgentError::new(ErrorCode::Internal, e.to_string())),
    }
}

fn fail(e: AgentError) -> AgentReply {
    AgentReply::Err(AgentFailure { code: e.code, message: e.detail })
}

fn reply<T: serde::Serialize>(r: Result<T, AgentError>) -> AgentReply {
    match r {
        Ok(v) => ok(v),
        Err(e) => fail(e),
    }
}

impl AgentCtx {
    fn dispatch(&self, req: AgentRequest) -> AgentReply {
        match req {
            AgentRequest::Status {} => ok(AgentStatus { sessions: self.live_sessions() }),
            AgentRequest::List { path, hidden } => reply(self.list(&path, hidden)),
            AgentRequest::Search { path, q } => reply(self.search(&path, &q)),
            AgentRequest::Recent {} => ok(self.history.as_ref().map(|h| h.recent(&self.home)).unwrap_or(
                remoter_proto::api::RecentResponse { entries: Vec::new(), typical_start_ms: None },
            )),
            AgentRequest::Sessions {} => ok(SessionsResponse { sessions: self.sessions.list(), cap: self.max_sessions }),
            AgentRequest::Session { id } => reply(self.detail(&id)),
            AgentRequest::History { path } => reply(self.history(&path)),
            AgentRequest::Tail { id } => reply(self.tail(&id)),
            AgentRequest::Events { id, after, wait_ms } => reply(self.events(&id, after, wait_ms)),
            AgentRequest::Live { after, wait_ms } => ok(self.live(after, wait_ms)),
            AgentRequest::Resources {} => ok(self.procs.resources(self.home.fd())),
            AgentRequest::Procs {} => ok(self.procs.procs(self.home.fd(), &self.session_names())),
            AgentRequest::Mutate { device, request_id, method, target, headers, body } => {
                let body = b64::decode(&body).unwrap_or_default();
                ok(self.mutate(&device, &request_id, &method, &target, &headers, &body))
            }
        }
    }

    fn live_sessions(&self) -> u32 {
        self.sessions
            .list()
            .iter()
            .filter(|s| matches!(s.state, SessionState::Starting | SessionState::Ready | SessionState::Stuck))
            .count() as u32
    }

    fn session_names(&self) -> std::collections::HashMap<String, String> {
        self.sessions.list().into_iter().filter(|s| s.state != SessionState::Gone).map(|s| (s.id, s.name)).collect()
    }

    fn rel(path_b64: &str) -> Result<crate::guard::RelPath, AgentError> {
        let raw = b64::decode(path_b64).ok_or_else(|| AgentError::new(ErrorCode::BadRequest, "path encoding"))?;
        parse_rel(&raw)
    }

    fn list(&self, path: &str, hidden: bool) -> Result<remoter_proto::api::ListResponse, AgentError> {
        let counts = self.sessions.counts();
        let lister =
            Lister { home: &self.home, trust: &self.trust, cwd_deny: &self.cwd_deny, sessions: &counts, budget: STAT_BUDGET };
        lister.list(&Self::rel(path)?, hidden)
    }

    fn search(&self, path: &str, q: &str) -> Result<remoter_proto::api::SearchResponse, AgentError> {
        let recent = self.history.as_ref().map(|h| h.paths()).unwrap_or_default();
        let s = Searcher { home: &self.home, skip: &self.search_skip, recent: &recent, budget: TIME_BUDGET };
        s.search(&Self::rel(path)?, q)
    }

    fn history(&self, path: &str) -> Result<remoter_proto::api::HistoryResponse, AgentError> {
        self.sessions.history(&Self::rel(path)?)
    }

    fn detail(&self, id: &str) -> Result<SessionDetail, AgentError> {
        let session = self.sessions.get(id)?;
        let (tail, tail_at) = self.sessions.tail(id)?;
        Ok(SessionDetail { session, tail, tail_at })
    }

    fn tail(&self, id: &str) -> Result<TailReply, AgentError> {
        self.sessions.get(id)?;
        let (lines, at) = self.sessions.tail(id)?;
        Ok(TailReply { lines, at })
    }

    fn live(&self, after: u64, wait_ms: u32) -> LiveReply {
        let wait = Duration::from_millis(wait_ms.min(MAX_EVENTS_WAIT_MS) as u64);
        let (seq, sessions) = self.sessions.live(after, wait);
        LiveReply { seq, sessions }
    }

    fn events(&self, id: &str, after: u64, wait_ms: u32) -> Result<EventsReply, AgentError> {
        self.sessions.get(id)?;
        let wait = Duration::from_millis(wait_ms.min(MAX_EVENTS_WAIT_MS) as u64);
        let events = self.sessions.events(id, after, wait).into_iter().map(|(seq, event)| SeqEvent { seq, event }).collect();
        let gone = self.sessions.get(id).map(|s| s.state == SessionState::Gone).unwrap_or(true);
        Ok(EventsReply { events, gone })
    }

    fn devices(&self) -> Result<Devices, AgentError> {
        let all = Devices::load(&self.devices_file).map_err(|e| AgentError::new(ErrorCode::Internal, e))?;
        // both lists only remove devices. ours is the one remoterd can't undo
        let theirs = remoter_auth::unpaired::effective(&all, &self.unpaired_file);
        Ok(remoter_auth::unpaired::effective(&theirs, &self.agent_state.join(AGENT_UNPAIRED_FILE)))
    }

    /// Seeing remoterd's flag sets ours, so deleting theirs changes nothing.
    fn locked(&self) -> bool {
        if exists_or_unreadable(&self.agent_state.join(AGENT_LOCK_FILE)) {
            return true;
        }
        if exists_or_unreadable(&self.locked_flag) {
            self.lock_self("remoterd locked");
            return true;
        }
        false
    }

    fn lock_self(&self, why: &str) {
        let _ = std::fs::create_dir_all(&self.agent_state);
        if let Err(e) = local::write_atomic(&self.agent_state.join(AGENT_LOCK_FILE), why.as_bytes()) {
            eprintln!("remoter-agent: can't write own lock flag: {e}");
        }
    }

    pub fn mutate(&self, device: &str, request_id: &str, method: &str, target: &str, headers: &remoter_auth::RawSigHeaders, body: &[u8]) -> MutateReply {
        let answer = |e: AgentError, server_time: Option<i64>| error_reply(e, request_id, server_time, None);
        let devices = match self.devices() {
            Ok(d) => d,
            Err(e) => return answer(e, None),
        };
        let admitted = match self.verifier.check(
            &devices,
            &Request { mtls_device: device, headers, method, target, body, now_ms: local::now_ms() },
        ) {
            Ok(a) => a,
            Err(rej) => {
                let now = std::time::Instant::now();
                if self.autolock.lock().unwrap_or_else(|p| p.into_inner()).record(device, rej.lock_weight(), now) {
                    self.lock_self("bad signatures");
                    self.notifier.notify(true, "remoter locked this laptop after bad signatures from a paired phone.");
                }
                return answer(AgentError::new(rej.code(), format!("{rej:?}")), rej.server_time());
            }
        };
        match &admitted.admit {
            Admit::Replay(c) => return MutateReply { status: c.status, body: c.body.clone(), audit_path: c.audit_path.clone() },
            Admit::InFlight => {
                let deadline = Instant::now() + IN_FLIGHT_WAIT;
                while Instant::now() < deadline {
                    if let Some(c) = self.verifier.cached(&admitted) {
                        return MutateReply { status: c.status, body: c.body, audit_path: c.audit_path };
                    }
                    std::thread::sleep(Duration::from_millis(100));
                }
                return answer(AgentError::new(ErrorCode::Internal, "the first copy is still running"), None);
            }
            Admit::Conflict => return answer(AgentError::new(ErrorCode::NonceReused, "nonce reused"), None),
            Admit::Fresh => {}
        }
        let out = self.run(&admitted.device, request_id, method, target, body, &devices);
        self.verifier.complete(&admitted, Cached { status: out.status, body: out.body.clone(), audit_path: out.audit_path.clone() });
        out
    }

    fn run(&self, device: &str, request_id: &str, method: &str, target: &str, body: &[u8], devices: &Devices) -> MutateReply {
        let path = target.split_once('?').map_or(target, |(p, _)| p);
        // view token is a read and unpairing only makes things safer
        let exempt = (method == "POST" && path == "/v1/view-token") || (method == "DELETE" && path == "/v1/devices/self");
        if !exempt && self.locked() {
            return error_reply(AgentError::new(ErrorCode::Locked, "locked"), request_id, None, None);
        }
        // third value is the canonical path, the requested one may be a symlink
        let result: Result<(u16, String, Option<String>), AgentError> = match (method, path) {
            ("POST", "/v1/fs/mkdir") => parse::<MkdirRequest>(body).and_then(|r| {
                let made = mkdir::mkdir(&self.home, &self.cwd_deny, &self.git_bin, &r)?;
                let at = made.path.clone();
                Ok((201, to_json(&made), Some(at)))
            }),
            ("POST", "/v1/sessions") => parse::<SpawnRequest>(body).and_then(|r| {
                let id = self.sessions.spawn(&r, Some(device))?;
                let at = self.sessions.get(&id)?.path;
                let (view_token, view_token_expires) = self.issue()?;
                let name = devices.get(device).map(|d| d.record.name.clone()).unwrap_or_else(|| device.into());
                let verb = if r.resume.is_some() { "resumed" } else { "started" };
                let with = if r.handoff.is_some() { " with a handoff" } else { "" };
                self.notifier.notify(false, &format!("remoter {verb} {}{with} in ~/{at} from {name}", r.name.trim()));
                Ok((202, to_json(&SpawnResponse { id, view_token, view_token_expires }), Some(at)))
            }),
            ("DELETE", p) if p.starts_with("/v1/sessions/") && body.is_empty() => {
                let id = &p["/v1/sessions/".len()..];
                self.sessions.kill(id).map(|()| (202, "{}".to_owned(), Some(id.to_owned())))
            }
            ("POST", p) if p.starts_with("/v1/procs/") && p.ends_with("/signal") => {
                let pid = p["/v1/procs/".len()..p.len() - "/signal".len()].parse::<i32>().map_err(|_| AgentError::new(ErrorCode::BadRequest, "pid"));
                pid.and_then(|pid| {
                    let r = parse::<SignalRequest>(body)?;
                    let what = self.procs.signal(pid, r.start, r.signal)?;
                    Ok((202, "{}".to_owned(), Some(what)))
                })
            }
            ("POST", "/v1/view-token") => {
                self.issue().map(|(token, expires)| (200, to_json(&ViewTokenResponse { token, expires }), None))
            }
            ("DELETE", "/v1/devices/self") if body.is_empty() => self.unpair(device).map(|()| (200, "{}".to_owned(), None)),
            _ => Err(AgentError::new(ErrorCode::NotFound, "no such mutation")),
        };
        match result {
            Ok((status, body, audit_path)) => MutateReply { status, body, audit_path },
            Err(e) => {
                let sessions = match e.code {
                    ErrorCode::SessionCap => Some(self.sessions.list()),
                    // just the one in the way, the phone offers to open it
                    ErrorCode::FolderBusy => {
                        let busy = e.detail.split_whitespace().next().unwrap_or_default().to_owned();
                        Some(self.sessions.list().into_iter().filter(|s| s.id == busy).collect())
                    }
                    _ => None,
                };
                error_reply(e, request_id, None, sessions)
            }
        }
    }

    fn unpair(&self, device: &str) -> Result<(), AgentError> {
        let f = self.agent_state.join(AGENT_UNPAIRED_FILE);
        let mut ids = match remoter_auth::unpaired::read(&f) {
            remoter_auth::unpaired::Unpaired::Some(s) => s,
            remoter_auth::unpaired::Unpaired::Unreadable => Default::default(),
        };
        ids.insert(device.to_owned());
        let mut v: Vec<String> = ids.into_iter().collect();
        v.sort();
        let json = serde_json::to_vec(&remoter_auth::unpaired::UnpairedFile { devices: v }).map_err(|e| AgentError::new(ErrorCode::Internal, e.to_string()))?;
        std::fs::create_dir_all(&self.agent_state).map_err(|e| AgentError::new(ErrorCode::Internal, e.to_string()))?;
        local::write_atomic(&f, &json).map_err(|e| AgentError::new(ErrorCode::Internal, e.to_string()))
    }

    fn issue(&self) -> Result<(String, i64), AgentError> {
        crate::tokens::mint(local::now_ms()).ok_or_else(|| AgentError::new(ErrorCode::Internal, "no token"))
    }
}

fn parse<T: serde::de::DeserializeOwned>(body: &[u8]) -> Result<T, AgentError> {
    serde_json::from_slice(body).map_err(|e| AgentError::new(ErrorCode::BadRequest, format!("body: {e}")))
}

fn to_json<T: serde::Serialize>(v: &T) -> String {
    serde_json::to_string(v).unwrap_or_else(|_| "{}".into())
}

pub fn error_reply(
    e: AgentError,
    request_id: &str,
    server_time: Option<i64>,
    sessions: Option<Vec<remoter_proto::api::SessionSummary>>,
) -> MutateReply {
    let body = ErrorBody {
        code: e.code,
        message: e.detail,
        request_id: request_id.to_owned(),
        retry_after_s: None,
        sessions,
        server_time,
    };
    MutateReply { status: e.code.http_status(), body: to_json(&body), audit_path: None }
}

#[cfg(test)]
mod lock_tests {
    use super::*;

    #[test]
    fn remoterd_lock_becomes_the_agents_own() {
        let root = std::path::PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("../target/tmp").join(format!("adopt-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&root);
        std::fs::create_dir_all(&root).expect("mk");
        let (state, flag) = (root.join("state"), root.join("locked"));
        assert!(!adopt_remoterd_lock(&state, &flag), "nothing to adopt");
        std::fs::write(&flag, b"").expect("flag");
        assert!(adopt_remoterd_lock(&state, &flag));
        assert!(state.join(AGENT_LOCK_FILE).exists());
        std::fs::remove_file(&flag).expect("rm");
        assert!(state.join(AGENT_LOCK_FILE).exists(), "remoterd dropping its flag changes nothing");
        assert!(!adopt_remoterd_lock(&state, &flag));
        clear_agent_lock(&state).expect("clear");
        assert!(!state.join(AGENT_LOCK_FILE).exists());
        let _ = std::fs::remove_dir_all(&root);
    }
}
