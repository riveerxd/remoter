//! remoterd's part of /etc/remoter/config.toml.

use std::net::Ipv4Addr;
use std::path::{Path, PathBuf};

use serde::Deserialize;

#[derive(Debug, Clone, Deserialize)]
pub struct Net {
    pub listen_device: String,
    pub listen_addr: Ipv4Addr,
    pub port: u16,
    pub pair_port: u16,
    pub phone_addr: Ipv4Addr,
    pub clock_window_s: u32,
}

#[derive(Debug, Clone, Deserialize)]
struct AgentPart {
    socket: PathBuf,
}

#[derive(Debug, Clone, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct Paths {
    #[serde(default = "d_devices")]
    pub devices: PathBuf,
    #[serde(default = "d_key")]
    pub server_key: PathBuf,
    #[serde(default = "d_cert")]
    pub server_cert: PathBuf,
    /// audit log, lock flag, unpaired list
    #[serde(default = "d_state")]
    pub state_dir: PathBuf,
    /// checked on the agent socket
    pub agent_user: String,
    #[serde(default = "d_power")]
    pub power_supply_dir: PathBuf,
    #[serde(default = "d_admin")]
    pub admin_socket: PathBuf,
}

fn d_admin() -> PathBuf {
    "/run/remoterd/admin.sock".into()
}

fn d_devices() -> PathBuf {
    "/etc/remoter/devices.json".into()
}
fn d_key() -> PathBuf {
    "/etc/remoter/server.key".into()
}
fn d_cert() -> PathBuf {
    "/etc/remoter/server.crt".into()
}
fn d_state() -> PathBuf {
    "/var/lib/remoterd".into()
}
fn d_power() -> PathBuf {
    "/sys/class/power_supply".into()
}

#[derive(Debug, Clone)]
pub struct Config {
    pub net: Net,
    pub agent_socket: PathBuf,
    pub paths: Paths,
    pub attestation: crate::attest::AttestConfig,
}

#[derive(Deserialize)]
struct File {
    net: Net,
    agent: AgentPart,
    remoterd: Paths,
    attestation: crate::attest::AttestConfig,
}

impl Config {
    pub fn parse(text: &str) -> Result<Config, String> {
        let f: File = toml::from_str(text).map_err(|e| e.to_string())?;
        if f.net.clock_window_s == 0 || f.net.clock_window_s > 120 {
            return Err("clock_window_s must be 1 to 120".into());
        }
        for (n, p) in [
            ("socket", &f.agent.socket),
            ("devices", &f.remoterd.devices),
            ("server_key", &f.remoterd.server_key),
            ("server_cert", &f.remoterd.server_cert),
            ("state_dir", &f.remoterd.state_dir),
        ] {
            if !p.is_absolute() {
                return Err(format!("{n} must be absolute"));
            }
        }
        f.attestation.policy()?;
        if f.attestation.fresh_hours == 0 || f.attestation.fresh_hours > 24 * 7 {
            return Err("fresh_hours must be 1 to 168".into());
        }
        Ok(Config { net: f.net, agent_socket: f.agent.socket, paths: f.remoterd, attestation: f.attestation })
    }

    pub fn load(path: &Path) -> Result<Config, String> {
        Config::parse(&std::fs::read_to_string(path).map_err(|e| format!("{}: {e}", path.display()))?)
    }

    pub fn locked_flag(&self) -> PathBuf {
        self.paths.state_dir.join("locked")
    }
    pub fn unpaired_file(&self) -> PathBuf {
        self.paths.state_dir.join("unpaired.json")
    }
    pub fn audit_file(&self) -> PathBuf {
        self.paths.state_dir.join("audit.jsonl")
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    const SAMPLE: &str = r#"
[net]
listen_device   = "rmt0"
listen_addr     = "10.66.66.3"
port            = 8443
pair_port       = 8444
phone_addr      = "10.66.66.2"
clock_window_s  = 30

[agent]
socket          = "/run/remoter/agent.sock"
home            = "/home/river"

[remoterd]
agent_user      = "river"

[attestation]
app_package     = "me.river.remoter"
app_cert_sha256 = "5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a"
max_patch_age_months = 6
fresh_hours     = 24
"#;

    #[test]
    fn parses_with_defaults() {
        let c = Config::parse(SAMPLE).expect("parses");
        assert_eq!(c.net.listen_addr, Ipv4Addr::new(10, 66, 66, 3));
        assert_eq!(c.paths.devices, PathBuf::from("/etc/remoter/devices.json"));
        assert_eq!(c.locked_flag(), PathBuf::from("/var/lib/remoterd/locked"));
    }

    #[test]
    fn refuses_bad_values() {
        assert!(Config::parse(&SAMPLE.replace("clock_window_s  = 30", "clock_window_s  = 0")).is_err());
        assert!(Config::parse(&SAMPLE.replace("\"/run/remoter/agent.sock\"", "\"agent.sock\"")).is_err());
        assert!(Config::parse(&SAMPLE.replace("agent_user      = \"river\"", "agent_user = \"river\"\nwhatever = 1")).is_err());
        assert!(Config::parse(&SAMPLE.replace("5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a", "<release signing cert digest>")).is_err(), "placeholder");
        assert!(Config::parse(&SAMPLE.replace("fresh_hours     = 24", "fresh_hours = 0")).is_err());
    }
}
