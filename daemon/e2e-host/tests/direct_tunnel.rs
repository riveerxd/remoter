//! The phone dialing the laptop straight, no hub: a real WireGuard rmt0 with a
//! fixed listen port, infra/laptop/nftables.conf loaded unchanged. A veth
//! "internet" joins the laptop to the phone and to a stranger. Plain listeners
//! stand in for remoterd.

use std::net::{SocketAddr, TcpListener, UdpSocket};
use std::os::fd::AsRawFd;
use std::path::{Path, PathBuf};
use std::process::{Child, Command, Stdio};
use std::sync::{Arc, Mutex};
use std::time::Duration;

const PORT: u16 = 47913;

fn infra() -> PathBuf {
    Path::new(env!("CARGO_MANIFEST_DIR")).join("../../infra/laptop")
}

#[test]
fn direct_tunnel() {
    let exe = std::env::current_exe().expect("exe");
    let out = Command::new("unshare")
        .args(["-rn", "--fork", "--"])
        .arg(exe)
        .args(["--ignored", "--exact", "inside_the_direct_namespace", "--nocapture", "--test-threads", "1"])
        .env("REMOTER_DIRECT_NETNS", "1")
        .output()
        .expect("unshare");
    let text = format!("{}\n{}", String::from_utf8_lossy(&out.stdout), String::from_utf8_lossy(&out.stderr));
    println!("{text}");
    assert!(out.status.success() && text.contains("1 passed"), "inside the namespace:\n{text}");
}

fn sh(cmd: &str) -> String {
    let out = Command::new("sh").args(["-ec", cmd]).output().expect("sh");
    assert!(out.status.success(), "failed: {cmd}\n{}", String::from_utf8_lossy(&out.stderr));
    String::from_utf8_lossy(&out.stdout).trim().to_owned()
}

fn nft(file: &Path) {
    let out = Command::new("nft").arg("-f").arg(file).output().expect("nft");
    assert!(out.status.success(), "nft -f {}: {}", file.display(), String::from_utf8_lossy(&out.stderr));
}

struct Key {
    file: PathBuf,
    public: String,
}

fn key(dir: &Path, name: &str) -> Key {
    let file = dir.join(name);
    sh(&format!("umask 077; wg genkey > {}", file.display()));
    let public = sh(&format!("wg pubkey < {}", file.display()));
    Key { file, public }
}

/// Another network namespace, entered with nsenter for commands and setns for sockets.
struct Ns(Child);

impl Ns {
    fn new() -> Ns {
        let ns = Ns(Command::new("unshare").args(["-n", "--", "sleep", "120"]).stdin(Stdio::null()).spawn().expect("ns"));
        std::thread::sleep(Duration::from_millis(100));
        ns.sh("ip link set lo up");
        ns
    }

    fn pid(&self) -> u32 {
        self.0.id()
    }

    fn sh(&self, cmd: &str) -> String {
        sh(&format!("nsenter -t {} -n -- sh -ec '{}'", self.pid(), cmd.replace('\'', r"'\''")))
    }

    fn reaches(&self, to: &str) -> bool {
        let (host, port) = to.split_once(':').expect("host:port");
        Command::new("nsenter")
            .args(["-t", &self.pid().to_string(), "-n", "--", "timeout", "2", "bash", "-c"])
            .arg(format!("exec 3<>/dev/tcp/{host}/{port}"))
            .stderr(Stdio::null())
            .status()
            .expect("connect")
            .success()
    }

    /// Runs `f` on a thread that has joined this namespace, so its sockets live there.
    fn with<T: Send + 'static>(&self, f: impl FnOnce() -> T + Send + 'static) -> T {
        let path = format!("/proc/{}/ns/net", self.pid());
        std::thread::spawn(move || {
            let ns = std::fs::File::open(path).expect("netns");
            // SAFETY: a valid fd for a netns, and this thread is ours alone.
            assert_eq!(unsafe { libc::setns(ns.as_raw_fd(), libc::CLONE_NEWNET) }, 0, "setns");
            f()
        })
        .join()
        .expect("ns thread")
    }
}

impl Drop for Ns {
    fn drop(&mut self) {
        let _ = self.0.kill();
        let _ = self.0.wait();
    }
}

fn listen(port: u16) {
    let l = TcpListener::bind(SocketAddr::from(([0, 0, 0, 0], port))).expect("listen");
    std::thread::spawn(move || for _ in l.incoming() {});
}

fn handshakes(ns: Option<&Ns>, dev: &str) -> Vec<u64> {
    let cmd = format!("wg show {dev} latest-handshakes");
    let out = match ns {
        Some(n) => n.sh(&cmd),
        None => sh(&cmd),
    };
    out.lines().filter_map(|l| l.split_whitespace().nth(1)?.parse().ok()).collect()
}

fn phone_endpoint() -> String {
    sh("wg show rmt0 endpoints").split_whitespace().nth(1).unwrap_or("").to_owned()
}

/// Sits between the phone and the laptop's port and keeps the last data packet,
/// so it can be replayed from somewhere else.
fn relay(on: &str) -> Arc<Mutex<Option<Vec<u8>>>> {
    let outer = UdpSocket::bind(on).expect("relay");
    let inner = UdpSocket::bind("127.0.0.1:0").expect("relay inner");
    inner.connect(("127.0.0.1", PORT)).expect("connect");
    let last = Arc::new(Mutex::new(None));
    let phone = Arc::new(Mutex::new(None::<SocketAddr>));
    let (o2, i2, p2) = (outer.try_clone().expect("clone"), inner.try_clone().expect("clone"), phone.clone());
    std::thread::spawn(move || {
        let mut buf = [0u8; 2048];
        while let Ok(n) = i2.recv(&mut buf) {
            if let Some(to) = *p2.lock().expect("lock") {
                let _ = o2.send_to(&buf[..n], to);
            }
        }
    });
    let kept = last.clone();
    std::thread::spawn(move || {
        let mut buf = [0u8; 2048];
        while let Ok((n, from)) = outer.recv_from(&mut buf) {
            *phone.lock().expect("lock") = Some(from);
            // 4 is a transport data packet
            if buf[0] == 4 {
                *kept.lock().expect("lock") = Some(buf[..n].to_vec());
            }
            let _ = inner.send(&buf[..n]);
        }
    });
    last
}

#[test]
#[ignore = "runs inside the namespace made by the test above"]
fn inside_the_direct_namespace() {
    if std::env::var_os("REMOTER_DIRECT_NETNS").is_none() {
        eprintln!("only runs inside the namespace");
        return;
    }
    let keys = PathBuf::from(env!("CARGO_TARGET_TMPDIR")).join(format!("direct-{}", std::process::id()));
    let _ = std::fs::remove_dir_all(&keys);
    std::fs::create_dir_all(&keys).expect("keys dir");
    let (laptop, phone_key, stranger_key) = (key(&keys, "laptop"), key(&keys, "phone"), key(&keys, "stranger"));
    let psk = keys.join("psk");
    sh(&format!("umask 077; wg genpsk > {}", psk.display()));

    let phone = Ns::new();
    let stranger = Ns::new();
    sh("ip link set lo up");
    sh(&format!("ip link add wan0 type veth peer name wan0 netns {}", phone.pid()));
    sh(&format!("ip link add wan1 type veth peer name wan0 netns {}", stranger.pid()));
    sh("ip addr add 198.51.100.1/24 dev wan0 && ip link set wan0 up");
    sh("ip addr add 203.0.113.1/24 dev wan1 && ip link set wan1 up");
    sh("ip link add lan0 type dummy && ip addr add 192.168.77.1/24 dev lan0 && ip link set lan0 up");
    phone.sh("ip addr add 198.51.100.2/24 dev wan0 && ip link set wan0 up");
    stranger.sh("ip addr add 203.0.113.2/24 dev wan0 && ip link set wan0 up");

    // as install-tunnel.sh --direct sets it up: a fixed port, the phone, no endpoint
    sh("ip link add rmt0 type wireguard");
    sh(&format!(
        "wg set rmt0 private-key {} listen-port {PORT} peer {} preshared-key {} allowed-ips 10.66.66.2/32",
        laptop.file.display(),
        phone_key.public,
        psk.display()
    ));
    sh("ip addr add 10.66.66.3/32 dev rmt0 && ip link set rmt0 up && ip route add 10.66.66.2/32 dev rmt0");

    phone.sh("ip link add rmt0 type wireguard");
    phone.sh(&format!(
        "wg set rmt0 private-key {} peer {} preshared-key {} endpoint 198.51.100.1:{PORT} allowed-ips 10.66.66.3/32,203.0.113.0/24,192.168.77.0/24 persistent-keepalive 25",
        phone_key.file.display(),
        laptop.public,
        psk.display()
    ));
    phone.sh("ip addr add 10.66.66.2/32 dev rmt0 && ip link set rmt0 mtu 1280 up && ip route add 10.66.66.3/32 dev rmt0");

    for port in [8443, 8444, 9443, 22] {
        listen(port);
    }
    stranger.with(|| listen(80));
    // so the forward check below has something to forward to and back
    sh("sysctl -qw net.ipv4.ip_forward=1");
    stranger.sh("ip route add 10.66.66.0/29 via 203.0.113.1");
    phone.sh("ip route add 203.0.113.2/32 dev rmt0 && ip route add 192.168.77.0/24 dev rmt0");

    // without the firewall, to show each check below can fail for the right reason
    assert!(phone.reaches("10.66.66.3:8443"));
    assert!(phone.reaches("10.66.66.3:22"));
    assert!(phone.reaches("192.168.77.1:8443"));
    assert!(phone.reaches("203.0.113.2:80"), "the laptop forwards out of rmt0");
    stranger.sh("ip route add 10.66.66.3/32 via 203.0.113.1");
    assert!(stranger.reaches("10.66.66.3:8443"), "the weak host route works on a bare laptop");

    nft(&infra().join("nftables.conf"));

    assert!(phone.reaches("10.66.66.3:8443"));
    assert!(phone.reaches("10.66.66.3:8444"));
    assert!(!phone.reaches("10.66.66.3:22"));
    assert!(!phone.reaches("10.66.66.3:9443"));
    assert!(!phone.reaches("192.168.77.1:8443"));
    assert!(!phone.reaches("203.0.113.2:80"), "nothing routed out of rmt0");
    nft(&infra().join("nftables-staging.conf"));
    assert!(phone.reaches("10.66.66.3:9443"));
    nft(&infra().join("nftables.conf"));

    assert!(!stranger.reaches("10.66.66.3:8443"), "the weak host route");
    let before = handshakes(None, "rmt0");
    let quiet = stranger.with(|| {
        let s = UdpSocket::bind("203.0.113.2:0").expect("udp");
        s.set_read_timeout(Some(Duration::from_secs(2))).expect("timeout");
        for len in [1usize, 32, 148, 1200] {
            s.send_to(&vec![1u8; len], ("203.0.113.1", PORT)).expect("send");
        }
        let mut buf = [0u8; 2048];
        s.recv_from(&mut buf).is_err()
    });
    assert!(quiet, "the port answered junk");

    // a stranger with its own key, dressed up as the phone
    stranger.sh("ip link add rmt0 type wireguard");
    stranger.sh(&format!(
        "wg set rmt0 private-key {} peer {} endpoint 203.0.113.1:{PORT} allowed-ips 10.66.66.3/32 persistent-keepalive 1",
        stranger_key.file.display(),
        laptop.public
    ));
    stranger.sh("ip route del 10.66.66.3/32 && ip addr add 10.66.66.2/32 dev rmt0 && ip link set rmt0 up && ip route add 10.66.66.3/32 dev rmt0");
    assert!(!stranger.reaches("10.66.66.3:8443"));
    std::thread::sleep(Duration::from_secs(3));
    assert_eq!(handshakes(Some(&stranger), "rmt0"), [0], "an unknown key got a handshake");
    assert_eq!(handshakes(None, "rmt0").len(), before.len(), "the laptop learned a peer");

    // known to the laptop, but only for 10.66.66.4: claiming the phone's address gets nothing
    sh(&format!("wg set rmt0 peer {} allowed-ips 10.66.66.4/32", stranger_key.public));
    let ok_handshake = (0..50).any(|_| {
        std::thread::sleep(Duration::from_millis(100));
        handshakes(Some(&stranger), "rmt0")[0] > 0
    });
    assert!(ok_handshake, "a configured key should handshake");
    assert!(!stranger.reaches("10.66.66.3:8443"), "spoofed the phone's tunnel address");
    stranger.sh("ip addr flush dev rmt0 && ip addr add 10.66.66.4/32 dev rmt0 && ip route add 10.66.66.3/32 dev rmt0");
    assert!(!stranger.reaches("10.66.66.3:8443"), "only the phone's address is let in");
    sh(&format!("wg set rmt0 peer {} remove", stranger_key.public));
    stranger.sh("ip link del rmt0");

    // the phone moves networks, the laptop follows
    phone.sh("ip addr del 198.51.100.2/24 dev wan0 && ip addr add 198.51.100.3/24 dev wan0");
    assert!(phone.reaches("10.66.66.3:8443"), "after the phone moved");
    assert!(phone_endpoint().starts_with("198.51.100.3:"), "{}", phone_endpoint());

    // a recorded packet sent again from elsewhere doesn't move the laptop's idea of the phone
    let kept = relay("198.51.100.1:47914");
    phone.sh(&format!("wg set rmt0 peer {} endpoint 198.51.100.1:47914", laptop.public));
    assert!(phone.reaches("10.66.66.3:8443"), "through the relay");
    let endpoint = phone_endpoint();
    assert!(endpoint.starts_with("127.0.0.1:"), "{endpoint}");
    let packet = kept.lock().expect("lock").clone().expect("a data packet went past");
    stranger.with(move || {
        let s = UdpSocket::bind("203.0.113.2:0").expect("udp");
        for _ in 0..3 {
            s.send_to(&packet, ("203.0.113.1", PORT)).expect("send");
        }
    });
    std::thread::sleep(Duration::from_millis(500));
    assert_eq!(phone_endpoint(), endpoint, "a replay moved the endpoint");

    let _ = std::fs::remove_dir_all(&keys);
}
