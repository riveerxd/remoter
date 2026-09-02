//! The `e2e-test` knobs must never reach a release build. The e2e build is the
//! control: if it lacks the marker too, the search proves nothing.

use std::path::{Path, PathBuf};
use std::process::Command;

const MARKER: &[u8] = b"remoter-e2e-test-build-marker";

fn workspace() -> PathBuf {
    Path::new(env!("CARGO_MANIFEST_DIR")).parent().expect("workspace").to_path_buf()
}

fn cargo(args: &[&str]) {
    let cargo = std::env::var("CARGO").unwrap_or_else(|_| "cargo".into());
    let out = Command::new(cargo).args(args).current_dir(workspace()).output().expect("cargo");
    assert!(out.status.success(), "cargo {args:?} failed:\n{}", String::from_utf8_lossy(&out.stderr));
}

fn contains(file: &Path, needle: &[u8]) -> bool {
    let bytes = std::fs::read(file).unwrap_or_else(|e| panic!("{}: {e}", file.display()));
    bytes.windows(needle.len()).any(|w| w == needle)
}

#[test]
fn release_binaries_have_no_test_knobs() {
    cargo(&["build", "--release", "--locked", "-p", "remoterd", "-p", "remoter-agent", "-p", "remoter-exec"]);
    let release = workspace().join("target/release");
    for bin in ["remoterd", "remoter-agent", "remoter-exec"] {
        assert!(!contains(&release.join(bin), MARKER), "{bin} release build carries the e2e marker");
    }

    // separate target dir so this feature set doesn't churn the main build
    let e2e_dir = workspace().join("target/e2e-marker");
    let dir = e2e_dir.to_string_lossy().into_owned();
    cargo(&["build", "--locked", "--target-dir", &dir, "-p", "remoterd", "-p", "remoter-agent", "--features", "remoterd/e2e-test,remoter-agent/e2e-test"]);
    for bin in ["remoterd", "remoter-agent"] {
        assert!(contains(&e2e_dir.join("debug").join(bin), MARKER), "control: e2e {bin} lacks the marker");
    }
}
