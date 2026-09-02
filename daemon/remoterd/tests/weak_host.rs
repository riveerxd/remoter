//! Weak host attack, in throwaway namespaces (the test reruns itself under
//! `unshare -rn`). `rmt0` is a veth to a "phone" netns at 10.66.66.2, and a
//! "LAN" netns plays the cafe neighbour adding `10.66.66.3/32 via 192.168.77.1`.

use std::process::{Child, Command, Stdio};
use std::time::{Duration, Instant};

const RAW_TABLE: &str = r#"
table inet remoter_raw {
  chain pre {
    type filter hook prerouting priority raw; policy accept;
    iifname != "rmt0" ip daddr 10.66.66.0/29 drop
    iifname != "rmt0" ip saddr 10.66.66.0/29 drop
  }
}
"#;

#[test]
fn static_route_attack_and_rebind() {
    let exe = std::env::current_exe().expect("exe");
    let out = Command::new("unshare")
        .args(["-rn", "--fork", "--"])
        .arg(exe)
        .args(["--ignored", "--exact", "inside_the_namespace", "--nocapture", "--test-threads", "1"])
        .output()
        .expect("unshare");
    let text = format!("{}\n{}", String::from_utf8_lossy(&out.stdout), String::from_utf8_lossy(&out.stderr));
    println!("{text}");
    assert!(out.status.success(), "inside the namespace:\n{text}");
    assert!(text.contains("1 passed"), "inner test didn't run:\n{text}");
}

fn sh(cmd: &str) {
    let st = Command::new("sh").args(["-ec", cmd]).status().expect("sh");
    assert!(st.success(), "failed: {cmd}");
}

struct Ns(Child);

impl Ns {
    fn new() -> Ns {
        Ns(Command::new("unshare").args(["-n", "--", "sleep", "120"]).stdin(Stdio::null()).spawn().expect("ns"))
    }
    fn pid(&self) -> u32 {
        self.0.id()
    }
    fn run(&self, cmd: &str) {
        sh(&format!("nsenter -t {} -n -- sh -ec '{cmd}'", self.pid()));
    }
    /// 0 connected, 1 refused, 124 dropped (no answer within 2 s).
    fn connect(&self, port: u16) -> i32 {
        let st = Command::new("nsenter")
            .args(["-t", &self.pid().to_string(), "-n", "--", "timeout", "2", "bash", "-c"])
            .arg(format!("exec 3<>/dev/tcp/10.66.66.3/{port}"))
            .stderr(Stdio::null())
            .status()
            .expect("connect");
        st.code().unwrap_or(-1)
    }
}

impl Drop for Ns {
    fn drop(&mut self) {
        let _ = self.0.kill();
        let _ = self.0.wait();
    }
}

fn tunnel(phone: &Ns) {
    sh("ip link add rmt0 type veth peer name ph0");
    sh(&format!("ip link set ph0 netns {}", phone.pid()));
    sh("ip addr add 10.66.66.3/32 dev rmt0 && ip link set rmt0 up && ip route add 10.66.66.2/32 dev rmt0");
    phone.run("ip link set lo up; ip addr add 10.66.66.2/32 dev ph0; ip link set ph0 up; ip route add 10.66.66.3/32 dev ph0");
}

#[test]
#[ignore = "run by static_route_attack_and_rebind inside unshare -rn"]
fn inside_the_namespace() {
    sh("ip link set lo up");
    let phone = Ns::new();
    let lan = Ns::new();
    std::thread::sleep(Duration::from_millis(200));
    tunnel(&phone);
    sh("ip link add lan0 type veth peer name lan1");
    sh(&format!("ip link set lan1 netns {}", lan.pid()));
    sh("ip addr add 192.168.77.1/24 dev lan0 && ip link set lan0 up");
    lan.run("ip link set lo up; ip addr add 192.168.77.2/24 dev lan1; ip link set lan1 up; ip route add 10.66.66.3/32 via 192.168.77.1");

    // plus a plain listener as the control, the attack must reach that one
    let bound = remoterd::listener::bind("rmt0", "10.66.66.3:8443".parse().expect("addr")).expect("bind rmt0");
    let plain = std::net::TcpListener::bind("10.66.66.3:9443").expect("plain");

    println!("without the raw table:");
    let (p, a, ctl) = (phone.connect(8443), lan.connect(8443), lan.connect(9443));
    println!("  phone -> 8443: {p}\n  lan -> 8443 (SO_BINDTODEVICE): {a}\n  lan -> 9443 (no device binding): {ctl}");
    assert_eq!(p, 0, "the phone reaches remoterd over rmt0");
    assert_eq!(ctl, 0, "control: attack reaches unbound socket");
    assert_ne!(a, 0, "SO_BINDTODEVICE alone stops it");

    let raw = std::env::temp_dir().join(format!("remoter-raw-{}.nft", std::process::id()));
    std::fs::write(&raw, RAW_TABLE).expect("w");
    sh(&format!("nft -f {}", raw.display()));
    let _ = std::fs::remove_file(&raw);
    println!("with the raw table:");
    let (p, a, ctl) = (phone.connect(8443), lan.connect(8443), lan.connect(9443));
    println!("  phone -> 8443: {p}\n  lan -> 8443: {a}\n  lan -> 9443: {ctl}");
    assert_eq!(p, 0, "the tunnel still works");
    assert_eq!(a, 124, "dropped before the stack even answers");
    assert_eq!(ctl, 124, "the raw table protects even an unbound socket");
    drop((bound, plain));

    // NetworkManager recreates rmt0 with a new ifindex on reconnect
    let rt = tokio::runtime::Builder::new_current_thread().enable_all().build().expect("rt");
    rt.block_on(async {
        let (tx, mut rx) = tokio::sync::mpsc::channel(8);
        tokio::spawn(remoterd::listener::run("rmt0".into(), "10.66.66.3:8445".parse().expect("addr"), tx));
        tokio::time::sleep(Duration::from_millis(300)).await;
        let before = remoterd::listener::ifindex("rmt0");
        assert_eq!(tokio::task::spawn_blocking({
            let pid = phone.pid();
            move || connect_pid(pid, 8445)
        }).await.expect("join"), 0);
        assert!(rx.recv().await.is_some());

        sh("ip link del rmt0");
        tokio::time::sleep(Duration::from_millis(300)).await;
        tunnel(&phone);
        let after = remoterd::listener::ifindex("rmt0");
        assert_ne!(before, after, "a new ifindex, as after a reconnect");
        let t0 = Instant::now();
        loop {
            let pid = phone.pid();
            let r = tokio::task::spawn_blocking(move || connect_pid(pid, 8445)).await.expect("join");
            if r == 0 {
                break;
            }
            assert!(t0.elapsed() < Duration::from_secs(8), "never rebound to the new rmt0 (last {r})");
            tokio::time::sleep(Duration::from_millis(200)).await;
        }
        println!("rebound to the new rmt0 in {:?}", t0.elapsed());
    });
}

fn connect_pid(pid: u32, port: u16) -> i32 {
    Command::new("nsenter")
        .args(["-t", &pid.to_string(), "-n", "--", "timeout", "2", "bash", "-c"])
        .arg(format!("exec 3<>/dev/tcp/10.66.66.3/{port}"))
        .stderr(Stdio::null())
        .status()
        .expect("connect")
        .code()
        .unwrap_or(-1)
}
