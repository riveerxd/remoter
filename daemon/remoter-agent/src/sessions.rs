//! Spawn, list, watch, kill. A session is its `rc-<id>.scope` plus its runtime
//! dir and all state is derived from those, so an agent restart loses nothing.

use std::collections::{HashMap, HashSet};
use std::os::unix::fs::{DirBuilderExt, MetadataExt};
use std::path::{Component, Path, PathBuf};
use std::process::{Command, Stdio};
use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
use std::sync::{Arc, Condvar, Mutex, MutexGuard};
use std::time::{Duration, Instant};

use remoter_proto::ErrorCode;
use remoter_proto::api::{ClaudeLink, Event, HistoryResponse, Phase, SessionState, SessionSummary, SpawnMode, SpawnRequest, StuckReason};
use remoter_proto::local::{self, DEBUG_FILE, ENDING_FILE, ExecState, HANDOFF_FILE, HANDOFF_PROMPT, HANDOFF_SOURCE_FILE, HandoffSpec, SPEC_FILE, STATUS_FILE, Spec, Status};
use remoter_proto::names::is_valid_session_name;

use crate::guard::{Home, RelPath, parse_rel};
use crate::launcher::Launcher;
use crate::transcripts::{Transcripts, condense, is_uuid};
use crate::trust::Trust;
use crate::{AgentError, policy, sys};

/// `--debug-file` lines, in the order they appear.
pub const LINE_CLAUDE: &str = "[bridge:init] bridgeId=";
pub const LINE_REGISTERED: &str = "[bridge:init] Registered,";
pub const LINE_READY: &str = "[bridge:init] Created initial session";
/// untrusted folder, claude exits 1 after this
pub const TEXT_UNTRUSTED: &str = "Workspace not trusted";
/// `--worktree` says this instead when only an ancestor is trusted (2.1.286).
pub const TEXT_WORKTREE_UNTRUSTED: &str = "Workspace trust not yet accepted";
/// folder already has a Remote Control on this machine
pub const TEXT_FOLDER_SERVED: &str = "already served";
/// The interactive trust dialog as 2.1.295 draws it. It waits there for a key.
const DIALOG_LINES: [&str; 3] = ["Accessing workspace:", "No, exit", "Yes, I trust this folder"];

/// Set when claude stopped at the trust dialog and we ended it.
pub const TRUST_DIALOG_FILE: &str = "trust-dialog";

/// Written on the first Ready so the link survives a restart and a truncated debug log.
pub const LINK_FILE: &str = "link.json";

/// claude leaves the worktree and branch behind when killed, so we remember it to clean up.
pub const WORKTREE_FILE: &str = "worktree.json";

#[derive(serde::Serialize, serde::Deserialize)]
struct WorktreeFile {
    path: String,
    name: String,
}

pub const TAIL_LINES: usize = 40;
// 90s: a start is ~2s, but claude once took 30s just loading CA certs. Real
// failures show up on screen in seconds anyway, this is only for true hangs.
pub const READY_TIMEOUT: Duration = Duration::from_secs(90);
const KILL_GRACE: Duration = Duration::from_secs(3);
const SCOPE_WAIT: Duration = Duration::from_secs(3);
const DEBUG_READ_CAP: u64 = 1024 * 1024;
// status.json can land before the terminal has drawn claude's last words
const EXIT_VERDICT_GRACE_MS: i64 = 2000;

pub struct SessionsConfig {
    pub cwd_deny: Vec<String>,
    pub claude_bin: PathBuf,
    pub git_bin: PathBuf,
    pub max_sessions: u32,
    /// `$XDG_RUNTIME_DIR/remoter`, one 0700 dir per session
    pub runtime_base: PathBuf,
    /// remoterd's lock flag: while it exists nothing is spawned or killed
    pub locked_flag: PathBuf,
    pub ready_timeout: Duration,
    pub exited_ttl: Duration,
    pub history: Option<Arc<crate::recent::History>>,
    pub transcripts: Option<Arc<Transcripts>>,
}

pub struct Sessions {
    inner: Arc<Inner>,
}

struct Inner {
    cfg: SessionsConfig,
    home: Home,
    trust: Trust,
    launcher: Box<dyn Launcher>,
    spawn_lock: Mutex<()>,
    events: Mutex<HashMap<String, Vec<(u64, Event)>>>,
    changed: Condvar,
    seq: AtomicU64,
    watching: Mutex<HashSet<String>>,
    /// read once off the window, None is a plain exit
    exit_reasons: Mutex<HashMap<String, Option<StuckReason>>>,
    shutdown: AtomicBool,
}

fn lock<T>(m: &Mutex<T>) -> MutexGuard<'_, T> {
    m.lock().unwrap_or_else(|p| p.into_inner())
}

fn err(code: ErrorCode, detail: impl Into<String>) -> AgentError {
    AgentError::new(code, detail)
}

pub fn is_session_id(id: &str) -> bool {
    id.len() == 29
        && id.starts_with("rc-")
        && remoter_proto::canonical::is_device_id(&id[3..].to_ascii_uppercase())
        && id[3..].bytes().all(|b| !b.is_ascii_uppercase())
}

#[derive(Debug, Clone)]
pub struct Derived {
    pub summary: SessionSummary,
    pub phases: Vec<Phase>,
    exited_at: Option<i64>,
}

impl Sessions {
    pub fn new(cfg: SessionsConfig, home: Home, trust: Trust, launcher: Box<dyn Launcher>) -> Result<Sessions, AgentError> {
        ensure_private_dir(&cfg.runtime_base)?;
        let inner = Arc::new(Inner {
            cfg,
            home,
            trust,
            launcher,
            spawn_lock: Mutex::new(()),
            events: Mutex::new(HashMap::new()),
            changed: Condvar::new(),
            // these are the SSE ids, seeded from the clock so they keep rising
            // across restarts and an old Last-Event-ID still works
            seq: AtomicU64::new(local::now_ms().max(1) as u64 * 1000),
            watching: Mutex::new(HashSet::new()),
            exit_reasons: Mutex::new(HashMap::new()),
            shutdown: AtomicBool::new(false),
        });
        let s = Sessions { inner };
        s.recover();
        Ok(s)
    }

    fn recover(&self) {
        let _g = lock(&self.inner.spawn_lock);
        let live = active_scopes();
        for id in self.inner.dirs() {
            if live.contains(&id) {
                Inner::watch(&self.inner, &id);
            } else {
                // ended while nobody was watching
                let dir = self.inner.cfg.runtime_base.join(&id);
                self.inner.clean_worktree(&dir);
                let _ = std::fs::remove_dir_all(dir);
            }
        }
    }

    pub fn spawn(&self, req: &SpawnRequest, device: Option<&str>) -> Result<String, AgentError> {
        let inner = &self.inner;
        let _g = lock(&inner.spawn_lock);

        inner.check_unlocked()?;
        let live = inner.live_count();
        if live >= inner.cfg.max_sessions as usize {
            return Err(err(ErrorCode::SessionCap, format!("{live} running")));
        }
        let rel = parse_rel(req.path.as_bytes())?;
        let opened = inner.home.open_dir(&rel)?;
        if policy::deny_reason(&opened.canonical, &inner.cfg.cwd_deny).is_some() {
            return Err(err(ErrorCode::PathDenied, "folder is on the deny list, or is home"));
        }
        let abs = inner.home.path().join(opened.canonical.as_string());
        // before trust, which writes
        if let Some(conv) = &req.resume {
            inner.check_resume(&abs, conv, req.mode)?;
        }
        // an empty conversation is refused here, not in a window that only says so
        let handoff_source = match &req.handoff {
            Some(conv) => Some((conv.clone(), inner.handoff_source(&abs, conv, req.resume.is_some())?)),
            None => None,
        };
        let name = req.name.trim_matches(' ');
        if !is_valid_session_name(name) {
            return Err(err(ErrorCode::NameInvalid, "session name breaks the rules"));
        }
        if req.mode == SpawnMode::Worktree && sys::statat(&opened.fd, b".git", false).is_err() {
            return Err(err(ErrorCode::BadRequest, "worktree needs a git repo"));
        }
        // claude would catch this too, but only after opening a window to exit 1 in
        if req.mode == SpawnMode::SameDir
            && let Some(id) = inner.live_in(&opened.canonical.as_string())
        {
            return Err(err(ErrorCode::FolderBusy, format!("{id} already runs there")));
        }
        // resolved now, a self update moves the target
        let claude = std::fs::canonicalize(&inner.cfg.claude_bin)
            .ok()
            .filter(|p| p.is_file())
            .ok_or_else(|| err(ErrorCode::SpawnFailed, "claude_bin doesn't exist"))?;
        inner.launcher.prepare()?;

        let id = format!("rc-{}", ulid::Ulid::generate().to_string().to_ascii_lowercase());
        // claude has asked under a trusted parent before, so every start gets its
        // own entry, and a worktree's too. Kept last so a refused spawn writes nothing.
        let worktree = (req.mode == SpawnMode::Worktree).then_some(id.as_str());
        let mut trust = vec![abs.clone()];
        trust.extend(worktree.map(|name| abs.join(".claude/worktrees").join(name)));
        for p in &trust {
            if !inner.trust.is_trusted_here(p) {
                inner.trust.grant(p).map_err(|e| err(ErrorCode::Internal, format!("couldn't trust {}: {e}", p.display())))?;
            }
        }
        let dir = inner.cfg.runtime_base.join(&id);
        std::fs::DirBuilder::new().mode(0o700).create(&dir).map_err(|e| err(ErrorCode::Internal, e.to_string()))?;
        let spec = Spec {
            id: id.clone(),
            dir: dir.display().to_string(),
            cwd: abs.display().to_string(),
            dev: opened.dev,
            ino: opened.ino,
            rel: opened.canonical.as_string(),
            name: name.to_owned(),
            device: device.map(str::to_owned),
            started: local::now_ms(),
            argv: interactive_argv(&claude.display().to_string(), name, worktree, req.resume.as_deref()),
            handoff: handoff_source.as_ref().map(|(conv, (transcript, _))| HandoffSpec {
                from: conv.clone(),
                transcript: transcript.display().to_string(),
                summarizer: summarizer_argv(&claude.display().to_string(), conv),
                source: dir.join(HANDOFF_SOURCE_FILE).display().to_string(),
            }),
        };
        let fail = |e: AgentError| {
            let _ = std::fs::remove_dir_all(&dir);
            e
        };
        if let Some((_, (_, text))) = &handoff_source {
            local::write_atomic(&dir.join(HANDOFF_SOURCE_FILE), text.as_bytes()).map_err(|e| fail(err(ErrorCode::Internal, e.to_string())))?;
        }
        let json = serde_json::to_vec(&spec).map_err(|e| fail(err(ErrorCode::Internal, e.to_string())))?;
        local::write_atomic(&dir.join(SPEC_FILE), &json).map_err(|e| fail(err(ErrorCode::Internal, e.to_string())))?;

        inner.push(&id, Event::Phase { step: Phase::Accepted, at: local::now_ms() });
        inner.launcher.launch(&id, &dir).map_err(fail)?;
        // wait under the lock so the next spawn counts this scope against the cap
        let deadline = Instant::now() + SCOPE_WAIT;
        while !scope_active(&id) {
            if Instant::now() > deadline {
                return Err(fail(err(ErrorCode::SpawnFailed, "scope never appeared")));
            }
            std::thread::sleep(Duration::from_millis(25));
        }
        if let Some(h) = &inner.cfg.history {
            h.record_spawn(&spec.rel, spec.started);
        }
        // Accepted went out before list() could see the scope
        inner.bump();
        Inner::watch(inner, &id);
        Ok(id)
    }

    pub fn list(&self) -> Vec<SessionSummary> {
        let live = active_scopes();
        let mut out: Vec<SessionSummary> = self
            .inner
            .dirs()
            .into_iter()
            .filter(|id| live.contains(id))
            .filter_map(|id| self.inner.derive(&id, true))
            .map(|d| d.summary)
            .collect();
        out.sort_by_key(|s| s.started);
        out
    }

    pub fn get(&self, id: &str) -> Result<SessionSummary, AgentError> {
        self.inner.lookup(id).map(|d| d.summary)
    }

    pub fn history(&self, rel: &RelPath) -> Result<HistoryResponse, AgentError> {
        let opened = self.inner.home.open_dir(rel)?;
        let path = opened.canonical.as_string();
        let Some(t) = &self.inner.cfg.transcripts else {
            return Ok(HistoryResponse { path, conversations: Vec::new(), truncated: false });
        };
        let abs = self.inner.home.path().join(&path);
        let (conversations, truncated) = t.list(&abs, &self.inner.open_conversations(t));
        Ok(HistoryResponse { path, conversations, truncated })
    }

    pub fn counts(&self) -> HashMap<String, u32> {
        let mut m = HashMap::new();
        for s in self.list() {
            if matches!(s.state, SessionState::Starting | SessionState::Ready | SessionState::Stuck) {
                *m.entry(s.path).or_insert(0) += 1;
            }
        }
        m
    }

    pub fn tail(&self, id: &str) -> Result<(Vec<String>, i64), AgentError> {
        self.inner.lookup(id)?;
        let text = self.inner.launcher.screen(&self.inner.cfg.runtime_base.join(id)).unwrap_or_default();
        Ok((last_lines(&text, TAIL_LINES), local::now_ms()))
    }

    /// Returns at once, the ending shows up as events.
    pub fn kill(&self, id: &str) -> Result<(), AgentError> {
        let inner = &self.inner;
        inner.check_unlocked()?;
        let d = inner.lookup(id)?;
        if matches!(d.summary.state, SessionState::Gone) {
            return Err(err(ErrorCode::NotFound, "session is gone"));
        }
        let dir = inner.cfg.runtime_base.join(id);
        let _ = std::fs::write(dir.join(ENDING_FILE), b"");
        inner.push(id, Event::State { state: SessionState::Ending, reason: None, exit_code: None });
        let (inner2, id2) = (self.inner.clone(), id.to_owned());
        std::thread::spawn(move || inner2.end(&id2));
        Ok(())
    }

    /// Events after `after`, waiting up to `timeout` for the first one.
    pub fn events(&self, id: &str, after: u64, timeout: Duration) -> Vec<(u64, Event)> {
        let deadline = Instant::now() + timeout;
        let mut g = lock(&self.inner.events);
        loop {
            let new: Vec<_> =
                g.get(id).map(|v| v.iter().filter(|(s, _)| *s > after).cloned().collect()).unwrap_or_default();
            let left = deadline.saturating_duration_since(Instant::now());
            if !new.is_empty() || left.is_zero() {
                return new;
            }
            g = self.inner.changed.wait_timeout(g, left).map(|(g, _)| g).unwrap_or_else(|p| p.into_inner().0);
        }
    }

    /// seq is read before the list, so a racing change shows up next call instead of getting lost
    pub fn live(&self, after: u64, timeout: Duration) -> (u64, Vec<SessionSummary>) {
        let deadline = Instant::now() + timeout;
        let mut g = lock(&self.inner.events);
        loop {
            let seq = self.inner.seq.load(Ordering::SeqCst);
            let left = deadline.saturating_duration_since(Instant::now());
            if seq > after || left.is_zero() {
                drop(g);
                return (seq, self.list());
            }
            g = self.inner.changed.wait_timeout(g, left).map(|(g, _)| g).unwrap_or_else(|p| p.into_inner().0);
        }
    }

    /// Stops the watchers. Sessions keep running.
    pub fn shutdown(&self) {
        self.inner.shutdown.store(true, Ordering::SeqCst);
    }
}

impl Drop for Sessions {
    fn drop(&mut self) {
        self.shutdown();
    }
}

impl Inner {
    fn check_unlocked(&self) -> Result<(), AgentError> {
        // symlink_metadata: a dangling link still counts, unreadable must not mean unlocked
        match std::fs::symlink_metadata(&self.cfg.locked_flag) {
            Ok(_) => Err(err(ErrorCode::Locked, "lock flag present")),
            Err(e) if e.kind() == std::io::ErrorKind::NotFound => Ok(()),
            Err(e) => Err(err(ErrorCode::Locked, format!("lock flag unreadable: {e}"))),
        }
    }

    fn dirs(&self) -> Vec<String> {
        let Ok(rd) = std::fs::read_dir(&self.cfg.runtime_base) else { return Vec::new() };
        rd.filter_map(|e| e.ok()?.file_name().into_string().ok()).filter(|n| is_session_id(n)).collect()
    }

    fn live_count(&self) -> usize {
        let live = active_scopes();
        self.dirs()
            .into_iter()
            .filter(|id| live.contains(id))
            .filter_map(|id| self.derive(&id, true))
            .filter(|d| d.summary.state != SessionState::Exited)
            .count()
    }

    fn lookup(&self, id: &str) -> Result<Derived, AgentError> {
        if !is_session_id(id) || !self.cfg.runtime_base.join(id).is_dir() {
            return Err(err(ErrorCode::NotFound, "no such session"));
        }
        self.derive(id, scope_active(id)).ok_or_else(|| err(ErrorCode::NotFound, "no such session"))
    }

    fn derive(&self, id: &str, active: bool) -> Option<Derived> {
        let dir = self.cfg.runtime_base.join(id);
        let spec: Spec = serde_json::from_slice(&std::fs::read(dir.join(SPEC_FILE)).ok()?).ok()?;
        let status: Option<Status> =
            std::fs::read(dir.join(STATUS_FILE)).ok().and_then(|b| serde_json::from_slice(&b).ok());
        let debug = read_capped(&dir.join(DEBUG_FILE));

        let mut phases = vec![Phase::Accepted];
        if status.is_some() {
            phases.push(Phase::Terminal);
        }
        if spec.handoff.is_some() && dir.join(HANDOFF_FILE).exists() {
            phases.push(Phase::Handoff);
        }
        // the summarizer has its own deadline, claude's clock starts after it
        let summarizing = status.as_ref().is_some_and(|s| s.state == ExecState::Handoff);
        let clock_from = match &status {
            Some(s) if spec.handoff.is_some() && s.state == ExecState::Running => s.at,
            _ => spec.started,
        };
        if debug_claude_started(&debug) {
            phases.push(Phase::Claude);
        }
        if debug_registered(&debug) {
            phases.push(Phase::RemoteControl);
        }

        let mut reason = None;
        let mut exit_code = None;
        let mut exited_at = None;
        let state = if !active {
            SessionState::Gone
        } else if dir.join(ENDING_FILE).exists() {
            SessionState::Ending
        } else {
            match &status {
                _ if dir.join(TRUST_DIALOG_FILE).exists() => {
                    reason = Some(StuckReason::Untrusted);
                    SessionState::Stuck
                }
                Some(s) if s.state == ExecState::Stuck => {
                    reason = s.reason;
                    SessionState::Stuck
                }
                Some(s) if s.state == ExecState::Exited && matches!(self.exit_verdict(id, &dir, s.at), Some(Some(_))) => {
                    reason = self.exit_verdict(id, &dir, s.at).flatten();
                    SessionState::Stuck
                }
                Some(s) if s.state == ExecState::Exited && self.exit_verdict(id, &dir, s.at) == Some(None) => {
                    exit_code = s.exit_code;
                    exited_at = Some(s.at);
                    SessionState::Exited
                }
                _ if debug_ready(&debug) || dir.join(LINK_FILE).exists() => SessionState::Ready,
                _ if !summarizing && local::now_ms() - clock_from > self.cfg.ready_timeout.as_millis() as i64 => {
                    reason = Some(StuckReason::Timeout);
                    SessionState::Stuck
                }
                _ => SessionState::Starting,
            }
        };
        let worktree = if spec.argv.iter().any(|a| a == "--worktree" || a.starts_with("--worktree=")) { self.worktree(&dir, &spec, status.as_ref()) } else { None };
        let claude = if state == SessionState::Starting {
            None
        } else {
            self.claude_link(&dir, &debug, state == SessionState::Ready)
        };
        Some(Derived {
            summary: SessionSummary {
                claude,
                id: spec.id,
                name: spec.name,
                path: spec.rel,
                device: spec.device,
                started: spec.started,
                state,
                reason,
                exit_code,
                worktree,
            },
            phases,
            exited_at,
        })
    }

    fn claude_link(&self, dir: &Path, debug: &str, ready: bool) -> Option<ClaudeLink> {
        let file = dir.join(LINK_FILE);
        if let Some(saved) = std::fs::read(&file).ok().and_then(|b| serde_json::from_slice::<ClaudeLink>(&b).ok()) {
            // re-check so a tampered file can't hand the phone some other URL
            let env = saved.environment_url.as_deref().and_then(|u| u.strip_prefix("https://claude.ai/code?environment="));
            return ClaudeLink::from_parts(&saved.session_id, env).filter(|l| *l == saved);
        }
        if !ready {
            return None;
        }
        let link = parse_link(debug)?;
        if let Ok(json) = serde_json::to_vec(&link) {
            let _ = local::write_atomic(&file, &json);
        }
        Some(link)
    }

    fn worktree(&self, dir: &Path, spec: &Spec, status: Option<&Status>) -> Option<String> {
        if let Some(saved) = read_worktree(dir, spec) {
            return Some(saved.name);
        }
        let pid = status.filter(|s| s.state == ExecState::Running)?.pid?;
        // pid came from a file
        if !belongs_to(pid, &spec.id) {
            return None;
        }
        let cwd = std::fs::read_link(format!("/proc/{pid}/cwd")).ok()?;
        let name = worktree_name(Path::new(&spec.cwd), &cwd).filter(|_| cwd.is_dir())?;
        let saved = WorktreeFile { path: cwd.to_str()?.to_owned(), name };
        if let Ok(json) = serde_json::to_vec(&saved) {
            let _ = local::write_atomic(&dir.join(WORKTREE_FILE), &json);
        }
        Some(saved.name)
    }

    /// Only what git agrees is safe to drop: never `--force`, never `-D`.
    fn clean_worktree(&self, dir: &Path) {
        let Some(spec) = std::fs::read(dir.join(SPEC_FILE)).ok().and_then(|b| serde_json::from_slice::<Spec>(&b).ok()) else {
            return;
        };
        let Some(wt) = read_worktree(dir, &spec) else { return };
        let git = |cwd: &str, args: &[&str]| {
            Command::new(&self.cfg.git_bin)
                .arg("-C")
                .arg(cwd)
                .args(args)
                .env_remove("GIT_DIR")
                .env_remove("GIT_WORK_TREE")
                .env_remove("GIT_INDEX_FILE")
                .stdin(Stdio::null())
                .output()
        };
        let why = |o: &std::io::Result<std::process::Output>| match o {
            Ok(o) => String::from_utf8_lossy(&o.stderr).trim().to_owned(),
            Err(e) => e.to_string(),
        };
        // claude locks it and never unlocks when killed. fails harmlessly if unlocked.
        let _ = git(&spec.cwd, &["worktree", "unlock", &wt.path]);
        let status = git(&wt.path, &["status", "--porcelain"]);
        match &status {
            Ok(o) if o.status.success() && o.stdout.is_empty() => {}
            Ok(o) if o.status.success() => {
                eprintln!("remoter-agent: {}: kept worktree {}, it has changes nobody committed", spec.id, wt.path);
                return;
            }
            _ => {
                eprintln!("remoter-agent: {}: kept worktree {}, git status failed: {}", spec.id, wt.path, why(&status));
                return;
            }
        }
        let removed = git(&spec.cwd, &["worktree", "remove", &wt.path]);
        if !removed.as_ref().is_ok_and(|o| o.status.success()) {
            eprintln!("remoter-agent: {}: kept worktree {}: {}", spec.id, wt.path, why(&removed));
            return;
        }
        let branch = format!("worktree-{}", wt.name);
        let deleted = git(&spec.cwd, &["branch", "-d", &branch]);
        if !deleted.as_ref().is_ok_and(|o| o.status.success()) {
            eprintln!("remoter-agent: {}: kept branch {branch}, git says: {}", spec.id, why(&deleted));
        }
    }

    /// Untrusted exits 1 before any debug line, so the screen is all we have.
    /// `None` until settled, so a session never flips Exited to Stuck.
    fn exit_verdict(&self, id: &str, dir: &Path, exited_at: i64) -> Option<Option<StuckReason>> {
        if let Some(&v) = lock(&self.exit_reasons).get(id) {
            return Some(v);
        }
        let reason = self.launcher.screen(dir).and_then(|t| exit_reason(&t));
        let settled = reason.is_some() || local::now_ms() - exited_at > EXIT_VERDICT_GRACE_MS;
        if settled {
            lock(&self.exit_reasons).insert(id.to_owned(), reason);
        }
        settled.then_some(reason)
    }

    /// Includes ones our sessions are resuming that haven't announced themselves yet.
    fn open_conversations(&self, t: &Transcripts) -> HashSet<String> {
        let mut open = t.open_ids();
        let live = active_scopes();
        for id in self.dirs().into_iter().filter(|id| live.contains(id)) {
            let Some(d) = self.derive(&id, true) else { continue };
            if matches!(d.summary.state, SessionState::Exited | SessionState::Gone) {
                continue;
            }
            let spec: Option<Spec> = std::fs::read(self.cfg.runtime_base.join(&id).join(SPEC_FILE)).ok().and_then(|b| serde_json::from_slice(&b).ok());
            if let Some(conv) = spec.as_ref().and_then(|s| resumed_id(&s.argv)) {
                open.insert(conv.to_owned());
            }
        }
        open
    }

    /// claude lets a second process take over an open conversation and then both
    /// write the same transcript, so refuse that here.
    fn check_resume(&self, abs: &Path, conv: &str, mode: SpawnMode) -> Result<(), AgentError> {
        if mode != SpawnMode::SameDir {
            return Err(err(ErrorCode::BadRequest, "a conversation resumes in its own folder only"));
        }
        if !is_uuid(conv) {
            return Err(err(ErrorCode::BadRequest, "resume must be a lowercase uuid"));
        }
        let Some(t) = &self.cfg.transcripts else {
            return Err(err(ErrorCode::BadRequest, "resume isn't set up on this laptop"));
        };
        let open = self.open_conversations(t);
        let found = t.find(abs, conv, &open).ok_or_else(|| err(ErrorCode::NotFound, "no such conversation in this folder"))?;
        if found.open {
            return Err(err(ErrorCode::ConversationOpen, format!("{conv} is open")));
        }
        Ok(())
    }

    /// An open conversation is fine here, it's only read.
    fn handoff_source(&self, abs: &Path, conv: &str, also_resume: bool) -> Result<(PathBuf, String), AgentError> {
        if also_resume {
            return Err(err(ErrorCode::BadRequest, "resume or handoff, not both"));
        }
        if !is_uuid(conv) {
            return Err(err(ErrorCode::BadRequest, "handoff must be a lowercase uuid"));
        }
        let Some(t) = &self.cfg.transcripts else {
            return Err(err(ErrorCode::BadRequest, "handoff isn't set up on this laptop"));
        };
        let path = t.path_of(abs, conv).ok_or_else(|| err(ErrorCode::NotFound, "no such conversation in this folder"))?;
        let text = condense(&path).ok_or_else(|| err(ErrorCode::NotFound, "that conversation has nothing to hand off"))?;
        Ok((path, text))
    }

    /// claude allows one Remote Control per folder. Only a session with claude
    /// actually in it counts: a Stuck one claude already left would otherwise
    /// make "trust and start" fail as folder_busy.
    fn live_in(&self, rel: &str) -> Option<String> {
        let live = active_scopes();
        self.dirs()
            .into_iter()
            .filter(|id| live.contains(id))
            .filter(|id| !self.cfg.runtime_base.join(id).join(TRUST_DIALOG_FILE).exists())
            .filter(|id| {
                let status: Option<Status> = std::fs::read(self.cfg.runtime_base.join(id).join(STATUS_FILE)).ok().and_then(|b| serde_json::from_slice(&b).ok());
                status.is_none_or(|s| matches!(s.state, ExecState::Running | ExecState::Handoff))
            })
            .filter_map(|id| self.derive(&id, true))
            .find(|d| d.summary.path == rel && !matches!(d.summary.state, SessionState::Exited | SessionState::Gone))
            .map(|d| d.summary.id)
    }

    fn push(&self, id: &str, e: Event) {
        let seq = self.seq.fetch_add(1, Ordering::SeqCst);
        lock(&self.events).entry(id.to_owned()).or_default().push((seq, e));
        self.changed.notify_all();
    }

    /// Under the events lock so a waiter between check and wait can't miss the notify.
    fn bump(&self) {
        let _g = lock(&self.events);
        self.seq.fetch_add(1, Ordering::SeqCst);
        self.changed.notify_all();
    }

    fn watch(this: &Arc<Inner>, id: &str) {
        if !lock(&this.watching).insert(id.to_owned()) {
            return;
        }
        let (inner, id) = (this.clone(), id.to_owned());
        std::thread::spawn(move || {
            inner.watch_loop(&id);
            lock(&inner.watching).remove(&id);
        });
    }

    fn watch_loop(&self, id: &str) {
        let started = Instant::now();
        let mut sent_phases = lock(&self.events)
            .get(id)
            .map(|v| v.iter().filter(|(_, e)| matches!(e, Event::Phase { .. })).count())
            .unwrap_or(0);
        loop {
            if self.shutdown.load(Ordering::SeqCst) {
                return;
            }
            let active = scope_active(id);
            let Some(d) = self.derive(id, active) else { return };
            for &p in d.phases.iter().skip(sent_phases) {
                self.push(id, Event::Phase { step: p, at: local::now_ms() });
            }
            sent_phases = sent_phases.max(d.phases.len());
            // against the history, so the Ending kill() sent isn't sent twice
            let now = (d.summary.state, d.summary.reason, d.summary.exit_code);
            let before = self.last_state(id);
            // only if we saw the start, or agent downtime counts as a slow start
            if now.0 == SessionState::Ready
                && before.is_some_and(|b| b.0 == SessionState::Starting)
                && let Some(h) = &self.cfg.history
            {
                h.record_start((local::now_ms() - d.summary.started).clamp(0, u32::MAX as i64) as u32);
            }
            if before != Some(now) {
                self.push(id, Event::State { state: now.0, reason: now.1, exit_code: now.2 });
            }
            let starting = matches!(now, (SessionState::Starting, ..) | (SessionState::Stuck, Some(StuckReason::Timeout), _));
            if starting || now.1 == Some(StuckReason::Untrusted) {
                self.stop_at_trust_dialog(id, starting);
            }
            match d.summary.state {
                SessionState::Gone => {
                    let dir = self.cfg.runtime_base.join(id);
                    self.clean_worktree(&dir);
                    let _ = std::fs::remove_dir_all(dir);
                    lock(&self.exit_reasons).remove(id);
                    return;
                }
                SessionState::Exited
                    if d.exited_at.is_some_and(|t| local::now_ms() - t > self.cfg.exited_ttl.as_millis() as i64) =>
                {
                    self.end(id);
                }
                _ => {}
            }
            let fast = started.elapsed() < self.cfg.ready_timeout + Duration::from_secs(1);
            std::thread::sleep(if fast { Duration::from_millis(500) } else { Duration::from_secs(2) });
        }
    }

    /// A claude at the trust dialog waits for someone at the laptop. End it, so the
    /// phone hears Untrusted now rather than Timeout in 90 s, and a retry isn't busy.
    fn stop_at_trust_dialog(&self, id: &str, look: bool) {
        let dir = self.cfg.runtime_base.join(id);
        let flag = dir.join(TRUST_DIALOG_FILE);
        let since = std::fs::metadata(&flag).and_then(|m| m.modified()).ok();
        if since.is_none() {
            if !look || !self.launcher.screen(&dir).is_some_and(|t| trust_dialog(&t)) {
                return;
            }
            let _ = local::write_atomic(&flag, b"");
        }
        let status: Option<Status> = std::fs::read(dir.join(STATUS_FILE)).ok().and_then(|b| serde_json::from_slice(&b).ok());
        let Some(pid) = status.filter(|s| s.state == ExecState::Running).and_then(|s| s.pid) else { return };
        // pid came from a file
        if !belongs_to(pid, id) {
            return;
        }
        let late = since.and_then(|t| t.elapsed().ok()).is_some_and(|e| e > KILL_GRACE);
        let sig = if late { libc::SIGKILL } else if since.is_none() { libc::SIGTERM } else { return };
        // SAFETY: kill has no memory safety preconditions.
        unsafe { libc::kill(pid, sig) };
    }

    fn last_state(&self, id: &str) -> Option<(SessionState, Option<StuckReason>, Option<i32>)> {
        lock(&self.events).get(id)?.iter().rev().find_map(|(_, e)| match e {
            Event::State { state, reason, exit_code } => Some((*state, *reason, *exit_code)),
            _ => None,
        })
    }

    /// SIGINT, then stop both scopes, then SIGKILL whatever lingers.
    fn end(&self, id: &str) {
        let dir = self.cfg.runtime_base.join(id);
        let status: Option<Status> =
            std::fs::read(dir.join(STATUS_FILE)).ok().and_then(|b| serde_json::from_slice(&b).ok());
        if let Some(pid) = status.as_ref().filter(|s| s.state == ExecState::Running).and_then(|s| s.pid) {
            // pid came from a file, don't signal a recycled stranger
            if belongs_to(pid, id) {
                // SAFETY: kill has no memory safety preconditions.
                unsafe { libc::kill(pid, libc::SIGINT) };
                let deadline = Instant::now() + KILL_GRACE;
                while Instant::now() < deadline && belongs_to(pid, id) {
                    std::thread::sleep(Duration::from_millis(100));
                }
            }
        }
        let mut units = vec![format!("{id}.scope")];
        if let Some(child) = status.as_ref().and_then(|s| child_scope(s.exec_pid, id)) {
            units.push(child);
        }
        for u in &units {
            let _ = systemctl(&["stop", "--no-block", u]);
        }
        let present = |u: &String| unit_present(u);
        let deadline = Instant::now() + KILL_GRACE;
        while Instant::now() < deadline && units.iter().any(present) {
            std::thread::sleep(Duration::from_millis(100));
        }
        for u in units.iter().filter(|u| present(u)) {
            let _ = systemctl(&["kill", "--signal=SIGKILL", u]);
        }
        let deadline = Instant::now() + KILL_GRACE;
        while Instant::now() < deadline && units.iter().any(present) {
            std::thread::sleep(Duration::from_millis(100));
        }
    }
}

fn ensure_private_dir(p: &Path) -> Result<(), AgentError> {
    match std::fs::DirBuilder::new().mode(0o700).create(p) {
        Ok(()) => {}
        Err(e) if e.kind() == std::io::ErrorKind::AlreadyExists => {}
        Err(e) => return Err(err(ErrorCode::Internal, format!("{}: {e}", p.display()))),
    }
    let m = std::fs::symlink_metadata(p).map_err(|e| err(ErrorCode::Internal, e.to_string()))?;
    // SAFETY: geteuid has no preconditions.
    let me = unsafe { libc::geteuid() };
    if !m.is_dir() || m.uid() != me || m.mode() & 0o7777 != 0o700 {
        return Err(err(ErrorCode::Internal, format!("{} must be a 0700 dir owned by us", p.display())));
    }
    Ok(())
}

fn systemctl(args: &[&str]) -> std::io::Result<std::process::Output> {
    Command::new("systemctl").arg("--user").args(args).stdin(Stdio::null()).output()
}

/// A scope that is still stopping counts, so a session isn't Gone before its last process left.
fn is_present(active_state: &str) -> bool {
    !matches!(active_state, "inactive" | "failed" | "")
}

pub fn scope_active(id: &str) -> bool {
    unit_present(&format!("{id}.scope"))
}

fn unit_present(unit: &str) -> bool {
    systemctl(&["show", "--property=ActiveState", "--value", unit])
        .map(|o| is_present(String::from_utf8_lossy(&o.stdout).trim()))
        .unwrap_or(false)
}

pub fn active_scopes() -> HashSet<String> {
    let Ok(out) = systemctl(&["list-units", "--type=scope", "--output=json", "--no-pager", "rc-*"]) else {
        return HashSet::new();
    };
    let Ok(units) = serde_json::from_slice::<Vec<serde_json::Value>>(&out.stdout) else {
        return HashSet::new();
    };
    units
        .iter()
        .filter(|u| u.get("active").and_then(|a| a.as_str()).is_some_and(is_present))
        .filter_map(|u| u.get("unit")?.as_str()?.strip_suffix(".scope").map(str::to_owned))
        .filter(|id| is_session_id(id))
        .collect()
}

fn cgroup_unit(pid: i32) -> Option<String> {
    let c = std::fs::read_to_string(format!("/proc/{pid}/cgroup")).ok()?;
    let line = c.lines().find(|l| l.starts_with("0::"))?;
    Some(line.rsplit('/').next()?.to_owned())
}

fn parent_of(pid: i32) -> Option<i32> {
    let stat = std::fs::read_to_string(format!("/proc/{pid}/stat")).ok()?;
    // comm can contain spaces and parens, count from the last `)`
    stat.rsplit_once(')')?.1.split_whitespace().nth(1)?.parse().ok()
}

/// `pid` or a near ancestor is in `rc-<id>.scope`. kitty moves its children
/// into a scope of its own, so for kitty that ancestor is kitty itself.
fn belongs_to(pid: i32, id: &str) -> bool {
    let want = format!("{id}.scope");
    let mut p = pid;
    for _ in 0..4 {
        if p <= 1 {
            return false;
        }
        if cgroup_unit(p).as_deref() == Some(want.as_str()) {
            return true;
        }
        match parent_of(p) {
            Some(pp) => p = pp,
            None => return false,
        }
    }
    false
}

fn child_scope(exec_pid: i32, id: &str) -> Option<String> {
    if !belongs_to(exec_pid, id) {
        return None;
    }
    let unit = cgroup_unit(exec_pid)?;
    let parts: Vec<&str> = unit.strip_suffix(".scope")?.split('-').collect();
    let kitty_shaped = parts.len() == 3
        && parts[0] == "kitty"
        && parts[1..].iter().all(|x| !x.is_empty() && x.bytes().all(|b| b.is_ascii_digit()));
    kitty_shaped.then_some(unit)
}

fn read_capped(p: &Path) -> String {
    use std::io::Read;
    let mut buf = Vec::new();
    if let Ok(f) = std::fs::File::open(p) {
        let _ = f.take(DEBUG_READ_CAP).read_to_end(&mut buf);
    }
    String::from_utf8_lossy(&buf).into_owned()
}

/// `<timestamp> [DEBUG] <message>`. The timestamp shape is checked so text
/// from the middle of a line can't pass as the start of one.
fn debug_message(line: &str) -> Option<&str> {
    let (ts, rest) = line.split_once(' ')?;
    let ts_ok = ts.len() >= 20 && ts.ends_with('Z') && ts.bytes().all(|b| b.is_ascii_digit() || b"-T:.Z".contains(&b));
    ts_ok.then_some(rest)?.strip_prefix("[DEBUG] ")
}

/// Whole lines only: the cwd is printed on the bridgeId line, so a folder
/// name could otherwise fake a marker.
fn marker_id<'a>(debug: &'a str, marker: &str, prefix: &str) -> Option<&'a str> {
    debug.lines().find_map(|l| {
        let id = debug_message(l)?.strip_prefix(marker)?;
        let tail = id.strip_prefix(prefix)?;
        (!tail.is_empty() && tail.bytes().all(|b| b.is_ascii_alphanumeric())).then_some(id)
    })
}

const MARK_READY: &str = "[bridge:init] Created initial session ";
const MARK_REGISTERED: &str = "[bridge:init] Registered, server environmentId=";

// `claude --remote-control` logs these instead. Same id the web uses, `cse_` for `session_`.
const MARK_REPL_CREATED: &str = "[remote-bridge] Created session ";
// resume of a never archived cloud session (claude killed, laptop died) only says this
const MARK_REPL_REATTACHED: &str = "[remote-bridge] Reattaching to session ";
const MARK_REPL_CONNECTED: &str = "[bridge:repl] handleStateChange state=connected";

fn repl_session(debug: &str) -> Option<String> {
    marker_id(debug, MARK_REPL_CREATED, "cse_")
        .or_else(|| marker_id(debug, MARK_REPL_REATTACHED, "cse_"))
        .map(|id| format!("session_{}", &id["cse_".len()..]))
}

fn repl_connected(debug: &str) -> bool {
    debug.lines().any(|l| debug_message(l).is_some_and(|m| m.starts_with(MARK_REPL_CONNECTED)))
}

/// Interactive claude with Remote Control on, so the whole chat is on the laptop
/// screen too. Server mode (`claude remote-control`) only shows a status line.
pub fn interactive_argv(claude: &str, name: &str, worktree: Option<&str>, resume: Option<&str>) -> Vec<String> {
    let mut argv = vec![
        claude.to_owned(),
        format!("--remote-control={name}"),
        format!("--name={name}"),
        "--permission-mode".into(),
        "bypassPermissions".into(),
    ];
    // named, so we know its path and can trust it before claude gets there
    if let Some(wt) = worktree {
        argv.push(format!("--worktree={wt}"));
    }
    // uuid only: nothing for kitty's `$VAR` expansion or claude's option parser to chew on
    if let Some(conv) = resume.filter(|c| is_uuid(c)) {
        argv.push("--resume".into());
        argv.push(conv.into());
    }
    argv
}

/// Throwaway claude that reads the old conversation on stdin and prints the handoff.
/// No tools, nothing saved, and no CLAUDE.md/hooks/MCP: it once copied those into
/// the handoff as if they were part of the work.
pub fn summarizer_argv(claude: &str, from: &str) -> Vec<String> {
    let prompt = format!("{HANDOFF_PROMPT} The earlier session's id is {from}.");
    [
        claude,
        "-p",
        "--model",
        "sonnet",
        "--safe-mode",
        "--strict-mcp-config",
        "--no-session-persistence",
        "--permission-mode",
        "dontAsk",
        &prompt,
    ]
    .map(String::from)
    .to_vec()
}

pub fn resumed_id(argv: &[String]) -> Option<&str> {
    let i = argv.iter().position(|a| a == "--resume")?;
    argv.get(i + 1).map(String::as_str).filter(|c| is_uuid(c))
}

pub fn debug_ready(debug: &str) -> bool {
    marker_id(debug, MARK_READY, "session_").is_some() || (repl_session(debug).is_some() && repl_connected(debug))
}

pub fn debug_registered(debug: &str) -> bool {
    marker_id(debug, MARK_REGISTERED, "env_").is_some() || repl_session(debug).is_some()
}

/// A wrong shaped id gives `None`, never a half checked link.
pub fn parse_link(debug: &str) -> Option<ClaudeLink> {
    let Some(session) = marker_id(debug, MARK_READY, "session_") else {
        return ClaudeLink::from_parts(&repl_session(debug)?, None);
    };
    // a malformed Registered id spoils the whole link, it isn't just dropped
    let registered_lines = debug.lines().filter(|l| debug_message(l).is_some_and(|m| m.starts_with(MARK_REGISTERED))).count();
    let env = marker_id(debug, MARK_REGISTERED, "env_");
    if registered_lines > 0 && env.is_none() {
        return None;
    }
    ClaudeLink::from_parts(session, env)
}

/// Interactive mode has no bridgeId line, so any debug line at all counts.
pub fn debug_claude_started(debug: &str) -> bool {
    debug.lines().any(|l| debug_message(l).is_some())
}

pub fn exit_reason(screen: &str) -> Option<StuckReason> {
    if screen.contains(TEXT_UNTRUSTED) || screen.contains(TEXT_WORKTREE_UNTRUSTED) {
        Some(StuckReason::Untrusted)
    } else if screen.contains(TEXT_FOLDER_SERVED) {
        Some(StuckReason::FolderBusy)
    } else {
        None
    }
}

/// Whole lines with spaces ignored, so a conversation that only quotes the
/// dialog doesn't count, and a screen read that loses spaces still does.
pub fn trust_dialog(screen: &str) -> bool {
    let squeeze = |l: &str| l.chars().filter(|c| !c.is_whitespace() && *c != '❯').collect::<String>();
    let lines: HashSet<String> = screen.lines().map(squeeze).collect();
    DIALOG_LINES.iter().all(|l| lines.contains(&squeeze(l)))
}

fn read_worktree(dir: &Path, spec: &Spec) -> Option<WorktreeFile> {
    let saved: WorktreeFile = serde_json::from_slice(&std::fs::read(dir.join(WORKTREE_FILE)).ok()?).ok()?;
    // re-checked, this path gets handed to git to delete
    (worktree_name(Path::new(&spec.cwd), Path::new(&saved.path)).as_deref() == Some(saved.name.as_str())).then_some(saved)
}

/// `<name>` when `cwd` is exactly `<repo>/.claude/worktrees/<name>`. It ends
/// up in a branch name, so one plain component only.
pub fn worktree_name(repo: &Path, cwd: &Path) -> Option<String> {
    let rest = cwd.strip_prefix(repo.join(".claude/worktrees")).ok()?;
    let mut parts = rest.components();
    let (Some(Component::Normal(name)), None) = (parts.next(), parts.next()) else { return None };
    let name = name.to_str()?;
    let ok = !name.is_empty()
        && name.len() <= 255
        && name != "."
        && name != ".."
        && !name.starts_with('-')
        && !name.contains('/')
        && !name.chars().any(char::is_control);
    ok.then(|| name.to_owned())
}

pub fn last_lines(text: &str, n: usize) -> Vec<String> {
    let lines: Vec<&str> = text.lines().map(|l| l.trim_end()).collect();
    let end = lines.iter().rposition(|l| !l.is_empty()).map_or(0, |i| i + 1);
    lines[..end].iter().skip(end.saturating_sub(n)).map(|l| l.to_string()).collect()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn session_ids() {
        assert!(is_session_id("rc-01k6b7y3m4n5p6q7r8s9t0v1w2"));
        for bad in ["rc-01K6B7Y3M4N5P6Q7R8S9T0V1W2", "rc-", "xx-01k6b7y3m4n5p6q7r8s9t0v1w2", "rc-01k6b7y3m4n5p6q7r8s9t0v1w", "rc-../../etc/passwd000000000"] {
            assert!(!is_session_id(bad), "{bad}");
        }
        let fresh = format!("rc-{}", ulid::Ulid::generate().to_string().to_ascii_lowercase());
        assert!(is_session_id(&fresh), "{fresh}");
    }

    const REAL: &str = "2026-09-28T15:58:26.908Z [DEBUG] [bridge:init] Registered, server environmentId=env_01Kd3fPzQw8nVb2sLxRt6uYm\n2026-09-28T15:58:27.724Z [DEBUG] [bridge:init] Created initial session session_01Hq7cXv2mTnR4bWkYe9pLsA\n";

    #[test]
    fn link_from_real_lines() {
        let l = parse_link(REAL).expect("link");
        assert_eq!(l.session_url, "https://claude.ai/code/session_01Hq7cXv2mTnR4bWkYe9pLsA");
        assert_eq!(l.environment_url.as_deref(), Some("https://claude.ai/code?environment=env_01Kd3fPzQw8nVb2sLxRt6uYm"));
    }

    /// Lines from a real `claude --remote-control` run, 2.1.285.
    const REPL: &str = "2026-09-30T15:56:16.121Z [DEBUG] MDM settings load completed in 0ms\n2026-09-30T15:56:17.215Z [DEBUG] [remote-bridge] Created session cse_01Ta8rLk3wYe6pQz1nVc4hMx\n2026-09-30T15:56:17.869Z [DEBUG] [bridge:repl] handleStateChange state=connected detail=\"undefined\" kind=undefined cancelled=false outboundOnly=false\n";

    #[test]
    fn interactive_ready_and_link() {
        let first = REPL.lines().next().expect("a line");
        assert!(debug_claude_started(first));
        assert!(!debug_registered(first) && !debug_ready(first));
        let created: String = REPL.lines().take(2).map(|l| format!("{l}\n")).collect();
        assert!(debug_registered(&created));
        assert!(!debug_ready(&created), "not ready until the transport connects");
        assert!(debug_ready(REPL));
        let l = parse_link(REPL).expect("link");
        assert_eq!(l.session_url, "https://claude.ai/code/session_01Ta8rLk3wYe6pQz1nVc4hMx");
        assert_eq!(l.environment_url, None);
        let bad = REPL.replace("cse_01Ta8rLk3wYe6pQz1nVc4hMx", "cse_01CH/../evil");
        assert!(!debug_ready(&bad) && parse_link(&bad).is_none());
    }

    // used to show up as a bare "exited 1"
    #[test]
    fn second_remote_control_is_folder_busy() {
        assert_eq!(exit_reason("folder already served: This folder is already served by another Claude Code on this device. Stop it first."), Some(StuckReason::FolderBusy));
        assert_eq!(exit_reason("Workspace not trusted, run claude here once"), Some(StuckReason::Untrusted));
        assert_eq!(exit_reason("some other crash"), None);
    }

    /// claude 2.1.286 in a folder trusted only through its parent, started with `--worktree`.
    #[test]
    fn worktree_trust_refusal_is_untrusted() {
        let screen = "Error creating worktree: Workspace trust not yet accepted. Run `claude` once in this directory and accept the trust dialog, then retry with --worktree";
        assert_eq!(exit_reason(screen), Some(StuckReason::Untrusted));
    }

    /// 2.1.295 in a folder it doesn't trust
    #[test]
    fn trust_dialog_on_screen() {
        let screen = " Accessing workspace:\n\n /home/r/Projects/x\n\n Quick safety check: Is this a project you created or one you trust? (Like your own code, a well-known open source\n project, or work from your team). If not, take a moment to review what's in this folder first.\n\n Claude Code'll be able to read, edit, and execute files here.\n\n Security guide\n\n ❯ No, exit\n   Yes, I trust this folder\n\n Enter to confirm · Esc to cancel\n";
        assert!(trust_dialog(screen));
        assert!(trust_dialog(&screen.replace(' ', "")), "a pty dump loses the spaces");
        assert!(!trust_dialog("> it showed ❯ No, exit / Yes, I trust this folder and waited"));
        assert!(!trust_dialog("  Yes, I trust this folder\n  No, exit"), "the menu alone");
        assert!(!trust_dialog(""));
        assert_eq!(exit_reason(screen), None, "it doesn't exit, so it isn't an exit reason");
    }

    #[test]
    fn worktree_name_only_from_claudes_worktree_dir() {
        let repo = Path::new("/h/Projects/remoter");
        let name = |p: &str| worktree_name(repo, Path::new(p));
        assert_eq!(name("/h/Projects/remoter/.claude/worktrees/bright-otter-3f2a").as_deref(), Some("bright-otter-3f2a"));
        assert_eq!(name("/h/Projects/remoter/.claude/worktrees/bright-otter-3f2a/").as_deref(), Some("bright-otter-3f2a"));
        for bad in [
            "/h/Projects/remoter",
            "/h/Projects/remoter/.claude/worktrees",
            "/h/Projects/remoter/.claude/worktrees/a/b",
            "/h/Projects/remoter/.claude/worktrees/..",
            "/h/Projects/remoter/.claude/worktrees/../x",
            "/h/Projects/remoter/.claude/worktrees/-D",
            "/h/Projects/remoter/.claude/worktrees/a\nb",
            "/h/Projects/remoter-2/.claude/worktrees/x",
            "/h/Projects/other/.claude/worktrees/x",
        ] {
            assert_eq!(name(bad), None, "{bad:?}");
        }
    }

    #[test]
    fn spawns_an_interactive_claude() {
        let a = interactive_argv("/usr/bin/claude", "my proj", None, None);
        assert_eq!(a, ["/usr/bin/claude", "--remote-control=my proj", "--name=my proj", "--permission-mode", "bypassPermissions"]);
        assert!(!a.iter().any(|x| x == "remote-control"), "server mode shows no chat in the window");
        assert_eq!(interactive_argv("c", "n", Some("rc-01k6b7y3m4n5p6q7r8s9t0v1w2"), None).last().map(String::as_str), Some("--worktree=rc-01k6b7y3m4n5p6q7r8s9t0v1w2"));
    }

    #[test]
    fn resume_adds_only_the_uuid() {
        let id = "c82d8b5c-edd4-453e-8d59-4748ff325c03";
        let a = interactive_argv("/usr/bin/claude", "fix", None, Some(id));
        assert_eq!(a, ["/usr/bin/claude", "--remote-control=fix", "--name=fix", "--permission-mode", "bypassPermissions", "--resume", id]);
        assert_eq!(resumed_id(&a), Some(id));
        let sneaky = interactive_argv("c", "n", None, Some("--dangerously-skip-permissions"));
        assert!(!sneaky.iter().any(|x| x.contains("dangerously") || x == "--resume"), "{sneaky:?}");
        assert_eq!(resumed_id(&interactive_argv("c", "n", None, None)), None);
    }

    /// claude 2.1.292, resuming a conversation whose cloud session was never archived.
    const REATTACH: &str = "2026-10-07T10:57:27.477Z [DEBUG] [bridge:repl] Reattaching to persisted bridge session cse_01Pm4tWq9zHc2vNe7gRb5kDy at seq 0 (fresh-mint fallback, restored_owner_match)\n2026-10-07T10:57:27.962Z [DEBUG] [remote-bridge] Reattaching to session cse_01Pm4tWq9zHc2vNe7gRb5kDy\n2026-10-07T10:57:29.376Z [DEBUG] [bridge:repl] handleStateChange state=connected detail=\"undefined\" kind=undefined cancelled=false outboundOnly=false\n";

    #[test]
    fn reattached_resume_ready_and_link() {
        assert!(debug_registered(REATTACH));
        assert!(debug_ready(REATTACH), "no Created session line on a reattach");
        assert_eq!(parse_link(REATTACH).map(|l| l.session_url).as_deref(), Some("https://claude.ai/code/session_01Pm4tWq9zHc2vNe7gRb5kDy"));
        let persisted_only: String = REATTACH.lines().filter(|l| !l.contains("[remote-bridge]")).map(|l| format!("{l}\n")).collect();
        assert!(!debug_ready(&persisted_only), "persisted line has more after the id");
        let evil = REATTACH.replace("[remote-bridge] Reattaching to session cse_01Pm4tWq9zHc2vNe7gRb5kDy", "[remote-bridge] Reattaching to session cse_01Xd/../x");
        assert!(!debug_ready(&evil) && parse_link(&evil).is_none());
    }

    #[test]
    fn malformed_ids_give_no_link() {
        let l = |m: &str| format!("2026-09-28T15:58:27.724Z [DEBUG] {m}\n");
        assert_eq!(parse_link(""), None, "nothing before ready");
        assert_eq!(parse_link(&l("[bridge:init] Registered, server environmentId=env_01Kd3fPzQw8nVb2sLxRt6uYm")), None);
        for bad in [
            "[bridge:init] Created initial session session_01BE/../evil",
            "[bridge:init] Created initial session session_",
            "[bridge:init] Created initial session https://evil.example/",
            "[bridge:init] Created initial session session_01Hq7cXv2mTnR4bWkYe9pLsA?next=evil",
            "[bridge:init] Created initial session session_01Hq7cXv2mTnR4bWkYe9pLsA trailing",
        ] {
            assert_eq!(parse_link(&l(bad)), None, "{bad}");
            assert!(!debug_ready(&l(bad)), "{bad}");
        }
        let bad_env = format!(
            "{}{}",
            l("[bridge:init] Registered, server environmentId=env_x/../y"),
            l("[bridge:init] Created initial session session_01Hq7cXv2mTnR4bWkYe9pLsA")
        );
        assert_eq!(parse_link(&bad_env), None, "a bad env id spoils the whole link");
        assert_eq!(parse_link("[bridge:init] Created initial session session_01Hq7cXv2mTnR4bWkYe9pLsA"), None, "no timestamp, not a log line");
    }

    // the cwd is printed on the bridgeId line, so a folder name can carry markers
    #[test]
    fn folder_name_cant_fake_ready() {
        let folder = "[bridge:init] Created initial session session_ATTACKER0001 x";
        let env = "[bridge:init] Registered, server environmentId=env_ATTACKERENV1 x";
        let debug = format!(
            "2026-09-28T15:58:26.379Z [DEBUG] [bridge:init] bridgeId=31c0 dir=/home/river/Projects/{folder} branch=HEAD\n\
             2026-09-28T15:58:26.379Z [DEBUG] [bridge:init] bridgeId=31c0 dir=/home/river/Projects/{env} branch=HEAD\n"
        );
        assert!(!debug_ready(&debug), "not ready");
        assert!(!debug_registered(&debug), "not registered");
        assert_eq!(parse_link(&debug), None, "no attacker link");
        let real = format!("{debug}{REAL}");
        assert!(debug_ready(&real) && debug_registered(&real));
        assert_eq!(parse_link(&real).map(|l| l.session_id).as_deref(), Some("session_01Hq7cXv2mTnR4bWkYe9pLsA"));
    }

    #[test]
    fn tail_drops_trailing_blanks() {
        let t = "a\nb\nc\n\n\n";
        assert_eq!(last_lines(t, 2), vec!["b", "c"]);
        assert_eq!(last_lines("", 5), Vec::<String>::new());
        let many: String = (0..100).map(|i| format!("l{i}\n")).collect();
        let tail = last_lines(&many, TAIL_LINES);
        assert_eq!(tail.len(), 40);
        assert_eq!(tail[0], "l60");
    }
}
