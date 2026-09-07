//! `remoterctl doctor`: one line per check, and a fix for every one that fails.
//! Runs without sudo, so where a check needs root it says so rather than
//! guessing.

use std::os::unix::fs::MetadataExt;
use std::path::Path;
use std::process::{Command, Stdio};

pub struct Check {
    pub name: String,
    pub ok: bool,
    pub detail: String,
    pub fix: String,
}

fn check(name: &str, ok: bool, detail: impl Into<String>, fix: &str) -> Check {
    Check { name: name.into(), ok, detail: detail.into(), fix: fix.into() }
}

fn out(cmd: &str, args: &[&str]) -> Option<String> {
    let o = Command::new(cmd).args(args).stdin(Stdio::null()).stderr(Stdio::null()).output().ok()?;
    o.status.success().then(|| String::from_utf8_lossy(&o.stdout).into_owned())
}

/// Owner, group and mode an installed file should have.
pub struct Expect<'a> {
    pub path: &'a Path,
    pub uid: u32,
    pub gid: Option<u32>,
    pub mode: u32,
}

pub fn file_check(e: &Expect<'_>) -> Check {
    let name = format!("{} owner and mode", e.path.display());
    match std::fs::symlink_metadata(e.path) {
        Err(err) => check(&name, false, err.to_string(), "run infra/laptop/install.sh"),
        Ok(m) => {
            let mode = m.mode() & 0o7777;
            let ok = m.uid() == e.uid && e.gid.is_none_or(|g| m.gid() == g) && mode == e.mode && !m.file_type().is_symlink();
            check(
                &name,
                ok,
                format!("uid {} gid {} mode {mode:o}", m.uid(), m.gid()),
                &format!("sudo chown {}{} {p} && sudo chmod {:o} {p}", e.uid, e.gid.map(|g| format!(":{g}")).unwrap_or_default(), e.mode, p = e.path.display()),
            )
        }
    }
}

pub struct Inputs<'a> {
    pub config: &'a Path,
    pub agent: &'a remoter_agent::config::AgentConfig,
    pub remoterd: &'a remoterd::config::Config,
    pub remoterd_uid: Option<u32>,
    pub remoterd_gid: Option<u32>,
    pub me: u32,
}

pub fn run(i: &Inputs<'_>) -> Vec<Check> {
    let mut v = Vec::new();

    let env = out("systemctl", &["--user", "show-environment"]).unwrap_or_default();
    let has = |k: &str| env.lines().any(|l| l.starts_with(&format!("{k}=")));
    v.push(check(
        "kitty and Hyprland reachable from the user manager",
        has("WAYLAND_DISPLAY") && has("HYPRLAND_INSTANCE_SIGNATURE") && i.agent.kitty_bin.exists(),
        format!("kitty {}, WAYLAND_DISPLAY {}, HYPRLAND_INSTANCE_SIGNATURE {}", i.agent.kitty_bin.exists(), has("WAYLAND_DISPLAY"), has("HYPRLAND_INSTANCE_SIGNATURE")),
        "log in to Hyprland (it exports both into the user manager) and install kitty",
    ));

    let target = std::fs::canonicalize(&i.agent.claude_bin);
    v.push(check(
        "claude_bin and its target exist",
        target.as_ref().is_ok_and(|t| t.is_file()),
        match &target {
            Ok(t) => t.display().to_string(),
            Err(e) => e.to_string(),
        },
        "fix claude_bin in /etc/remoter/config.toml; a self update moves the target",
    ));

    let addr = out("ip", &["-j", "addr", "show", "dev", &i.remoterd.net.listen_device]).unwrap_or_default();
    let want = i.remoterd.net.listen_addr.to_string();
    v.push(check(
        "rmt0 up with the right address",
        addr.contains(&format!("\"local\":\"{want}\"")) && (addr.contains("\"UP\"") || addr.contains("\"operstate\":\"UNKNOWN\"")),
        if addr.is_empty() { "no such device".into() } else { format!("wants {want}") },
        "nmcli connection up rmt0",
    ));

    // `wg show` needs root, so the handshake is judged by whether the hub
    // answers through the tunnel right now.
    let ping = Command::new("ping").args(["-c", "1", "-W", "2", "-I", &i.remoterd.net.listen_device, "10.66.66.1"]).stdout(Stdio::null()).stderr(Stdio::null()).status().is_ok_and(|s| s.success());
    v.push(check("VPS answers over rmt0", ping, if ping { "yes" } else { "no answer in 2 s" }, "check the VPS and `sudo wg show rmt0 latest-handshakes`"));

    for (unit, user) in [("remoterd.service", false), ("remoter-firewall.service", false), ("remoter-agent.service", true)] {
        let mut args = vec!["is-active", unit];
        if user {
            args.insert(0, "--user");
        }
        let state = out("systemctl", &args).unwrap_or_else(|| "inactive".into());
        let fix = if user { format!("systemctl --user enable --now {unit}") } else { format!("sudo systemctl enable --now {unit}") };
        v.push(check(&format!("{unit} running"), state.trim() == "active", state.trim(), &fix));
    }

    let root = 0;
    for bin in ["remoterd", "remoter-agent", "remoter-exec", "remoterctl"] {
        v.push(file_check(&Expect { path: &Path::new("/usr/local/bin").join(bin), uid: root, gid: Some(0), mode: 0o755 }));
    }
    v.push(file_check(&Expect { path: i.config, uid: root, gid: Some(0), mode: 0o644 }));
    v.push(file_check(&Expect { path: &i.remoterd.paths.devices, uid: root, gid: Some(0), mode: 0o644 }));
    v.push(file_check(&Expect { path: &i.remoterd.paths.server_key, uid: root, gid: i.remoterd_gid, mode: 0o640 }));
    if let Some(uid) = i.remoterd_uid {
        v.push(file_check(&Expect { path: &i.remoterd.paths.state_dir, uid, gid: None, mode: 0o711 }));
        v.push(file_check(&Expect { path: &i.agent.socket, uid: i.me, gid: i.remoterd_gid, mode: 0o660 }));
    }
    v.push(file_check(&Expect { path: Path::new("/run/remoter"), uid: i.me, gid: i.remoterd_gid, mode: 0o2750 }));

    // Listing nft tables needs root; as a user the firewall unit's state
    // stands in, and `sudo remoterctl doctor` checks the tables directly.
    let tables = out("nft", &["list", "tables"]);
    match tables {
        Some(t) => {
            v.push(check("remoter_host table loaded", t.contains("inet remoter_host"), "", "sudo systemctl restart remoter-firewall"));
            v.push(check("weak host protection (remoter_raw table)", t.contains("inet remoter_raw"), "", "sudo systemctl restart remoter-firewall"));
        }
        None => v.push(check("nft tables (needs sudo to list)", out("systemctl", &["is-active", "remoter-firewall.service"]).is_some_and(|s| s.trim() == "active"), "judged from remoter-firewall.service", "sudo remoterctl doctor")),
    }

    let trust = remoter_agent::trust::Trust::new(i.agent.home.join(".claude.json"));
    for r in &i.agent.trusted_roots {
        let p = i.agent.home.join(r);
        v.push(check(&format!("~/{r} trusted by claude"), trust.is_trusted(&p), "", &format!("cd {} && claude, then accept the trust dialog once", p.display())));
    }

    let settings = std::fs::read_to_string(i.agent.home.join(".claude/settings.json")).unwrap_or_default();
    v.push(check(
        "bypass permissions accepted once",
        settings.contains("\"skipDangerousModePermissionPrompt\": true") || settings.contains("\"skipDangerousModePermissionPrompt\":true"),
        "",
        "run claude --permission-mode bypassPermissions once and accept",
    ));

    let ntp = out("timedatectl", &["show", "-p", "NTPSynchronized", "--value"]).unwrap_or_default();
    v.push(check("clock synced", ntp.trim() == "yes", ntp.trim(), "sudo timedatectl set-ntp true"));
    v
}
