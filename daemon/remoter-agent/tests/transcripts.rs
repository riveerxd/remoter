//! Transcripts laid out the way claude 2.1.292 leaves them.

use std::collections::HashSet;
use std::io::Write;
use std::os::unix::fs::OpenOptionsExt;
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicUsize, Ordering};
use std::time::{Duration, Instant};

use remoter_agent::transcripts::{HEAD_BYTES, MAX_CONVERSATIONS, TAIL_BYTES, Transcripts, project_dir_name};
use serde_json::json;

struct Dirs {
    root: PathBuf,
}

impl Dirs {
    fn new() -> Dirs {
        static N: AtomicUsize = AtomicUsize::new(0);
        let root = PathBuf::from(env!("CARGO_TARGET_TMPDIR")).join(format!("tx-{}-{}", std::process::id(), N.fetch_add(1, Ordering::Relaxed)));
        let _ = std::fs::remove_dir_all(&root);
        std::fs::create_dir_all(root.join("claude/projects")).expect("mk");
        Dirs { root }
    }

    fn t(&self) -> Transcripts {
        Transcripts::new(self.root.join("claude"))
    }

    fn project(&self, cwd: &str) -> PathBuf {
        let d = self.root.join("claude/projects").join(project_dir_name(cwd));
        std::fs::create_dir_all(&d).expect("project");
        d
    }

    fn write(&self, cwd: &str, id: &str, lines: &[serde_json::Value]) -> PathBuf {
        let p = self.project(cwd).join(format!("{id}.jsonl"));
        std::fs::write(&p, lines.iter().map(|l| format!("{l}\n")).collect::<String>()).expect("write");
        p
    }
}

impl Drop for Dirs {
    fn drop(&mut self) {
        let _ = std::fs::remove_dir_all(&self.root);
    }
}

fn turn(cwd: &str, text: &str) -> serde_json::Value {
    json!({"type":"user","cwd":cwd,"entrypoint":"cli","timestamp":"2026-10-06T18:40:00.000Z","message":{"role":"user","content":text}})
}

fn id(n: usize) -> String {
    format!("00000000-0000-4000-8000-{n:012x}")
}

fn set_mtime(p: &Path, secs: i64) {
    let t = std::time::UNIX_EPOCH + Duration::from_secs(secs as u64);
    std::fs::File::options().write(true).open(p).expect("open").set_modified(t).expect("mtime");
}

const NONE: fn() -> HashSet<String> = HashSet::new;

#[test]
fn newest_first_and_only_this_folder() {
    let d = Dirs::new();
    let (a, b) = ("/h/Projects/a b", "/h/Projects/a-b");
    assert_eq!(project_dir_name(a), project_dir_name(b), "claude spells both the same");
    let older = d.write(a, &id(1), &[turn(a, "older")]);
    let newer = d.write(a, &id(2), &[turn(a, "newer")]);
    d.write(b, &id(3), &[turn(b, "the other folder")]);
    d.write(a, &id(4), &[turn("/h/Projects/a b/sub", "started in a subfolder")]);
    set_mtime(&older, 1_000);
    set_mtime(&newer, 2_000);
    let (list, more) = d.t().list(Path::new(a), &NONE());
    assert!(!more);
    let titles: Vec<&str> = list.iter().map(|c| c.title.as_str()).collect();
    assert_eq!(titles, ["newer", "older"]);
    assert_eq!(list[0].updated, 2_000_000);
    assert_eq!(d.t().list(Path::new(b), &NONE()).0.len(), 1);
}

#[test]
fn capped_with_truncated() {
    let d = Dirs::new();
    let cwd = "/h/p";
    for n in 0..MAX_CONVERSATIONS + 3 {
        let p = d.write(cwd, &id(n), &[turn(cwd, &format!("ask {n}"))]);
        set_mtime(&p, 1_000 + n as i64);
    }
    let (list, more) = d.t().list(Path::new(cwd), &NONE());
    assert_eq!(list.len(), MAX_CONVERSATIONS);
    assert!(more);
    assert_eq!(list[0].title, format!("ask {}", MAX_CONVERSATIONS + 2));
}

#[test]
fn skips_non_transcripts() {
    let d = Dirs::new();
    let cwd = "/h/p";
    let dir = d.project(cwd);
    d.write(cwd, &id(1), &[turn(cwd, "real")]);
    std::fs::write(dir.join("not-a-uuid.jsonl"), format!("{}\n", turn(cwd, "x"))).expect("w");
    std::fs::write(dir.join(format!("{}.json", id(2))), format!("{}\n", turn(cwd, "x"))).expect("w");
    std::os::unix::fs::symlink(dir.join(format!("{}.jsonl", id(1))), dir.join(format!("{}.jsonl", id(3)))).expect("ln");
    std::fs::create_dir_all(dir.join(id(1)).join("subagents")).expect("subagents");
    std::fs::write(dir.join(id(1)).join("subagents").join(format!("{}.jsonl", id(4))), format!("{}\n", turn(cwd, "x"))).expect("w");
    // a FIFO in its place must not hang the listing
    let fifo = dir.join(format!("{}.jsonl", id(5)));
    let c = std::ffi::CString::new(fifo.as_os_str().as_encoded_bytes()).expect("c");
    // SAFETY: a valid C string and mode.
    assert_eq!(unsafe { libc::mkfifo(c.as_ptr(), 0o600) }, 0);
    let started = Instant::now();
    let (list, _) = d.t().list(Path::new(cwd), &NONE());
    assert!(started.elapsed() < Duration::from_secs(2));
    assert_eq!(list.iter().map(|c| c.id.clone()).collect::<Vec<_>>(), [id(1)]);
}

#[test]
fn big_transcript_reads_ends() {
    let d = Dirs::new();
    let cwd = "/h/p";
    let path = d.write(cwd, &id(1), &[turn(cwd, "the first ask"), json!({"type":"custom-title","customTitle":"old name"})]);
    let mut f = std::fs::OpenOptions::new().append(true).custom_flags(0).open(&path).expect("open");
    // 20 MB of tool output in the middle
    let filler = format!("{}\n", json!({"type":"assistant","cwd":cwd,"message":{"content":"y".repeat(64 * 1024)}}));
    for _ in 0..(20 * 1024 * 1024 / filler.len()) {
        f.write_all(filler.as_bytes()).expect("fill");
    }
    writeln!(f, "{}", json!({"type":"custom-title","customTitle":"new name"})).expect("t");
    writeln!(f, "{}", json!({"type":"last-prompt","lastPrompt":"the latest ask"})).expect("t");
    drop(f);
    let started = Instant::now();
    let (list, _) = d.t().list(Path::new(cwd), &NONE());
    assert!(started.elapsed() < Duration::from_millis(500), "took {:?}", started.elapsed());
    assert_eq!(list[0].title, "new name");
    assert_eq!(list[0].last_prompt.as_deref(), Some("the latest ask"));
}

#[test]
fn head_and_tail_join_cleanly() {
    let d = Dirs::new();
    let cwd = "/h/p";
    let mut lines = vec![turn(cwd, "first")];
    // straddle the head's end so the tail starts mid line
    let pad = "z".repeat(1000);
    while lines.iter().map(|l| l.to_string().len() + 1).sum::<usize>() < (HEAD_BYTES + TAIL_BYTES / 2) as usize {
        lines.push(json!({"type":"assistant","cwd":cwd,"pad":pad}));
    }
    lines.push(json!({"type":"ai-title","aiTitle":"from the tail"}));
    d.write(cwd, &id(1), &lines);
    assert_eq!(d.t().list(Path::new(cwd), &NONE()).0[0].title, "from the tail");
}

#[test]
fn cache_follows_growth() {
    let d = Dirs::new();
    let cwd = "/h/p";
    let path = d.write(cwd, &id(1), &[turn(cwd, "first")]);
    let t = d.t();
    assert_eq!(t.list(Path::new(cwd), &NONE()).0[0].title, "first");
    let mut f = std::fs::OpenOptions::new().append(true).open(&path).expect("open");
    writeln!(f, "{}", json!({"type":"custom-title","customTitle":"renamed"})).expect("w");
    drop(f);
    assert_eq!(t.list(Path::new(cwd), &NONE()).0[0].title, "renamed");
}

#[test]
fn find_is_this_folder_only() {
    let d = Dirs::new();
    d.write("/h/a", &id(1), &[turn("/h/a", "x")]);
    let t = d.t();
    assert!(t.find(Path::new("/h/a"), &id(1), &NONE()).is_some());
    assert!(t.find(Path::new("/h/b"), &id(1), &NONE()).is_none());
    assert!(t.find(Path::new("/h/a"), &id(2), &NONE()).is_none());
    assert!(t.find(Path::new("/h/a"), "../../etc/passwd", &NONE()).is_none());
    let open: HashSet<String> = [id(1)].into();
    assert!(t.find(Path::new("/h/a"), &id(1), &open).expect("found").open);
}

#[test]
fn long_folder_cut_name() {
    let d = Dirs::new();
    let cwd = format!("/h/{}", "deep/".repeat(50));
    let cwd = cwd.trim_end_matches('/');
    let full = project_dir_name(cwd);
    assert!(full.len() > 200);
    // first 200 chars, a dash, and some hash
    let dir = d.root.join("claude/projects").join(format!("{}-1a2b3c", &full[..200]));
    std::fs::create_dir_all(&dir).expect("mk");
    std::fs::write(dir.join(format!("{}.jsonl", id(1))), format!("{}\n", turn(cwd, "deep work"))).expect("w");
    assert_eq!(d.t().list(Path::new(cwd), &NONE()).0[0].title, "deep work");
}

fn my_start() -> String {
    let stat = std::fs::read_to_string(format!("/proc/{}/stat", std::process::id())).expect("stat");
    stat.rsplit_once(')').expect("comm").1.split_whitespace().nth(19).expect("start").to_owned()
}

#[test]
fn open_needs_live_pid() {
    let d = Dirs::new();
    let sessions = d.root.join("claude/sessions");
    std::fs::create_dir_all(&sessions).expect("mk");
    let me = std::process::id();
    let put = |pid: u32, conv: &str, start: &str| {
        std::fs::write(sessions.join(format!("{pid}.json")), json!({"pid": pid, "sessionId": conv, "procStart": start}).to_string()).expect("w");
    };
    put(me, &id(1), &my_start());
    // crashed claude's leftover, pid gone
    put(4_000_000, &id(2), "1");
    let open = d.t().open_ids();
    assert_eq!(open, [id(1)].into());
    // pid reused
    put(me, &id(1), "42");
    assert!(d.t().open_ids().is_empty());
    // file name and pid disagree
    std::fs::remove_file(sessions.join(format!("{me}.json"))).expect("rm");
    std::fs::write(sessions.join("1.json"), json!({"pid": me, "sessionId": id(1), "procStart": my_start()}).to_string()).expect("w");
    assert!(d.t().open_ids().is_empty());
}
