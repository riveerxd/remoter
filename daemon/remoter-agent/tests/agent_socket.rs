//! The test plays a hostile remoterd: it can send anything down the socket
//! but doesn't have the phone's signing key.

use std::io::{Read, Write};
use std::os::unix::net::{UnixListener, UnixStream};
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicUsize, Ordering};
use std::sync::{Arc, Mutex};
use std::time::Duration;

use remoter_agent::AgentError;
use remoter_agent::guard::Home;
use remoter_agent::launcher::Launcher;
use remoter_agent::notify::Notifier;
use remoter_agent::server::{AgentCtx, serve};
use remoter_agent::sessions::{Sessions, SessionsConfig};
use remoter_agent::trust::Trust;
use remoter_auth::testkit::{Phone, device_file};
use remoter_auth::{RawSigHeaders, Verifier};
use remoter_proto::api::ErrorBody;
use remoter_proto::ipc::{AgentReply, AgentRequest, LiveReply, MutateReply};
use remoter_proto::{ErrorCode, b64, local};

const A: &str = "01K6B7Y3M4N5P6Q7R8S9T0V1W2";
const B: &str = "01K6B7Y3M4N5P6Q7R8S9T0V1W3";
const C: &str = "01K6B7Y3M4N5P6Q7R8S9T0V1W4";

struct NoLaunch;

impl Launcher for NoLaunch {
    fn prepare(&self) -> Result<(), AgentError> {
        Err(AgentError::new(ErrorCode::DesktopDown, "no desktop in this test"))
    }
    fn launch(&self, _: &str, _: &Path) -> Result<(), AgentError> {
        panic!("never reached")
    }
    fn screen(&self, _: &Path) -> Option<String> {
        None
    }
}

#[derive(Default)]
struct Recorder(Arc<Mutex<Vec<String>>>);

impl Notifier for Recorder {
    fn notify(&self, _: bool, body: &str) {
        self.0.lock().expect("lock").push(body.to_owned());
    }
}

struct World {
    root: PathBuf,
    home: PathBuf,
    socket: PathBuf,
    locked: PathBuf,
    unpaired: PathBuf,
    a: Phone,
    b: Phone,
    started: i64,
    runtime: PathBuf,
    agent_state: PathBuf,
}

impl World {
    /// `peer_uid` is who the agent answers, our own uid plays remoterd.
    fn start(peer_uid: u32) -> World {
        World::start_with(peer_uid, Box::new(NoLaunch), None, 30_000).0
    }

    /// `agent_age_ms`: how long ago the agent "started".
    fn start_with(peer_uid: u32, launcher: Box<dyn Launcher>, claude: Option<&str>, agent_age_ms: i64) -> (World, Arc<Mutex<Vec<String>>>) {
        static N: AtomicUsize = AtomicUsize::new(0);
        let n = N.fetch_add(1, Ordering::Relaxed);
        let root = PathBuf::from(env!("CARGO_TARGET_TMPDIR")).join(format!("sock-{}-{n}", std::process::id()));
        let _ = std::fs::remove_dir_all(&root);
        let home = root.join("home");
        for d in ["Projects/remoter", ".ssh"] {
            std::fs::create_dir_all(home.join(d)).expect("mk");
        }
        let a = Phone::new(A, 1, b"tls-a".to_vec());
        let b = Phone::new(B, 2, b"tls-b".to_vec());
        std::fs::write(root.join("devices.json"), device_file(&[&a, &b])).expect("devices");
        std::fs::write(
            root.join("claude.json"),
            format!(r#"{{"projects":{{"{}/Projects":{{"hasTrustDialogAccepted":true}}}}}}"#, home.display()),
        )
        .expect("trust");
        let claude_bin = match claude {
            Some(script) => {
                let p = root.join("fake-claude");
                std::fs::write(&p, script).expect("fake");
                use std::os::unix::fs::PermissionsExt;
                std::fs::set_permissions(&p, std::fs::Permissions::from_mode(0o755)).expect("chmod");
                p
            }
            None => root.join("no-claude"),
        };
        let notes = Arc::new(Mutex::new(Vec::new()));
        let run = std::env::var_os("XDG_RUNTIME_DIR").map(PathBuf::from).unwrap_or_else(std::env::temp_dir);
        let runtime = run.join(format!("rmt-s{}-{n}", std::process::id()));
        let runtime_dir = runtime.clone();
        // signed requests are refused for a window after start, so by default
        // pretend the agent has been up for a while
        let started = local::now_ms();
        let sessions = Sessions::new(
            SessionsConfig {
                cwd_deny: vec![".ssh".into()],
                claude_bin,
                git_bin: "/usr/bin/git".into(),
                max_sessions: 8,
                runtime_base: runtime,
                locked_flag: root.join("locked"),
                ready_timeout: Duration::from_secs(20),
                exited_ttl: Duration::from_secs(3600),
                history: None,
                transcripts: Some(Arc::new(remoter_agent::transcripts::Transcripts::new(root.join("dot-claude")))),
            },
            Home::open(&home).expect("home"),
            Trust::new(root.join("claude.json")),
            launcher,
        )
        .expect("sessions");
        let ctx = Arc::new(AgentCtx {
            home: Home::open(&home).expect("home"),
            trust: Trust::new(root.join("claude.json")),
            sessions,
            cwd_deny: vec![".ssh".into()],
            search_skip: vec![],
            max_sessions: 8,
            git_bin: "/usr/bin/git".into(),
            devices_file: root.join("devices.json"),
            unpaired_file: root.join("unpaired.json"),
            locked_flag: root.join("locked"),
            verifier: Verifier::for_agent(30_000, started - agent_age_ms, 64),
            history: None,
            notifier: Box::new(Recorder(notes.clone())),
            peer_uid,
            agent_state: root.join("agent-state"),
            autolock: Mutex::default(),
        });
        let socket = root.join("agent.sock");
        let l = UnixListener::bind(&socket).expect("bind");
        std::thread::spawn(move || serve(l, ctx));
        (World { locked: root.join("locked"), unpaired: root.join("unpaired.json"), agent_state: root.join("agent-state"), root, home, socket, a, b, started, runtime: runtime_dir }, notes)
    }

    /// A refused peer or oversized frame gets closed with unread data, which
    /// shows up as a reset. That counts as an empty reply.
    fn raw(&self, req: &[u8]) -> Vec<u8> {
        let mut s = UnixStream::connect(&self.socket).expect("connect");
        let io = (|| {
            s.write_all(req)?;
            s.shutdown(std::net::Shutdown::Write)?;
            let mut out = Vec::new();
            s.read_to_end(&mut out)?;
            Ok::<_, std::io::Error>(out)
        })();
        match io {
            Ok(out) => out,
            Err(e) if matches!(e.kind(), std::io::ErrorKind::ConnectionReset | std::io::ErrorKind::BrokenPipe) => Vec::new(),
            Err(e) => panic!("socket: {e}"),
        }
    }

    fn call(&self, req: &AgentRequest) -> AgentReply {
        let out = self.raw(&serde_json::to_vec(req).expect("json"));
        serde_json::from_slice(&out).unwrap_or_else(|_| panic!("no reply: {:?}", String::from_utf8_lossy(&out)))
    }

    fn mutate(&self, device: &str, method: &str, target: &str, headers: &RawSigHeaders, body: &[u8]) -> MutateReply {
        let req = AgentRequest::Mutate {
            device: device.into(),
            request_id: "rid".into(),
            method: method.into(),
            target: target.into(),
            headers: headers.clone(),
            body: b64::encode(body),
        };
        match self.call(&req) {
            AgentReply::Ok(v) => serde_json::from_value(v).expect("mutate reply"),
            AgentReply::Err(f) => panic!("mutate answered with an agent error: {f:?}"),
        }
    }

    fn mkdir_body(name: &str) -> Vec<u8> {
        format!(r#"{{"parent":"Projects","name":"{name}","git_init":false}}"#).into_bytes()
    }
}

impl Drop for World {
    fn drop(&mut self) {
        if let Ok(rd) = std::fs::read_dir(&self.runtime) {
            for e in rd.flatten() {
                let unit = format!("{}.scope", e.file_name().to_string_lossy());
                let _ = std::process::Command::new("systemctl").args(["--user", "kill", "--signal=SIGKILL", &unit]).output();
            }
        }
        let _ = std::fs::remove_dir_all(&self.runtime);
        let _ = std::fs::remove_dir_all(&self.root);
    }
}

fn code(r: &MutateReply) -> Option<ErrorCode> {
    (r.status >= 400).then(|| serde_json::from_str::<ErrorBody>(&r.body).expect("error body").code)
}

fn uid() -> u32 {
    // SAFETY: getuid has no preconditions.
    unsafe { libc::getuid() }
}

#[test]
fn a_peer_that_isnt_remoterd_gets_nothing() {
    let w = World::start(uid() + 4242);
    let out = w.raw(br#"{"op":"status"}"#);
    assert!(out.is_empty(), "stranger got a reply");
    let body = World::mkdir_body("x");
    let h = w.a.sign("POST", "/v1/fs/mkdir", &body, local::now_ms(), &[1; 16]);
    let req = serde_json::to_vec(&AgentRequest::Mutate {
        device: A.into(),
        request_id: "r".into(),
        method: "POST".into(),
        target: "/v1/fs/mkdir".into(),
        headers: h,
        body: b64::encode(&body),
    })
    .expect("json");
    assert!(w.raw(&req).is_empty(), "not even a correctly signed mutation");
    assert!(!w.home.join("Projects/x").exists());
}

#[test]
fn valid_signature_runs_once_and_replays() {
    let w = World::start(uid());
    let body = World::mkdir_body("made");
    let h = w.a.sign("POST", "/v1/fs/mkdir", &body, local::now_ms(), &[2; 16]);
    let first = w.mutate(A, "POST", "/v1/fs/mkdir", &h, &body);
    assert_eq!(first.status, 201, "{}", first.body);
    assert!(w.home.join("Projects/made").is_dir());
    // a second run would say `exists`, so getting the same answer means it ran once
    let again = w.mutate(A, "POST", "/v1/fs/mkdir", &h, &body);
    assert_eq!(again, first);
}

#[test]
fn every_forgery_is_refused_by_the_agent_itself() {
    let w = World::start(uid());
    let now = local::now_ms();
    let body = World::mkdir_body("forged");
    let t = "/v1/fs/mkdir";
    // why, device remoterd claims, headers, body sent, target sent, expected
    type Case<'a> = (&'a str, &'a str, RawSigHeaders, Vec<u8>, &'a str, ErrorCode);
    let cases: Vec<Case> = vec![
        ("wrong key", A, w.a.sign_as(A, &w.b.sig, "POST", t, &body, now, &[3; 16]), body.clone(), t, ErrorCode::SigInvalid),
        ("remoterd lies about the mTLS device", B, w.a.sign("POST", t, &body, now, &[4; 16]), body.clone(), t, ErrorCode::SigInvalid),
        ("right key, other device's headers", A, w.b.sign("POST", t, &body, now, &[5; 16]), body.clone(), t, ErrorCode::SigInvalid),
        ("body changed", A, w.a.sign("POST", t, &body, now, &[6; 16]), World::mkdir_body("other"), t, ErrorCode::SigInvalid),
        ("path changed", A, w.a.sign("POST", t, &body, now, &[7; 16]), body.clone(), "/v1/fs/mkdir?x=1", ErrorCode::SigInvalid),
        ("31 s old", A, w.a.sign("POST", t, &body, now - 31_000, &[8; 16]), body.clone(), t, ErrorCode::ClockSkew),
        ("31 s ahead", A, w.a.sign("POST", t, &body, now + 31_000, &[9; 16]), body.clone(), t, ErrorCode::ClockSkew),
        ("could have run before the agent started", A, w.a.sign("POST", t, &body, w.started - 1_000, &[10; 16]), body.clone(), t, ErrorCode::ClockSkew),
        ("device not in the device file", C, Phone::new(C, 3, b"tls-c".to_vec()).sign("POST", t, &body, now, &[11; 16]), body.clone(), t, ErrorCode::DeviceUnknown),
    ];
    for (why, device, h, sent, target, want) in cases {
        let r = w.mutate(device, "POST", target, &h, &sent);
        assert_eq!(code(&r), Some(want), "{why}: {}", r.body);
    }
    let mut missing = w.a.sign("POST", t, &body, now, &[12; 16]);
    missing.signature = None;
    assert_eq!(code(&w.mutate(A, "POST", t, &missing, &body)), Some(ErrorCode::BadRequest), "missing header");
    assert!(!w.home.join("Projects/forged").exists(), "nothing forged ever ran");
    assert!(!w.home.join("Projects/other").exists());
}

#[test]
fn okhttp_style_encoded_target_verifies_as_sent() {
    let w = World::start(uid());
    let body = World::mkdir_body("enc");
    let target = "/v1/fs/mkdir?path=Projects%2Fx%20y%2Fcaf%C3%A9";
    let h = w.a.sign("POST", target, &body, local::now_ms(), &[13; 16]);
    assert_eq!(w.mutate(A, "POST", target, &h, &body).status, 201);
}

#[test]
fn nonce_reuse_is_refused_including_two_empty_deletes() {
    let w = World::start(uid());
    let now = local::now_ms();
    let b1 = World::mkdir_body("n1");
    let h1 = w.a.sign("POST", "/v1/fs/mkdir", &b1, now, &[14; 16]);
    assert_eq!(w.mutate(A, "POST", "/v1/fs/mkdir", &h1, &b1).status, 201);
    let b2 = World::mkdir_body("n2");
    let h2 = w.a.sign("POST", "/v1/fs/mkdir", &b2, now, &[14; 16]);
    assert_eq!(code(&w.mutate(A, "POST", "/v1/fs/mkdir", &h2, &b2)), Some(ErrorCode::NonceReused));
    assert!(!w.home.join("Projects/n2").exists());

    // a reused nonce locks the agent, so use a fresh one for the deletes
    let w = World::start(uid());
    let now = local::now_ms();
    let d1 = w.a.sign("DELETE", "/v1/sessions/rc-01k6b7y3m4n5p6q7r8s9t0v1w2", b"", now, &[15; 16]);
    let first = w.mutate(A, "DELETE", "/v1/sessions/rc-01k6b7y3m4n5p6q7r8s9t0v1w2", &d1, b"");
    assert_eq!(code(&first), Some(ErrorCode::NotFound), "no such session, but it did run");
    let d2 = w.a.sign("DELETE", "/v1/sessions/rc-01k6b7y3m4n5p6q7r8s9t0v1w3", b"", now, &[15; 16]);
    let second = w.mutate(A, "DELETE", "/v1/sessions/rc-01k6b7y3m4n5p6q7r8s9t0v1w3", &d2, b"");
    assert_eq!(code(&second), Some(ErrorCode::NonceReused), "not served the first one's reply");
}

#[test]
fn locked_flag_beats_a_valid_signature() {
    let w = World::start(uid());
    std::fs::write(&w.locked, b"").expect("lock");
    let body = World::mkdir_body("while-locked");
    let h = w.a.sign("POST", "/v1/fs/mkdir", &body, local::now_ms(), &[16; 16]);
    assert_eq!(code(&w.mutate(A, "POST", "/v1/fs/mkdir", &h, &body)), Some(ErrorCode::Locked));
    assert!(!w.home.join("Projects/while-locked").exists());
    // view token is a read, fine while locked
    let v = w.a.sign("POST", "/v1/view-token", b"{}", local::now_ms(), &[17; 16]);
    assert_eq!(w.mutate(A, "POST", "/v1/view-token", &v, b"{}").status, 200);
}

#[test]
fn unpaired_devices_are_unknown_to_the_agent() {
    let w = World::start(uid());
    std::fs::write(&w.unpaired, format!(r#"{{"devices":["{A}"]}}"#)).expect("w");
    let body = World::mkdir_body("after-unpair");
    let h = w.a.sign("POST", "/v1/fs/mkdir", &body, local::now_ms(), &[18; 16]);
    assert_eq!(code(&w.mutate(A, "POST", "/v1/fs/mkdir", &h, &body)), Some(ErrorCode::DeviceUnknown));
    std::fs::write(&w.unpaired, b"not json").expect("w");
    let h2 = w.b.sign("POST", "/v1/fs/mkdir", &body, local::now_ms(), &[19; 16]);
    assert_eq!(code(&w.mutate(B, "POST", "/v1/fs/mkdir", &h2, &body)), Some(ErrorCode::DeviceUnknown), "unreadable list refuses everyone");
}

#[test]
fn view_tokens_still_come_from_a_signed_request() {
    let w = World::start(uid());
    let id = "rc-01k6b7y3m4n5p6q7r8s9t0v1w2";
    let r = w.call(&AgentRequest::Session { id: id.into() });
    assert!(matches!(r, AgentReply::Err(f) if f.code == ErrorCode::NotFound));
    // older phones still ask for one, it opens nothing
    let v = w.a.sign("POST", "/v1/view-token", b"{}", local::now_ms(), &[20; 16]);
    let got = w.mutate(A, "POST", "/v1/view-token", &v, b"{}");
    let token: remoter_proto::api::ViewTokenResponse = serde_json::from_str(&got.body).expect("token");
    assert_eq!(b64::decode(&token.token).map(|t| t.len()), Some(32));
    assert!(token.expires > local::now_ms() + 14 * 60 * 1000);
}

#[test]
fn mkdir_rules() {
    let w = World::start(uid());
    let mut n = 30u8;
    let mut mk = |parent: &str, name: &str, git: bool| {
        n += 1;
        let body = format!(r#"{{"parent":"{parent}","name":"{name}","git_init":{git}}}"#).into_bytes();
        let h = w.a.sign("POST", "/v1/fs/mkdir", &body, local::now_ms(), &[n; 16]);
        w.mutate(A, "POST", "/v1/fs/mkdir", &h, &body)
    };
    for bad in ["-rf", "", ".", "..", "a b", "a/b", "x".repeat(101).as_str()] {
        assert_eq!(code(&mk("Projects", bad, false)), Some(ErrorCode::NameInvalid), "{bad:?}");
    }
    assert_eq!(mk("Projects", ".dot", false).status, 201, "a leading dot is fine");
    assert_eq!(code(&mk("Projects", ".dot", false)), Some(ErrorCode::Exists), "never merges");
    std::fs::write(w.home.join("Projects/file"), "x").expect("w");
    assert_eq!(code(&mk("Projects/file", "x", false)), Some(ErrorCode::NotADirectory), "parent is a file");
    assert_eq!(code(&mk(".ssh", "keys", false)), Some(ErrorCode::PathDenied), "parent denied");
    assert_eq!(code(&mk("../outside", "x", false)), Some(ErrorCode::PathOutsideHome));
    let made = mk("Projects", "repo", true);
    assert_eq!(made.status, 201, "{}", made.body);
    assert!(w.home.join("Projects/repo/.git").is_dir(), "git init ran in the new folder");
    assert!(!w.home.join("Projects/.git").exists(), "and not in its parent");
}

#[test]
fn garbage_and_oversized_requests() {
    let w = World::start(uid());
    let out = w.raw(b"{not json");
    let r: AgentReply = serde_json::from_slice(&out).expect("reply");
    assert!(matches!(r, AgentReply::Err(f) if f.code == ErrorCode::BadRequest));
    let big = vec![b'x'; remoter_proto::ipc::MAX_FRAME + 10];
    assert!(w.raw(&big).is_empty(), "oversized frames are dropped");
}

#[test]
fn live_holds_until_the_wait_runs_out() {
    let w = World::start(uid());
    let live = |after, wait_ms| match w.call(&AgentRequest::Live { after, wait_ms }) {
        AgentReply::Ok(v) => serde_json::from_value::<LiveReply>(v).expect("live reply"),
        AgentReply::Err(f) => panic!("{f:?}"),
    };
    let first = live(0, 20_000);
    assert!(first.sessions.is_empty());
    let t0 = std::time::Instant::now();
    let again = live(first.seq, 500);
    assert!(t0.elapsed() >= Duration::from_millis(500), "held for the wait");
    assert_eq!(again, first, "same version and list when nothing changed");
}

#[cfg(feature = "e2e-test")]
#[test]
fn signed_spawn_notifies_and_tail_needs_no_token() {
    let exec = std::env::current_exe().expect("exe").parent().and_then(Path::parent).expect("t").join("remoter-exec");
    assert!(exec.exists(), "build remoter-exec first");
    let fake = "#!/bin/sh\nfor last; do :; done\necho '[bridge:init] Created initial session session_01Hq7cXv2mTnR4bWkYe9pLsA' >> \"$last\"\necho hello from fake\nexec sleep 600\n";
    let (w, notes) = World::start_with(uid(), Box::new(remoter_agent::launcher::HeadlessLauncher { exec_bin: exec }), Some(fake), 30_000);
    // asked through a symlink, reported as the real folder
    std::os::unix::fs::symlink("remoter", w.home.join("Projects/alias")).expect("ln");
    let body = br#"{"path":"Projects/alias","name":"sock","mode":"same-dir"}"#;
    let h = w.a.sign("POST", "/v1/sessions", body, local::now_ms(), &[40; 16]);
    let r = w.mutate(A, "POST", "/v1/sessions", &h, body);
    assert_eq!(r.status, 202, "{}", r.body);
    assert_eq!(r.audit_path.as_deref(), Some("Projects/remoter"), "canonical path");
    let spawned: remoter_proto::api::SpawnResponse = serde_json::from_str(&r.body).expect("spawn");
    assert_eq!(b64::decode(&spawned.view_token).map(|t| t.len()), Some(32), "old phones read a token off the spawn");
    let detail = |id: &str| w.call(&AgentRequest::Session { id: id.into() });
    let mut ok = false;
    for _ in 0..50 {
        if let AgentReply::Ok(v) = detail(&spawned.id)
            && v["tail"].as_array().is_some_and(|t| t.iter().any(|l| l.as_str().is_some_and(|l| l.contains("hello from fake"))))
        {
            ok = true;
            break;
        }
        std::thread::sleep(Duration::from_millis(200));
    }
    assert!(ok, "the session's output, with no token at all");
    let other = "rc-01k6b7y3m4n5p6q7r8s9t0v1w2";
    assert!(matches!(detail(other), AgentReply::Err(f) if f.code == ErrorCode::NotFound), "an unknown session is still not found");
    let n = notes.lock().expect("l").clone();
    assert_eq!(n, vec![format!("remoter started sock in ~/Projects/remoter from phone {}", &A[..4])]);
    let end = w.a.sign("DELETE", &format!("/v1/sessions/{}", spawned.id), b"", local::now_ms(), &[41; 16]);
    assert_eq!(w.mutate(A, "DELETE", &format!("/v1/sessions/{}", spawned.id), &end, b"").status, 202);
}

#[cfg(feature = "e2e-test")]
#[test]
fn history_needs_no_token_and_a_resume_says_so() {
    use remoter_proto::api::HistoryResponse;
    let exec = std::env::current_exe().expect("exe").parent().and_then(Path::parent).expect("t").join("remoter-exec");
    assert!(exec.exists(), "build remoter-exec first");
    let fake = "#!/bin/sh\nfor last; do :; done\necho '2026-10-07T10:57:27.962Z [DEBUG] [remote-bridge] Reattaching to session cse_01Pm4tWq9zHc2vNe7gRb5kDy' >> \"$last\"\necho '2026-10-07T10:57:29.376Z [DEBUG] [bridge:repl] handleStateChange state=connected' >> \"$last\"\nexec sleep 600\n";
    let (w, notes) = World::start_with(uid(), Box::new(remoter_agent::launcher::HeadlessLauncher { exec_bin: exec }), Some(fake), 30_000);
    let conv = "c82d8b5c-edd4-453e-8d59-4748ff325c03";
    let cwd = w.home.join("Projects/remoter").display().to_string();
    let dir = w.root.join("dot-claude/projects").join(remoter_agent::transcripts::project_dir_name(&cwd));
    std::fs::create_dir_all(&dir).expect("mk");
    let line = serde_json::json!({"type":"user","cwd":cwd,"entrypoint":"cli","message":{"content":"fix the banner"}});
    std::fs::write(dir.join(format!("{conv}.jsonl")), format!("{line}\n")).expect("transcript");

    let body = format!(r#"{{"path":"Projects/remoter","name":"fix the banner","mode":"same-dir","resume":"{conv}"}}"#);
    let h = w.a.sign("POST", "/v1/sessions", body.as_bytes(), local::now_ms(), &[70; 16]);
    let r = w.mutate(A, "POST", "/v1/sessions", &h, body.as_bytes());
    assert_eq!(r.status, 202, "{}", r.body);
    let spawned: remoter_proto::api::SpawnResponse = serde_json::from_str(&r.body).expect("spawn");
    assert_eq!(notes.lock().expect("l").clone(), vec![format!("remoter resumed fix the banner in ~/Projects/remoter from phone {}", &A[..4])]);

    let path = b64::encode(b"Projects/remoter");
    let history = |path: &str| w.call(&AgentRequest::History { path: path.into() });
    let refused = |r: AgentReply| match r {
        AgentReply::Err(f) => Some(f.code),
        AgentReply::Ok(_) => None,
    };
    assert_eq!(refused(history(&b64::encode(b"../etc"))), Some(ErrorCode::PathOutsideHome), "the path guard still holds");
    let AgentReply::Ok(v) = history(&path) else { panic!("history refused") };
    let got: HistoryResponse = serde_json::from_value(v).expect("history");
    assert_eq!(got.path, "Projects/remoter");
    assert_eq!(got.conversations.len(), 1);
    assert_eq!(got.conversations[0].title, "fix the banner");
    assert!(got.conversations[0].open, "our resumed session holds it");

    let again = w.a.sign("POST", "/v1/sessions", body.as_bytes(), local::now_ms(), &[72; 16]);
    assert_eq!(code(&w.mutate(A, "POST", "/v1/sessions", &again, body.as_bytes())), Some(ErrorCode::ConversationOpen));
    let end = w.a.sign("DELETE", &format!("/v1/sessions/{}", spawned.id), b"", local::now_ms(), &[73; 16]);
    assert_eq!(w.mutate(A, "DELETE", &format!("/v1/sessions/{}", spawned.id), &end, b"").status, 202);
}

// empty replay store after a restart: anything the old process could have run is refused
#[test]
fn fresh_agent_refuses_requests_dated_ahead() {
    let (w, _) = World::start_with(uid(), Box::new(NoLaunch), None, 0);
    let body = World::mkdir_body("replayed");
    for (why, off, n) in [("dated 20 s ahead", 20_000i64, 60u8), ("dated now", 0, 61)] {
        let h = w.a.sign("POST", "/v1/fs/mkdir", &body, local::now_ms() + off, &[n; 16]);
        assert_eq!(code(&w.mutate(A, "POST", "/v1/fs/mkdir", &h, &body)), Some(ErrorCode::ClockSkew), "{why}");
    }
    let v = w.a.sign("POST", "/v1/view-token", b"{}", local::now_ms() + 20_000, &[62; 16]);
    assert_eq!(code(&w.mutate(A, "POST", "/v1/view-token", &v, b"{}")), Some(ErrorCode::ClockSkew), "no fresh token from a replay");
    assert!(!w.home.join("Projects/replayed").exists());
}

// once the agent saw remoterd's flag it keeps its own lock until root clears both
#[test]
fn agent_stays_locked_after_remoterd_clears_its_flag() {
    let w = World::start(uid());
    std::fs::write(&w.locked, b"").expect("lock");
    let b1 = World::mkdir_body("one");
    let h1 = w.a.sign("POST", "/v1/fs/mkdir", &b1, local::now_ms(), &[70; 16]);
    assert_eq!(code(&w.mutate(A, "POST", "/v1/fs/mkdir", &h1, &b1)), Some(ErrorCode::Locked));
    std::fs::remove_file(&w.locked).expect("a compromised remoterd deletes its flag");
    let b2 = World::mkdir_body("two");
    let h2 = w.a.sign("POST", "/v1/fs/mkdir", &b2, local::now_ms(), &[71; 16]);
    assert_eq!(code(&w.mutate(A, "POST", "/v1/fs/mkdir", &h2, &b2)), Some(ErrorCode::Locked), "still locked");
    assert!(!w.home.join("Projects/two").exists());
    // `remoterctl lock off`
    remoter_agent::server::clear_agent_lock(&w.agent_state).expect("clear");
    let b3 = World::mkdir_body("three");
    let h3 = w.a.sign("POST", "/v1/fs/mkdir", &b3, local::now_ms(), &[72; 16]);
    assert_eq!(w.mutate(A, "POST", "/v1/fs/mkdir", &h3, &b3).status, 201);
}

// counted by the agent too, so a remoterd that doesn't count can't dodge the lock
#[test]
fn agent_locks_itself_on_bad_signatures() {
    let w = World::start(uid());
    let body = World::mkdir_body("after-bad");
    for n in 0..3u8 {
        let bad = w.a.sign_as(A, &w.b.sig, "POST", "/v1/fs/mkdir", &body, local::now_ms(), &[80 + n; 16]);
        assert_eq!(code(&w.mutate(A, "POST", "/v1/fs/mkdir", &bad, &body)), Some(ErrorCode::SigInvalid));
    }
    let good = w.a.sign("POST", "/v1/fs/mkdir", &body, local::now_ms(), &[83; 16]);
    assert_eq!(code(&w.mutate(A, "POST", "/v1/fs/mkdir", &good, &body)), Some(ErrorCode::Locked));

    let w = World::start(uid());
    let (b1, b2) = (World::mkdir_body("r1"), World::mkdir_body("r2"));
    let t = local::now_ms();
    assert_eq!(w.mutate(A, "POST", "/v1/fs/mkdir", &w.a.sign("POST", "/v1/fs/mkdir", &b1, t, &[84; 16]), &b1).status, 201);
    let reused = w.a.sign("POST", "/v1/fs/mkdir", &b2, t, &[84; 16]);
    assert_eq!(code(&w.mutate(A, "POST", "/v1/fs/mkdir", &reused, &b2)), Some(ErrorCode::NonceReused));
    let good = w.a.sign("POST", "/v1/fs/mkdir", &b2, local::now_ms(), &[85; 16]);
    assert_eq!(code(&w.mutate(A, "POST", "/v1/fs/mkdir", &good, &b2)), Some(ErrorCode::Locked));
}

// recorded in agent state, which remoterd can't write
#[test]
fn unpair_is_verified_and_recorded_by_the_agent() {
    let w = World::start(uid());
    let forged = w.a.sign_as(A, &w.b.sig, "DELETE", "/v1/devices/self", b"", local::now_ms(), &[90; 16]);
    assert_eq!(code(&w.mutate(A, "DELETE", "/v1/devices/self", &forged, b"")), Some(ErrorCode::SigInvalid));
    let h = w.a.sign("DELETE", "/v1/devices/self", b"", local::now_ms(), &[91; 16]);
    assert_eq!(w.mutate(A, "DELETE", "/v1/devices/self", &h, b"").status, 200);
    assert!(!w.unpaired.exists(), "nothing relied on remoterd's own list");
    let body = World::mkdir_body("after-unpair");
    let h = w.a.sign("POST", "/v1/fs/mkdir", &body, local::now_ms(), &[92; 16]);
    assert_eq!(code(&w.mutate(A, "POST", "/v1/fs/mkdir", &h, &body)), Some(ErrorCode::DeviceUnknown));
    let hb = w.b.sign("POST", "/v1/fs/mkdir", &body, local::now_ms(), &[93; 16]);
    assert_eq!(w.mutate(B, "POST", "/v1/fs/mkdir", &hb, &body).status, 201, "only that device");
}

fn threads() -> usize {
    std::fs::read_dir("/proc/self/task").map(|d| d.count()).unwrap_or(0)
}

// one byte every 500ms used to keep a connection and its thread alive forever
#[test]
fn a_trickling_connection_is_cut_at_its_deadline() {
    let w = World::start(uid());
    let mut s = UnixStream::connect(&w.socket).expect("connect");
    let t0 = std::time::Instant::now();
    while t0.elapsed() < Duration::from_secs(7) {
        if s.write_all(b" ").is_err() {
            break;
        }
        std::thread::sleep(Duration::from_millis(500));
    }
    let _ = s.shutdown(std::net::Shutdown::Write);
    let mut out = Vec::new();
    let _ = s.read_to_end(&mut out);
    assert!(out.is_empty(), "{:?}", String::from_utf8_lossy(&out));
}

// Threads are counted for the whole process, so this runs in a process of its
// own: other tests here start threads of their own and used to tip it over.
#[test]
fn a_flood_of_idle_connections_is_capped() {
    let out = std::process::Command::new(std::env::current_exe().expect("exe"))
        .args(["--ignored", "--exact", "flood_in_its_own_process", "--nocapture", "--test-threads", "1"])
        .env("REMOTER_FLOOD_ALONE", "1")
        .output()
        .expect("rerun");
    let text = format!("{}\n{}", String::from_utf8_lossy(&out.stdout), String::from_utf8_lossy(&out.stderr));
    assert!(out.status.success() && text.contains("1 passed"), "{text}");
}

#[test]
#[ignore = "run by a_flood_of_idle_connections_is_capped"]
fn flood_in_its_own_process() {
    if std::env::var_os("REMOTER_FLOOD_ALONE").is_none() {
        return;
    }
    let w = World::start(uid());
    let before = threads();
    let flood: Vec<UnixStream> = (0..200).map(|_| UnixStream::connect(&w.socket).expect("connect")).collect();
    std::thread::sleep(Duration::from_millis(500));
    let during = threads();
    assert!(during <= before + remoter_agent::server::MAX_CONNECTIONS + 4, "{before} threads before, {during} during the flood");
    drop(flood);
    std::thread::sleep(Duration::from_millis(300));
    assert!(matches!(w.call(&AgentRequest::Status {}), AgentReply::Ok(_)), "still answering");
}
