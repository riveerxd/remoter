//! Same bodies run on both launchers:
//! kitty (real windows on workspace 9, `cargo test -- --ignored`) and
//! headless (`script` pty, `--features e2e-test`). claude is always a fake script.

use std::os::unix::fs::PermissionsExt;
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicUsize, Ordering};
use std::sync::{Arc, Barrier, Mutex};
use std::time::{Duration, Instant};

use remoter_agent::AgentError;
use remoter_agent::guard::Home;
use remoter_agent::launcher::{KittyLauncher, Launcher};
use remoter_agent::sessions::{Sessions, SessionsConfig, active_scopes};
use remoter_agent::transcripts::{Transcripts, project_dir_name};
use remoter_agent::trust::Trust;
use remoter_proto::ErrorCode;
use remoter_proto::api::{Event, Phase, SessionState, SessionSummary, SpawnMode, SpawnRequest, StuckReason};

const PRELUDE: &str = r#"#!/bin/sh
for last; do :; done
dbg="$last"
d=$(dirname "$dbg")
{ pwd; for a in "$@"; do echo "[$a]"; done; echo "REMOTER_ID=$REMOTER_ID"; } > "$d/fake-argv"
log() { echo "2026-09-28T00:00:00.000Z [DEBUG] $1" >> "$dbg"; }
ready() {
  log "[bridge:init] bridgeId=fake dir=$(pwd)"
  log "[bridge:init] Registered, server environmentId=env_01Kd3fPzQw8nVb2sLxRt6uYm"
  log "[bridge:init] Created initial session session_01Hq7cXv2mTnR4bWkYe9pLsA"
  echo "Connected to fake remote control"
}
"#;

const FAKES: &[(&str, &str)] = &[
    ("ready", "ready\nexec sleep 600"),
    ("hang", "log \"[bridge:init] bridgeId=fake\"\necho hanging here\nexec sleep 600"),
    ("exit1", "for i in $(seq 1 60); do echo \"output line $i\"; done\necho something broke\nexit 1"),
    ("untrusted", "echo \"Error: Workspace not trusted. Please run \\`claude\\` in $(pwd) first\"\nexit 1"),
    ("stubborn", "trap 'echo int >> \"$PWD/got-int\"' INT\ntrap '' HUP TERM\necho $$ > \"$PWD/fake-pid\"\nready\nwhile :; do sleep 1; done"),
    ("slow", "sleep 15\nready\nexec sleep 600"),
    // real 2.1.286 output
    ("served", "echo \"folder already served: This folder is already served by another Claude Code on this device. Stop it first.\"\nexit 1"),
    ("late", "sleep 5\nready\nexec sleep 600"),
    // 2.1.292 resuming a never archived cloud session: no Created line, only the reattach
    (
        "reattach",
        "log \"[remote-bridge] Reattaching to session cse_01Pm4tWq9zHc2vNe7gRb5kDy\"\nlog \"[bridge:repl] handleStateChange state=connected detail=x\"\nexec sleep 600",
    ),
    ("cwd_only", "log \"[bridge:init] bridgeId=fake dir=$(pwd) branch=HEAD\"\nexec sleep 600"),
    // what 2.1.286 --worktree does
    (
        "worktree",
        "/usr/bin/git worktree add -q -b worktree-fake-wt .claude/worktrees/fake-wt\n/usr/bin/git worktree lock .claude/worktrees/fake-wt\ncd .claude/worktrees/fake-wt\nready\nexec sleep 600",
    ),
];

struct World {
    root: PathBuf,
    home: PathBuf,
    runtime: PathBuf,
    locked: PathBuf,
}

impl World {
    fn new() -> World {
        static N: AtomicUsize = AtomicUsize::new(0);
        let n = N.fetch_add(1, Ordering::Relaxed);
        let root = PathBuf::from(env!("CARGO_TARGET_TMPDIR")).join(format!("sess-{}-{n}", std::process::id()));
        let _ = std::fs::remove_dir_all(&root);
        let home = root.join("home");
        for d in ["Projects/remoter/.git", "Projects/plain", "Projects/.ssh", "Documents"] {
            std::fs::create_dir_all(home.join(d)).expect("mkdir");
        }
        let fakes = root.join("fakes");
        std::fs::create_dir_all(&fakes).expect("fakes");
        for (name, body) in FAKES {
            let p = fakes.join(name);
            std::fs::write(&p, format!("{PRELUDE}{body}\n")).expect("fake");
            std::fs::set_permissions(&p, std::fs::Permissions::from_mode(0o755)).expect("chmod");
        }
        std::fs::write(
            root.join("claude.json"),
            format!(r#"{{"projects":{{"{}/Projects":{{"hasTrustDialogAccepted":true}}}}}}"#, home.display()),
        )
        .expect("trust");
        // short: kitty's control socket goes in here, 108 byte limit
        let run = std::env::var_os("XDG_RUNTIME_DIR").map(PathBuf::from).unwrap_or_else(std::env::temp_dir);
        let runtime = run.join(format!("rmt-t{}-{n}", std::process::id()));
        World { locked: root.join("locked"), root, home, runtime }
    }

    fn cfg(&self, fake: &str) -> SessionsConfig {
        let claude = self.root.join("fakes").join(fake);
        assert!(claude.starts_with(&self.root), "tests only ever run a fake claude");
        SessionsConfig {
            cwd_deny: [".ssh", ".gnupg", ".claude", ".config", ".local", ".password-store"].map(String::from).to_vec(),
            claude_bin: claude,
            git_bin: "/usr/bin/git".into(),
            max_sessions: 8,
            runtime_base: self.runtime.clone(),
            locked_flag: self.locked.clone(),
            ready_timeout: Duration::from_secs(20),
            exited_ttl: Duration::from_secs(3600),
            history: None,
            transcripts: Some(Arc::new(Transcripts::new(self.claude_dir()))),
        }
    }

    fn claude_dir(&self) -> PathBuf {
        self.root.join("dot-claude")
    }

    fn seed_conversation(&self, rel: &str, id: &str, title: &str) -> PathBuf {
        let cwd = self.home.join(rel).display().to_string();
        let dir = self.claude_dir().join("projects").join(project_dir_name(&cwd));
        std::fs::create_dir_all(&dir).expect("projects");
        let lines = [
            serde_json::json!({"type":"user","cwd":cwd,"entrypoint":"cli","sessionId":id,"timestamp":"2026-10-06T18:40:00.000Z","gitBranch":"main","message":{"role":"user","content":"the first ask"}}),
            serde_json::json!({"type":"assistant","cwd":cwd,"sessionId":id,"message":{"role":"assistant","content":[]}}),
            serde_json::json!({"type":"custom-title","customTitle":title,"sessionId":id}),
        ];
        let path = dir.join(format!("{id}.jsonl"));
        std::fs::write(&path, lines.iter().map(|l| format!("{l}\n")).collect::<String>()).expect("transcript");
        path
    }

    /// Pretends this test process is a claude holding `id` open.
    fn announce_open(&self, id: &str) {
        let me = std::process::id();
        let stat = std::fs::read_to_string(format!("/proc/{me}/stat")).expect("stat");
        let start = stat.rsplit_once(')').expect("comm").1.split_whitespace().nth(19).expect("starttime").to_owned();
        let dir = self.claude_dir().join("sessions");
        std::fs::create_dir_all(&dir).expect("sessions");
        let body = serde_json::json!({"pid": me, "sessionId": id, "procStart": start, "cwd": "/x", "kind": "interactive"});
        std::fs::write(dir.join(format!("{me}.json")), body.to_string()).expect("announce");
    }

    fn sessions(&self, cfg: SessionsConfig, launcher: Box<dyn Launcher>) -> Sessions {
        let home = Home::open(&self.home).expect("home");
        let trust = Trust::new(self.root.join("claude.json"));
        Sessions::new(cfg, home, trust, launcher).expect("sessions")
    }

    fn dir_of(&self, id: &str) -> PathBuf {
        self.runtime.join(id)
    }
}

impl Drop for World {
    fn drop(&mut self) {
        if let Ok(rd) = std::fs::read_dir(&self.runtime) {
            for e in rd.flatten() {
                let id = e.file_name().to_string_lossy().into_owned();
                let _ = std::process::Command::new("systemctl")
                    .args(["--user", "kill", "--signal=SIGKILL", &format!("{id}.scope")])
                    .output();
            }
        }
        let _ = std::fs::remove_dir_all(&self.runtime);
        let _ = std::fs::remove_dir_all(&self.root);
    }
}

fn exec_bin() -> PathBuf {
    let p = std::env::current_exe().expect("exe").parent().and_then(Path::parent).expect("target dir").join("remoter-exec");
    assert!(p.exists(), "{} missing: run `cargo build -p remoter-exec` first (cargo test --workspace does it)", p.display());
    p
}

fn kitty() -> Box<dyn Launcher> {
    Box::new(KittyLauncher {
        kitty_bin: "/usr/bin/kitty".into(),
        hyprctl_bin: "/usr/bin/hyprctl".into(),
        exec_bin: exec_bin(),
        workspace: 9,
    })
}

#[cfg(feature = "e2e-test")]
fn headless() -> Box<dyn Launcher> {
    Box::new(remoter_agent::launcher::HeadlessLauncher { exec_bin: exec_bin() })
}

fn req(path: &str, name: &str) -> SpawnRequest {
    SpawnRequest { path: path.into(), name: name.into(), mode: SpawnMode::SameDir, trust: false, resume: None, handoff: None }
}

fn wait_state(s: &Sessions, id: &str, want: SessionState, within: Duration) -> SessionSummary {
    let deadline = Instant::now() + within;
    loop {
        let got = s.get(id);
        if let Ok(ref g) = got
            && g.state == want
        {
            return g.clone();
        }
        assert!(Instant::now() < deadline, "wanted {want:?} for {id}, have {got:?}");
        std::thread::sleep(Duration::from_millis(100));
    }
}

fn wait_gone(s: &Sessions, id: &str, within: Duration) {
    let deadline = Instant::now() + within;
    while s.get(id).is_ok_and(|g| g.state != SessionState::Gone) {
        assert!(Instant::now() < deadline, "{id} never went away: {:?}", s.get(id));
        std::thread::sleep(Duration::from_millis(100));
    }
    assert!(!active_scopes().contains(id), "scope left behind");
}

fn wait_removed(dir: &Path) {
    let deadline = Instant::now() + Duration::from_secs(3);
    while dir.exists() {
        assert!(Instant::now() < deadline, "runtime dir {} never cleaned up", dir.display());
        std::thread::sleep(Duration::from_millis(100));
    }
}

// keep the user's git config out, signing or hooks would break setup
fn git(dir: &Path, args: &[&str]) -> String {
    let o = std::process::Command::new("/usr/bin/git")
        .arg("-C")
        .arg(dir)
        .args(["-c", "user.name=t", "-c", "user.email=t@example.com"])
        .args(args)
        .env("GIT_CONFIG_GLOBAL", "/dev/null")
        .env("GIT_CONFIG_NOSYSTEM", "1")
        .output()
        .expect("git");
    assert!(o.status.success(), "git {args:?}: {}", String::from_utf8_lossy(&o.stderr));
    String::from_utf8_lossy(&o.stdout).into_owned()
}

fn make_repo(w: &World) -> PathBuf {
    let repo = w.home.join("Projects/wtrepo");
    std::fs::create_dir_all(&repo).expect("mkdir");
    git(&repo, &["init", "-q", "-b", "main"]);
    std::fs::write(repo.join("README"), "hi\n").expect("write");
    git(&repo, &["add", "README"]);
    git(&repo, &["commit", "-q", "-m", "first"]);
    repo
}

fn has_branch(repo: &Path, branch: &str) -> bool {
    !git(repo, &["branch", "--list", branch]).trim().is_empty()
}

fn history(s: &Sessions, id: &str) -> Vec<Event> {
    s.events(id, 0, Duration::ZERO).into_iter().map(|(_, e)| e).collect()
}

fn t_ready_argv_cwd_phases_and_kill(l: Box<dyn Launcher>) {
    let w = World::new();
    let s = w.sessions(w.cfg("ready"), l);
    let id = s.spawn(&req("Projects/remoter", "  my proj "), Some("01K6B7Y3M4N5P6Q7R8S9T0V1W2")).expect("spawn");
    let got = wait_state(&s, &id, SessionState::Ready, Duration::from_secs(15));
    assert_eq!((got.name.as_str(), got.path.as_str()), ("my proj", "Projects/remoter"));
    let link = got.claude.expect("a link once ready");
    assert_eq!(link.session_url, "https://claude.ai/code/session_01Hq7cXv2mTnR4bWkYe9pLsA");
    assert_eq!(link.environment_url.as_deref(), Some("https://claude.ai/code?environment=env_01Kd3fPzQw8nVb2sLxRt6uYm"));
    assert_eq!(got.device.as_deref(), Some("01K6B7Y3M4N5P6Q7R8S9T0V1W2"));

    let dir = w.dir_of(&id);
    let argv = std::fs::read_to_string(dir.join("fake-argv")).expect("fake ran");
    assert_eq!(
        argv,
        format!(
            "{}\n[--remote-control=my proj]\n[--name=my proj]\n[--permission-mode]\n[bypassPermissions]\n[--debug-file]\n[{}]\nREMOTER_ID={id}\n",
            w.home.join("Projects/remoter").display(),
            dir.join("debug.log").display()
        )
    );

    std::thread::sleep(Duration::from_millis(700));
    let steps: Vec<Phase> = history(&s, &id)
        .into_iter()
        .filter_map(|e| if let Event::Phase { step, .. } = e { Some(step) } else { None })
        .collect();
    assert_eq!(steps, vec![Phase::Accepted, Phase::Terminal, Phase::Claude, Phase::RemoteControl]);
    assert!(history(&s, &id).iter().any(|e| matches!(e, Event::State { state: SessionState::Ready, .. })));

    assert_eq!(s.list().iter().map(|x| x.id.clone()).collect::<Vec<_>>(), vec![id.clone()]);
    assert_eq!(s.counts().get("Projects/remoter"), Some(&1));
    let deadline = Instant::now() + Duration::from_secs(5);
    loop {
        let (tail, _) = s.tail(&id).expect("tail");
        if tail.iter().any(|l| l.contains("Connected to fake remote control")) {
            break;
        }
        assert!(Instant::now() < deadline, "tail never showed claude's output: {tail:?}");
        std::thread::sleep(Duration::from_millis(200));
    }

    s.kill(&id).expect("kill");
    assert!(history(&s, &id).iter().any(|e| matches!(e, Event::State { state: SessionState::Ending, .. })));
    wait_gone(&s, &id, Duration::from_secs(10));
    wait_removed(&dir);
}

fn t_hang_is_stuck_on_timeout(l: Box<dyn Launcher>) {
    let w = World::new();
    let mut cfg = w.cfg("hang");
    cfg.ready_timeout = Duration::from_secs(3);
    let s = w.sessions(cfg, l);
    let id = s.spawn(&req("Projects/remoter", "hang"), None).expect("spawn");
    let got = wait_state(&s, &id, SessionState::Stuck, Duration::from_secs(10));
    assert_eq!(got.reason, Some(StuckReason::Timeout));
    let deadline = Instant::now() + Duration::from_secs(5);
    while !s.tail(&id).expect("tail").0.iter().any(|l| l.contains("hanging here")) {
        assert!(Instant::now() < deadline, "stuck view has no last lines");
        std::thread::sleep(Duration::from_millis(200));
    }
    s.kill(&id).expect("kill");
    wait_gone(&s, &id, Duration::from_secs(10));
}

fn t_exit1_is_exited_with_its_code(l: Box<dyn Launcher>) {
    let w = World::new();
    let s = w.sessions(w.cfg("exit1"), l);
    let id = s.spawn(&req("Projects/remoter", "boom"), None).expect("spawn");
    let got = wait_state(&s, &id, SessionState::Exited, Duration::from_secs(10));
    assert_eq!(got.exit_code, Some(1));
    let tail = s.tail(&id).expect("tail").0;
    assert!(tail.iter().any(|l| l.contains("something broke")), "{tail:?}");
    // last 40 of all output, however small the window is tiled
    assert_eq!(tail.len(), 40, "{tail:?}");
    assert!(tail.iter().any(|l| l == "output line 60"), "{tail:?}");
    assert_eq!(s.counts().get("Projects/remoter"), None, "an exited session isn't running");
    s.kill(&id).expect("kill");
    wait_gone(&s, &id, Duration::from_secs(10));
}

fn t_untrusted_text_is_stuck_untrusted(l: Box<dyn Launcher>) {
    let w = World::new();
    let s = w.sessions(w.cfg("untrusted"), l);
    let id = s.spawn(&req("Projects/remoter", "trust"), None).expect("spawn");
    let got = wait_state(&s, &id, SessionState::Stuck, Duration::from_secs(10));
    assert_eq!(got.reason, Some(StuckReason::Untrusted));
    s.kill(&id).expect("kill");
    wait_gone(&s, &id, Duration::from_secs(10));
}

fn t_kill_escalates_past_ignored_sigint(l: Box<dyn Launcher>) {
    let w = World::new();
    let s = w.sessions(w.cfg("stubborn"), l);
    let id = s.spawn(&req("Projects/remoter", "stubborn"), None).expect("spawn");
    wait_state(&s, &id, SessionState::Ready, Duration::from_secs(15));
    let dir = w.dir_of(&id);
    let t0 = Instant::now();
    s.kill(&id).expect("kill");
    wait_gone(&s, &id, Duration::from_secs(15));
    assert!(t0.elapsed() >= Duration::from_secs(3), "SIGINT got its 3 s before the scope was stopped");
    assert!(w.home.join("Projects/remoter/got-int").exists(), "claude got the SIGINT first");
    // ignores INT/HUP/TERM, so only the scope SIGKILL can have done it
    let pid = std::fs::read_to_string(w.home.join("Projects/remoter/fake-pid")).expect("pid");
    assert!(!Path::new(&format!("/proc/{}", pid.trim())).exists(), "claude {} survived the session", pid.trim());
    let deadline = Instant::now() + Duration::from_secs(3);
    let states = loop {
        let states: Vec<SessionState> = history(&s, &id)
            .into_iter()
            .filter_map(|e| if let Event::State { state, .. } = e { Some(state) } else { None })
            .collect();
        if states.last() == Some(&SessionState::Gone) || Instant::now() > deadline {
            break states;
        }
        std::thread::sleep(Duration::from_millis(100));
    };
    assert_eq!(states.last(), Some(&SessionState::Gone), "{states:?}");
    assert!(states.contains(&SessionState::Ending));
    wait_removed(&dir);
}

fn t_slow_start_is_ready_not_stuck(l: Box<dyn Launcher>) {
    let w = World::new();
    let s = w.sessions(w.cfg("slow"), l);
    let id = s.spawn(&req("Projects/remoter", "slow"), None).expect("spawn");
    std::thread::sleep(Duration::from_secs(2));
    let early = s.get(&id).expect("get");
    assert_eq!(early.state, SessionState::Starting);
    assert_eq!(early.claude, None, "no link before ready");
    wait_state(&s, &id, SessionState::Ready, Duration::from_secs(20));
    assert!(
        !history(&s, &id).iter().any(|e| matches!(e, Event::State { state: SessionState::Stuck, .. })),
        "15 s is inside the 20 s window"
    );
    s.kill(&id).expect("kill");
    wait_gone(&s, &id, Duration::from_secs(10));
}

fn t_cap_holds_under_two_concurrent_spawns(l: Box<dyn Launcher>) {
    let w = World::new();
    let mut cfg = w.cfg("ready");
    cfg.max_sessions = 1;
    let s = Arc::new(w.sessions(cfg, l));
    let gate = Arc::new(Barrier::new(2));
    let handles: Vec<_> = (0..2)
        .map(|i| {
            let (s, gate) = (s.clone(), gate.clone());
            std::thread::spawn(move || {
                gate.wait();
                s.spawn(&req("Projects/remoter", &format!("race {i}")), None)
            })
        })
        .collect();
    let results: Vec<Result<String, AgentError>> = handles.into_iter().map(|h| h.join().expect("join")).collect();
    let ok: Vec<&String> = results.iter().filter_map(|r| r.as_ref().ok()).collect();
    let capped = results.iter().filter(|r| r.as_ref().is_err_and(|e| e.code == ErrorCode::SessionCap)).count();
    assert_eq!((ok.len(), capped), (1, 1), "{results:?}");
    let id = ok[0].clone();
    wait_state(&s, &id, SessionState::Ready, Duration::from_secs(15));
    assert_eq!(s.spawn(&req("Projects/plain", "third"), None).err().map(|e| e.code), Some(ErrorCode::SessionCap));
    s.kill(&id).expect("kill");
    wait_gone(&s, &id, Duration::from_secs(10));
}

fn t_agent_restart_loses_nothing(l: Box<dyn Launcher>, l2: Box<dyn Launcher>) {
    let w = World::new();
    let first = w.sessions(w.cfg("ready"), l);
    let id = first.spawn(&req("Projects/remoter", "survivor"), None).expect("spawn");
    wait_state(&first, &id, SessionState::Ready, Duration::from_secs(15));
    drop(first);
    std::thread::sleep(Duration::from_secs(1));
    assert!(active_scopes().contains(&id), "the session outlives the agent");

    let second = w.sessions(w.cfg("ready"), l2);
    let got = wait_state(&second, &id, SessionState::Ready, Duration::from_secs(3));
    assert_eq!(got.name, "survivor");
    // link must come from link.json now
    std::fs::remove_file(w.dir_of(&id).join("debug.log")).expect("rm debug");
    let after = second.get(&id).expect("get");
    assert_eq!(after.claude.map(|l| l.session_id).as_deref(), Some("session_01Hq7cXv2mTnR4bWkYe9pLsA"));
    assert_eq!(second.list().len(), 1);
    let evs = second.events(&id, 0, Duration::from_secs(3));
    assert!(evs.iter().any(|(_, e)| matches!(e, Event::State { state: SessionState::Ready, .. })), "{evs:?}");
    second.kill(&id).expect("kill");
    wait_gone(&second, &id, Duration::from_secs(10));
}

/// kitty's screen can lag behind a process that prints and exits at once.
struct LateScreen {
    inner: Box<dyn Launcher>,
    since: Mutex<Option<Instant>>,
}

impl Launcher for LateScreen {
    fn prepare(&self) -> Result<(), AgentError> {
        self.inner.prepare()
    }
    fn launch(&self, id: &str, dir: &Path) -> Result<(), AgentError> {
        *self.since.lock().expect("lock") = Some(Instant::now());
        self.inner.launch(id, dir)
    }
    fn screen(&self, dir: &Path) -> Option<String> {
        let t = *self.since.lock().expect("lock");
        if t.is_none_or(|t| t.elapsed() < Duration::from_secs(1)) {
            return Some(String::new());
        }
        self.inner.screen(dir)
    }
}

fn t_untrusted_screen_arriving_late_is_still_untrusted(l: Box<dyn Launcher>) {
    let w = World::new();
    let s = w.sessions(w.cfg("untrusted"), Box::new(LateScreen { inner: l, since: Mutex::new(None) }));
    let id = s.spawn(&req("Projects/remoter", "late"), None).expect("spawn");
    let got = wait_state(&s, &id, SessionState::Stuck, Duration::from_secs(10));
    assert_eq!(got.reason, Some(StuckReason::Untrusted));
    assert!(
        !history(&s, &id).iter().any(|e| matches!(e, Event::State { state: SessionState::Exited, .. })),
        "never reported as a plain exit first"
    );
    s.kill(&id).expect("kill");
    wait_gone(&s, &id, Duration::from_secs(10));
}

// claude prints the cwd on its bridgeId line
const ATTACK_FOLDER: &str = "Projects/[bridge:init] Created initial session session_ATTACKER0001 x";

fn t_folder_name_cant_fake_ready(l: Box<dyn Launcher>) {
    let w = World::new();
    std::fs::create_dir_all(w.home.join(ATTACK_FOLDER)).expect("mkdir");
    let mut cfg = w.cfg("cwd_only");
    cfg.ready_timeout = Duration::from_secs(3);
    let s = w.sessions(cfg, l);
    let id = s.spawn(&req(ATTACK_FOLDER, "attack"), None).expect("spawn");
    let dbg = w.dir_of(&id).join("debug.log");
    let deadline = Instant::now() + Duration::from_secs(10);
    while !std::fs::read_to_string(&dbg).unwrap_or_default().contains("session_ATTACKER0001") {
        assert!(Instant::now() < deadline, "the fake never logged its cwd");
        std::thread::sleep(Duration::from_millis(100));
    }
    let got = wait_state(&s, &id, SessionState::Stuck, Duration::from_secs(10));
    assert_eq!(got.reason, Some(StuckReason::Timeout), "never Ready");
    assert_eq!(got.claude, None, "no link from a folder name");
    assert!(!history(&s, &id).iter().any(|e| matches!(e, Event::State { state: SessionState::Ready, .. })));
    s.kill(&id).expect("kill");
    wait_gone(&s, &id, Duration::from_secs(10));
}

/// Swaps the folder between the agent's check and the launch.
struct Swap {
    inner: Box<dyn Launcher>,
    folder: PathBuf,
}

impl Launcher for Swap {
    fn prepare(&self) -> Result<(), AgentError> {
        self.inner.prepare()
    }
    fn launch(&self, id: &str, dir: &Path) -> Result<(), AgentError> {
        std::fs::rename(&self.folder, self.folder.with_extension("moved")).expect("mv");
        std::fs::create_dir(&self.folder).expect("mkdir");
        self.inner.launch(id, dir)
    }
    fn screen(&self, dir: &Path) -> Option<String> {
        self.inner.screen(dir)
    }
}

fn t_folder_swapped_between_check_and_exec(l: Box<dyn Launcher>) {
    let w = World::new();
    let s = w.sessions(w.cfg("ready"), Box::new(Swap { inner: l, folder: w.home.join("Projects/plain") }));
    let id = s.spawn(&req("Projects/plain", "swap"), None).expect("spawn");
    let got = wait_state(&s, &id, SessionState::Stuck, Duration::from_secs(10));
    assert_eq!(got.reason, Some(StuckReason::FolderChanged));
    assert!(!w.dir_of(&id).join("fake-argv").exists(), "claude never started");
    s.kill(&id).expect("kill");
    wait_gone(&s, &id, Duration::from_secs(10));
}

fn t_second_session_in_a_folder_is_folder_busy(l: Box<dyn Launcher>) {
    let w = World::new();
    let s = w.sessions(w.cfg("ready"), l);
    let first = s.spawn(&req("Projects/remoter", "first"), None).expect("spawn");
    wait_state(&s, &first, SessionState::Ready, Duration::from_secs(15));
    let e = s.spawn(&req("Projects/remoter", "second"), None).expect_err("a second one in the same folder");
    assert_eq!(e.code, ErrorCode::FolderBusy);
    assert!(e.detail.starts_with(&first), "names the session in the way: {}", e.detail);
    // other folder fine, worktree of the same one too
    let other = s.spawn(&req("Projects/plain", "other"), None).expect("another folder");
    let wt = s.spawn(&SpawnRequest { mode: SpawnMode::Worktree, ..req("Projects/remoter", "wt") }, None).expect("worktree");
    for id in [&first, &other, &wt] {
        s.kill(id).expect("kill");
    }
    for id in [&first, &other, &wt] {
        wait_gone(&s, id, Duration::from_secs(10));
    }
}

// a claude started by hand is invisible to us, only its refusal tells
fn t_folder_served_elsewhere_is_stuck_folder_busy(l: Box<dyn Launcher>) {
    let w = World::new();
    let s = w.sessions(w.cfg("served"), l);
    let id = s.spawn(&req("Projects/remoter", "held"), None).expect("spawn");
    let got = wait_state(&s, &id, SessionState::Stuck, Duration::from_secs(10));
    assert_eq!(got.reason, Some(StuckReason::FolderBusy));
    s.kill(&id).expect("kill");
    wait_gone(&s, &id, Duration::from_secs(10));
}

// "trust and start" used to fail as folder_busy, blocked by its own failed try
fn t_a_refused_start_doesnt_hold_its_folder(l: Box<dyn Launcher>) {
    let w = World::new();
    let s = w.sessions(w.cfg("untrusted"), l);
    let failed = s.spawn(&req("Projects/remoter", "first"), None).expect("spawn");
    let got = wait_state(&s, &failed, SessionState::Stuck, Duration::from_secs(10));
    assert_eq!(got.reason, Some(StuckReason::Untrusted));
    let again = s.spawn(&SpawnRequest { trust: true, ..req("Projects/remoter", "again") }, None);
    assert!(again.is_ok(), "nothing runs in the folder, so it isn't busy: {:?}", again.err());
    for id in [failed, again.expect("again")] {
        s.kill(&id).expect("kill");
        wait_gone(&s, &id, Duration::from_secs(10));
    }
}

fn t_a_slow_stuck_start_still_holds_its_folder(l: Box<dyn Launcher>) {
    let w = World::new();
    let mut cfg = w.cfg("hang");
    cfg.ready_timeout = Duration::from_secs(1);
    let s = w.sessions(cfg, l);
    let hung = s.spawn(&req("Projects/remoter", "hung"), None).expect("spawn");
    let stuck = wait_state(&s, &hung, SessionState::Stuck, Duration::from_secs(10));
    assert_eq!(stuck.reason, Some(StuckReason::Timeout));
    assert_eq!(s.spawn(&req("Projects/remoter", "second"), None).err().map(|e| e.code), Some(ErrorCode::FolderBusy));
    s.kill(&hung).expect("kill");
    wait_gone(&s, &hung, Duration::from_secs(10));
}

fn t_trust_from_the_phone_trusts_then_starts(l: Box<dyn Launcher>) {
    let w = World::new();
    let s = w.sessions(w.cfg("ready"), l);
    // Documents is outside the trusted ~/Projects
    assert_eq!(s.spawn(&req("Documents", "plain ask"), None).err().map(|e| e.code), Some(ErrorCode::UntrustedFolder));
    let id = s.spawn(&SpawnRequest { trust: true, ..req("Documents", "trusted now") }, None).expect("trust and start");
    wait_state(&s, &id, SessionState::Ready, Duration::from_secs(15));
    let json: serde_json::Value = serde_json::from_slice(&std::fs::read(w.root.join("claude.json")).expect("read")).expect("json");
    let key = w.home.join("Documents").display().to_string();
    assert_eq!(json["projects"][&key]["hasTrustDialogAccepted"], true);
    assert_eq!(json["projects"][format!("{}/Projects", w.home.display())]["hasTrustDialogAccepted"], true, "the rest is kept");
    s.kill(&id).expect("kill");
    wait_gone(&s, &id, Duration::from_secs(10));
}

const CONV: &str = "c82d8b5c-edd4-453e-8d59-4748ff325c03";

fn resume(path: &str, name: &str, conv: &str) -> SpawnRequest {
    SpawnRequest { resume: Some(conv.into()), ..req(path, name) }
}

fn t_resume_reaches_ready_and_holds_the_conversation(l: Box<dyn Launcher>) {
    let w = World::new();
    w.seed_conversation("Projects/remoter", CONV, "fix the banner");
    let s = w.sessions(w.cfg("reattach"), l);
    let rel = remoter_agent::guard::parse_rel(b"Projects/remoter").expect("rel");
    let before = s.history(&rel).expect("history");
    assert_eq!(before.conversations.len(), 1);
    assert!(!before.conversations[0].open);

    let id = s.spawn(&resume("Projects/remoter", "fix the banner", CONV), None).expect("resume");
    let ready = wait_state(&s, &id, SessionState::Ready, Duration::from_secs(15));
    assert_eq!(ready.claude.map(|c| c.session_url).as_deref(), Some("https://claude.ai/code/session_01Pm4tWq9zHc2vNe7gRb5kDy"));
    let argv = std::fs::read_to_string(w.dir_of(&id).join("fake-argv")).expect("argv");
    let lines: Vec<&str> = argv.lines().collect();
    assert_eq!(lines[0], w.home.join("Projects/remoter").display().to_string(), "cwd");
    let at = lines.iter().position(|l| *l == "[--resume]").expect("--resume passed");
    assert_eq!(lines[at + 1], format!("[{CONV}]"));

    assert!(s.history(&rel).expect("history").conversations[0].open, "ours holds it now");
    assert_eq!(
        s.spawn(&resume("Projects/remoter", "again", CONV), None).err().map(|e| e.code),
        Some(ErrorCode::ConversationOpen)
    );
    s.kill(&id).expect("kill");
    wait_gone(&s, &id, Duration::from_secs(10));
    assert!(!s.history(&rel).expect("history").conversations[0].open, "free again once it ended");
}

/// With `-p` it acts as the summarizer (echoes what it read), otherwise comes up ready.
fn handoff_fake(w: &World, name: &str, delay: u32, code: u32) -> SessionsConfig {
    let p = w.root.join("fakes").join(name);
    let body = format!(
        "#!/bin/sh\nif [ \"$1\" = -p ]; then sleep {delay}; [ {code} = 0 ] || exit {code}; echo '## Goal'; sed 's/^/read: /'; exit 0; fi\n{}ready\nexec sleep 600\n",
        PRELUDE.trim_start_matches("#!/bin/sh\n")
    );
    std::fs::write(&p, body).expect("fake");
    std::fs::set_permissions(&p, std::fs::Permissions::from_mode(0o755)).expect("chmod");
    w.cfg(name)
}

fn handoff(path: &str, name: &str, conv: &str) -> SpawnRequest {
    SpawnRequest { handoff: Some(conv.into()), ..req(path, name) }
}

fn t_handoff_written_then_session_starts(l: Box<dyn Launcher>) {
    let w = World::new();
    w.seed_conversation("Projects/remoter", CONV, "fix the banner");
    w.announce_open(CONV);
    let s = w.sessions(handoff_fake(&w, "handoff-ok", 0, 0), l);
    let id = s.spawn(&handoff("Projects/remoter", "fresh", CONV), None).expect("handoff start");
    wait_state(&s, &id, SessionState::Ready, Duration::from_secs(15));
    // state is read off disk, events lag a tick
    let deadline = Instant::now() + Duration::from_secs(5);
    while !history(&s, &id).iter().any(|e| matches!(e, Event::State { state: SessionState::Ready, .. })) {
        assert!(Instant::now() < deadline, "no Ready event");
        std::thread::sleep(Duration::from_millis(100));
    }
    let steps: Vec<Phase> = history(&s, &id).into_iter().filter_map(|e| match e {
        Event::Phase { step, .. } => Some(step),
        _ => None,
    }).collect();
    assert_eq!(steps, [Phase::Accepted, Phase::Terminal, Phase::Handoff, Phase::Claude, Phase::RemoteControl]);
    let kept = std::fs::read_to_string(w.dir_of(&id).join("handoff.md")).expect("handoff kept");
    assert_eq!(kept, "## Goal\nread: [me] the first ask");
    let argv = std::fs::read_to_string(w.dir_of(&id).join("fake-argv")).expect("argv");
    assert!(argv.contains("read: [me] the first ask"), "{argv}");
    assert!(argv.contains(&format!("Earlier session: {CONV}")), "{argv}");
    let spec: remoter_proto::local::Spec = serde_json::from_slice(&std::fs::read(w.dir_of(&id).join("spec.json")).expect("spec")).expect("json");
    let h = spec.handoff.expect("handoff spec");
    assert!(h.summarizer.iter().any(|a| a.contains(CONV)), "the summarizer is told the id too");
    assert!(h.transcript.ends_with(&format!("{CONV}.jsonl")) && std::path::Path::new(&h.transcript).is_file());
    assert!(!argv.contains("[--resume]"), "a fresh session, not a resume");
    s.kill(&id).expect("kill");
    wait_gone(&s, &id, Duration::from_secs(10));
}

fn t_a_failed_handoff_is_stuck_handoff_failed(l: Box<dyn Launcher>) {
    let w = World::new();
    w.seed_conversation("Projects/remoter", CONV, "fix the banner");
    let s = w.sessions(handoff_fake(&w, "handoff-bad", 0, 1), l);
    let id = s.spawn(&handoff("Projects/remoter", "fresh", CONV), None).expect("spawn");
    let got = wait_state(&s, &id, SessionState::Stuck, Duration::from_secs(15));
    assert_eq!(got.reason, Some(StuckReason::HandoffFailed));
    s.kill(&id).expect("kill");
    wait_gone(&s, &id, Duration::from_secs(10));
}

// the summarizer can take most of a minute, that isn't claude's time
fn t_a_slow_handoff_never_reads_as_stuck(l: Box<dyn Launcher>) {
    let w = World::new();
    w.seed_conversation("Projects/remoter", CONV, "fix the banner");
    let mut cfg = handoff_fake(&w, "handoff-slow", 4, 0);
    cfg.ready_timeout = Duration::from_secs(2);
    let s = w.sessions(cfg, l);
    let id = s.spawn(&handoff("Projects/remoter", "fresh", CONV), None).expect("spawn");
    let deadline = Instant::now() + Duration::from_secs(15);
    loop {
        let g = s.get(&id).expect("get");
        assert_ne!(g.state, SessionState::Stuck, "read as stuck while the summarizer worked");
        if g.state == SessionState::Ready {
            break;
        }
        assert!(Instant::now() < deadline, "never ready: {g:?}");
        std::thread::sleep(Duration::from_millis(100));
    }
    s.kill(&id).expect("kill");
    wait_gone(&s, &id, Duration::from_secs(10));
}

// went red at 20s once, claude connected at 31s
fn t_a_start_past_the_timeout_still_turns_ready(l: Box<dyn Launcher>) {
    let w = World::new();
    let mut cfg = w.cfg("late");
    cfg.ready_timeout = Duration::from_secs(2);
    let s = w.sessions(cfg, l);
    let id = s.spawn(&req("Projects/remoter", "late"), None).expect("spawn");
    let stuck = wait_state(&s, &id, SessionState::Stuck, Duration::from_secs(10));
    assert_eq!(stuck.reason, Some(StuckReason::Timeout));
    let ready = wait_state(&s, &id, SessionState::Ready, Duration::from_secs(15));
    assert!(ready.claude.is_some(), "the link comes with it");
    // past the timeout the watcher polls every 2s, so the event trails a bit
    let deadline = Instant::now() + Duration::from_secs(5);
    loop {
        let states: Vec<SessionState> = history(&s, &id).into_iter().filter_map(|e| match e { Event::State { state, .. } => Some(state), _ => None }).collect();
        if states.last() == Some(&SessionState::Ready) {
            assert!(states.contains(&SessionState::Stuck), "{states:?}");
            break;
        }
        assert!(Instant::now() < deadline, "{states:?}");
        std::thread::sleep(Duration::from_millis(200));
    }
    s.kill(&id).expect("kill");
    wait_gone(&s, &id, Duration::from_secs(10));
}

fn t_live_wakes_on_spawn_and_kill(l: Box<dyn Launcher>) {
    let w = World::new();
    let s = Arc::new(w.sessions(w.cfg("ready"), l));
    let (seq, list) = s.live(0, Duration::from_secs(5));
    assert!(list.is_empty(), "{list:?}");
    let waiter = {
        let s = s.clone();
        std::thread::spawn(move || {
            let t0 = Instant::now();
            let mut at = seq;
            loop {
                let (next, list) = s.live(at, Duration::from_secs(10));
                assert!(next > at, "woke without a change after {:?}", t0.elapsed());
                at = next;
                if !list.is_empty() {
                    return (at, list, t0.elapsed());
                }
            }
        })
    };
    std::thread::sleep(Duration::from_millis(300));
    let id = s.spawn(&req("Projects/remoter", "live"), None).expect("spawn");
    let (_, list, took) = waiter.join().expect("waiter");
    assert_eq!(list.iter().map(|x| x.id.as_str()).collect::<Vec<_>>(), vec![id.as_str()]);
    assert!(took < Duration::from_secs(5), "timed out instead of waking: {took:?}");

    // let the start settle, phases keep bumping the counter
    wait_state(&s, &id, SessionState::Ready, Duration::from_secs(15));
    std::thread::sleep(Duration::from_millis(700));
    let (seq, _) = s.live(0, Duration::ZERO);

    let waiter = {
        let s = s.clone();
        std::thread::spawn(move || {
            let t0 = Instant::now();
            let r = s.live(seq, Duration::from_secs(10));
            (r, t0.elapsed())
        })
    };
    std::thread::sleep(Duration::from_millis(300));
    s.kill(&id).expect("kill");
    let ((after_kill, list), took) = waiter.join().expect("waiter");
    assert!(after_kill > seq);
    assert!(took < Duration::from_secs(5), "the kill woke the wait: {took:?}");
    assert_eq!(list.first().map(|x| x.state), Some(SessionState::Ending), "{list:?}");
    wait_gone(&s, &id, Duration::from_secs(10));
    let (_, list) = s.live(0, Duration::ZERO);
    assert!(list.is_empty(), "gone sessions leave the list: {list:?}");
}

fn t_worktree_is_tracked_then_cleaned_up(l: Box<dyn Launcher>) {
    let w = World::new();
    let repo = make_repo(&w);
    let s = w.sessions(w.cfg("worktree"), l);
    let id = s.spawn(&SpawnRequest { mode: SpawnMode::Worktree, ..req("Projects/wtrepo", "wt") }, None).expect("spawn");
    let got = wait_state(&s, &id, SessionState::Ready, Duration::from_secs(15));
    assert_eq!(got.worktree.as_deref(), Some("fake-wt"));
    let wt = repo.join(".claude/worktrees/fake-wt");
    let dir = w.dir_of(&id);
    let saved: serde_json::Value = serde_json::from_slice(&std::fs::read(dir.join("worktree.json")).expect("saved")).expect("json");
    assert_eq!(saved["path"], wt.display().to_string());
    assert_eq!(saved["name"], "fake-wt");
    assert!(git(&repo, &["worktree", "list", "--porcelain"]).contains("locked"), "claude holds it locked while running");

    s.kill(&id).expect("kill");
    wait_gone(&s, &id, Duration::from_secs(10));
    wait_removed(&dir);
    assert!(!wt.exists(), "the clean worktree is removed");
    assert!(!has_branch(&repo, "worktree-fake-wt"), "and its branch, which had nothing new");
}

macro_rules! suite {
    ($m:ident, $make:path, $attr:meta) => {
        mod $m {
            use super::*;
            #[$attr]
            #[test]
            fn ready_argv_cwd_phases_and_kill() {
                t_ready_argv_cwd_phases_and_kill($make());
            }
            #[$attr]
            #[test]
            fn hang_is_stuck_on_timeout() {
                t_hang_is_stuck_on_timeout($make());
            }
            #[$attr]
            #[test]
            fn exit1_is_exited_with_its_code() {
                t_exit1_is_exited_with_its_code($make());
            }
            #[$attr]
            #[test]
            fn untrusted_text_is_stuck_untrusted() {
                t_untrusted_text_is_stuck_untrusted($make());
            }
            #[$attr]
            #[test]
            fn untrusted_screen_arriving_late_is_still_untrusted() {
                t_untrusted_screen_arriving_late_is_still_untrusted($make());
            }
            #[$attr]
            #[test]
            fn kill_escalates_past_ignored_sigint() {
                t_kill_escalates_past_ignored_sigint($make());
            }
            #[$attr]
            #[test]
            fn slow_start_is_ready_not_stuck() {
                t_slow_start_is_ready_not_stuck($make());
            }
            #[$attr]
            #[test]
            fn a_start_past_the_timeout_still_turns_ready() {
                t_a_start_past_the_timeout_still_turns_ready($make());
            }
            #[$attr]
            #[test]
            fn trust_from_the_phone_trusts_then_starts() {
                t_trust_from_the_phone_trusts_then_starts($make());
            }
            #[$attr]
            #[test]
            fn second_session_in_a_folder_is_folder_busy() {
                t_second_session_in_a_folder_is_folder_busy($make());
            }
            #[$attr]
            #[test]
            fn folder_served_elsewhere_is_stuck_folder_busy() {
                t_folder_served_elsewhere_is_stuck_folder_busy($make());
            }
            #[$attr]
            #[test]
            fn cap_holds_under_two_concurrent_spawns() {
                t_cap_holds_under_two_concurrent_spawns($make());
            }
            #[$attr]
            #[test]
            fn agent_restart_loses_nothing() {
                t_agent_restart_loses_nothing($make(), $make());
            }
            #[$attr]
            #[test]
            fn folder_name_cant_fake_ready() {
                t_folder_name_cant_fake_ready($make());
            }
            #[$attr]
            #[test]
            fn folder_swapped_between_check_and_exec() {
                t_folder_swapped_between_check_and_exec($make());
            }
            #[$attr]
            #[test]
            fn live_wakes_on_spawn_and_kill() {
                t_live_wakes_on_spawn_and_kill($make());
            }
            #[$attr]
            #[test]
            fn resume_reaches_ready_and_holds_the_conversation() {
                t_resume_reaches_ready_and_holds_the_conversation($make());
            }
            #[$attr]
            #[test]
            fn handoff_written_then_session_starts() {
                t_handoff_written_then_session_starts($make());
            }
            #[$attr]
            #[test]
            fn a_failed_handoff_is_stuck_handoff_failed() {
                t_a_failed_handoff_is_stuck_handoff_failed($make());
            }
            #[$attr]
            #[test]
            fn a_slow_handoff_never_reads_as_stuck() {
                t_a_slow_handoff_never_reads_as_stuck($make());
            }
            #[$attr]
            #[test]
            fn a_refused_start_doesnt_hold_its_folder() {
                t_a_refused_start_doesnt_hold_its_folder($make());
            }
            #[$attr]
            #[test]
            fn a_slow_stuck_start_still_holds_its_folder() {
                t_a_slow_stuck_start_still_holds_its_folder($make());
            }
            #[$attr]
            #[test]
            fn worktree_is_tracked_then_cleaned_up() {
                t_worktree_is_tracked_then_cleaned_up($make());
            }
        }
    };
}

suite!(kitty_ws9, kitty, ignore = "opens real kitty windows on Hyprland workspace 9: cargo test -- --ignored");
#[cfg(feature = "e2e-test")]
suite!(headless_pty, headless, allow(unused_attributes));

// refusals, no launch needed, run in every build

struct NeverLaunch;

impl Launcher for NeverLaunch {
    fn prepare(&self) -> Result<(), AgentError> {
        panic!("a refused spawn reached the launcher")
    }
    fn launch(&self, _: &str, _: &Path) -> Result<(), AgentError> {
        panic!("a refused spawn reached the launcher")
    }
    fn screen(&self, _: &Path) -> Option<String> {
        None
    }
}

fn refused(w: &World, cfg: SessionsConfig, r: SpawnRequest) -> ErrorCode {
    let s = w.sessions(cfg, Box::new(NeverLaunch));
    let code = s.spawn(&r, None).expect_err("must be refused").code;
    assert!(std::fs::read_dir(&w.runtime).expect("runtime").next().is_none(), "no session dir left behind");
    code
}

#[test]
fn every_refusal_has_its_own_code() {
    let w = World::new();
    std::fs::write(&w.locked, b"").expect("lock");
    assert_eq!(refused(&w, w.cfg("ready"), req("Projects/remoter", "x")), ErrorCode::Locked);
    std::fs::remove_file(&w.locked).expect("unlock");
    std::os::unix::fs::symlink("/nonexistent", &w.locked).expect("dangling");
    assert_eq!(refused(&w, w.cfg("ready"), req("Projects/remoter", "x")), ErrorCode::Locked, "a dangling flag still locks");
    std::fs::remove_file(&w.locked).expect("unlock");

    assert_eq!(refused(&w, w.cfg("ready"), req("Projects/.ssh", "x")), ErrorCode::PathDenied);
    assert_eq!(refused(&w, w.cfg("ready"), req("", "x")), ErrorCode::PathDenied, "home itself");
    assert_eq!(refused(&w, w.cfg("ready"), req("Documents", "x")), ErrorCode::UntrustedFolder);
    assert_eq!(refused(&w, w.cfg("ready"), req("Projects/nope", "x")), ErrorCode::NotFound);
    assert_eq!(refused(&w, w.cfg("ready"), req("../x", "x")), ErrorCode::PathOutsideHome);
    assert_eq!(refused(&w, w.cfg("ready"), req("/etc", "x")), ErrorCode::PathOutsideHome);
    for bad in ["", "   ", "-rf", "a;b", "$HOME", "x\u{1b}]2;y"] {
        assert_eq!(refused(&w, w.cfg("ready"), req("Projects/remoter", bad)), ErrorCode::NameInvalid, "{bad:?}");
    }
    let wt = SpawnRequest { path: "Projects/plain".into(), name: "x".into(), mode: SpawnMode::Worktree, trust: false, resume: None, handoff: None };
    assert_eq!(refused(&w, w.cfg("ready"), wt), ErrorCode::BadRequest, "worktree needs git");
    let mut cfg = w.cfg("ready");
    cfg.claude_bin = w.root.join("fakes/missing");
    assert_eq!(refused(&w, cfg, req("Projects/remoter", "x")), ErrorCode::SpawnFailed);
}

#[test]
fn resume_refusals_write_nothing() {
    let w = World::new();
    w.seed_conversation("Projects/remoter", CONV, "fix the banner");
    let other = "0b4910a1-dc2c-41b3-81c1-b8c9fd626592";
    w.seed_conversation("Projects/plain", other, "elsewhere");
    let trust_before = std::fs::read(w.root.join("claude.json")).expect("trust");

    for bad in ["C82D8B5C-EDD4-453E-8D59-4748FF325C03", "--dangerously-skip-permissions", "$HOME", "", "c82d8b5c"] {
        assert_eq!(refused(&w, w.cfg("ready"), resume("Projects/remoter", "x", bad)), ErrorCode::BadRequest, "{bad:?}");
    }
    let wt = SpawnRequest { mode: SpawnMode::Worktree, ..resume("Projects/remoter", "x", CONV) };
    assert_eq!(refused(&w, w.cfg("ready"), wt), ErrorCode::BadRequest, "a resume runs in its own folder");
    assert_eq!(refused(&w, w.cfg("ready"), resume("Projects/remoter", "x", "11111111-2222-3333-4444-555555555555")), ErrorCode::NotFound);
    assert_eq!(refused(&w, w.cfg("ready"), resume("Projects/remoter", "x", other)), ErrorCode::NotFound, "a conversation from another folder");
    let mut bare = w.cfg("ready");
    bare.transcripts = None;
    assert_eq!(refused(&w, bare, resume("Projects/remoter", "x", CONV)), ErrorCode::BadRequest);
    // a refused resume must not trust Documents on the way
    std::fs::create_dir_all(w.home.join("Documents")).expect("docs");
    let untrusted = SpawnRequest { trust: true, ..resume("Documents", "x", CONV) };
    assert_eq!(refused(&w, w.cfg("ready"), untrusted), ErrorCode::NotFound);
    assert_eq!(std::fs::read(w.root.join("claude.json")).expect("trust"), trust_before, "nothing trusted");

    w.announce_open(CONV);
    assert_eq!(refused(&w, w.cfg("ready"), resume("Projects/remoter", "x", CONV)), ErrorCode::ConversationOpen);
    // lock still comes first
    std::fs::write(&w.locked, b"").expect("lock");
    assert_eq!(refused(&w, w.cfg("ready"), resume("Projects/remoter", "x", CONV)), ErrorCode::Locked);
}

#[test]
fn handoff_refusals_write_nothing() {
    let w = World::new();
    w.seed_conversation("Projects/remoter", CONV, "fix the banner");
    let other = "0b4910a1-dc2c-41b3-81c1-b8c9fd626592";
    w.seed_conversation("Projects/plain", other, "elsewhere");
    let both = SpawnRequest { resume: Some(CONV.into()), ..handoff("Projects/remoter", "x", CONV) };
    assert_eq!(refused(&w, w.cfg("ready"), both), ErrorCode::BadRequest, "resume or handoff, not both");
    for bad in ["C82D8B5C-EDD4-453E-8D59-4748FF325C03", "--x", ""] {
        assert_eq!(refused(&w, w.cfg("ready"), handoff("Projects/remoter", "x", bad)), ErrorCode::BadRequest, "{bad:?}");
    }
    assert_eq!(refused(&w, w.cfg("ready"), handoff("Projects/remoter", "x", other)), ErrorCode::NotFound, "only from this folder");
    // nothing said, nothing to hand off
    let empty = "11111111-2222-3333-4444-555555555555";
    let path = w.seed_conversation("Projects/remoter", empty, "x");
    let cwd = w.home.join("Projects/remoter").display().to_string();
    std::fs::write(&path, format!("{}\n", serde_json::json!({"type":"system","cwd":cwd}))).expect("w");
    assert_eq!(refused(&w, w.cfg("ready"), handoff("Projects/remoter", "x", empty)), ErrorCode::NotFound);
    let mut bare = w.cfg("ready");
    bare.transcripts = None;
    assert_eq!(refused(&w, bare, handoff("Projects/remoter", "x", CONV)), ErrorCode::BadRequest);
}

#[test]
fn kill_refuses_unknown_ids_and_honours_the_lock() {
    let w = World::new();
    let s = w.sessions(w.cfg("ready"), Box::new(NeverLaunch));
    for id in ["rc-01k6b7y3m4n5p6q7r8s9t0v1w2", "../etc", "rc-", "RC-01K6B7Y3M4N5P6Q7R8S9T0V1W2"] {
        assert_eq!(s.kill(id).err().map(|e| e.code), Some(ErrorCode::NotFound), "{id}");
    }
    std::fs::write(&w.locked, b"").expect("lock");
    assert_eq!(s.kill("rc-01k6b7y3m4n5p6q7r8s9t0v1w2").err().map(|e| e.code), Some(ErrorCode::Locked));
}

#[test]
fn runtime_base_must_be_private() {
    let w = World::new();
    std::fs::create_dir_all(&w.runtime).expect("mk");
    std::fs::set_permissions(&w.runtime, std::fs::Permissions::from_mode(0o755)).expect("chmod");
    let home = Home::open(&w.home).expect("home");
    let r = Sessions::new(w.cfg("ready"), home, Trust::new(w.root.join("claude.json")), Box::new(NeverLaunch));
    assert!(r.is_err(), "a 0755 runtime dir is refused");
}

#[test]
fn live_waits_out_the_timeout_when_nothing_happens() {
    let w = World::new();
    let s = w.sessions(w.cfg("ready"), Box::new(NeverLaunch));
    let (seq, list) = s.live(0, Duration::from_secs(5));
    assert!(seq > 0 && list.is_empty(), "a fresh agent answers at once: {seq} {list:?}");
    let t0 = Instant::now();
    let (again, list) = s.live(seq, Duration::from_millis(600));
    let took = t0.elapsed();
    assert_eq!(again, seq, "nothing happened, so the version is the same");
    assert!(list.is_empty());
    assert!(took >= Duration::from_millis(600) && took < Duration::from_secs(3), "{took:?}");
    let t0 = Instant::now();
    let _ = s.live(seq - 1, Duration::from_secs(5));
    assert!(t0.elapsed() < Duration::from_secs(1), "an older version answers at once");
}

/// Passes every check then fails, so we can look at what spawn did first.
struct FailLaunch;

impl Launcher for FailLaunch {
    fn prepare(&self) -> Result<(), AgentError> {
        Ok(())
    }
    fn launch(&self, _: &str, _: &Path) -> Result<(), AgentError> {
        Err(AgentError::new(ErrorCode::SpawnFailed, "test launcher"))
    }
    fn screen(&self, _: &Path) -> Option<String> {
        None
    }
}

// --worktree refuses a folder only trusted through its parent
#[test]
fn worktree_spawn_adds_own_trust_entry() {
    let w = World::new();
    let s = w.sessions(w.cfg("ready"), Box::new(FailLaunch));
    let entry = |rel: &str| {
        let json: serde_json::Value = serde_json::from_slice(&std::fs::read(w.root.join("claude.json")).expect("read")).expect("json");
        json["projects"][w.home.join(rel).display().to_string()]["hasTrustDialogAccepted"].clone()
    };
    s.spawn(&req("Projects/plain", "same dir"), None).expect_err("the launcher fails");
    assert_eq!(entry("Projects/plain"), serde_json::Value::Null, "plain claude is fine with the parent's trust");
    s.spawn(&SpawnRequest { mode: SpawnMode::Worktree, ..req("Projects/remoter", "wt") }, None).expect_err("the launcher fails");
    assert_eq!(entry("Projects/remoter"), true);
    assert_eq!(entry("Projects"), true, "the parent is kept");
}

const ENDED: &str = "rc-01k6b7y3m4n5p6q7r8s9t0zzzz";

/// Ended while no agent watched, left the way claude leaves it (locked, branch there).
fn ended_worktree_session(w: &World, repo: &Path, name: &str) -> PathBuf {
    use std::os::unix::fs::DirBuilderExt;
    let wt = repo.join(".claude/worktrees").join(name);
    let wt_s = wt.display().to_string();
    git(repo, &["worktree", "add", "-q", "-b", &format!("worktree-{name}"), &wt_s]);
    git(repo, &["worktree", "lock", &wt_s]);
    let dir = w.runtime.join(ENDED);
    std::fs::DirBuilder::new().mode(0o700).recursive(true).create(&dir).expect("session dir");
    let spec = remoter_proto::local::Spec {
        id: ENDED.into(),
        dir: dir.display().to_string(),
        cwd: repo.display().to_string(),
        dev: 0,
        ino: 0,
        rel: "Projects/wtrepo".into(),
        name: "wt".into(),
        device: None,
        started: 0,
        argv: vec!["claude".into(), "--worktree".into()],
        handoff: None,
    };
    std::fs::write(dir.join("spec.json"), serde_json::to_vec(&spec).expect("json")).expect("spec");
    std::fs::write(dir.join("worktree.json"), serde_json::json!({ "path": wt_s, "name": name }).to_string()).expect("worktree.json");
    wt
}

fn agent_starts(w: &World) {
    let _s = w.sessions(w.cfg("ready"), Box::new(NeverLaunch));
    assert!(!w.runtime.join(ENDED).exists(), "the ended session's dir is cleaned up");
}

#[test]
fn an_ended_sessions_clean_worktree_and_branch_go() {
    let w = World::new();
    let repo = make_repo(&w);
    let wt = ended_worktree_session(&w, &repo, "calm-heron");
    agent_starts(&w);
    assert!(!wt.exists(), "worktree removed");
    assert!(!has_branch(&repo, "worktree-calm-heron"), "branch deleted");
    assert!(!git(&repo, &["worktree", "list"]).contains("calm-heron"));
}

#[test]
fn an_ended_sessions_dirty_worktree_is_kept() {
    let w = World::new();
    let repo = make_repo(&w);
    let wt = ended_worktree_session(&w, &repo, "busy-heron");
    std::fs::write(wt.join("notes.txt"), "half done\n").expect("untracked file");
    std::fs::write(wt.join("README"), "changed\n").expect("edit");
    agent_starts(&w);
    assert_eq!(std::fs::read_to_string(wt.join("notes.txt")).ok().as_deref(), Some("half done\n"), "nothing lost");
    assert_eq!(std::fs::read_to_string(wt.join("README")).ok().as_deref(), Some("changed\n"));
    assert!(has_branch(&repo, "worktree-busy-heron"));
}

#[test]
fn an_ended_sessions_commits_keep_their_branch() {
    let w = World::new();
    let repo = make_repo(&w);
    let wt = ended_worktree_session(&w, &repo, "proud-heron");
    std::fs::write(wt.join("feature.txt"), "done\n").expect("write");
    git(&wt, &["add", "feature.txt"]);
    git(&wt, &["commit", "-q", "-m", "the work"]);
    agent_starts(&w);
    assert!(!wt.exists(), "a clean worktree goes even with new commits");
    assert!(has_branch(&repo, "worktree-proud-heron"), "the unmerged branch stays");
    assert_eq!(git(&repo, &["log", "-1", "--format=%s", "worktree-proud-heron"]).trim(), "the work");
}
