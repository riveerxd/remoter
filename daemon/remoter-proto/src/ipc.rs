//! What remoterd and remoter-agent say over `/run/remoter/agent.sock`. One
//! request per connection: remoterd writes a JSON request and shuts down its
//! write side, the agent answers with one JSON reply and closes.
//!
//! remoterd forwards mutations untouched (method, raw target, headers, body)
//! so the agent can verify the phone's signature itself. It never forwards a
//! decision the agent would have to trust.

use serde::{Deserialize, Serialize};

use crate::ErrorCode;
use crate::api::{Event, SessionSummary};

pub const MAX_FRAME: usize = 512 * 1024;
/// The longest the agent holds an events request open.
pub const MAX_EVENTS_WAIT_MS: u32 = 25_000;

/// The four signing headers as they arrived, before any parsing.
#[derive(Debug, Clone, Default, PartialEq, Eq, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct RawSigHeaders {
    pub device: Option<String>,
    pub timestamp: Option<String>,
    pub nonce: Option<String>,
    pub signature: Option<String>,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(tag = "op", rename_all = "snake_case", deny_unknown_fields)]
pub enum AgentRequest {
    // Empty braces rather than unit variants: serde only refuses unknown
    // fields on struct shaped variants of an internally tagged enum.
    Status {},
    /// `path` is base64url of the raw decoded query bytes, so non UTF-8 input
    /// reaches the path guard as it was sent.
    List { path: String, hidden: bool },
    Search { path: String, q: String },
    Recent {},
    Sessions {},
    /// One session with its last screen lines. Like every read, mTLS at remoterd is the only gate.
    Session { id: String },
    /// A folder's past conversations.
    History { path: String },
    /// The session's screen as it is now.
    Tail { id: String },
    Events { id: String, after: u64, wait_ms: u32 },
    /// The session list once the agent's event counter passes `after`, or
    /// after `wait_ms` with whatever is current.
    Live { after: u64, wait_ms: u32 },
    Resources {},
    Procs {},
    Mutate {
        /// The device that passed mTLS at remoterd.
        device: String,
        request_id: String,
        method: String,
        /// Path and query exactly as on the wire.
        target: String,
        headers: RawSigHeaders,
        /// base64url of the body bytes.
        body: String,
    },
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case", deny_unknown_fields)]
pub enum AgentReply {
    Ok(serde_json::Value),
    Err(AgentFailure),
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct AgentFailure {
    pub code: ErrorCode,
    pub message: String,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct AgentStatus {
    pub sessions: u32,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct TailReply {
    pub lines: Vec<String>,
    pub at: i64,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct SeqEvent {
    pub seq: u64,
    pub event: Event,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct EventsReply {
    pub events: Vec<SeqEvent>,
    /// The session is gone, nothing more will come.
    pub gone: bool,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct LiveReply {
    /// Pass back as `after` to wait for the next change.
    pub seq: u64,
    pub sessions: Vec<SessionSummary>,
}

/// The complete HTTP answer to a mutation, status and JSON body text, so
/// remoterd can send it as is and keep it for the idempotent retry.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct MutateReply {
    pub status: u16,
    pub body: String,
    /// The canonical path the agent acted on, relative to home, for the
    /// audit line. Never the path as requested: that can be a symlink.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub audit_path: Option<String>,
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn request_shape() {
        let r = AgentRequest::List { path: "UHJvamVjdHM".into(), hidden: false };
        assert_eq!(serde_json::to_string(&r).ok().as_deref(), Some(r#"{"op":"list","path":"UHJvamVjdHM","hidden":false}"#));
        assert!(serde_json::from_str::<AgentRequest>(r#"{"op":"status","extra":1}"#).is_err());
        assert!(serde_json::from_str::<AgentRequest>(r#"{"op":"shell"}"#).is_err());
        assert!(serde_json::from_str::<AgentRequest>(r#"{"op":"status"}"#).is_ok());
    }

    #[test]
    fn live_shape() {
        let r = AgentRequest::Live { after: 7, wait_ms: 20_000 };
        let text = serde_json::to_string(&r).ok();
        assert_eq!(text.as_deref(), Some(r#"{"op":"live","after":7,"wait_ms":20000}"#));
        assert_eq!(text.and_then(|t| serde_json::from_str::<AgentRequest>(&t).ok()), Some(r));
        assert!(serde_json::from_str::<AgentRequest>(r#"{"op":"live","after":7}"#).is_err(), "wait_ms is required");
        let reply = LiveReply { seq: 9, sessions: Vec::new() };
        let text = serde_json::to_string(&reply).unwrap_or_default();
        assert_eq!(text, r#"{"seq":9,"sessions":[]}"#);
        assert_eq!(serde_json::from_str::<LiveReply>(&text).ok(), Some(reply));
        assert!(serde_json::from_str::<LiveReply>(r#"{"seq":9,"sessions":[],"cap":8}"#).is_err());
    }

    #[test]
    fn reply_shape() {
        let r = AgentReply::Err(AgentFailure { code: ErrorCode::Locked, message: "m".into() });
        assert_eq!(serde_json::to_string(&r).ok().as_deref(), Some(r#"{"err":{"code":"locked","message":"m"}}"#));
    }
}
