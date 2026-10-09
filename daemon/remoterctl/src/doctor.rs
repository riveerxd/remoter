//! `remoterctl doctor`: one line per check, and a fix for every one that fails.
//! Runs without sudo, so where a check needs root it says so rather than
//! guessing.

use std::os::unix::fs::MetadataExt;
use std::path::Path;
use std::process::{Command, Stdio};

use remoter_agent::config::OpenIn;
use remoter_agent::launcher::{Terminal, Wm, i3_assign_line, i3_places_windows};

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

fn tunnel_checks_direct(v: &mut Vec<Check>, dev: &str) {
    let port = out("nmcli", &["-g", "wireguard.listen-port", "connection", "show", "rmt0-direct"]).unwrap_or_default();
    let port = port.trim();
    v.push(check(
        "direct tunnel on a fixed port",
        port.parse::<u16>().is_ok_and(|p| p > 0),
        if port.is_empty() { "no rmt0-direct profile".into() } else { format!("UDP {port}") },
        "nmcli connection modify rmt0-direct wireguard.listen-port <the port the router forwards>",
    ));
    let route = out("ip", &["route", "get", "1.1.1.1"]).unwrap_or_default();
    let outer = outer_dev(&route);
    let physical = outer.is_some_and(|d| Path::new("/sys/class/net").join(d).join("device").exists());
    v.push(check(
        "the phone's replies leave through a network card",
        physical,
        format!("internet traffic goes out {}", outer.unwrap_or("nowhere")),
        "turn off the VPN that takes all traffic (wg0?), it carries the phone's replies away",
    ));
    // root only; without it there's nothing to judge, and the app shows whether the phone gets through
    if let Some(hs) = out("wg", &["show", dev, "latest-handshakes"]) {
        let now = std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).map(|d| d.as_secs()).unwrap_or(0);
        let fresh = handshake_fresh(&hs, now);
        v.push(check(
            "phone handshake in the last 3 minutes",
            fresh,
            if fresh { "yes" } else { "no recent handshake" },
            "turn the tunnel on in the phone's WireGuard app; if it's on, check the router forwards the UDP port here",
        ));
    }
}

/// The `dev` of an `ip route get` line.
pub fn outer_dev(route: &str) -> Option<&str> {
    let mut words = route.split_whitespace();
    words.by_ref().find(|w| *w == "dev")?;
    words.next()
}

/// `-D` (a server in the foreground) came in 3.2.
pub fn tmux_new_enough(version: &str) -> bool {
    let Some(v) = version.split_whitespace().nth(1) else {
        return false;
    };
    let v = v.strip_prefix("next-").unwrap_or(v);
    let mut parts = v.split('.');
    let major: u32 = parts.next().and_then(|m| m.parse().ok()).unwrap_or(0);
    let minor: u32 = parts.next().map(|m| m.trim_end_matches(|c: char| c.is_ascii_alphabetic())).and_then(|m| m.parse().ok()).unwrap_or(0);
    (major, minor) >= (3, 2)
}

pub fn handshake_fresh(wg_latest: &str, now: u64) -> bool {
    wg_latest.lines().filter_map(|l| l.split_whitespace().nth(1)?.parse::<u64>().ok()).any(|t| t > 0 && now.saturating_sub(t) < 180)
}

/// (name, autoconnect, active). NM races two autoconnecting profiles for rmt0 at boot.
pub fn autoconnect_clash(profiles: &[(String, bool, bool)]) -> Option<String> {
    if profiles.iter().filter(|p| p.1).count() < 2 {
        return None;
    }
    let idle = profiles.iter().find(|p| !p.2).unwrap_or(&profiles[1]);
    Some(format!("nmcli connection modify {} connection.autoconnect no", idle.0))
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
    let get = |k: &str| env.lines().find_map(|l| l.strip_prefix(&format!("{k}=")));
    let has = |k: &str| get(k).is_some();
    if i.agent.open_in == OpenIn::Tmux {
        let version = out(&i.agent.tmux_bin.to_string_lossy(), &["-V"]);
        v.push(check(
            "tmux 3.2 or newer installed",
            version.as_deref().is_some_and(tmux_new_enough),
            match &version {
                Some(said) => format!("{}, attach with `tmux -L {} attach`", said.trim(), i.agent.tmux_socket),
                None => format!("{} didn't run", i.agent.tmux_bin.display()),
            },
            "install tmux, or point tmux_bin in /etc/remoter/config.toml at it",
        ));
    } else {
        let (term_name, term_bin) = match i.agent.terminal() {
            Terminal::Kitty(b) => ("kitty", b),
            Terminal::Alacritty(b) => ("Alacritty", b),
        };
        v.push(check(
            &format!("{term_name} installed"),
            term_bin.exists(),
            term_bin.display().to_string(),
            "install kitty or Alacritty, or point kitty_bin or alacritty_bin in /etc/remoter/config.toml at it",
        ));
        match i.agent.wm().resolve(has) {
            Some(Wm::Hyprland { .. }) => v.push(check(
                "Hyprland reachable from the user manager",
                has("WAYLAND_DISPLAY") && has("HYPRLAND_INSTANCE_SIGNATURE"),
                format!("WAYLAND_DISPLAY {}, HYPRLAND_INSTANCE_SIGNATURE {}", has("WAYLAND_DISPLAY"), has("HYPRLAND_INSTANCE_SIGNATURE")),
                "log in to Hyprland, it exports both into the user manager",
            )),
            Some(Wm::I3 { i3msg_bin }) => {
                // asked the way the agent asks: with the user manager's DISPLAY, not this shell's
                let config = get("DISPLAY").and_then(|d| {
                    let o = Command::new(&i3msg_bin)
                        .args(["-t", "get_config"])
                        .env("DISPLAY", d)
                        .env_remove("WAYLAND_DISPLAY")
                        .env_remove("I3SOCK")
                        .stdin(Stdio::null())
                        .stderr(Stdio::null())
                        .output()
                        .ok()?;
                    o.status.success().then(|| String::from_utf8_lossy(&o.stdout).into_owned())
                });
                v.push(check(
                    "i3 reachable from the user manager",
                    config.is_some(),
                    format!("DISPLAY {}, i3 answered {}", has("DISPLAY"), config.is_some()),
                    "put `exec --no-startup-id systemctl --user import-environment DISPLAY XAUTHORITY` in the i3 config and log in again",
                ));
                let line = i3_assign_line(i.agent.workspace);
                let placed = config.as_deref().is_some_and(i3_places_windows);
                v.push(check(
                    &format!("i3 sends session windows to workspace {}", i.agent.workspace),
                    placed,
                    match (&config, placed) {
                        (None, _) => "i3 didn't answer",
                        (Some(_), true) => "the loaded config has a rule for remoter-rc",
                        (Some(_), false) => "no assign for remoter-rc in the loaded config",
                    },
                    &format!("add `{line}` to the i3 config, then `i3-msg reload`"),
                ));
            }
            _ => v.push(check(
                "a desktop the agent can open windows on",
                false,
                "the user manager has neither HYPRLAND_INSTANCE_SIGNATURE nor DISPLAY",
                "log in to Hyprland or i3; on i3, import DISPLAY into the user manager (see the README)",
            )),
        }
    }

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

    let tunnel = remoterd::route::tunnel(&i.remoterd.net.listen_device);
    if tunnel == Some(remoter_proto::api::Tunnel::Direct) {
        tunnel_checks_direct(&mut v, &i.remoterd.net.listen_device);
    } else {
        // `wg show` needs root, so the handshake is judged by whether the hub
        // answers through the tunnel right now.
        let ping = Command::new("ping").args(["-c", "1", "-W", "2", "-I", &i.remoterd.net.listen_device, "10.66.66.1"]).stdout(Stdio::null()).stderr(Stdio::null()).status().is_ok_and(|s| s.success());
        v.push(check("VPS answers over rmt0", ping, if ping { "yes" } else { "no answer in 2 s" }, "check the VPS and `sudo wg show rmt0 latest-handshakes`"));
    }
    let profiles: Vec<(String, bool, bool)> = ["rmt0", "rmt0-direct"]
        .iter()
        .filter_map(|n| {
            let f = out("nmcli", &["-g", "connection.autoconnect,GENERAL.STATE", "connection", "show", n])?;
            Some((n.to_string(), f.lines().next() == Some("yes"), f.contains("activated")))
        })
        .collect();
    if let Some(fix) = autoconnect_clash(&profiles) {
        v.push(check("only the tunnel in use starts on boot", false, "both rmt0 profiles autoconnect", &fix));
    }

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

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn outer_dev_from_ip_route_get() {
        assert_eq!(outer_dev("1.1.1.1 via 192.168.0.1 dev eno1 src 192.168.0.68 uid 1000\n    cache"), Some("eno1"));
        assert_eq!(outer_dev("1.1.1.1 dev wg0 table 51820 src 10.8.0.2 uid 1000"), Some("wg0"));
        assert_eq!(outer_dev(""), None);
        assert_eq!(outer_dev("RTNETLINK answers: Network is unreachable dev"), None);
    }

    #[test]
    fn tmux_versions() {
        for (said, ok) in [("tmux 3.8", true), ("tmux 3.2", true), ("tmux 3.3a", true), ("tmux next-3.6", true), ("tmux 3.1c", false), ("tmux 2.9", false), ("tmux", false), ("", false)] {
            assert_eq!(tmux_new_enough(said), ok, "{said}");
        }
    }

    #[test]
    fn handshakes() {
        let now = 1_791_500_000;
        assert!(handshake_fresh("PUBKEY=\t1791499950\n", now));
        assert!(!handshake_fresh("PUBKEY=\t1791499000\n", now));
        assert!(!handshake_fresh("PUBKEY=\t0\n", now), "never");
        assert!(!handshake_fresh("", now));
    }

    #[test]
    fn only_one_profile_may_autoconnect() {
        let p = |n: &str, auto: bool, active: bool| (n.to_string(), auto, active);
        assert_eq!(autoconnect_clash(&[p("rmt0", true, true)]), None);
        assert_eq!(autoconnect_clash(&[p("rmt0", true, true), p("rmt0-direct", false, false)]), None);
        assert_eq!(
            autoconnect_clash(&[p("rmt0", true, false), p("rmt0-direct", true, true)]).as_deref(),
            Some("nmcli connection modify rmt0 connection.autoconnect no")
        );
        assert_eq!(
            autoconnect_clash(&[p("rmt0", true, true), p("rmt0-direct", true, false)]).as_deref(),
            Some("nmcli connection modify rmt0-direct connection.autoconnect no")
        );
    }
}
