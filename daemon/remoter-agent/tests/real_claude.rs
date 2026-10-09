//! Real claude, real kitty on workspace 9, your real `~/.claude`. Run from a
//! desktop session, outside any claude, one at a time (`REMOTER_ALACRITTY`
//! set to its binary runs them in Alacritty instead):
//!
//!     cargo build -p remoter-exec
//!     env -u CLAUDECODE -u CLAUDE_CODE_CHILD_SESSION ... \
//!       cargo test -p remoter-agent --test real_claude -- --ignored --nocapture --test-threads=1
//!
//! Makes a short real conversation in `~/Projects/spike-a`, costs a few tokens.

use std::collections::HashSet;
use std::path::{Path, PathBuf};
use std::process::{Child, Command, Stdio};
use std::sync::Arc;
use std::time::{Duration, Instant};

use remoter_agent::guard::{Home, parse_rel};
use remoter_agent::launcher::{Terminal, WindowLauncher, Wm};
use remoter_agent::sessions::{Sessions, SessionsConfig};
use remoter_agent::transcripts::{Transcripts, project_dir_name};
use remoter_agent::trust::Trust;
use remoter_proto::ErrorCode;
use remoter_proto::api::{SessionState, SpawnMode, SpawnRequest};

const FOLDER: &str = "Projects/spike-a";

fn window(exec_bin: PathBuf) -> Box<WindowLauncher> {
    let terminal = std::env::var_os("REMOTER_ALACRITTY").map_or_else(|| Terminal::Kitty("/usr/bin/kitty".into()), |a| Terminal::Alacritty(a.into()));
    Box::new(WindowLauncher { terminal, wm: Wm::Hyprland { hyprctl_bin: "/usr/bin/hyprctl".into() }, exec_bin, workspace: 9 })
}

fn home() -> PathBuf {
    PathBuf::from(std::env::var_os("HOME").expect("HOME"))
}

fn claude() -> PathBuf {
    home().join(".local/bin/claude")
}

fn transcripts_dir() -> PathBuf {
    home().join(".claude/projects").join(project_dir_name(&home().join(FOLDER).display().to_string()))
}

fn jsonl_ids() -> HashSet<String> {
    std::fs::read_dir(transcripts_dir())
        .map(|rd| rd.filter_map(|e| e.ok()?.file_name().into_string().ok()?.strip_suffix(".jsonl").map(str::to_owned)).collect())
        .unwrap_or_default()
}

fn has_reply(path: &Path) -> bool {
    std::fs::read_to_string(path).unwrap_or_default().lines().any(|l| l.contains(r#""type":"assistant""#))
}

/// Like running claude by hand with a first prompt, until it answered.
fn make_conversation(word: &str) -> String {
    let before = jsonl_ids();
    let cmd = format!("{} 'Remember the codeword {word}. Reply only OK.'", claude().display());
    let mut child: Child = Command::new("script")
        .args(["-qfec", &cmd, "/dev/null"])
        .current_dir(home().join(FOLDER))
        .stdin(Stdio::piped())
        .stdout(Stdio::null())
        .stderr(Stdio::null())
        .spawn()
        .expect("script");
    let deadline = Instant::now() + Duration::from_secs(120);
    let id = loop {
        assert!(Instant::now() < deadline, "the first conversation never got an answer");
        let new: Vec<String> = jsonl_ids().difference(&before).cloned().collect();
        if let Some(id) = new.into_iter().find(|id| has_reply(&transcripts_dir().join(format!("{id}.jsonl")))) {
            break id;
        }
        std::thread::sleep(Duration::from_millis(500));
    };
    let _ = child.kill();
    let _ = child.wait();
    // give claude a moment to drop its ~/.claude/sessions entry
    let t = Transcripts::new(home().join(".claude"));
    let deadline = Instant::now() + Duration::from_secs(10);
    while t.open_ids().contains(&id) && Instant::now() < deadline {
        std::thread::sleep(Duration::from_millis(200));
    }
    id
}

#[test]
#[ignore = "real claude, real kitty, real ~/.claude: see the file header"]
fn real_resume() {
    for v in ["CLAUDECODE", "CLAUDE_CODE_CHILD_SESSION", "CLAUDE_CODE_SESSION_ID"] {
        assert!(std::env::var_os(v).is_none(), "{v} is set: run outside claude, or claude won't save the transcript");
    }
    let exec = std::env::current_exe().expect("exe").parent().and_then(Path::parent).expect("target").join("remoter-exec");
    assert!(exec.exists(), "cargo build -p remoter-exec first");
    let word = format!("HERON{}", std::process::id());
    let conv = make_conversation(&word);
    eprintln!("conversation {conv}");
    let transcript = transcripts_dir().join(format!("{conv}.jsonl"));
    let size_before = std::fs::metadata(&transcript).expect("transcript").len();

    let runtime = PathBuf::from(std::env::var_os("XDG_RUNTIME_DIR").expect("XDG_RUNTIME_DIR")).join(format!("rmt-real{}", std::process::id()));
    let transcripts = Arc::new(Transcripts::new(home().join(".claude")));
    let s = Sessions::new(
        SessionsConfig {
            cwd_deny: vec![".ssh".into(), ".claude".into()],
            claude_bin: claude(),
            git_bin: "/usr/bin/git".into(),
            max_sessions: 8,
            runtime_base: runtime.clone(),
            locked_flag: runtime.with_extension("locked"),
            ready_timeout: Duration::from_secs(90),
            exited_ttl: Duration::from_secs(3600),
            history: None,
            transcripts: Some(transcripts),
        },
        Home::open(&home()).expect("home"),
        Trust::new(home().join(".claude.json")),
        window(exec),
    )
    .expect("sessions");

    let rel = parse_rel(FOLDER.as_bytes()).expect("rel");
    let listed = s.history(&rel).expect("history");
    let mine = listed.conversations.iter().find(|c| c.id == conv).unwrap_or_else(|| panic!("not listed: {listed:?}"));
    assert!(!mine.open, "nothing holds it yet");
    assert!(mine.title.contains(&word), "{:?}", mine.title);

    // claude writes an explicit untrusted entry for a folder it ran in without the
    // dialog, so this also checks the agent trusts it first
    let req = SpawnRequest { path: FOLDER.into(), name: "resume e2e".into(), mode: SpawnMode::SameDir, trust: false, resume: Some(conv.clone()), handoff: None };
    let id = s.spawn(&req, None).expect("resume");
    let deadline = Instant::now() + Duration::from_secs(90);
    let ready = loop {
        let got = s.get(&id).expect("get");
        if got.state == SessionState::Ready {
            break got;
        }
        assert!(matches!(got.state, SessionState::Starting), "left Starting for {got:?}: {:?}", s.tail(&id));
        assert!(Instant::now() < deadline, "never ready: {:?}", s.tail(&id));
        std::thread::sleep(Duration::from_millis(500));
    };
    let link = ready.claude.expect("an Open Claude link");
    eprintln!("ready: {}", link.session_url);

    // old turn on screen means it really loaded the conversation
    let deadline = Instant::now() + Duration::from_secs(20);
    loop {
        let (tail, _) = s.tail(&id).expect("tail");
        if tail.iter().any(|l| l.contains(&word)) {
            break;
        }
        assert!(Instant::now() < deadline, "the old turn never showed: {tail:?}");
        std::thread::sleep(Duration::from_millis(500));
    }

    let deadline = Instant::now() + Duration::from_secs(20);
    while !s.history(&rel).expect("history").conversations.iter().any(|c| c.id == conv && c.open) {
        assert!(Instant::now() < deadline, "never read as open");
        std::thread::sleep(Duration::from_millis(250));
    }
    assert!(home().join(".claude/sessions").exists());
    let again = SpawnRequest { name: "twice".into(), ..req.clone() };
    assert_eq!(s.spawn(&again, None).err().map(|e| e.code), Some(ErrorCode::ConversationOpen));

    let deadline = Instant::now() + Duration::from_secs(20);
    while std::fs::metadata(&transcript).map(|m| m.len()).unwrap_or(0) <= size_before {
        assert!(Instant::now() < deadline, "the resumed session never wrote to the same transcript");
        std::thread::sleep(Duration::from_millis(250));
    }

    s.kill(&id).expect("kill");
    let deadline = Instant::now() + Duration::from_secs(20);
    while s.get(&id).is_ok_and(|g| g.state != SessionState::Gone) {
        assert!(Instant::now() < deadline, "never ended");
        std::thread::sleep(Duration::from_millis(250));
    }
    let deadline = Instant::now() + Duration::from_secs(20);
    while s.history(&rel).expect("history").conversations.iter().any(|c| c.id == conv && c.open) {
        assert!(Instant::now() < deadline, "still open after it ended");
        std::thread::sleep(Duration::from_millis(250));
    }

    // again, now that it has been on Remote Control. claude may reattach the old cloud session.
    let id2 = s.spawn(&SpawnRequest { name: "resume e2e 2".into(), ..req }, None).expect("second resume");
    let deadline = Instant::now() + Duration::from_secs(90);
    loop {
        let got = s.get(&id2).expect("get");
        if got.state == SessionState::Ready {
            assert!(got.claude.is_some(), "a link the second time too");
            eprintln!("ready again: {:?}", got.claude.map(|c| c.session_url));
            break;
        }
        assert!(matches!(got.state, SessionState::Starting), "left Starting for {got:?}: {:?}", s.tail(&id2));
        assert!(Instant::now() < deadline, "never ready: {:?}", s.tail(&id2));
        std::thread::sleep(Duration::from_millis(500));
    }
    s.kill(&id2).expect("kill");
    let deadline = Instant::now() + Duration::from_secs(20);
    while s.get(&id2).is_ok_and(|g| g.state != SessionState::Gone) {
        assert!(Instant::now() < deadline, "never ended");
        std::thread::sleep(Duration::from_millis(250));
    }
    let _ = std::fs::remove_dir_all(&runtime);
}

#[test]
#[ignore = "real claude, real kitty, real ~/.claude: see the file header"]
fn real_handoff() {
    for v in ["CLAUDECODE", "CLAUDE_CODE_CHILD_SESSION", "CLAUDE_CODE_SESSION_ID"] {
        assert!(std::env::var_os(v).is_none(), "{v} is set: run outside claude");
    }
    let exec = std::env::current_exe().expect("exe").parent().and_then(Path::parent).expect("target").join("remoter-exec");
    assert!(exec.exists(), "cargo build -p remoter-exec first");
    let word = format!("OSPREY{}", std::process::id());
    let conv = make_conversation(&word);
    eprintln!("conversation {conv}");
    let runtime = PathBuf::from(std::env::var_os("XDG_RUNTIME_DIR").expect("XDG_RUNTIME_DIR")).join(format!("rmt-hand{}", std::process::id()));
    let s = Sessions::new(
        SessionsConfig {
            cwd_deny: vec![".ssh".into(), ".claude".into()],
            claude_bin: claude(),
            git_bin: "/usr/bin/git".into(),
            max_sessions: 8,
            runtime_base: runtime.clone(),
            locked_flag: runtime.with_extension("locked"),
            ready_timeout: Duration::from_secs(90),
            exited_ttl: Duration::from_secs(3600),
            history: None,
            transcripts: Some(Arc::new(Transcripts::new(home().join(".claude")))),
        },
        Home::open(&home()).expect("home"),
        Trust::new(home().join(".claude.json")),
        window(exec),
    )
    .expect("sessions");
    let before = jsonl_ids();
    let req = SpawnRequest { path: FOLDER.into(), name: "handoff e2e".into(), mode: SpawnMode::SameDir, trust: false, resume: None, handoff: Some(conv.clone()) };
    let id = s.spawn(&req, None).expect("handoff start");
    let started = Instant::now();
    let deadline = started + Duration::from_secs(300);
    let ready = loop {
        let got = s.get(&id).expect("get");
        if got.state == SessionState::Ready {
            break got;
        }
        assert!(matches!(got.state, SessionState::Starting), "left Starting for {got:?}: {:?}", s.tail(&id));
        assert!(Instant::now() < deadline, "never ready: {:?}", s.tail(&id));
        std::thread::sleep(Duration::from_millis(500));
    };
    eprintln!("ready after {:?}: {:?}", started.elapsed(), ready.claude.map(|c| c.session_url));
    let handoff = std::fs::read_to_string(runtime.join(&id).join("handoff.md")).expect("handoff kept");
    eprintln!("handoff:\n{handoff}");
    assert!(handoff.contains(&word), "codeword lost");
    let deadline = Instant::now() + Duration::from_secs(20);
    loop {
        let screen = s.tail(&id).expect("tail").0.join("\n");
        // claude wraps its own text to the window, and ws 9 may be crowded
        if screen.split_whitespace().collect::<String>().contains(&conv) {
            break;
        }
        assert!(Instant::now() < deadline, "{screen}");
        std::thread::sleep(Duration::from_millis(500));
    }
    // exactly one new transcript: the summarizer must not leave its own
    let deadline = Instant::now() + Duration::from_secs(30);
    let new: Vec<String> = loop {
        let n: Vec<String> = jsonl_ids().difference(&before).cloned().collect();
        if !n.is_empty() {
            break n;
        }
        assert!(Instant::now() < deadline, "the new session never wrote a transcript");
        std::thread::sleep(Duration::from_millis(500));
    };
    assert_eq!(new.len(), 1, "{new:?}");
    assert_ne!(new[0], conv);
    s.kill(&id).expect("kill");
    let deadline = Instant::now() + Duration::from_secs(20);
    while s.get(&id).is_ok_and(|g| g.state != SessionState::Gone) {
        assert!(Instant::now() < deadline, "never ended");
        std::thread::sleep(Duration::from_millis(250));
    }
    let _ = std::fs::remove_dir_all(&runtime);
}

/// A folder nothing trusts, not even a parent: interactive claude would stop at
/// its trust dialog unless the agent trusted the folder first.
#[test]
#[ignore = "real claude, real kitty, real ~/.claude: see the file header"]
fn real_untrusted_folder() {
    let exec = std::env::current_exe().expect("exe").parent().and_then(Path::parent).expect("target").join("remoter-exec");
    assert!(exec.exists(), "cargo build -p remoter-exec first");
    let rel = format!("remoter-trust-{}", std::process::id());
    let folder = home().join(&rel);
    std::fs::create_dir_all(&folder).expect("folder");
    let runtime = PathBuf::from(std::env::var_os("XDG_RUNTIME_DIR").expect("XDG_RUNTIME_DIR")).join(format!("rmt-real{}", std::process::id()));
    let trust = Trust::new(home().join(".claude.json"));
    assert!(!trust.is_trusted(&folder), "pick a folder nothing trusts");
    let s = Sessions::new(
        SessionsConfig {
            cwd_deny: vec![".ssh".into(), ".claude".into()],
            claude_bin: claude(),
            git_bin: "/usr/bin/git".into(),
            max_sessions: 8,
            runtime_base: runtime.clone(),
            locked_flag: runtime.with_extension("locked"),
            ready_timeout: Duration::from_secs(90),
            exited_ttl: Duration::from_secs(3600),
            history: None,
            transcripts: None,
        },
        Home::open(&home()).expect("home"),
        trust,
        window(exec),
    )
    .expect("sessions");

    let req = SpawnRequest { path: rel, name: "trust e2e".into(), mode: SpawnMode::SameDir, trust: false, resume: None, handoff: None };
    let id = s.spawn(&req, None).expect("spawn");
    let deadline = Instant::now() + Duration::from_secs(90);
    loop {
        let got = s.get(&id).expect("get");
        if got.state == SessionState::Ready {
            break;
        }
        assert!(matches!(got.state, SessionState::Starting), "left Starting for {got:?}: {:?}", s.tail(&id));
        assert!(Instant::now() < deadline, "never ready: {:?}", s.tail(&id));
        std::thread::sleep(Duration::from_millis(500));
    }
    assert!(Trust::new(home().join(".claude.json")).is_trusted_here(&folder));
    let (tail, _) = s.tail(&id).expect("tail");
    eprintln!("screen:\n{}", tail.join("\n"));
    assert!(!tail.is_empty(), "nothing on screen");

    s.kill(&id).expect("kill");
    let deadline = Instant::now() + Duration::from_secs(20);
    while s.get(&id).is_ok_and(|g| g.state != SessionState::Gone) {
        assert!(Instant::now() < deadline, "never ended");
        std::thread::sleep(Duration::from_millis(250));
    }
    let _ = std::fs::remove_dir_all(&runtime);
    let _ = std::fs::remove_dir(&folder);
}
