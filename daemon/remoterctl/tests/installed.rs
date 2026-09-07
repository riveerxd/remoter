//! After install.sh your uid can't write any trust file or read the server key
//! or the audit log, and the installed binaries carry no e2e marker. Run it as
//! yourself, after installing:
//!
//!   cargo test -p remoterctl --test installed -- --ignored

use std::fs::OpenOptions;
use std::path::Path;

const TRUST_FILES: &[&str] = &[
    "/usr/local/bin/remoterd",
    "/usr/local/bin/remoter-agent",
    "/usr/local/bin/remoter-exec",
    "/usr/local/bin/remoterctl",
    "/etc/remoter/config.toml",
    "/etc/remoter/devices.json",
    "/etc/remoter/server.key",
    "/etc/remoter/server.crt",
    "/etc/remoter/nftables.conf",
    "/etc/systemd/system/remoterd.service",
    "/etc/systemd/system/remoter-firewall.service",
];

fn writable(p: &Path) -> bool {
    OpenOptions::new().append(true).open(p).is_ok()
}

#[test]
#[ignore = "run after install.sh, as your own user"]
fn your_uid_cannot_write_any_trust_file() {
    // SAFETY: getuid has no preconditions.
    assert_ne!(unsafe { libc::getuid() }, 0, "run this as yourself, not root");
    for f in TRUST_FILES {
        let p = Path::new(f);
        assert!(p.exists(), "{f} missing: was install.sh run?");
        assert!(!writable(p), "{f} is writable by you");
    }
    for dir in ["/etc/remoter", "/usr/local/bin", "/var/lib/remoterd"] {
        let probe = Path::new(dir).join(".remoter-write-probe");
        assert!(std::fs::write(&probe, b"x").is_err(), "you can create files in {dir}");
    }
    assert!(std::fs::read("/etc/remoter/server.key").is_err(), "you can read the server key");
    assert!(std::fs::read("/var/lib/remoterd/audit.jsonl").is_err(), "you can read the audit log");
    assert!(std::fs::write("/var/lib/remoterd/locked", b"").is_err(), "you can set the lock flag");
    assert!(std::os::unix::net::UnixStream::connect("/run/remoterd/admin.sock").is_err(), "you can reach the admin socket");
}

#[test]
#[ignore = "run after install.sh"]
fn installed_binaries_carry_no_e2e_marker() {
    let marker = b"remoter-e2e-test-build-marker";
    for b in ["remoterd", "remoter-agent", "remoter-exec", "remoterctl"] {
        let bytes = std::fs::read(Path::new("/usr/local/bin").join(b)).expect("installed binary");
        assert!(!bytes.windows(marker.len()).any(|w| w == marker), "{b}");
    }
}

#[test]
#[ignore = "run after install.sh"]
fn remoterd_exposure_is_three_or_lower() {
    let out = std::process::Command::new("systemd-analyze").args(["security", "remoterd.service", "--no-pager"]).output().expect("systemd-analyze");
    let text = String::from_utf8_lossy(&out.stdout);
    let line = text.lines().find(|l| l.contains("Overall exposure level")).unwrap_or_else(|| panic!("{text}"));
    let score: f64 = line.split(':').nth(1).and_then(|r| r.split_whitespace().next()).and_then(|n| n.parse().ok()).expect("score");
    println!("{line}");
    assert!(score <= 3.0, "{line}");
}
