//! The `e2e-test` marker never reaches an installed binary. install.sh runs
//! unmodified from a scratch copy with a stub `cargo`, and stops at its own
//! prompt so nothing reaches sudo.

use std::os::unix::fs::PermissionsExt;
use std::path::{Path, PathBuf};
use std::process::{Command, Stdio};

use remoter_e2e_host::{bins, contains};

const MARKER: &[u8] = b"remoter-e2e-test-build-marker";
const BINS: [&str; 4] = ["remoterd", "remoter-agent", "remoter-exec", "remoterctl"];

fn workspace() -> PathBuf {
    Path::new(env!("CARGO_MANIFEST_DIR")).parent().expect("workspace").to_path_buf()
}

fn release() -> PathBuf {
    let cargo = std::env::var("CARGO").unwrap_or_else(|_| "cargo".into());
    let mut cmd = Command::new(cargo);
    cmd.args(["build", "--release", "--locked"]).current_dir(workspace());
    for b in BINS {
        cmd.args(["-p", b]);
    }
    let out = cmd.output().expect("cargo");
    assert!(out.status.success(), "release build:\n{}", String::from_utf8_lossy(&out.stderr));
    workspace().join("target/release")
}

#[test]
fn release_build_has_no_marker() {
    let rel = release();
    for b in BINS {
        assert!(!contains(&rel.join(b), MARKER), "release {b} carries the e2e marker");
    }
    // control, or not finding it proves nothing
    for b in ["remoterd", "remoter-agent"] {
        assert!(contains(&bins().join(b), MARKER), "{b}: e2e build without marker");
    }
}

struct Scratch(PathBuf);

impl Drop for Scratch {
    fn drop(&mut self) {
        let _ = std::fs::remove_dir_all(&self.0);
    }
}

/// `marked` binaries get the e2e build instead of the release one.
fn scratch(tag: &str, marked: &[&str]) -> Scratch {
    let root = workspace().join("target/tmp").join(format!("install-{tag}-{}", std::process::id()));
    let _ = std::fs::remove_dir_all(&root);
    let laptop = root.join("infra/laptop");
    std::fs::create_dir_all(&laptop).expect("mk");
    for e in std::fs::read_dir(workspace().join("../infra/laptop")).expect("infra").flatten() {
        if e.file_type().is_ok_and(|t| t.is_file()) {
            std::fs::copy(e.path(), laptop.join(e.file_name())).expect("copy");
        }
    }
    let target = root.join("daemon/target/release");
    std::fs::create_dir_all(&target).expect("mk");
    let rel = release();
    for b in BINS {
        let from = if marked.contains(&b) { bins().join(b) } else { rel.join(b) };
        std::fs::hard_link(&from, target.join(b)).unwrap_or_else(|e| panic!("{}: {e}", from.display()));
    }
    let stub = root.join("stub");
    std::fs::create_dir_all(&stub).expect("mk");
    let cargo = stub.join("cargo");
    std::fs::write(&cargo, format!("#!/bin/sh\nprintf '%s\\n' \"$*\" >> {}/cargo.log\n", root.display())).expect("stub");
    std::fs::set_permissions(&cargo, std::fs::Permissions::from_mode(0o755)).expect("chmod");
    Scratch(root)
}

fn run_install(s: &Scratch) -> (bool, String, String) {
    let path = format!("{}:{}", s.0.join("stub").display(), std::env::var("PATH").unwrap_or_default());
    let out = Command::new("bash")
        .arg(s.0.join("infra/laptop/install.sh"))
        .args(["--app-cert-sha256", &"5a".repeat(32)])
        .env("PATH", path)
        .stdin(Stdio::piped())
        .stdout(Stdio::piped())
        .stderr(Stdio::piped())
        .spawn()
        .and_then(|mut c| {
            use std::io::Write;
            c.stdin.take().expect("stdin").write_all(b"n\n")?;
            c.wait_with_output()
        })
        .expect("install.sh");
    (out.status.success(), String::from_utf8_lossy(&out.stdout).into_owned(), String::from_utf8_lossy(&out.stderr).into_owned())
}

#[test]
fn install_refuses_a_marked_binary() {
    let s = scratch("marked", &["remoterd"]);
    let (ok, stdout, stderr) = run_install(&s);
    assert!(!ok);
    assert!(stderr.contains("remoterd carries the e2e test marker, refusing to install it"), "{stderr}");
    assert!(!stdout.contains("These run as root"), "{stdout}");
    let cargo = std::fs::read_to_string(s.0.join("cargo.log")).expect("cargo was called");
    assert!(cargo.contains("build --release --locked") && !cargo.contains("--features"), "{cargo}");
}

#[test]
fn install_accepts_the_release_build() {
    // control for the one above: clean binaries get to the prompt, "n" stops it
    let s = scratch("clean", &[]);
    let (ok, stdout, stderr) = run_install(&s);
    assert!(!ok, "a no still exits non-zero");
    assert!(!stderr.contains("marker"), "{stderr}");
    assert!(stdout.contains("These run as root") && stdout.contains("stopped, nothing was changed"), "{stdout}\n{stderr}");
}
