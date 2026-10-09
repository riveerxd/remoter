//! Opens a session's window: kitty or Alacritty, on Hyprland or i3. Or no
//! window, a detached tmux session. Headless only with `e2e-test`.

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

    /// After its scope is gone.
    fn ended(&self, _id: &str) {}
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

/// `text` is `i3-msg -t get_config` output, includes already expanded.
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

/// One tmux server for all sessions, as its own user service. Each pane runs
/// systemd-run itself, so claude still ends up in `rc-<id>.scope`.
pub struct TmuxLauncher {
    pub tmux_bin: std::path::PathBuf,
    pub socket: String,
    pub exec_bin: std::path::PathBuf,
}

/// The size a pane gets while nobody is attached.
const TMUX_COLS: &str = "160";
const TMUX_ROWS: &str = "48";

impl TmuxLauncher {
    pub fn unit(&self) -> String {
        format!("{}-tmux.service", self.socket)
    }

    fn tmux(&self) -> Command {
        let mut c = Command::new(&self.tmux_bin);
        // -N: never start a server from here, it would land in the agent's cgroup
        c.args(["-L", &self.socket, "-N"]).stdin(Stdio::null());
        c
    }

    fn up(&self) -> bool {
        self.tmux().arg("list-sessions").stdout(Stdio::null()).stderr(Stdio::null()).status().is_ok_and(|s| s.success())
    }

    fn wait_up(&self) -> bool {
        let deadline = std::time::Instant::now() + std::time::Duration::from_secs(3);
        loop {
            if self.up() {
                return true;
            }
            if std::time::Instant::now() > deadline {
                return false;
            }
            std::thread::sleep(std::time::Duration::from_millis(50));
        }
    }

    /// `tmux new-session`'s arguments. tmux ends a command at any argument
    /// that ends in `;`, even after `--`, so those are refused.
    pub fn new_session_args(&self, id: &str, dir: &Path) -> Option<Vec<std::ffi::OsString>> {
        let mut a: Vec<std::ffi::OsString> =
            ["new-session", "-d", "-s", id, "-x", TMUX_COLS, "-y", TMUX_ROWS, "--", "systemd-run"].into_iter().map(Into::into).collect();
        a.extend(scope_prefix(id).into_iter().map(Into::into));
        a.push(self.exec_bin.clone().into());
        a.push("--spec".into());
        a.push(dir.join(SPEC_FILE).into());
        a.iter().all(|x| !x.as_encoded_bytes().ends_with(b";")).then_some(a)
    }
}

impl Launcher for TmuxLauncher {
    fn prepare(&self) -> Result<(), AgentError> {
        if self.up() {
            return Ok(());
        }
        let unit = self.unit();
        let systemctl = |verb: &str| Command::new("systemctl").args(["--user", verb, &unit]).stdin(Stdio::null()).output();
        // someone else may have just started it and it isn't listening yet
        if systemctl("is-active").is_ok_and(|o| o.status.success()) && self.wait_up() {
            return Ok(());
        }
        let _ = systemctl("stop");
        let _ = systemctl("reset-failed");
        let out = Command::new("systemd-run")
            // a window session inherits this from remoter-agent.service, a pane would not
            .args(["--user", "--quiet", "--collect", "--expand-environment=no", "-p", "NoNewPrivileges=yes", &format!("--unit={unit}"), "--"])
            .arg(&self.tmux_bin)
            // -D keeps the server in the foreground and alive with no sessions
            .args(["-L", &self.socket, "-D"])
            .stdin(Stdio::null())
            .output()
            .map_err(|e| AgentError::new(ErrorCode::SpawnFailed, format!("systemd-run: {e}")))?;
        // losing a race to start it is fine, as long as it answers
        if self.wait_up() {
            return Ok(());
        }
        let said = String::from_utf8_lossy(&out.stderr);
        Err(AgentError::new(ErrorCode::SpawnFailed, format!("tmux server in {unit} never answered: {}", said.trim())))
    }

    fn launch(&self, id: &str, dir: &Path) -> Result<(), AgentError> {
        if !is_plain_path(dir) || !is_plain_path(&self.exec_bin) {
            return Err(AgentError::new(ErrorCode::SpawnFailed, "runtime or exec path has odd characters"));
        }
        let args = self.new_session_args(id, dir).ok_or_else(|| AgentError::new(ErrorCode::SpawnFailed, "an argument ends in ;"))?;
        let out = self
            .tmux()
            .args(args)
            .output()
            .map_err(|e| AgentError::new(ErrorCode::SpawnFailed, format!("tmux: {e}")))?;
        if !out.status.success() {
            return Err(AgentError::new(ErrorCode::SpawnFailed, format!("tmux said {:?}", String::from_utf8_lossy(&out.stderr).trim())));
        }
        Ok(())
    }

    fn screen(&self, dir: &Path) -> Option<String> {
        let id = dir.file_name()?.to_str()?;
        let out = self
            .tmux()
            // -J joins wrapped rows back into lines, like kitty's get-text
            .args(["capture-pane", "-p", "-J", "-S", "-200", "-t", &format!("={id}:")])
            .stderr(Stdio::null())
            .output()
            .ok()?;
        out.status.success().then(|| String::from_utf8_lossy(&out.stdout).into_owned())
    }

    fn ended(&self, id: &str) {
        // only matters with remain-on-exit, otherwise the pane closed with the scope
        let _ = self.tmux().args(["kill-session", "-t", &format!("={id}")]).stderr(Stdio::null()).output();
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
    fn scope_no_expansion() {
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
    fn tmux_new_session_args() {
        let t = TmuxLauncher { tmux_bin: "/usr/bin/tmux".into(), socket: "remoter".into(), exec_bin: "/usr/local/bin/remoter-exec".into() };
        assert_eq!(t.unit(), "remoter-tmux.service");
        let a: Vec<String> = t
            .new_session_args("rc-01", Path::new("/run/user/1000/remoter/rc-01"))
            .expect("args")
            .into_iter()
            .map(|x| x.into_string().expect("utf8"))
            .collect();
        assert_eq!(
            a.join(" "),
            "new-session -d -s rc-01 -x 160 -y 48 -- systemd-run --user --scope --quiet --collect --expand-environment=no --unit=rc-01 -- \
             /usr/local/bin/remoter-exec --spec /run/user/1000/remoter/rc-01/spec.json"
        );
        let semi = TmuxLauncher { exec_bin: "/opt/x;".into(), ..t };
        assert!(semi.new_session_args("rc-01", Path::new("/run/r")).is_none());
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
    fn auto_follows_login() {
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
