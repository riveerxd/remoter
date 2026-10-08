//! Request and response bodies for `/v1`. Times are unix milliseconds. Paths
//! are always relative to home, `""` meaning home itself.

use serde::{Deserialize, Serialize};

use crate::ErrorCode;

pub const BODY_LIMIT: usize = 16 * 1024;
pub const HDR_REQUEST_ID: &str = "remoter-request-id";
pub const HDR_VIEW_TOKEN: &str = "remoter-view-token";

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct Health {
    pub hostname: String,
    pub version: String,
    pub server_time: i64,
    pub locked: bool,
    pub sessions: u32,
    pub on_ac: Option<bool>,
    pub battery_pct: Option<u8>,
    /// When the last re-attestation stops counting. `None` means a
    /// re-attestation is due before the next mutation.
    pub fresh_until: Option<i64>,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum SymlinkKind {
    None,
    Relative,
    Absolute,
}

/// Why a session can't start in a folder. Browsing still works.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum DenyReason {
    /// A `cwd_deny` component such as `.ssh`.
    Denied,
    /// Home itself.
    Home,
    /// Only from an older laptop. Now the agent trusts a folder itself before starting there.
    Untrusted,
    Unsupported,
    SymlinkAbsolute,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct FsEntry {
    /// Shown as is when `unsupported` is false. When it is true, this is a
    /// lossy rendering for display only and the entry can't be acted on.
    pub name: String,
    pub mtime: i64,
    pub is_git: bool,
    pub has_claude_md: bool,
    pub symlink: SymlinkKind,
    /// For an absolute symlink whose target is inside home: that target,
    /// relative to home, so the app can offer to jump there.
    pub symlink_target: Option<String>,
    /// Number of subfolders, shown as "3 folders". `None` when the stat budget
    /// ran out.
    pub file_count: Option<u32>,
    pub session_count: u32,
    /// What `~/.claude.json` says today. Starting doesn't depend on it.
    pub trusted: bool,
    pub spawn_allowed: bool,
    pub deny_reason: Option<DenyReason>,
    pub unsupported: bool,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct ListResponse {
    /// Canonical path of the listed folder, relative to home.
    pub path: String,
    pub is_git: bool,
    pub trusted: bool,
    pub spawn_allowed: bool,
    pub deny_reason: Option<DenyReason>,
    pub entries: Vec<FsEntry>,
    pub truncated: bool,
    pub partial: bool,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct SearchHit {
    pub path: String,
    pub name: String,
    pub is_git: bool,
    pub depth: u32,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct SearchResponse {
    pub query: String,
    pub hits: Vec<SearchHit>,
    /// Stopped at 50 hits or 800 ms before the walk finished.
    pub capped: bool,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct RecentEntry {
    pub path: String,
    pub name: String,
    pub is_git: bool,
    pub last_spawn: i64,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct RecentResponse {
    pub entries: Vec<RecentEntry>,
    /// Median start time of the last starts in ms, `None` below 3 starts.
    pub typical_start_ms: Option<u32>,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct MkdirRequest {
    pub parent: String,
    pub name: String,
    pub git_init: bool,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct MkdirResponse {
    pub path: String,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "kebab-case")]
pub enum SpawnMode {
    SameDir,
    Worktree,
}

impl SpawnMode {
    pub fn as_arg(self) -> &'static str {
        match self {
            SpawnMode::SameDir => "same-dir",
            SpawnMode::Worktree => "worktree",
        }
    }
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct SpawnRequest {
    pub path: String,
    pub name: String,
    pub mode: SpawnMode,
    /// Older phones ask for it on a folder claude didn't trust. Still accepted, but ignored:
    /// the agent trusts every folder it starts in.
    #[serde(default, skip_serializing_if = "std::ops::Not::not")]
    pub trust: bool,
    /// A past conversation in this folder to bring back, by its claude session uuid. Left off
    /// the wire when absent, so a phone and a laptop on either side of it still agree.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub resume: Option<String>,
    /// A past conversation in this folder to start fresh from: a summarizer reads it and the new
    /// session opens with that handoff. Never together with `resume`.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub handoff: Option<String>,
}

/// A Claude Code conversation that once ran in a folder and can be resumed there. The text
/// fields are for display only and are cleaned of control, bidi and zero width characters.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct Conversation {
    /// claude's session uuid, lowercase.
    pub id: String,
    /// The `/rename` title, else claude's own, else the first prompt.
    pub title: String,
    pub last_prompt: Option<String>,
    pub started: i64,
    /// When the transcript last changed.
    pub updated: i64,
    pub branch: Option<String>,
    /// A claude has it open right now, so it can't be resumed until that one ends.
    pub open: bool,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct HistoryResponse {
    /// Canonical path of the folder, relative to home.
    pub path: String,
    /// Newest first.
    pub conversations: Vec<Conversation>,
    pub truncated: bool,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct SpawnResponse {
    pub id: String,
    pub view_token: String,
    pub view_token_expires: i64,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum SessionState {
    Starting,
    Ready,
    Stuck,
    Exited,
    Ending,
    Gone,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum StuckReason {
    Untrusted,
    NotLoggedIn,
    FolderChanged,
    Network,
    Timeout,
    /// claude refused: another Remote Control already serves the folder, maybe one started by hand.
    FolderBusy,
    /// The summarizer couldn't write the handoff, so the new session never started.
    HandoffFailed,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct SessionSummary {
    pub id: String,
    pub name: String,
    pub path: String,
    pub device: Option<String>,
    pub started: i64,
    pub state: SessionState,
    pub reason: Option<StuckReason>,
    pub exit_code: Option<i32>,
    /// Where "Open Claude" goes once the session is ready, taken from what
    /// `remote-control` itself reported. `None` until then.
    pub claude: Option<ClaudeLink>,
    /// The name of the worktree claude made for a worktree session, once it
    /// is running in it. Left off the wire when there is none, so a phone
    /// built before the field existed still reads every other session.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub worktree: Option<String>,
}

/// Both links remote-control gives us. The app tries `session_url` first, then
/// `environment_url`, then just launches the Claude app.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct ClaudeLink {
    /// `session_...` from `[bridge:init] Created initial session`.
    pub session_id: String,
    /// `https://claude.ai/code/<session_id>`. Not verified yet.
    pub session_url: String,
    /// `https://claude.ai/code?environment=env_...`, exactly as printed on the
    /// session's screen.
    pub environment_url: Option<String>,
}

impl ClaudeLink {
    /// Only ids and URLs of the shapes remote-control prints are accepted, so
    /// terminal output can never turn into an arbitrary link the phone opens.
    pub fn from_parts(session_id: &str, environment_id: Option<&str>) -> Option<ClaudeLink> {
        let ok = |s: &str, prefix: &str| {
            s.strip_prefix(prefix).is_some_and(|rest| (10..=64).contains(&rest.len()) && rest.bytes().all(|b| b.is_ascii_alphanumeric()))
        };
        if !ok(session_id, "session_") || environment_id.is_some_and(|e| !ok(e, "env_")) {
            return None;
        }
        Some(ClaudeLink {
            session_id: session_id.to_owned(),
            session_url: format!("https://claude.ai/code/{session_id}"),
            environment_url: environment_id.map(|e| format!("https://claude.ai/code?environment={e}")),
        })
    }
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct SessionsResponse {
    pub sessions: Vec<SessionSummary>,
    pub cap: u32,
}

/// The data of a `sessions` event on `/v1/live`: the same list as
/// `SessionsResponse`, without the cap, which never changes while running.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct LiveSessions {
    pub sessions: Vec<SessionSummary>,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct SessionDetail {
    pub session: SessionSummary,
    pub tail: Vec<String>,
    pub tail_at: i64,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct ViewTokenResponse {
    pub token: String,
    pub expires: i64,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct AttestChallenge {
    pub challenge: String,
    pub expires: i64,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct AttestRequest {
    /// Leaf first, base64url DER.
    pub chain: Vec<String>,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct AttestResponse {
    pub fresh_until: i64,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct LockResponse {
    pub locked: bool,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct AuditEntry {
    pub ts: i64,
    pub device: Option<String>,
    pub action: String,
    pub path: Option<String>,
    pub result: String,
    pub request_id: String,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct AuditPage {
    pub entries: Vec<AuditEntry>,
    /// Pass as `before=` for the next page. `None` on the last page.
    pub next_before: Option<i64>,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct PairRequest {
    pub device_name: String,
    /// Leaf first, base64url DER.
    pub tls_chain: Vec<String>,
    pub sig_chain: Vec<String>,
    pub mac: String,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct PairResponse {
    pub device_id: String,
    pub hostname: String,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct ErrorBody {
    pub code: ErrorCode,
    /// For logs only. The app never shows it.
    pub message: String,
    pub request_id: String,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub retry_after_s: Option<u32>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub sessions: Option<Vec<SessionSummary>>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub server_time: Option<i64>,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum Phase {
    Accepted,
    Terminal,
    /// Only on a start with a handoff: the summarizer finished it.
    Handoff,
    Claude,
    RemoteControl,
}

/// One SSE event. The SSE `event:` field is the variant name, `data:` is the
/// JSON of its fields.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(tag = "event", content = "data", rename_all = "snake_case")]
pub enum Event {
    Phase { step: Phase, at: i64 },
    State { state: SessionState, reason: Option<StuckReason>, exit_code: Option<i32> },
    Tail { lines: Vec<String>, at: i64 },
}

impl Event {
    pub fn name(&self) -> &'static str {
        match self {
            Event::Phase { .. } => "phase",
            Event::State { .. } => "state",
            Event::Tail { .. } => "tail",
        }
    }

    /// The `data:` payload on its own.
    pub fn data_json(&self) -> String {
        let v = serde_json::to_value(self).unwrap_or_default();
        v.get("data").map(|d| d.to_string()).unwrap_or_default()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn unknown_fields_are_refused() {
        let r: Result<SpawnRequest, _> =
            serde_json::from_str(r#"{"path":"p","name":"n","mode":"same-dir","extra":1}"#);
        assert!(r.is_err());
    }

    #[test]
    fn spawn_mode_spelling() {
        assert_eq!(serde_json::to_string(&SpawnMode::SameDir).ok().as_deref(), Some("\"same-dir\""));
        assert!(serde_json::from_str::<SpawnMode>("\"session\"").is_err());
    }

    #[test]
    fn claude_link_accepts_only_known_shapes() {
        let l = ClaudeLink::from_parts("session_01Hq7cXv2mTnR4bWkYe9pLsA", Some("env_01Kd3fPzQw8nVb2sLxRt6uYm")).expect("valid");
        assert_eq!(l.session_url, "https://claude.ai/code/session_01Hq7cXv2mTnR4bWkYe9pLsA");
        assert_eq!(l.environment_url.as_deref(), Some("https://claude.ai/code?environment=env_01Kd3fPzQw8nVb2sLxRt6uYm"));
        for bad in ["session_", "session_a/b0123456789", "session_x\u{0}yyyyyyyyyy", "sess_01Hq7cXv2mTnR4bWkYe9pLsA", "session_01Hq7cXv2mTnR4bWkYe9pLsA?x=1"] {
            assert!(ClaudeLink::from_parts(bad, None).is_none(), "{bad}");
        }
        assert!(ClaudeLink::from_parts("session_01Hq7cXv2mTnR4bWkYe9pLsA", Some("env_../evil.example")).is_none());
    }

    #[test]
    fn event_data_json() {
        let e = Event::Phase { step: Phase::RemoteControl, at: 5 };
        assert_eq!(e.name(), "phase");
        assert_eq!(e.data_json(), r#"{"at":5,"step":"remote_control"}"#);
    }
}
