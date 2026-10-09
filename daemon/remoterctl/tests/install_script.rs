//! infra/laptop/install.sh without sudo: it must never enable the e2e
//! features, must refuse a binary that carries the marker, and must change
//! nothing when the answer is no.

use std::path::{Path, PathBuf};
use std::process::{Command, Stdio};

fn script() -> PathBuf {
    Path::new(env!("CARGO_MANIFEST_DIR")).join("../../infra/laptop/install.sh")
}

#[test]
fn no_features_and_marker_checked() {
    let s = std::fs::read_to_string(script()).expect("install.sh");
    assert!(!s.contains("--features"), "no feature flags in the installer");
    assert!(!s.replace("remoter-e2e-test-build-marker", "").contains("e2e-test"), "the e2e feature is never named, let alone enabled");
    assert!(s.contains("cargo build --release --locked"));
    assert!(s.contains("remoter-e2e-test-build-marker") && s.contains("grep -qa \"$marker\""), "installed binaries are checked for the marker");
    assert!(!s.contains("REMOTER_E2E"), "no test knobs in the environment either");
    let status = Command::new("bash").arg("-n").arg(script()).status().expect("bash");
    assert!(status.success(), "syntax");
}

#[test]
fn answering_no_changes_nothing() {
    let out = Command::new("bash")
        .arg(script())
        .args(["--app-cert-sha256", &"5a".repeat(32)])
        .stdin(Stdio::piped())
        .stdout(Stdio::piped())
        .stderr(Stdio::piped())
        .spawn()
        .and_then(|mut c| {
            use std::io::Write;
            c.stdin.take().expect("stdin").write_all(b"n\n")?;
            c.wait_with_output()
        })
        .expect("run");
    let text = String::from_utf8_lossy(&out.stdout);
    assert!(!out.status.success());
    assert!(text.contains("stopped, nothing was changed"), "{text}");
    for must in [
        "/usr/local/bin/",
        "/etc/sysusers.d/remoter.conf",
        "/etc/tmpfiles.d/remoter.conf",
        "remoterd.service",
        "remoter-firewall.service",
        "remoterctl init",
        "only if /etc/remoter/config.toml is missing",
        "only if /etc/remoter/devices.json is missing",
    ] {
        assert!(text.contains(must), "step {must:?} not shown:\n{text}");
    }
    assert!(!text.contains("+ sudo"), "nothing ran");
    // A stock Arch install has no /etc/sysusers.d; the first real install died there.
    let mkdir = text.find("install -d -o root -g root -m 0755 /etc/sysusers.d /etc/tmpfiles.d").expect("drop-in dirs are created");
    let first_use = text.find("/etc/sysusers.d/remoter.conf").expect("sysusers step");
    assert!(mkdir < first_use, "drop-in dirs created too late:\n{text}");
}

#[test]
fn refuses_a_placeholder_digest() {
    let out = Command::new("bash").arg(script()).args(["--app-cert-sha256", "<release signing cert digest>"]).stdin(Stdio::null()).output().expect("run");
    assert!(!out.status.success());
    assert!(String::from_utf8_lossy(&out.stderr).contains("64 hex digits"));
}

#[test]
fn no_root_step_is_a_shell_string() {
    // Every root step is an argv, never `sudo sh -c` with paths pasted into
    // a string, and no template is filled in with sed.
    let s = std::fs::read_to_string(script()).expect("install.sh");
    assert!(!s.contains("sh -c"), "a root step is built as a shell string");
    assert!(!s.contains("sed "), "a template is filled in by sed");
}

#[test]
fn refuses_an_unsafe_home() {
    for home in ["/tmp/a b", "/tmp/x\"; touch /tmp/pwn; #", "/tmp/$(id)", "relative/home", "/tmp/a|b"] {
        let out = Command::new("bash")
            .arg(script())
            .args(["--app-cert-sha256", &"5a".repeat(32)])
            .env("HOME", home)
            .stdin(Stdio::null())
            .output()
            .expect("run");
        let err = String::from_utf8_lossy(&out.stderr);
        assert!(!out.status.success(), "{home:?}");
        assert!(err.contains("home directory"), "{home:?} must be refused up front, got:\n{err}");
    }
}

fn plan(args: &[&str]) -> (bool, String, String) {
    let out = Command::new("bash")
        .arg(script())
        .args(args)
        .stdin(Stdio::piped())
        .stdout(Stdio::piped())
        .stderr(Stdio::piped())
        .spawn()
        .and_then(|mut c| {
            use std::io::Write;
            c.stdin.take().expect("stdin").write_all(b"n\n")?;
            c.wait_with_output()
        })
        .expect("run");
    (out.status.success(), String::from_utf8_lossy(&out.stdout).into_owned(), String::from_utf8_lossy(&out.stderr).into_owned())
}

#[test]
fn staging_needs_flag_and_own_cert() {
    let release = "5a".repeat(32);
    let e2e = "e2".repeat(32);
    let (_, plain, _) = plan(&["--app-cert-sha256", &release]);
    assert!(!plain.contains("staging"), "off unless asked for:\n{plain}");
    let (_, with, _) = plan(&["--app-cert-sha256", &release, "--with-staging", "--e2e-cert-sha256", &e2e]);
    for must in ["/etc/remoter/staging/config.toml", "remoterd-staging.service", "nftables-staging.conf", "remoter-agent-staging.service", "--config /etc/remoter/staging/config.toml init"] {
        assert!(with.contains(must), "{must} missing:\n{with}");
    }
    let (ok, _, err) = plan(&["--app-cert-sha256", &release, "--with-staging"]);
    assert!(!ok && err.contains("--e2e-cert-sha256"), "{err}");
    let (ok, _, err) = plan(&["--app-cert-sha256", &release, "--with-staging", "--e2e-cert-sha256", &release]);
    assert!(!ok && err.contains("must differ"), "{err}");
    let (ok, _, err) = plan(&["--app-cert-sha256", &release, "--e2e-cert-sha256", &e2e]);
    assert!(!ok && err.contains("only goes with --with-staging"), "{err}");
}

/// install.sh's `render`, for the tests.
fn render(file: &str) -> String {
    render_for(file, "auto", "auto", "window")
}

fn render_for(file: &str, desktop: &str, terminal: &str, open_in: &str) -> String {
    let t = std::fs::read_to_string(Path::new(env!("CARGO_MANIFEST_DIR")).join("../../infra/laptop").join(file)).expect("template");
    t.replace("@DESKTOP@", desktop)
        .replace("@TERMINAL@", terminal)
        .replace("@OPEN_IN@", open_in)
        .replace("@HOME@", "/home/river")
        .replace("@USER@", "river")
        .replace("@UID@", "1000")
        .replace("@APP_CERT_SHA256@", &"5a".repeat(32))
        .replace("@E2E_CERT_SHA256@", &"e2".repeat(32))
}

#[test]
fn staging_shares_nothing() {
    let (p, s) = (render("config.toml.tmpl"), render("config-staging.toml.tmpl"));
    assert!(!p.contains('@') && !s.contains('@'), "every placeholder is filled");
    let (pd, sd) = (remoterd::config::Config::parse(&p).expect("production parses"), remoterd::config::Config::parse(&s).expect("staging parses"));
    let (pa, sa) = (remoter_agent::config::AgentConfig::parse(&p).expect("agent"), remoter_agent::config::AgentConfig::parse(&s).expect("agent"));
    assert_eq!((pd.net.port, pd.net.pair_port), (8443, 8444));
    assert_eq!((sd.net.port, sd.net.pair_port), (9443, 9444));
    assert_eq!(pd.attestation.app_package, "me.river.remoter");
    assert_eq!(sd.attestation.app_package, "me.river.remoter.e2e");
    assert_ne!(pd.attestation.app_cert_sha256, sd.attestation.app_cert_sha256);
    for (what, a, b) in [
        ("devices", &pd.paths.devices, &sd.paths.devices),
        ("server key", &pd.paths.server_key, &sd.paths.server_key),
        ("state", &pd.paths.state_dir, &sd.paths.state_dir),
        ("admin socket", &pd.paths.admin_socket, &sd.paths.admin_socket),
        ("agent socket", &pd.agent_socket, &sd.agent_socket),
        ("agent devices", &pa.devices, &sa.devices),
        ("agent lock dir", &pa.lock_dir, &sa.lock_dir),
    ] {
        assert_ne!(a, b, "{what} is shared");
    }
    assert_eq!(pa.devices, pd.paths.devices, "each agent reads its own remoterd's device list");
    assert_eq!(sa.devices, sd.paths.devices);
    assert_eq!(sa.lock_dir, sd.paths.state_dir, "and its own remoterd's lock");
    assert_ne!(pa.agent_state(), sa.agent_state());
    assert_ne!(pa.runtime_dir, sa.runtime_dir);
    assert_eq!(sd.agent_socket, sa.socket);
    assert_ne!(pa.tmux_socket, sa.tmux_socket, "tmux server is shared");
}

#[test]
fn desktop_and_terminal_choices() {
    use remoter_agent::config::{AgentConfig, Desktop, TerminalChoice};
    let release = "5a".repeat(32);
    for (flag, bad, why) in [("--desktop", "sway", "auto, hyprland or i3"), ("--terminal", "xterm", "auto, kitty or alacritty")] {
        let (ok, _, err) = plan(&["--app-cert-sha256", &release, flag, bad]);
        assert!(!ok && err.contains(why), "{err}");
    }
    let (_, out, err) = plan(&["--app-cert-sha256", &release, "--desktop", "i3", "--terminal", "alacritty"]);
    assert!(out.contains("These run as root"), "{out}\n{err}");
    for file in ["config.toml.tmpl", "config-staging.toml.tmpl"] {
        let auto = AgentConfig::parse(&render(file)).expect("parses");
        assert_eq!((auto.desktop, auto.terminal), (Desktop::Auto, TerminalChoice::Auto));
        let set = AgentConfig::parse(&render_for(file, "i3", "alacritty", "window")).expect("parses");
        assert_eq!((set.desktop, set.terminal), (Desktop::I3, TerminalChoice::Alacritty));
    }
}

#[test]
fn open_in_choice() {
    use remoter_agent::config::{AgentConfig, OpenIn};
    let release = "5a".repeat(32);
    let (ok, _, err) = plan(&["--app-cert-sha256", &release, "--open-in", "screen"]);
    assert!(!ok && err.contains("window or tmux"), "{err}");
    for file in ["config.toml.tmpl", "config-staging.toml.tmpl"] {
        assert_eq!(AgentConfig::parse(&render(file)).expect("parses").open_in, OpenIn::Window);
        assert_eq!(AgentConfig::parse(&render_for(file, "auto", "auto", "tmux")).expect("parses").open_in, OpenIn::Tmux);
    }
}
