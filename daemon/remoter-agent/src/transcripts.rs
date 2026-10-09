//! Past claude conversations in a folder, read from `<claude dir>/projects/<folder>/<uuid>.jsonl`.
//! The folder spelling there is lossy, so a conversation only counts when its first `cwd`
//! matches. Transcripts get to 30 MB, so only head and tail are read.

use std::collections::{HashMap, HashSet};
use std::io::{Read, Seek, SeekFrom};
use std::os::unix::fs::{MetadataExt, OpenOptionsExt};
use std::path::{Path, PathBuf};
use std::sync::Mutex;

use remoter_proto::api::Conversation;
use remoter_proto::names::display_text;
use serde_json::Value;

pub const MAX_CONVERSATIONS: usize = 30;
/// ~75k tokens for the summarizer, newest kept
pub const CONDENSED_MAX: usize = 300 * 1024;
/// per message, so one pasted log can't crowd out the rest
pub const MESSAGE_MAX: usize = 4000;
pub const HEAD_BYTES: u64 = 256 * 1024;
pub const TAIL_BYTES: u64 = 256 * 1024;
pub const TITLE_MAX: usize = 80;
pub const PROMPT_MAX: usize = 160;
/// claude truncates longer names and appends a hash, so those match by prefix
const DIR_NAME_MAX: usize = 200;
const LIVE_FILE_MAX: u64 = 64 * 1024;
const LIVE_FILES_MAX: usize = 256;
const CACHE_MAX: usize = 4096;

pub struct Transcripts {
    claude_dir: PathBuf,
    /// valid while size, mtime and inode match. transcripts only grow.
    cache: Mutex<HashMap<PathBuf, (Stamp, Option<Parsed>)>>,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
struct Stamp {
    size: u64,
    mtime_ns: i128,
    ino: u64,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Parsed {
    /// where it started, not where it wandered
    pub cwd: String,
    pub started: Option<i64>,
    pub title: String,
    pub last_prompt: Option<String>,
    pub branch: Option<String>,
}

struct Candidate {
    path: PathBuf,
    id: String,
    stamp: Stamp,
    updated: i64,
}

impl Transcripts {
    pub fn new(claude_dir: PathBuf) -> Transcripts {
        Transcripts { claude_dir, cache: Mutex::new(HashMap::new()) }
    }

    /// Newest first. The bool is true when there were more than `MAX_CONVERSATIONS`.
    pub fn list(&self, abs: &Path, open: &HashSet<String>) -> (Vec<Conversation>, bool) {
        let Some(want) = abs.to_str() else { return (Vec::new(), false) };
        let mut found = self.candidates(want, None);
        found.sort_by(|a, b| b.updated.cmp(&a.updated).then_with(|| a.id.cmp(&b.id)));
        let mut out = Vec::new();
        for c in found {
            let Some(p) = self.parsed(&c) else { continue };
            if p.cwd != want {
                continue;
            }
            if out.len() == MAX_CONVERSATIONS {
                return (out, true);
            }
            out.push(conversation(&c, p, open));
        }
        (out, false)
    }

    pub fn find(&self, abs: &Path, id: &str, open: &HashSet<String>) -> Option<Conversation> {
        let want = abs.to_str()?;
        if !is_uuid(id) {
            return None;
        }
        self.candidates(want, Some(id)).into_iter().find_map(|c| {
            let p = self.parsed(&c).filter(|p| p.cwd == want)?;
            Some(conversation(&c, p, open))
        })
    }

    pub fn path_of(&self, abs: &Path, id: &str) -> Option<PathBuf> {
        let want = abs.to_str()?;
        if !is_uuid(id) {
            return None;
        }
        self.candidates(want, Some(id)).into_iter().find(|c| self.parsed(c).is_some_and(|p| p.cwd == want)).map(|c| c.path)
    }

    /// From `sessions/<pid>.json`. claude leaves those behind when it crashes, so the
    /// pid's start time has to match too.
    pub fn open_ids(&self) -> HashSet<String> {
        let mut out = HashSet::new();
        let Ok(rd) = std::fs::read_dir(self.claude_dir.join("sessions")) else { return out };
        for e in rd.filter_map(Result::ok).take(LIVE_FILES_MAX) {
            let name = e.file_name();
            let Some(stem) = name.to_str().and_then(|n| n.strip_suffix(".json")) else { continue };
            if stem.is_empty() || !stem.bytes().all(|b| b.is_ascii_digit()) {
                continue;
            }
            let Some(bytes) = read_regular(&e.path(), 0, LIVE_FILE_MAX) else { continue };
            let Ok(v) = serde_json::from_slice::<Value>(&bytes) else { continue };
            let (Some(pid), Some(id), Some(start)) = (
                v.get("pid").and_then(Value::as_i64),
                v.get("sessionId").and_then(Value::as_str),
                v.get("procStart").and_then(Value::as_str),
            ) else {
                continue;
            };
            if stem.parse::<i64>().ok() == Some(pid) && is_uuid(id) && proc_start(pid).as_deref() == Some(start) {
                out.insert(id.to_owned());
            }
        }
        out
    }

    fn candidates(&self, abs: &str, only: Option<&str>) -> Vec<Candidate> {
        let projects = self.claude_dir.join("projects");
        let name = project_dir_name(abs);
        let dirs: Vec<PathBuf> = if name.len() <= DIR_NAME_MAX {
            vec![projects.join(&name)]
        } else {
            let prefix = format!("{}-", &name[..DIR_NAME_MAX]);
            std::fs::read_dir(&projects)
                .map(|rd| {
                    rd.filter_map(Result::ok)
                        .filter(|e| e.file_name().to_str().is_some_and(|n| n.starts_with(&prefix)))
                        .map(|e| e.path())
                        .collect()
                })
                .unwrap_or_default()
        };
        let mut out = Vec::new();
        for dir in dirs {
            let names: Vec<String> = match only {
                Some(id) => vec![format!("{id}.jsonl")],
                None => std::fs::read_dir(&dir)
                    .map(|rd| rd.filter_map(|e| e.ok()?.file_name().into_string().ok()).collect())
                    .unwrap_or_default(),
            };
            for n in names {
                let Some(id) = n.strip_suffix(".jsonl").filter(|s| is_uuid(s)) else { continue };
                let path = dir.join(&n);
                let Ok(m) = std::fs::symlink_metadata(&path) else { continue };
                if !m.is_file() {
                    continue;
                }
                let stamp = Stamp { size: m.size(), mtime_ns: m.mtime() as i128 * 1_000_000_000 + m.mtime_nsec() as i128, ino: m.ino() };
                let updated = m.mtime() * 1000 + m.mtime_nsec() / 1_000_000;
                out.push(Candidate { path, id: id.to_owned(), stamp, updated });
            }
        }
        out
    }

    fn parsed(&self, c: &Candidate) -> Option<Parsed> {
        let mut cache = self.cache.lock().unwrap_or_else(|p| p.into_inner());
        if let Some((stamp, p)) = cache.get(&c.path)
            && *stamp == c.stamp
        {
            return p.clone();
        }
        let p = parse_file(&c.path, c.stamp.size);
        if cache.len() >= CACHE_MAX {
            cache.clear();
        }
        cache.insert(c.path.clone(), (c.stamp, p.clone()));
        p
    }
}

fn conversation(c: &Candidate, p: Parsed, open: &HashSet<String>) -> Conversation {
    Conversation {
        open: open.contains(&c.id),
        id: c.id.clone(),
        title: p.title,
        last_prompt: p.last_prompt,
        started: p.started.unwrap_or(c.updated),
        updated: c.updated,
        branch: p.branch,
    }
}

/// Every UTF-16 unit that isn't `A-Za-z0-9` becomes `-`, same as claude.
pub fn project_dir_name(abs: &str) -> String {
    abs.chars().flat_map(|c| if c.is_ascii_alphanumeric() { vec![c] } else { vec!['-'; c.len_utf16()] }).collect()
}

/// Lowercase canonical only, this ends up in claude's argv.
pub fn is_uuid(s: &str) -> bool {
    s.len() == 36
        && s.bytes().enumerate().all(|(i, b)| match i {
            8 | 13 | 18 | 23 => b == b'-',
            _ => b.is_ascii_digit() || (b'a'..=b'f').contains(&b),
        })
}

/// claude records this as `procStart`.
fn proc_start(pid: i64) -> Option<String> {
    if pid <= 0 {
        return None;
    }
    let stat = std::fs::read_to_string(format!("/proc/{pid}/stat")).ok()?;
    // comm may contain spaces or `)`
    stat.rsplit_once(')')?.1.split_whitespace().nth(19).map(str::to_owned)
}

/// No symlinks, and O_NONBLOCK so a FIFO left in its place can't hang us.
fn read_regular(path: &Path, offset: u64, max: u64) -> Option<Vec<u8>> {
    let mut f = std::fs::OpenOptions::new()
        .read(true)
        .custom_flags(libc::O_NOFOLLOW | libc::O_NONBLOCK | libc::O_CLOEXEC)
        .open(path)
        .ok()?;
    if !f.metadata().ok()?.is_file() {
        return None;
    }
    f.seek(SeekFrom::Start(offset)).ok()?;
    let mut buf = Vec::new();
    f.take(max).read_to_end(&mut buf).ok()?;
    Some(buf)
}

pub fn parse_file(path: &Path, size: u64) -> Option<Parsed> {
    let head = read_regular(path, 0, HEAD_BYTES)?;
    let tail = if size > HEAD_BYTES {
        let from = HEAD_BYTES.max(size.saturating_sub(TAIL_BYTES));
        let bytes = read_regular(path, from, TAIL_BYTES)?;
        // first line is partial unless we started exactly where the head ended
        match bytes.iter().position(|&b| b == b'\n') {
            Some(i) if from > HEAD_BYTES || head.last() != Some(&b'\n') => bytes[i + 1..].to_vec(),
            _ => bytes,
        }
    } else {
        Vec::new()
    };
    let head_complete = size <= HEAD_BYTES;
    parse_parts(&head, head_complete, &tail)
}

/// An incomplete head ends in a cut line, which gets dropped.
pub fn parse_parts(head: &[u8], head_complete: bool, tail: &[u8]) -> Option<Parsed> {
    let head_lines: Vec<Value> = {
        let mut lines: Vec<&[u8]> = head.split(|&b| b == b'\n').collect();
        if !head_complete {
            lines.pop();
        }
        lines.into_iter().filter_map(|l| serde_json::from_slice(l).ok()).collect()
    };
    let tail_lines: Vec<Value> = tail.split(|&b| b == b'\n').filter_map(|l| serde_json::from_slice(l).ok()).collect();

    let s = |v: &Value, k: &str| v.get(k).and_then(Value::as_str).map(str::to_owned);
    let cwd = head_lines.iter().find_map(|v| s(v, "cwd"))?;
    if head_lines.iter().find_map(|v| s(v, "entrypoint")).is_some_and(|e| e.starts_with("sdk")) {
        return None;
    }
    let has_turn = |lines: &[Value]| lines.iter().any(is_turn);
    if !has_turn(&head_lines) && !has_turn(&tail_lines) {
        return None;
    }
    let newest = |kind: &str, key: &str| {
        let pick = |lines: &[Value]| lines.iter().rev().filter(|v| s(v, "type").as_deref() == Some(kind)).find_map(|v| s(v, key));
        pick(&tail_lines).or_else(|| pick(&head_lines))
    };
    let clean = |t: String, max: usize| Some(display_text(&t, max)).filter(|t| !t.is_empty());
    let first_prompt = head_lines.iter().find_map(prompt_text).and_then(|t| clean(t, TITLE_MAX));
    let last_prompt = newest("last-prompt", "lastPrompt").and_then(|t| clean(t, PROMPT_MAX));
    let title = newest("custom-title", "customTitle")
        .and_then(|t| clean(t, TITLE_MAX))
        .or_else(|| newest("ai-title", "aiTitle").and_then(|t| clean(t, TITLE_MAX)))
        .or(first_prompt)
        .or_else(|| last_prompt.as_ref().map(|p| display_text(p, TITLE_MAX)))
        .unwrap_or_else(|| "Untitled".to_owned());
    let branch = tail_lines.iter().rev().chain(head_lines.iter().rev()).find_map(|v| s(v, "gitBranch")).and_then(|b| clean(b, TITLE_MAX));
    let started = head_lines.iter().find_map(|v| s(v, "timestamp").and_then(|t| iso_ms(&t)));
    Some(Parsed { cwd, started, title, last_prompt, branch })
}

/// Just the talk, no tool calls, tool output or thinking. Over budget the oldest go
/// first, but the first prompt stays since it usually says what the work was for.
pub fn condense(path: &Path) -> Option<String> {
    use std::io::BufRead;
    let f = std::fs::OpenOptions::new()
        .read(true)
        .custom_flags(libc::O_NOFOLLOW | libc::O_NONBLOCK | libc::O_CLOEXEC)
        .open(path)
        .ok()?;
    if !f.metadata().ok()?.is_file() {
        return None;
    }
    let mut messages: Vec<String> = Vec::new();
    for line in std::io::BufReader::new(f).split(b'\n') {
        let Ok(line) = line else { break };
        let Ok(v) = serde_json::from_slice::<Value>(&line) else { continue };
        let Some(text) = message_text(&v) else { continue };
        let who = if v.get("type").and_then(Value::as_str) == Some("user") { "me" } else { "claude" };
        let cut: String = text.chars().take(MESSAGE_MAX).collect();
        messages.push(format!("[{who}] {cut}"));
    }
    let first = messages.first()?.clone();
    let mut kept: Vec<&str> = Vec::new();
    let mut size = first.len();
    for m in messages[1..].iter().rev() {
        if size + m.len() + 2 > CONDENSED_MAX {
            break;
        }
        size += m.len() + 2;
        kept.push(m);
    }
    kept.reverse();
    let skipped = messages.len() - 1 - kept.len();
    let mut out = first;
    if skipped > 0 {
        out.push_str(&format!("\n\n[{skipped} older messages left out]"));
    }
    for m in kept {
        out.push_str("\n\n");
        out.push_str(m);
    }
    Some(out)
}

fn message_text(v: &Value) -> Option<String> {
    if !is_turn(v) {
        return None;
    }
    let user = v.get("type").and_then(Value::as_str) == Some("user");
    let content = v.get("message")?.get("content")?;
    let text = match content {
        Value::String(t) => t.clone(),
        Value::Array(items) => items
            .iter()
            .filter(|i| i.get("type").and_then(Value::as_str) == Some("text"))
            .filter_map(|i| i.get("text")?.as_str())
            .collect::<Vec<_>>()
            .join("\n"),
        _ => return None,
    };
    let t = text.trim();
    (!t.is_empty() && (!user || !t.starts_with('<'))).then(|| t.to_owned())
}

fn is_turn(v: &Value) -> bool {
    let kind = v.get("type").and_then(Value::as_str);
    let meta = v.get("isMeta").and_then(Value::as_bool) == Some(true);
    let side = v.get("isSidechain").and_then(Value::as_bool) == Some(true);
    matches!(kind, Some("user") | Some("assistant")) && !meta && !side
}

/// Skips tool results, slash commands and anything else claude wraps in tags.
fn prompt_text(v: &Value) -> Option<String> {
    if v.get("type").and_then(Value::as_str) != Some("user") || !is_turn(v) {
        return None;
    }
    let content = v.get("message")?.get("content")?;
    let text = match content {
        Value::String(t) => t.clone(),
        Value::Array(items) => items
            .iter()
            .find(|i| i.get("type").and_then(Value::as_str) == Some("text"))?
            .get("text")?
            .as_str()?
            .to_owned(),
        _ => return None,
    };
    let t = text.trim();
    (!t.is_empty() && !t.starts_with('<')).then(|| t.to_owned())
}

/// `2026-09-28T11:57:33.936Z` only, which is what claude writes.
pub fn iso_ms(t: &str) -> Option<i64> {
    let b = t.as_bytes();
    if b.len() < 20 || b[4] != b'-' || b[7] != b'-' || b[10] != b'T' || b[13] != b':' || b[16] != b':' || *b.last()? != b'Z' {
        return None;
    }
    let num = |r: std::ops::Range<usize>| t.get(r)?.parse::<i64>().ok();
    let (y, mo, d, h, mi, s) = (num(0..4)?, num(5..7)?, num(8..10)?, num(11..13)?, num(14..16)?, num(17..19)?);
    let frac = &t[19..t.len() - 1];
    let ms = match frac.strip_prefix('.') {
        None if frac.is_empty() => 0,
        Some(f) if !f.is_empty() && f.bytes().all(|c| c.is_ascii_digit()) => format!("{f:0<3}")[..3].parse::<i64>().ok()?,
        _ => return None,
    };
    if !(1..=12).contains(&mo) || !(1..=31).contains(&d) || h > 23 || mi > 59 || s > 60 {
        return None;
    }
    // days_from_civil, Howard Hinnant
    let y2 = if mo <= 2 { y - 1 } else { y };
    let era = y2.div_euclid(400);
    let yoe = y2 - era * 400;
    let mp = (mo + 9) % 12;
    let doy = (153 * mp + 2) / 5 + d - 1;
    let doe = yoe * 365 + yoe / 4 - yoe / 100 + doy;
    let days = era * 146_097 + doe - 719_468;
    Some(((days * 24 + h) * 60 + mi) * 60_000 + s * 1000 + ms)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn lines(v: &[Value]) -> Vec<u8> {
        v.iter().map(|x| format!("{x}\n")).collect::<String>().into_bytes()
    }

    fn user(text: &str) -> Value {
        serde_json::json!({"type":"user","cwd":"/h/p","entrypoint":"cli","timestamp":"2026-10-06T18:40:00.000Z","gitBranch":"main","message":{"role":"user","content":text}})
    }

    #[test]
    fn dir_names_match_claudes() {
        assert_eq!(project_dir_name("/home/river/Projects/remoter"), "-home-river-Projects-remoter");
        assert_eq!(project_dir_name("/home/river/Projects/monitoring app"), "-home-river-Projects-monitoring-app");
        assert_eq!(project_dir_name("/home/river/4-0"), "-home-river-4-0");
        assert_eq!(project_dir_name("/h/caf\u{e9}"), "-h-caf-", "one UTF-16 unit, one dash");
        assert_eq!(project_dir_name("/h/\u{1F600}"), "-h---", "two units, two dashes");
    }

    #[test]
    fn lowercase_uuids_only() {
        assert!(is_uuid("c82d8b5c-edd4-453e-8d59-4748ff325c03"));
        for bad in [
            "C82D8B5C-EDD4-453E-8D59-4748FF325C03",
            "c82d8b5c-edd4-453e-8d59-4748ff325c0",
            "c82d8b5cedd4453e8d594748ff325c03aaaa",
            "c82d8b5c-edd4-453e-8d59-4748ff325c0g",
            "--resume-edd4-453e-8d59-4748ff325c03",
            "$HOME000-edd4-453e-8d59-4748ff325c03",
            "",
        ] {
            assert!(!is_uuid(bad), "{bad}");
        }
    }

    #[test]
    fn iso_times() {
        assert_eq!(iso_ms("1970-01-01T00:00:00.000Z"), Some(0));
        assert_eq!(iso_ms("2026-09-28T11:57:33.936Z"), Some(1_790_596_653_936));
        assert_eq!(iso_ms("2026-09-28T11:57:33Z"), Some(1_790_596_653_000));
        assert_eq!(iso_ms("2026-09-28T11:57:33.9Z"), Some(1_790_596_653_900));
        for bad in ["2026-09-28 11:57:33.936Z", "2026-13-28T11:57:33.936Z", "2026-09-28T11:57:33.936", "2026-09-28T11:57:33.Z", "x"] {
            assert_eq!(iso_ms(bad), None, "{bad}");
        }
    }

    #[test]
    fn title_order() {
        let base = vec![
            serde_json::json!({"type":"permission-mode","permissionMode":"bypassPermissions","sessionId":"x"}),
            user("fix the  banner\nplease"),
            serde_json::json!({"type":"assistant","cwd":"/h/p","message":{"role":"assistant","content":[]}}),
        ];
        let p = parse_parts(&lines(&base), true, b"").expect("parsed");
        assert_eq!(p.title, "fix the banner please");
        assert_eq!(p.cwd, "/h/p");
        assert_eq!(p.started, iso_ms("2026-10-06T18:40:00.000Z"));
        assert_eq!(p.branch.as_deref(), Some("main"));

        let mut with_ai = base.clone();
        with_ai.push(serde_json::json!({"type":"ai-title","aiTitle":"Banner overlap fix","sessionId":"x"}));
        assert_eq!(parse_parts(&lines(&with_ai), true, b"").expect("parsed").title, "Banner overlap fix");

        let tail = lines(&[
            serde_json::json!({"type":"custom-title","customTitle":"banner","sessionId":"x"}),
            serde_json::json!({"type":"custom-title","customTitle":"banner v2","sessionId":"x"}),
            serde_json::json!({"type":"last-prompt","lastPrompt":"now the dark theme too","sessionId":"x"}),
            serde_json::json!({"type":"assistant","cwd":"/h/p/sub","gitBranch":"feature","message":{"role":"assistant","content":[]}}),
        ]);
        let p = parse_parts(&lines(&with_ai), false, &tail).expect("parsed");
        assert_eq!(p.title, "banner v2", "the newest rename");
        assert_eq!(p.last_prompt.as_deref(), Some("now the dark theme too"));
        assert_eq!(p.branch.as_deref(), Some("feature"), "the newest branch");
        assert_eq!(p.cwd, "/h/p", "the folder it started in, not where it wandered");
    }

    #[test]
    fn skips_empty() {
        let meta_only = lines(&[
            serde_json::json!({"type":"custom-title","customTitle":"remoter","sessionId":"x"}),
            serde_json::json!({"type":"system","cwd":"/h/p","subtype":"x"}),
        ]);
        assert_eq!(parse_parts(&meta_only, true, b""), None, "no turn at all");
        let scripted = lines(&[serde_json::json!({"type":"user","cwd":"/h/p","entrypoint":"sdk-cli","message":{"content":"x"}})]);
        assert_eq!(parse_parts(&scripted, true, b""), None, "claude -p and scripts");
        let no_cwd = lines(&[serde_json::json!({"type":"user","message":{"content":"x"}})]);
        assert_eq!(parse_parts(&no_cwd, true, b""), None);
        assert_eq!(parse_parts(b"", true, b""), None);
        assert_eq!(parse_parts(b"not json\n{\n", true, b""), None);
    }

    #[test]
    fn prompt_skips_wrapped() {
        let head = lines(&[
            serde_json::json!({"type":"user","cwd":"/h/p","isMeta":true,"message":{"content":[{"type":"text","text":"Base directory for this skill"}]}}),
            serde_json::json!({"type":"user","cwd":"/h/p","message":{"content":[{"type":"tool_result","content":"secret file"}]}}),
            serde_json::json!({"type":"user","cwd":"/h/p","message":{"content":"<command-name>/clear</command-name>"}}),
            serde_json::json!({"type":"user","cwd":"/h/p","message":{"content":[{"type":"text","text":"the real ask"}]}}),
        ]);
        assert_eq!(parse_parts(&head, true, b"").expect("parsed").title, "the real ask");
    }

    #[test]
    fn hostile_text_cleaned() {
        let long = "x".repeat(500);
        let head = lines(&[
            user("hi"),
            serde_json::json!({"type":"custom-title","customTitle":"evil\u{202e}gnp.exe\u{7}\nnext","sessionId":"x"}),
            serde_json::json!({"type":"last-prompt","lastPrompt":long,"sessionId":"x"}),
        ]);
        let p = parse_parts(&head, true, b"").expect("parsed");
        assert_eq!(p.title, "evil gnp.exe next");
        assert_eq!(p.last_prompt.map(|l| l.chars().count()), Some(PROMPT_MAX));
    }

    #[test]
    fn condense_drops_tools() {
        let dir = PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("../target/tmp").join(format!("condense-{}", std::process::id()));
        std::fs::create_dir_all(&dir).expect("mk");
        let p = dir.join("t.jsonl");
        let body = lines(&[
            user("build the thing"),
            serde_json::json!({"type":"assistant","message":{"content":[{"type":"thinking","thinking":"secret plan"},{"type":"text","text":"On it."},{"type":"tool_use","name":"Bash","input":{"command":"ls"}}]}}),
            serde_json::json!({"type":"user","message":{"content":[{"type":"tool_result","content":"AWS_KEY=xyz"}]}}),
            serde_json::json!({"type":"user","isMeta":true,"message":{"content":"meta stuff"}}),
            serde_json::json!({"type":"user","message":{"content":"<command-name>/clear</command-name>"}}),
            serde_json::json!({"type":"assistant","isSidechain":true,"message":{"content":[{"type":"text","text":"subagent chatter"}]}}),
            user("now ship it"),
        ]);
        std::fs::write(&p, body).expect("w");
        let c = condense(&p).expect("condensed");
        assert_eq!(c, "[me] build the thing\n\n[claude] On it.\n\n[me] now ship it");
        std::fs::write(&p, lines(&[serde_json::json!({"type":"system","cwd":"/h/p"})])).expect("w");
        assert_eq!(condense(&p), None, "nothing said, nothing to hand off");
        let _ = std::fs::remove_dir_all(dir);
    }

    #[test]
    fn condensed_stays_in_budget() {
        let dir = PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("../target/tmp").join(format!("condense-big-{}", std::process::id()));
        std::fs::create_dir_all(&dir).expect("mk");
        let p = dir.join("t.jsonl");
        let mut all = vec![user("THE GOAL")];
        for i in 0..400 {
            all.push(user(&format!("msg {i} {}", "x".repeat(MESSAGE_MAX * 2))));
        }
        std::fs::write(&p, lines(&all)).expect("w");
        let c = condense(&p).expect("condensed");
        assert!(c.len() <= CONDENSED_MAX + 64, "{}", c.len());
        assert!(c.starts_with("[me] THE GOAL\n\n["), "the first ask stays");
        assert!(c.contains("older messages left out"));
        assert!(c.ends_with(&"x".repeat(10)) && c.contains("msg 399 "), "the newest stays");
        assert!(!c.contains("msg 0 "), "the oldest go first");
        assert!(c.split("\n\n").all(|m| m.chars().count() <= MESSAGE_MAX + 8), "each message is cut");
        let _ = std::fs::remove_dir_all(dir);
    }

    #[test]
    fn cut_head_line_ignored() {
        let mut head = lines(&[user("first")]);
        head.extend_from_slice(br#"{"type":"custom-title","customTitle":"half"#);
        assert_eq!(parse_parts(&head, false, b"").expect("parsed").title, "first");
    }
}
