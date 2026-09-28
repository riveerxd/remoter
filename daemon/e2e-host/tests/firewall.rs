//! infra/laptop/nftables.conf loaded for real in a throwaway netns. Plain
//! listeners stand in for remoterd, so only the firewall decides what gets
//! through (`SO_BINDTODEVICE` is covered in remoterd/tests/weak_host.rs).
//! `lan0` at 192.168.77.1 is what a phone could aim at if the rules only
//! checked the port.

use std::net::{SocketAddr, TcpListener};
use std::path::{Path, PathBuf};
use std::process::{Child, Command, Stdio};
use std::time::Duration;

fn infra() -> PathBuf {
    Path::new(env!("CARGO_MANIFEST_DIR")).join("../../infra/laptop")
}

#[test]
fn only_tunnel_ports_on_tunnel_address() {
    let exe = std::env::current_exe().expect("exe");
    let out = Command::new("unshare")
        .args(["-rn", "--fork", "--"])
        .arg(exe)
        .args(["--ignored", "--exact", "inside_the_firewall_namespace", "--nocapture", "--test-threads", "1"])
        .env("REMOTER_FW_NETNS", "1")
        .output()
        .expect("unshare");
    let text = format!("{}\n{}", String::from_utf8_lossy(&out.stdout), String::from_utf8_lossy(&out.stderr));
    println!("{text}");
    assert!(out.status.success() && text.contains("1 passed"), "inside the namespace:\n{text}");
}

fn sh(cmd: &str) {
    let st = Command::new("sh").args(["-ec", cmd]).status().expect("sh");
    assert!(st.success(), "failed: {cmd}");
}

fn nft(file: &Path) {
    let out = Command::new("nft").arg("-f").arg(file).output().expect("nft");
    assert!(out.status.success(), "nft -f {}: {}", file.display(), String::from_utf8_lossy(&out.stderr));
}

struct Phone(Child);

impl Phone {
    fn reaches(&self, to: &str) -> bool {
        let (host, port) = to.split_once(':').expect("host:port");
        Command::new("nsenter")
            .args(["-t", &self.0.id().to_string(), "-n", "--", "timeout", "2", "bash", "-c"])
            .arg(format!("exec 3<>/dev/tcp/{host}/{port}"))
            .stderr(Stdio::null())
            .status()
            .expect("connect")
            .success()
    }
}

impl Drop for Phone {
    fn drop(&mut self) {
        let _ = self.0.kill();
        let _ = self.0.wait();
    }
}

fn listen(port: u16) {
    let l = TcpListener::bind(SocketAddr::from(([0, 0, 0, 0], port))).expect("listen");
    std::thread::spawn(move || for _ in l.incoming() {});
}

#[test]
#[ignore = "runs inside the namespace made by the test above"]
fn inside_the_firewall_namespace() {
    // never on the host's real network, even if someone runs it as root
    if std::env::var_os("REMOTER_FW_NETNS").is_none() {
        eprintln!("only runs inside the namespace");
        return;
    }
    let phone = Phone(Command::new("unshare").args(["-n", "--", "sleep", "60"]).stdin(Stdio::null()).spawn().expect("ns"));
    std::thread::sleep(Duration::from_millis(100));
    let pid = phone.0.id();
    sh("ip link set lo up");
    sh(&format!("ip link add rmt0 type veth peer name ph0 netns {pid}"));
    sh("ip addr add 10.66.66.3/32 dev rmt0 && ip link set rmt0 up && ip route add 10.66.66.2/32 dev rmt0");
    sh("ip link add lan0 type dummy && ip addr add 192.168.77.1/24 dev lan0 && ip link set lan0 up");
    sh(&format!(
        "nsenter -t {pid} -n -- sh -ec 'ip link set lo up; ip addr add 10.66.66.2/32 dev ph0; ip link set ph0 up; ip route add 10.66.66.3/32 dev ph0; ip route add 192.168.77.0/24 dev ph0'"
    ));
    for port in [8443, 8444, 9443, 9444, 22] {
        listen(port);
    }
    assert!(phone.reaches("192.168.77.1:8443"), "no firewall: should reach");

    nft(&infra().join("nftables.conf"));
    assert!(phone.reaches("10.66.66.3:8443"), "the API port on the tunnel address");
    assert!(phone.reaches("10.66.66.3:8444"), "the pairing port on the tunnel address");
    assert!(!phone.reaches("10.66.66.3:22"), "nothing else on the tunnel address");
    assert!(!phone.reaches("192.168.77.1:8443"), "LAN address");
    assert!(!phone.reaches("10.66.66.3:9443"), "staging port");
    // reload, like the unit does
    nft(&infra().join("nftables.conf"));
    assert!(phone.reaches("10.66.66.3:8443") && !phone.reaches("192.168.77.1:8443"));

    // staging, the way the remoter-firewall.service drop-in loads it
    nft(&infra().join("nftables-staging.conf"));
    assert!(phone.reaches("10.66.66.3:9443") && phone.reaches("10.66.66.3:9444"), "staging's ports on the tunnel address");
    assert!(!phone.reaches("192.168.77.1:9443"), "and only there");
    assert!(phone.reaches("10.66.66.3:8443") && !phone.reaches("10.66.66.3:22"), "production unchanged");
    // a reload recreates the table, hence the drop-in adding them back
    nft(&infra().join("nftables.conf"));
    assert!(!phone.reaches("10.66.66.3:9443"), "gone until the add-on runs again");
}
