//! Opens a session's window: kitty or Alacritty, on Hyprland or i3. Headless only with `e2e-test`.

use std::path::Path;
use std::process::{Command, Stdio};

use remoter_proto::ErrorCode;
use remoter_proto::local::{KITTY_SOCKET, SCREEN_FILE, SPEC_FILE};

use crate::AgentError;

pub trait Launcher: Send + Sync {
    /// Under the spawn lock. `desktop_down` when there's nowhere to open a window.
    fn prepare(&self) -> Result<(), AgentError>;

    /// `remoter-exec --spec <dir>/spec.json` in a user scope named after the session.
    fn launch(&self, id: &str, dir: &Path) -> Result<(), AgentError>;

    fn screen(&self, dir: &Path) -> Option<String>;

    /// remoter-exec keeps the screen in `SCREEN_FILE` because the terminal can't be asked for it.
    fn renders_screen(&self) -> bool {
        false
    }
}

/// kitty expands `$VAR` in its child's argv, so paths stick to a boring
/// charset (and systemd-run gets `--expand-environment=no`).
pub fn is_plain_path(p: &Path) -> bool {
    p.is_absolute()
        && p.as_os_str()
            .as_encoded_bytes()
            .iter()
            .all(|b| b.is_ascii_alphanumeric() || matches!(b, b'/' | b'.' | b'_' | b'-'))
}

fn scope_prefix(id: &str) -> Vec<String> {
    ["--user", "--scope", "--quiet", "--collect", "--expand-environment=no"]
        .into_iter()
        .map(String::from)
        .chain([format!("--unit={id}"), "--".into()])
        .collect()
}

fn spawn_detached(mut cmd: Command) -> Result<(), AgentError> {
    let mut child = cmd
        .stdin(Stdio::null())
        .stdout(Stdio::null())
        .stderr(Stdio::null())
        .spawn()
        .map_err(|e| AgentError::new(ErrorCode::SpawnFailed, format!("systemd-run: {e}")))?;
    // systemd-run --scope execs in place, so this child is the session. reap it or it zombies.
    std::thread::spawn(move || {
        let _ = child.wait();
    });
    Ok(())
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Wm {
    Hyprland { hyprctl_bin: std::path::PathBuf },
    I3 { i3msg_bin: std::path::PathBuf },
    /// whichever this login is
    Auto { hyprctl_bin: std::path::PathBuf, i3msg_bin: std::path::PathBuf },
}

impl Wm {
    /// Hyprland exports its instance signature into the user manager; an X
    /// login without it is taken for i3.
    pub fn resolve(&self, set: impl Fn(&str) -> bool) -> Option<Wm> {
        match self {
            Wm::Auto { hyprctl_bin, i3msg_bin } => {
                if set("HYPRLAND_INSTANCE_SIGNATURE") {
                    Some(Wm::Hyprland { hyprctl_bin: hyprctl_bin.clone() })
                } else if set("DISPLAY") {
                    Some(Wm::I3 { i3msg_bin: i3msg_bin.clone() })
                } else {
                    None
                }
            }
            wm => Some(wm.clone()),
        }
    }
}

fn env_set(var: &str) -> bool {
    std::env::var_os(var).is_some_and(|v| !v.is_empty())
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Terminal {
    Kitty(std::path::PathBuf),
    Alacritty(std::path::PathBuf),
}

pub struct WindowLauncher {
    pub terminal: Terminal,
    pub wm: Wm,
    pub exec_bin: std::path::PathBuf,
    pub workspace: u32,
}

pub const WINDOW_CLASS: &str = "remoter-rc";

impl WindowLauncher {
    pub fn window_rule(&self) -> String {
        format!("match:class ^({WINDOW_CLASS})$, workspace {} silent", self.workspace)
    }

    fn need_env(vars: &[&str]) -> Result<(), AgentError> {
        for var in vars {
            if !env_set(var) {
                return Err(AgentError::new(ErrorCode::DesktopDown, format!("{var} not set")));
            }
        }
        Ok(())
    }

    fn wm(&self) -> Result<Wm, AgentError> {
        self.wm
            .resolve(env_set)
            .ok_or_else(|| AgentError::new(ErrorCode::DesktopDown, "neither HYPRLAND_INSTANCE_SIGNATURE nor DISPLAY is set"))
    }

    /// The terminal's argv, up to and including remoter-exec's.
    pub fn terminal_argv(&self, id: &str, dir: &Path) -> Vec<std::ffi::OsString> {
        let mut a: Vec<std::ffi::OsString> = Vec::new();
        match &self.terminal {
            Terminal::Kitty(bin) => {
                a.push(bin.into());
                for s in ["--class", WINDOW_CLASS, "--title", id, "--listen-on"] {
                    a.push(s.into());
                }
                a.push(format!("unix:{}", dir.join(KITTY_SOCKET).display()).into());
                for s in ["-o", "allow_remote_control=socket-only", "--"] {
                    a.push(s.into());
                }
            }
            Terminal::Alacritty(bin) => {
                a.push(bin.into());
                for s in ["--class", WINDOW_CLASS, "--title", id, "-e"] {
                    a.push(s.into());
                }
            }
        }
        a.push(self.exec_bin.clone().into());
        a.push("--spec".into());
        a.push(dir.join(SPEC_FILE).into());
        a
    }
}

/// The line the i3 config needs. i3 has no runtime window rules, so the agent
/// can only check for it.
pub fn i3_assign_line(workspace: u32) -> String {
    format!("assign [class=\"^{WINDOW_CLASS}$\"] number {workspace}")
}

/// `text` is what `i3-msg -t get_config` printed: the loaded config, includes
/// already pasted in. Any `assign` or `for_window` naming the class will do.
pub fn i3_places_windows(text: &str) -> bool {
    text.lines().map(str::trim).any(|l| (l.starts_with("assign ") || l.starts_with("for_window ")) && l.contains(WINDOW_CLASS))
}

impl Launcher for WindowLauncher {
    fn prepare(&self) -> Result<(), AgentError> {
        match self.wm()? {
            Wm::Hyprland { hyprctl_bin } => {
                Self::need_env(&["WAYLAND_DISPLAY", "HYPRLAND_INSTANCE_SIGNATURE"])?;
                // a Hyprland reload drops runtime rules, so set it every time
                let out = Command::new(&hyprctl_bin)
                    .args(["keyword", "windowrule", &self.window_rule()])
                    .stdin(Stdio::null())
                    .output()
                    .map_err(|e| AgentError::new(ErrorCode::DesktopDown, format!("hyprctl: {e}")))?;
                let said = String::from_utf8_lossy(&out.stdout);
                if !out.status.success() || said.trim() != "ok" {
                    return Err(AgentError::new(ErrorCode::DesktopDown, format!("hyprctl said {:?}", said.trim())));
                }
            }
            Wm::I3 { i3msg_bin } => {
                Self::need_env(&["DISPLAY"])?;
                let out = Command::new(&i3msg_bin)
                    .args(["-t", "get_config"])
                    .env_remove("WAYLAND_DISPLAY")
                    .stdin(Stdio::null())
                    .output()
                    .map_err(|e| AgentError::new(ErrorCode::DesktopDown, format!("i3-msg: {e}")))?;
                if !out.status.success() {
                    let said = String::from_utf8_lossy(&out.stderr);
                    return Err(AgentError::new(ErrorCode::DesktopDown, format!("i3-msg said {:?}", said.trim())));
                }
                // without it the window lands on whatever workspace is in front
                if !i3_places_windows(&String::from_utf8_lossy(&out.stdout)) {
                    return Err(AgentError::new(
                        ErrorCode::SpawnFailed,
                        format!("the i3 config needs `{}`", i3_assign_line(self.workspace)),
                    ));
                }
            }
            Wm::Auto { .. } => return Err(AgentError::new(ErrorCode::DesktopDown, "no desktop found")),
        }
        Ok(())
    }

    fn launch(&self, id: &str, dir: &Path) -> Result<(), AgentError> {
        if !is_plain_path(dir) || !is_plain_path(&self.exec_bin) {
            return Err(AgentError::new(ErrorCode::SpawnFailed, "runtime or exec path has characters kitty would expand"));
        }
        let mut cmd = Command::new("systemd-run");
        cmd.args(scope_prefix(id)).args(self.terminal_argv(id, dir));
        if matches!(self.wm()?, Wm::I3 { .. }) {
            // a WAYLAND_DISPLAY left in the user manager from an earlier
            // Wayland login would win over the X server i3 runs on
            cmd.env_remove("WAYLAND_DISPLAY");
        }
        spawn_detached(cmd)
    }

    fn renders_screen(&self) -> bool {
        matches!(self.terminal, Terminal::Alacritty(_))
    }

    fn screen(&self, dir: &Path) -> Option<String> {
        let kitty = match &self.terminal {
            Terminal::Kitty(bin) => bin,
            Terminal::Alacritty(_) => return std::fs::read_to_string(dir.join(SCREEN_FILE)).ok(),
        };
        let out = Command::new(kitty)
            .arg("@")
            .arg("--to")
            .arg(format!("unix:{}", dir.join(KITTY_SOCKET).display()))
            // `all`, not `screen`: screen is only as tall as the tile and can
            // start mid wrapped line
            .args(["get-text", "--extent", "all"])
            .stdin(Stdio::null())
            .stderr(Stdio::null())
            .output()
            .ok()?;
        out.status.success().then(|| String::from_utf8_lossy(&out.stdout).into_owned())
    }
}

/// A test checks the release binary doesn't contain this.
#[cfg(feature = "e2e-test")]
pub const E2E_MARKER: &str = "remoter-e2e-test-build-marker";

/// remoter-exec under a `script` pty, screen goes to a file.
#[cfg(feature = "e2e-test")]
pub struct HeadlessLauncher {
    pub exec_bin: std::path::PathBuf,
}

#[cfg(feature = "e2e-test")]
pub const SCREEN_LOG: &str = "screen.log";

#[cfg(feature = "e2e-test")]
impl Launcher for HeadlessLauncher {
    fn prepare(&self) -> Result<(), AgentError> {
        let _ = E2E_MARKER;
        Ok(())
    }

    fn launch(&self, id: &str, dir: &Path) -> Result<(), AgentError> {
        if !is_plain_path(dir) || !is_plain_path(&self.exec_bin) {
            return Err(AgentError::new(ErrorCode::SpawnFailed, "path has characters the shell would expand"));
        }
        // `script -c` goes through a shell, fine since both paths are plain
        let inner = format!("{} --spec {}", self.exec_bin.display(), dir.join(SPEC_FILE).display());
        let mut cmd = Command::new("systemd-run");
        cmd.args(scope_prefix(id)).args(["/usr/bin/script", "-q", "-f", "-e", "-c", &inner]).arg(dir.join(SCREEN_LOG));
        spawn_detached(cmd)
    }

    fn screen(&self, dir: &Path) -> Option<String> {
        let raw = std::fs::read(dir.join(SCREEN_LOG)).ok()?;
        Some(strip_escapes(&String::from_utf8_lossy(&raw)))
    }
}

/// Close enough to kitty's plain `get-text` for a simple screen.
#[cfg(feature = "e2e-test")]
fn strip_escapes(s: &str) -> String {
    let mut out = String::with_capacity(s.len());
    let mut it = s.chars().peekable();
    while let Some(c) = it.next() {
        match c {
            '\u{1b}' => match it.next() {
                Some('[') => {
                    for c in it.by_ref() {
                        if ('@'..='~').contains(&c) {
                            break;
                        }
                    }
                }
                Some(']') => {
                    while let Some(c) = it.next() {
                        if c == '\u{7}' || (c == '\u{1b}' && it.peek() == Some(&'\\')) {
                            it.next_if_eq(&'\\');
                            break;
                        }
                    }
                }
                _ => {}
            },
            '\r' => {}
            _ => out.push(c),
        }
    }
    out
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn plain_paths_only() {
        assert!(is_plain_path(Path::new("/run/user/1000/remoter/rc-01k6b7y3m4n5p6q7r8s9t0v1w2")));
        for bad in ["relative/x", "/run/$HOME/x", "/a b", "/a;b", "/a`id`", "/a\nb", "/caf\u{e9}"] {
            assert!(!is_plain_path(Path::new(bad)), "{bad:?}");
        }
    }

    #[test]
    fn scope_argv_turns_off_expansion() {
        let a = scope_prefix("rc-x");
        assert!(a.contains(&"--expand-environment=no".to_owned()));
        assert_eq!(a.last().map(String::as_str), Some("--"));
        assert!(a.contains(&"--unit=rc-x".to_owned()));
    }

    #[test]
    fn window_rule_text() {
        let k = WindowLauncher { terminal: Terminal::Kitty("/k".into()), wm: Wm::Hyprland { hyprctl_bin: "/h".into() }, exec_bin: "/e".into(), workspace: 9 };
        assert_eq!(k.window_rule(), "match:class ^(remoter-rc)$, workspace 9 silent");
    }

    #[test]
    fn terminal_argv_per_terminal() {
        let dir = Path::new("/run/user/1000/remoter/rc-x");
        let mut l = WindowLauncher { terminal: Terminal::Kitty("/usr/bin/kitty".into()), wm: Wm::I3 { i3msg_bin: "/i".into() }, exec_bin: "/usr/local/bin/remoter-exec".into(), workspace: 9 };
        let joined = |l: &WindowLauncher| l.terminal_argv("rc-x", dir).iter().map(|a| a.to_string_lossy().into_owned()).collect::<Vec<_>>().join(" ");
        assert_eq!(
            joined(&l),
            "/usr/bin/kitty --class remoter-rc --title rc-x --listen-on unix:/run/user/1000/remoter/rc-x/kitty.sock -o allow_remote_control=socket-only -- /usr/local/bin/remoter-exec --spec /run/user/1000/remoter/rc-x/spec.json"
        );
        assert!(!l.renders_screen());
        l.terminal = Terminal::Alacritty("/usr/bin/alacritty".into());
        assert_eq!(joined(&l), "/usr/bin/alacritty --class remoter-rc --title rc-x -e /usr/local/bin/remoter-exec --spec /run/user/1000/remoter/rc-x/spec.json");
        assert!(l.renders_screen());
    }

    #[test]
    fn auto_desktop_follows_the_login() {
        let auto = Wm::Auto { hyprctl_bin: "/h".into(), i3msg_bin: "/i".into() };
        let only = |vars: &'static [&'static str]| move |v: &str| vars.contains(&v);
        assert_eq!(auto.resolve(only(&["HYPRLAND_INSTANCE_SIGNATURE", "DISPLAY", "WAYLAND_DISPLAY"])), Some(Wm::Hyprland { hyprctl_bin: "/h".into() }));
        assert_eq!(auto.resolve(only(&["DISPLAY"])), Some(Wm::I3 { i3msg_bin: "/i".into() }));
        assert_eq!(auto.resolve(only(&["DISPLAY", "WAYLAND_DISPLAY"])), Some(Wm::I3 { i3msg_bin: "/i".into() }), "a leftover WAYLAND_DISPLAY isn't Hyprland");
        assert_eq!(auto.resolve(only(&[])), None);
        let fixed = Wm::Hyprland { hyprctl_bin: "/h".into() };
        assert_eq!(fixed.resolve(only(&[])), Some(fixed.clone()), "a fixed choice isn't second guessed");
    }

    #[test]
    fn i3_rule_lines() {
        assert_eq!(i3_assign_line(9), r#"assign [class="^remoter-rc$"] number 9"#);
        assert!(i3_places_windows(&format!("bindsym $mod+Return exec kitty\n  {}\n", i3_assign_line(9))));
        assert!(i3_places_windows(r#"for_window [class="remoter-rc"] move container to workspace 9"#));
        assert!(!i3_places_windows("bindsym $mod+9 workspace 9\n# assign [class=\"^remoter-rc$\"] 9\n"));
        assert!(!i3_places_windows(""));
    }

    #[cfg(feature = "e2e-test")]
    #[test]
    fn strips_escapes() {
        assert_eq!(strip_escapes("\u{1b}]2;title\u{7}a\u{1b}[31mb\u{1b}[0m\r\nc"), "ab\nc");
    }
}
