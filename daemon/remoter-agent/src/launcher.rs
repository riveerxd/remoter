//! Opens a session's window. Kitty in real builds, headless only with `e2e-test`.

use std::path::Path;
use std::process::{Command, Stdio};

use remoter_proto::ErrorCode;
use remoter_proto::local::{KITTY_SOCKET, SPEC_FILE};

use crate::AgentError;

pub trait Launcher: Send + Sync {
    /// Under the spawn lock. `desktop_down` when there's nowhere to open a window.
    fn prepare(&self) -> Result<(), AgentError>;

    /// `remoter-exec --spec <dir>/spec.json` in a user scope named after the session.
    fn launch(&self, id: &str, dir: &Path) -> Result<(), AgentError>;

    fn screen(&self, dir: &Path) -> Option<String>;
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

pub struct KittyLauncher {
    pub kitty_bin: std::path::PathBuf,
    pub hyprctl_bin: std::path::PathBuf,
    pub exec_bin: std::path::PathBuf,
    pub workspace: u32,
}

pub const WINDOW_CLASS: &str = "remoter-rc";

impl KittyLauncher {
    pub fn window_rule(&self) -> String {
        format!("match:class ^({WINDOW_CLASS})$, workspace {} silent", self.workspace)
    }
}

impl Launcher for KittyLauncher {
    fn prepare(&self) -> Result<(), AgentError> {
        for var in ["WAYLAND_DISPLAY", "HYPRLAND_INSTANCE_SIGNATURE"] {
            if std::env::var_os(var).is_none_or(|v| v.is_empty()) {
                return Err(AgentError::new(ErrorCode::DesktopDown, format!("{var} not set")));
            }
        }
        // a Hyprland reload drops runtime rules, so set it every time
        let out = Command::new(&self.hyprctl_bin)
            .args(["keyword", "windowrule", &self.window_rule()])
            .stdin(Stdio::null())
            .output()
            .map_err(|e| AgentError::new(ErrorCode::DesktopDown, format!("hyprctl: {e}")))?;
        let said = String::from_utf8_lossy(&out.stdout);
        if !out.status.success() || said.trim() != "ok" {
            return Err(AgentError::new(ErrorCode::DesktopDown, format!("hyprctl said {:?}", said.trim())));
        }
        Ok(())
    }

    fn launch(&self, id: &str, dir: &Path) -> Result<(), AgentError> {
        if !is_plain_path(dir) || !is_plain_path(&self.exec_bin) {
            return Err(AgentError::new(ErrorCode::SpawnFailed, "runtime or exec path has characters kitty would expand"));
        }
        let mut cmd = Command::new("systemd-run");
        cmd.args(scope_prefix(id))
            .arg(&self.kitty_bin)
            .args(["--class", WINDOW_CLASS, "--title", id])
            .arg("--listen-on")
            .arg(format!("unix:{}", dir.join(KITTY_SOCKET).display()))
            .args(["-o", "allow_remote_control=socket-only", "--"])
            .arg(&self.exec_bin)
            .arg("--spec")
            .arg(dir.join(SPEC_FILE));
        spawn_detached(cmd)
    }

    fn screen(&self, dir: &Path) -> Option<String> {
        let out = Command::new(&self.kitty_bin)
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
        let k = KittyLauncher { kitty_bin: "/k".into(), hyprctl_bin: "/h".into(), exec_bin: "/e".into(), workspace: 9 };
        assert_eq!(k.window_rule(), "match:class ^(remoter-rc)$, workspace 9 silent");
    }

    #[cfg(feature = "e2e-test")]
    #[test]
    fn strips_escapes() {
        assert_eq!(strip_escapes("\u{1b}]2;title\u{7}a\u{1b}[31mb\u{1b}[0m\r\nc"), "ab\nc");
    }
}
