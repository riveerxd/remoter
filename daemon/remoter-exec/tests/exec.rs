//! Spec checks, the folder check, and how claude is run and held. A shell
//! script stands in for claude, the real one never starts here.

use std::io::Read;
use std::os::unix::fs::{DirBuilderExt, OpenOptionsExt, PermissionsExt, symlink};
use std::os::unix::fs::MetadataExt;
use std::path::{Path, PathBuf};
use std::process::{Child, Command, Stdio};
use std::sync::atomic::{AtomicUsize, Ordering};
use std::time::{Duration, Instant};

use remoter_proto::api::StuckReason;
use remoter_proto::local::{ExecState, HANDOFF_LEAD, HandoffSpec, Spec, Status};

const EXEC: &str = env!("CARGO_BIN_EXE_remoter-exec");
const ID: &str = "rc-01k6b7y3m4n5p6q7r8s9t0v1w2";

struct Case {
    root: PathBuf,
    dir: PathBuf,
    folder: PathBuf,
    spec: Spec,
}

impl Case {
    fn new(fake_body: &str) -> Case {
        static N: AtomicUsize = AtomicUsize::new(0);
        let root = PathBuf::from(env!("CARGO_TARGET_TMPDIR"))
            .join(format!("exec-{}-{}", std::process::id(), N.fetch_add(1, Ordering::Relaxed)));
        let _ = std::fs::remove_dir_all(&root);
        std::fs::create_dir_all(&root).expect("root");
        let dir = root.join(ID);
        std::fs::DirBuilder::new().mode(0o700).create(&dir).expect("dir");
        let folder = root.join("my folder");
        std::fs::create_dir(&folder).expect("folder");
        let fake = root.join("fake-claude");
        std::fs::write(&fake, format!("#!/bin/sh\n{fake_body}\n")).expect("fake");
        std::fs::set_permissions(&fake, std::fs::Permissions::from_mode(0o755)).expect("chmod");
        let m = std::fs::metadata(&folder).expect("stat");
        let spec = Spec {
            id: ID.into(),
            dir: dir.display().to_string(),
            cwd: folder.display().to_string(),
            dev: m.dev(),
            ino: m.ino(),
            rel: "my folder".into(),
            name: "my proj".into(),
            device: None,
            started: 0,
            argv: vec![fake.display().to_string(), "remote-control".into(), "--name=my proj".into(), "$HOME;x".into()],
            handoff: None,
        };
        Case { root, dir, folder, spec }
    }

    fn write_spec(&self) -> PathBuf {
        let p = self.dir.join("spec.json");
        let _ = std::fs::remove_file(&p);
        let mut f = std::fs::OpenOptions::new().write(true).create_new(true).mode(0o600).open(&p).expect("spec");
        std::io::Write::write_all(&mut f, &serde_json::to_vec(&self.spec).expect("json")).expect("write");
        p
    }

    fn run(&self, spec_arg: &Path) -> Held {
        Held(Command::new(EXEC)
            .arg("--spec")
            .arg(spec_arg)
            .stdin(Stdio::null())
            .stdout(Stdio::piped())
            .stderr(Stdio::piped())
            .spawn()
            .expect("exec"))
    }

    fn status(&self) -> Option<Status> {
        serde_json::from_slice(&std::fs::read(self.dir.join("status.json")).ok()?).ok()
    }

    fn wait_status(&self, want: ExecState) -> Status {
        let deadline = Instant::now() + Duration::from_secs(10);
        loop {
            if let Some(s) = self.status().filter(|s| s.state == want) {
                return s;
            }
            assert!(Instant::now() < deadline, "no {want:?} status, have {:?}", self.status());
            std::thread::sleep(Duration::from_millis(20));
        }
    }

    fn ran(&self) -> bool {
        self.root.join("ran").exists()
    }
}

impl Drop for Case {
    fn drop(&mut self) {
        let _ = std::fs::remove_dir_all(&self.root);
    }
}

/// A running remoter-exec that is killed if the test fails first, so a
/// failing test can't leave a held window process behind.
struct Held(Child);

impl Drop for Held {
    fn drop(&mut self) {
        let _ = self.0.kill();
        let _ = self.0.wait();
    }
}

fn term_and_collect(mut held: Held) -> (String, String) {
    let c = &mut held.0;
    // SAFETY: plain kill on a child we own.
    unsafe { libc::kill(c.id() as i32, libc::SIGTERM) };
    let st = c.wait().expect("wait");
    assert_eq!(std::os::unix::process::ExitStatusExt::signal(&st), Some(libc::SIGTERM), "held until SIGTERM");
    let (mut out, mut err) = (String::new(), String::new());
    c.stdout.take().expect("stdout").read_to_string(&mut out).expect("read");
    c.stderr.take().expect("stderr").read_to_string(&mut err).expect("read");
    (out, err)
}

fn still_running(c: &mut Held) -> bool {
    c.0.try_wait().expect("try_wait").is_none()
}

const DUMP: &str = r#"touch "$(dirname "$0")/ran"
{ pwd; echo "REMOTER_ID=$REMOTER_ID"; for a in "$@"; do echo "[$a]"; done; } > "$(dirname "$0")/argv"
exit 3"#;

#[test]
fn runs_claude_in_folder_and_holds() {
    let c = Case::new(DUMP);
    let mut child = c.run(&c.write_spec());
    let st = c.wait_status(ExecState::Exited);
    assert_eq!(st.exit_code, Some(3));
    std::thread::sleep(Duration::from_millis(300));
    assert!(still_running(&mut child), "the window is held after claude exits");
    let (out, _) = term_and_collect(child);
    assert!(out.starts_with("\u{1b}]2;my proj\u{7}"), "title escape first: {out:?}");
    assert!(out.contains("claude exited with code 3"), "{out:?}");
    let argv = std::fs::read_to_string(c.root.join("argv")).expect("argv");
    let debug = c.dir.join("debug.log");
    assert_eq!(
        argv,
        format!(
            "{}\nREMOTER_ID={ID}\n[remote-control]\n[--name=my proj]\n[$HOME;x]\n[--debug-file]\n[{}]\n",
            c.folder.display(),
            debug.display()
        ),
        "argv reaches claude literally, cwd is the folder"
    );
}

impl Case {
    /// A start with a handoff: `summarizer` is a shell script body, and the condensed
    /// conversation sits where the agent puts it.
    fn with_handoff(mut self, summarizer: &str) -> Case {
        let s = self.root.join("summarizer");
        std::fs::write(&s, format!("#!/bin/sh\n{summarizer}\n")).expect("summarizer");
        std::fs::set_permissions(&s, std::fs::Permissions::from_mode(0o755)).expect("chmod");
        std::fs::write(self.dir.join("handoff-source.txt"), "[me] build the thing").expect("source");
        self.spec.handoff = Some(HandoffSpec {
            from: "c82d8b5c-edd4-453e-8d59-4748ff325c03".into(),
            transcript: "/h/.claude/projects/-h-p/c82d8b5c-edd4-453e-8d59-4748ff325c03.jsonl".into(),
            summarizer: vec![s.display().to_string(), "-p".into()],
            source: self.dir.join("handoff-source.txt").display().to_string(),
        });
        self
    }
}

#[test]
fn handoff_becomes_first_message() {
    let c = Case::new(DUMP).with_handoff("touch \"$(dirname \"$0\")/summarized\"\necho \"## Goal\"\nsed 's/^/read: /'");
    let child = c.run(&c.write_spec());
    let st = c.wait_status(ExecState::Exited);
    assert_eq!(st.exit_code, Some(3));
    let handoff = "## Goal\nread: [me] build the thing";
    assert_eq!(std::fs::read_to_string(c.dir.join("handoff.md")).expect("kept"), handoff);
    let argv = std::fs::read_to_string(c.root.join("argv")).expect("argv");
    let note = remoter_proto::local::handoff_source_note("c82d8b5c-edd4-453e-8d59-4748ff325c03", "/h/.claude/projects/-h-p/c82d8b5c-edd4-453e-8d59-4748ff325c03.jsonl");
    assert!(note.contains("c82d8b5c-edd4-453e-8d59-4748ff325c03") && note.contains(".jsonl"));
    assert!(argv.contains(&format!("[$HOME;x]\n[{HANDOFF_LEAD}\n\n{handoff}\n\n{note}]\n[--debug-file]")), "handoff and note go last: {argv}");
    let (out, _) = term_and_collect(child);
    assert!(out.contains("Writing a handoff") && out.contains("read: [me] build the thing"), "shown in the window: {out:?}");
}

#[test]
fn failed_handoff_is_stuck() {
    for (body, why) in [
        ("echo boom >&2\nexit 1", "boom"),
        ("cat >/dev/null", "exited"),
        ("exec sleep 1 </dev/null && true; exit 2", "exited"),
    ] {
        let c = Case::new(DUMP).with_handoff(body);
        let child = c.run(&c.write_spec());
        let st = c.wait_status(ExecState::Stuck);
        assert_eq!(st.reason, Some(StuckReason::HandoffFailed), "{body}");
        assert!(!c.ran(), "claude must not start without its handoff: {body}");
        let (out, _) = term_and_collect(child);
        assert!(out.contains("Couldn't write the handoff") && out.contains(why), "{body}: {out:?}");
    }
}

#[test]
fn a_handoff_source_from_elsewhere_is_refused() {
    let mut c = Case::new(DUMP).with_handoff("touch \"$(dirname \"$0\")/summarized\"\necho hi");
    if let Some(h) = c.spec.handoff.as_mut() {
        h.source = c.root.join("elsewhere.txt").display().to_string();
    }
    let child = c.run(&c.write_spec());
    assert_eq!(c.wait_status(ExecState::Stuck).reason, Some(StuckReason::HandoffFailed));
    assert!(!c.root.join("summarized").exists() && !c.ran());
    drop(child);
}

#[test]
fn records_pid_and_ignores_sigint() {
    let c = Case::new("exec sleep 30");
    let mut child = c.run(&c.write_spec());
    let st = c.wait_status(ExecState::Running);
    let pid = st.pid.expect("pid");
    // SAFETY: plain kill on processes this test started.
    unsafe { libc::kill(child.0.id() as i32, libc::SIGINT) };
    std::thread::sleep(Duration::from_millis(300));
    assert!(still_running(&mut child), "remoter-exec survives Ctrl-C");
    assert_eq!(c.status().map(|s| s.state), Some(ExecState::Running), "claude isn't touched by it");
    // SAFETY: as above.
    unsafe { libc::kill(pid, libc::SIGINT) };
    let st = c.wait_status(ExecState::Exited);
    assert_eq!(st.exit_code, Some(128 + libc::SIGINT), "claude got SIGINT with the default action back");
    term_and_collect(child);
}

#[test]
fn swapped_folder_is_stuck() {
    let c = Case::new(DUMP);
    let spec = c.write_spec();
    std::fs::rename(&c.folder, c.root.join("moved")).expect("mv");
    std::fs::create_dir(&c.folder).expect("new folder at the same path");
    let mut child = c.run(&spec);
    let st = c.wait_status(ExecState::Stuck);
    assert_eq!(st.reason, Some(StuckReason::FolderChanged));
    std::thread::sleep(Duration::from_millis(200));
    assert!(still_running(&mut child));
    let (out, _) = term_and_collect(child);
    assert!(out.contains("Folder changed while starting"), "{out:?}");
    assert!(!c.ran(), "claude must never start in the wrong folder");
}

#[test]
fn folder_replaced_by_a_symlink_is_stuck() {
    let c = Case::new(DUMP);
    let spec = c.write_spec();
    std::fs::rename(&c.folder, c.root.join("moved")).expect("mv");
    symlink(c.root.join("moved"), &c.folder).expect("ln");
    // The symlink lands on the same inode, which is the folder the agent
    // checked, so this one is allowed to run.
    let child = c.run(&spec);
    assert_eq!(c.wait_status(ExecState::Exited).exit_code, Some(3));
    term_and_collect(child);
    let c = Case::new(DUMP);
    let spec = c.write_spec();
    std::fs::rename(&c.folder, c.root.join("moved")).expect("mv");
    std::fs::create_dir(c.root.join("other")).expect("other");
    symlink(c.root.join("other"), &c.folder).expect("ln");
    let child = c.run(&spec);
    assert_eq!(c.wait_status(ExecState::Stuck).reason, Some(StuckReason::FolderChanged));
    term_and_collect(child);
    assert!(!c.ran());
}

#[test]
fn folder_gone_is_stuck() {
    let c = Case::new(DUMP);
    let spec = c.write_spec();
    std::fs::remove_dir(&c.folder).expect("rm");
    let child = c.run(&spec);
    assert_eq!(c.wait_status(ExecState::Stuck).reason, Some(StuckReason::FolderChanged));
    term_and_collect(child);
    assert!(!c.ran());
}

fn assert_refused(c: &Case, args: &[&std::ffi::OsStr], why: &str) {
    let mut held = Held(
        Command::new(EXEC).args(args).stdin(Stdio::null()).stdout(Stdio::null()).stderr(Stdio::piped()).spawn().expect("exec"),
    );
    // A spec that wrongly passes gets held open forever, so this waits with
    // a deadline and fails rather than hanging the suite.
    let deadline = Instant::now() + Duration::from_secs(5);
    let status = loop {
        if let Some(st) = held.0.try_wait().expect("try_wait") {
            break st;
        }
        assert!(Instant::now() < deadline, "{why}: not refused, remoter-exec kept running");
        std::thread::sleep(Duration::from_millis(20));
    };
    let mut err = String::new();
    held.0.stderr.take().expect("stderr").read_to_string(&mut err).expect("read");
    assert_eq!(status.code(), Some(2), "{why}: must exit 2, got {status:?}");
    assert!(err.contains("refused"), "{why}");
    assert!(!c.ran(), "{why}: claude must not run");
    assert!(c.status().is_none(), "{why}: wrote into an untrusted dir");
}

fn refused_with(mutate: impl FnOnce(&mut Case) -> PathBuf, why: &str) {
    let mut c = Case::new(DUMP);
    let spec = mutate(&mut c);
    assert_refused(&c, &["--spec".as_ref(), spec.as_os_str()], why);
}

#[test]
fn refuses_every_untrusted_spec() {
    refused_with(
        |c| {
            let p = c.write_spec();
            std::fs::set_permissions(&c.dir, std::fs::Permissions::from_mode(0o755)).expect("chmod");
            p
        },
        "dir 0755",
    );
    refused_with(
        |c| {
            let p = c.write_spec();
            std::fs::set_permissions(&p, std::fs::Permissions::from_mode(0o644)).expect("chmod");
            p
        },
        "spec 0644",
    );
    refused_with(
        |c| {
            let real = c.write_spec();
            let other = c.dir.join("other.json");
            std::fs::rename(&real, &other).expect("mv");
            symlink(&other, &real).expect("ln");
            real
        },
        "spec is a symlink",
    );
    refused_with(
        |c| {
            let p = c.write_spec();
            std::fs::hard_link(&p, c.root.join("second-name")).expect("ln");
            p
        },
        "spec has a second hard link outside the dir",
    );
    refused_with(
        |c| {
            let link = c.root.join("rc-01k6b7y3m4n5p6q7r8s9t0v1w9");
            symlink(&c.dir, &link).expect("ln");
            c.write_spec();
            link.join("spec.json")
        },
        "dir reached through a symlink",
    );
    refused_with(
        |c| {
            c.spec.id = "rc-01k6b7y3m4n5p6q7r8s9t0v1w3".into();
            c.write_spec()
        },
        "id doesn't match the dir",
    );
    refused_with(
        |c| {
            c.spec.dir = c.root.display().to_string();
            c.write_spec()
        },
        "spec names another dir",
    );
    refused_with(
        |c| {
            c.spec.name = "evil\u{1b}]2;x\u{7}".into();
            c.write_spec()
        },
        "name with an escape",
    );
    refused_with(
        |c| {
            c.spec.argv[0] = "fake-claude".into();
            c.write_spec()
        },
        "relative argv[0]",
    );
    refused_with(
        |c| {
            c.spec.argv.clear();
            c.write_spec()
        },
        "empty argv",
    );
    refused_with(
        |c| {
            let p = c.dir.join("spec.json");
            let mut f = std::fs::OpenOptions::new().write(true).create_new(true).mode(0o600).open(&p).expect("spec");
            std::io::Write::write_all(&mut f, br#"{"id":"x","shell":"sh"}"#).expect("write");
            p
        },
        "unknown fields",
    );
    refused_with(
        |c| {
            let p = c.dir.join("spec.json");
            let mut f = std::fs::OpenOptions::new().write(true).create_new(true).mode(0o600).open(&p).expect("spec");
            std::io::Write::write_all(&mut f, &vec![b' '; 70 * 1024]).expect("write");
            p
        },
        "oversized spec",
    );
}

#[test]
fn refuses_bad_command_lines() {
    let c = Case::new(DUMP);
    let spec = c.write_spec();
    let rel = PathBuf::from(ID).join("spec.json");
    assert_refused(&c, &[], "no args");
    assert_refused(&c, &["--spec".as_ref()], "missing value");
    assert_refused(&c, &["--spec".as_ref(), spec.as_os_str(), "--extra".as_ref()], "extra arg");
    assert_refused(&c, &["--spec".as_ref(), rel.as_os_str()], "relative spec path");
    assert_refused(&c, &["--other".as_ref(), spec.as_os_str()], "wrong flag");
}

/// kitty moves its child into a scope of its own some time after starting it,
/// so claude can end up in either scope. Whatever the cgroups say, closing
/// the window (SIGHUP) or ending the session (SIGTERM) must take claude with it.
#[test]
fn ending_remoter_exec_takes_claude_with_it() {
    for sig in [libc::SIGTERM, libc::SIGHUP] {
        let c = Case::new("trap '' INT HUP TERM\nwhile :; do sleep 1; done");
        let mut held = c.run(&c.write_spec());
        let pid = c.wait_status(ExecState::Running).pid.expect("pid");
        // SAFETY: plain kill on a child this test started.
        unsafe { libc::kill(held.0.id() as i32, sig) };
        let _ = held.0.wait();
        let deadline = Instant::now() + Duration::from_secs(3);
        while Path::new(&format!("/proc/{pid}/stat")).exists() && !is_zombie(pid) {
            if Instant::now() > deadline {
                // SAFETY: cleaning up the survivor so it can't outlive the test.
                unsafe { libc::kill(pid, libc::SIGKILL) };
                panic!("claude ignored everything and outlived remoter-exec after signal {sig}");
            }
            std::thread::sleep(Duration::from_millis(50));
        }
    }
}

fn is_zombie(pid: i32) -> bool {
    std::fs::read_to_string(format!("/proc/{pid}/stat"))
        .ok()
        .and_then(|s| s.rsplit_once(')').map(|(_, rest)| rest.trim_start().starts_with('Z')))
        .unwrap_or(false)
}
