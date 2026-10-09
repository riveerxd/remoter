//! `[agent]` from /etc/remoter/config.toml, plus the clock window from `[net]`.

use std::path::{Path, PathBuf};

use serde::Deserialize;

#[derive(Debug, Clone, PartialEq, Eq, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct AgentConfig {
    pub socket: PathBuf,
    pub home: PathBuf,
    pub claude_bin: PathBuf,
    #[serde(default)]
    pub terminal: TerminalChoice,
    #[serde(default = "d_kitty")]
    pub kitty_bin: PathBuf,
    #[serde(default = "d_alacritty")]
    pub alacritty_bin: PathBuf,
    #[serde(default)]
    pub desktop: Desktop,
    #[serde(default = "d_hyprctl")]
    pub hyprctl_bin: PathBuf,
    #[serde(default = "d_i3msg")]
    pub i3msg_bin: PathBuf,
    pub exec_bin: PathBuf,
    pub workspace: u32,
    pub max_sessions: u32,
    pub cwd_deny: Vec<String>,
    pub search_skip: Vec<String>,
    pub trusted_roots: Vec<String>,
    /// only this uid gets answers on the socket
    #[serde(default = "d_remoterd_user")]
    pub remoterd_user: String,
    #[serde(default = "d_devices")]
    pub devices: PathBuf,
    /// remoterd's state dir, for the lock flag and unpaired list
    #[serde(default = "d_lock_dir")]
    pub lock_dir: PathBuf,
    /// default `$XDG_RUNTIME_DIR/remoter`
    #[serde(default)]
    pub runtime_dir: Option<PathBuf>,
    /// default `<home>/.local/state/remoter/recent.json`
    #[serde(default)]
    pub history_file: Option<PathBuf>,
    /// sticky lock and unpaired list, default `<home>/.local/state/remoter`
    #[serde(default)]
    pub state_dir: Option<PathBuf>,
    #[serde(default = "d_git")]
    pub git_bin: PathBuf,
    /// see `sessions::READY_TIMEOUT`
    #[serde(default = "d_ready_timeout")]
    pub ready_timeout_s: u64,
    #[serde(default = "d_notify")]
    pub notify_bin: PathBuf,
    /// default `<home>/.claude`
    #[serde(default)]
    pub claude_dir: Option<PathBuf>,
    #[serde(skip)]
    pub clock_window_s: u32,
}

#[derive(Debug, Clone, Copy, Default, PartialEq, Eq, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum Desktop {
    #[default]
    Auto,
    Hyprland,
    I3,
}

#[derive(Debug, Clone, Copy, Default, PartialEq, Eq, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum TerminalChoice {
    /// kitty if it's installed, else Alacritty
    #[default]
    Auto,
    Kitty,
    Alacritty,
}

fn d_kitty() -> PathBuf {
    "/usr/bin/kitty".into()
}
fn d_alacritty() -> PathBuf {
    "/usr/bin/alacritty".into()
}

fn d_hyprctl() -> PathBuf {
    "/usr/bin/hyprctl".into()
}
fn d_i3msg() -> PathBuf {
    "/usr/bin/i3-msg".into()
}

fn d_remoterd_user() -> String {
    "remoterd".into()
}
fn d_devices() -> PathBuf {
    "/etc/remoter/devices.json".into()
}
fn d_lock_dir() -> PathBuf {
    "/var/lib/remoterd".into()
}
fn d_ready_timeout() -> u64 {
    crate::sessions::READY_TIMEOUT.as_secs()
}

fn d_git() -> PathBuf {
    "/usr/bin/git".into()
}
fn d_notify() -> PathBuf {
    "/usr/bin/notify-send".into()
}

#[derive(Deserialize)]
struct NetPart {
    #[serde(default = "d_window")]
    clock_window_s: u32,
}

fn d_window() -> u32 {
    30
}

#[derive(Deserialize)]
struct File {
    agent: AgentConfig,
    net: Option<NetPart>,
}

impl AgentConfig {
    pub fn parse(text: &str) -> Result<AgentConfig, String> {
        let file: File = toml::from_str(text).map_err(|e| e.to_string())?;
        let mut agent = file.agent;
        agent.clock_window_s = file.net.map_or(30, |n| n.clock_window_s);
        agent.validate()?;
        Ok(agent)
    }

    pub fn wm(&self) -> crate::launcher::Wm {
        match self.desktop {
            Desktop::Auto => crate::launcher::Wm::Auto { hyprctl_bin: self.hyprctl_bin.clone(), i3msg_bin: self.i3msg_bin.clone() },
            Desktop::Hyprland => crate::launcher::Wm::Hyprland { hyprctl_bin: self.hyprctl_bin.clone() },
            Desktop::I3 => crate::launcher::Wm::I3 { i3msg_bin: self.i3msg_bin.clone() },
        }
    }

    pub fn terminal(&self) -> crate::launcher::Terminal {
        use crate::launcher::Terminal;
        match self.terminal {
            TerminalChoice::Kitty => Terminal::Kitty(self.kitty_bin.clone()),
            TerminalChoice::Alacritty => Terminal::Alacritty(self.alacritty_bin.clone()),
            TerminalChoice::Auto if !self.kitty_bin.exists() && self.alacritty_bin.exists() => Terminal::Alacritty(self.alacritty_bin.clone()),
            TerminalChoice::Auto => Terminal::Kitty(self.kitty_bin.clone()),
        }
    }

    pub fn locked_flag(&self) -> PathBuf {
        self.lock_dir.join("locked")
    }

    pub fn unpaired_file(&self) -> PathBuf {
        self.lock_dir.join("unpaired.json")
    }

    pub fn agent_state(&self) -> PathBuf {
        self.state_dir.clone().unwrap_or_else(|| self.home.join(".local/state/remoter"))
    }

    pub fn load(path: &Path) -> Result<AgentConfig, String> {
        let text = std::fs::read_to_string(path).map_err(|e| format!("{}: {e}", path.display()))?;
        AgentConfig::parse(&text)
    }

    fn validate(&self) -> Result<(), String> {
        for (name, p) in [
            ("socket", &self.socket),
            ("home", &self.home),
            ("claude_bin", &self.claude_bin),
            ("kitty_bin", &self.kitty_bin),
            ("alacritty_bin", &self.alacritty_bin),
            ("hyprctl_bin", &self.hyprctl_bin),
            ("i3msg_bin", &self.i3msg_bin),
            ("exec_bin", &self.exec_bin),
        ] {
            if !p.is_absolute() {
                return Err(format!("{name} must be an absolute path"));
            }
        }
        if !(1..=120).contains(&self.clock_window_s) {
            return Err("clock_window_s must be 1 to 120".into());
        }
        for (name, p) in [("devices", &self.devices), ("lock_dir", &self.lock_dir), ("git_bin", &self.git_bin), ("notify_bin", &self.notify_bin)] {
            if !p.is_absolute() {
                return Err(format!("{name} must be an absolute path"));
            }
        }
        if self.home == Path::new("/") {
            return Err("home can't be /".into());
        }
        if !(1..=10).contains(&self.workspace) {
            return Err("workspace must be 1 to 10".into());
        }
        if self.max_sessions == 0 || self.max_sessions > 32 {
            return Err("max_sessions must be 1 to 32".into());
        }
        if !(5..=600).contains(&self.ready_timeout_s) {
            return Err("ready_timeout_s must be 5 to 600".into());
        }
        // matched per component, so a slash would silently never match
        for list in [&self.cwd_deny, &self.search_skip, &self.trusted_roots] {
            for item in list {
                if item.is_empty() || item.contains('/') || item == "." || item == ".." {
                    return Err(format!("{item:?} is not a single folder name"));
                }
            }
        }
        Ok(())
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    pub const SAMPLE: &str = r#"
[net]
listen_device   = "rmt0"
port            = 8443

[agent]
socket          = "/run/remoter/agent.sock"
home            = "/home/river"
claude_bin      = "/home/river/.local/bin/claude"
kitty_bin       = "/usr/bin/kitty"
hyprctl_bin     = "/usr/bin/hyprctl"
exec_bin        = "/usr/local/bin/remoter-exec"
workspace       = 9
max_sessions    = 8
cwd_deny        = [".ssh", ".gnupg", ".claude", ".config", ".local", ".password-store"]
search_skip     = ["node_modules", ".git", "target", "build", ".gradle", ".venv", ".cache"]
trusted_roots   = ["Projects"]

[attestation]
app_package     = "me.river.remoter"
"#;

    #[test]
    fn parses_sample() {
        let c = AgentConfig::parse(SAMPLE).expect("parses");
        assert_eq!(c.workspace, 9);
        assert_eq!(c.claude_bin, PathBuf::from("/home/river/.local/bin/claude"));
        assert_eq!(c.cwd_deny.len(), 6);
        assert_eq!(c.ready_timeout_s, 90, "default");
    }

    #[test]
    fn ready_timeout_has_sane_bounds() {
        let with = |v: &str| SAMPLE.replace("max_sessions    = 8", &format!("max_sessions    = 8\nready_timeout_s = {v}"));
        assert_eq!(AgentConfig::parse(&with("20")).expect("20 s").ready_timeout_s, 20);
        assert!(AgentConfig::parse(&with("0")).is_err());
        assert!(AgentConfig::parse(&with("3600")).is_err());
    }

    #[test]
    fn refuses_bad_values() {
        let cases = [
            ("home            = \"/home/river\"", "home            = \"relative\""),
            ("workspace       = 9", "workspace       = 0"),
            ("max_sessions    = 8", "max_sessions    = 0"),
            ("\".ssh\", ", "\".ssh/keys\", "),
            ("trusted_roots   = [\"Projects\"]", "trusted_roots   = [\"..\"]"),
            ("workspace       = 9", "workspace       = 9\ntmux_bin = \"/usr/bin/tmux\""),
        ];
        for (from, to) in cases {
            let text = SAMPLE.replace(from, to);
            assert_ne!(text, SAMPLE, "{to}");
            assert!(AgentConfig::parse(&text).is_err(), "{to}");
        }
    }

    #[test]
    fn clock_window_comes_from_net_and_is_bounded() {
        assert_eq!(AgentConfig::parse(SAMPLE).expect("parses").clock_window_s, 30);
        let w = SAMPLE.replace("port            = 8443", "port = 8443\nclock_window_s = 45");
        assert_eq!(AgentConfig::parse(&w).expect("parses").clock_window_s, 45);
        let bad = SAMPLE.replace("port            = 8443", "port = 8443\nclock_window_s = 0");
        assert!(AgentConfig::parse(&bad).is_err());
    }

    #[test]
    fn desktop_defaults_to_auto_and_takes_either() {
        use crate::launcher::Wm;
        let set = |line: &str| AgentConfig::parse(&SAMPLE.replace("hyprctl_bin     = \"/usr/bin/hyprctl\"", line));
        let both = Wm::Auto { hyprctl_bin: "/usr/bin/hyprctl".into(), i3msg_bin: "/usr/bin/i3-msg".into() };
        assert_eq!(AgentConfig::parse(SAMPLE).expect("parses").wm(), both);
        assert_eq!(set("desktop = \"hyprland\"").expect("hyprland").wm(), Wm::Hyprland { hyprctl_bin: "/usr/bin/hyprctl".into() });
        assert_eq!(set("desktop = \"i3\"").expect("i3").wm(), Wm::I3 { i3msg_bin: "/usr/bin/i3-msg".into() });
        assert_eq!(set("desktop = \"i3\"\ni3msg_bin = \"/opt/i3/i3-msg\"").expect("own").wm(), Wm::I3 { i3msg_bin: "/opt/i3/i3-msg".into() });
        for bad in ["desktop = \"sway\"", "desktop = \"i3\"\ni3msg_bin = \"i3-msg\""] {
            assert!(set(bad).is_err(), "{bad}");
        }
    }

    #[test]
    fn terminal_is_chosen_or_found() {
        use crate::launcher::Terminal;
        let dir = std::env::temp_dir().join(format!("remoter-term-{}", std::process::id()));
        std::fs::create_dir_all(&dir).expect("mkdir");
        let (k, a) = (dir.join("kitty"), dir.join("alacritty"));
        let with = |choice: &str| {
            let text = SAMPLE.replace(
                "kitty_bin       = \"/usr/bin/kitty\"",
                &format!("kitty_bin = \"{}\"\nalacritty_bin = \"{}\"\n{choice}", k.display(), a.display()),
            );
            AgentConfig::parse(&text).expect("parses").terminal()
        };
        assert_eq!(with(""), Terminal::Kitty(k.clone()), "neither installed: kitty, so doctor names it");
        std::fs::write(&a, "").expect("alacritty");
        assert_eq!(with(""), Terminal::Alacritty(a.clone()));
        assert_eq!(with("terminal = \"kitty\""), Terminal::Kitty(k.clone()));
        std::fs::write(&k, "").expect("kitty");
        assert_eq!(with(""), Terminal::Kitty(k.clone()), "kitty wins when both are there");
        assert_eq!(with("terminal = \"alacritty\""), Terminal::Alacritty(a.clone()));
        let _ = std::fs::remove_dir_all(&dir);
        assert!(AgentConfig::parse(&SAMPLE.replace("max_sessions    = 8", "max_sessions = 8\nterminal = \"xterm\"")).is_err());
    }

    #[test]
    fn missing_agent_table_is_an_error() {
        assert!(AgentConfig::parse("[net]\nport = 1\n").is_err());
    }
}
