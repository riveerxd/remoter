//! Files the agent and remoter-exec share inside a session's private runtime
//! dir. Never sent over the network.

use serde::{Deserialize, Serialize};

use crate::api::StuckReason;

pub const SPEC_FILE: &str = "spec.json";
pub const STATUS_FILE: &str = "status.json";
pub const DEBUG_FILE: &str = "debug.log";
pub const ENDING_FILE: &str = "ending";
pub const KITTY_SOCKET: &str = "kitty.sock";
/// The rendered screen, for terminals that can't be asked for their text.
pub const SCREEN_FILE: &str = "screen.txt";
pub const SPEC_MAX_BYTES: u64 = 64 * 1024;

/// What the agent checked and what remoter-exec must start. The folder path
/// and the session name travel only in here, never on a command line: kitty
/// and systemd-run both expand `$VAR` in argv.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct Spec {
    pub id: String,
    /// Absolute path of the session dir that holds this file.
    pub dir: String,
    /// Absolute canonical path of the folder, as the agent resolved it.
    pub cwd: String,
    /// `st_dev` and `st_ino` of that folder, from the fd the agent opened.
    pub dev: u64,
    pub ino: u64,
    /// The same folder relative to home, for listing.
    pub rel: String,
    pub name: String,
    pub device: Option<String>,
    pub started: i64,
    /// The full claude command line, `argv[0]` an absolute resolved path.
    /// remoter-exec appends `--debug-file <dir>/debug.log`.
    pub argv: Vec<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub handoff: Option<HandoffSpec>,
    /// Run claude on a pty of remoter-exec's own and keep `SCREEN_FILE` current.
    #[serde(default, skip_serializing_if = "std::ops::Not::not")]
    pub screen: bool,
}

/// A start from an earlier conversation. remoter-exec runs `summarizer` with `source` on stdin,
/// keeps what it prints in `HANDOFF_FILE`, and gives claude `HANDOFF_LEAD` plus that text as its
/// first message.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct HandoffSpec {
    /// The earlier conversation's session uuid and its full transcript, which the new session
    /// is told about so it can look up what the handoff leaves out.
    pub from: String,
    pub transcript: String,
    pub summarizer: Vec<String>,
    /// The earlier conversation, condensed by the agent, in the session dir.
    pub source: String,
}

pub const HANDOFF_SOURCE_FILE: &str = "handoff-source.txt";
pub const HANDOFF_FILE: &str = "handoff.md";
/// Ends every handoff, written by remoter-exec rather than left to the summarizer.
pub fn handoff_source_note(from: &str, transcript: &str) -> String {
    format!(
        "Earlier session: {from}\nIts full transcript, with every tool call and output the handoff leaves out: {transcript}\nRead or grep it when you need a detail. It is JSON lines, one record per line, and can be large, so search it rather than reading it whole."
    )
}

pub const HANDOFF_LEAD: &str = "This is a handoff from an earlier Claude Code session in this folder, written by a summarizer that read it. Read it, then reply with a short summary of where things stand and wait for my next instruction.";
pub const HANDOFF_PROMPT: &str = "The text on stdin is an earlier Claude Code conversation in this folder, condensed to its messages, oldest first. Write a handoff for a fresh session that continues the work: the goal, what was decided and why, what was built or changed (files, commands), what is still open, and traps to avoid. Use only what is in that conversation, nothing about your own setup or instructions. Plain markdown, about 600 words, no preamble.";

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum ExecState {
    /// The summarizer is writing the handoff; claude hasn't started.
    Handoff,
    Running,
    Exited,
    Stuck,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct Status {
    pub state: ExecState,
    /// claude's pid while it runs.
    pub pid: Option<i32>,
    /// remoter-exec's own pid. kitty moves its child into a scope of its own
    /// (`kitty-<pid>-<n>.scope`), so ending a session has to find that scope
    /// through this pid as well as stopping `rc-<id>.scope`.
    pub exec_pid: i32,
    /// Exit code, or 128 plus the signal number when a signal ended it.
    pub exit_code: Option<i32>,
    pub reason: Option<StuckReason>,
    pub at: i64,
}

/// Writes through a temp file and a rename, so a reader never sees half a file.
pub fn write_atomic(path: &std::path::Path, bytes: &[u8]) -> std::io::Result<()> {
    use std::io::Write;
    use std::os::unix::fs::OpenOptionsExt;
    let tmp = path.with_extension("tmp");
    let _ = std::fs::remove_file(&tmp);
    let mut f = std::fs::OpenOptions::new().write(true).create_new(true).mode(0o600).open(&tmp)?;
    f.write_all(bytes)?;
    f.sync_all()?;
    std::fs::rename(&tmp, path)
}

pub fn now_ms() -> i64 {
    std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.as_millis() as i64)
        .unwrap_or(0)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn status_round_trips() {
        let s = Status {
            state: ExecState::Stuck,
            pid: None,
            exec_pid: 9,
            exit_code: None,
            reason: Some(StuckReason::FolderChanged),
            at: 1,
        };
        let j = serde_json::to_string(&s).expect("json");
        assert_eq!(j, r#"{"state":"stuck","pid":null,"exec_pid":9,"exit_code":null,"reason":"folder_changed","at":1}"#);
        assert_eq!(serde_json::from_str::<Status>(&j).expect("parse"), s);
    }

    #[test]
    fn spec_refuses_unknown_fields() {
        let j = r#"{"id":"rc-x","dir":"/d","cwd":"/c","dev":1,"ino":2,"rel":"r","name":"n","device":null,"started":0,"argv":["/c"],"shell":"sh"}"#;
        assert!(serde_json::from_str::<Spec>(j).is_err());
        let j = r#"{"id":"rc-x","dir":"/d","cwd":"/c","dev":1,"ino":2,"rel":"r","name":"n","device":null,"started":0,"argv":["/c"],"handoff":{"summarizer":["/c"],"source":"/d/s","x":1}}"#;
        assert!(serde_json::from_str::<Spec>(j).is_err());
    }
}
