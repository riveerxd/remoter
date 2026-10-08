//! infra/laptop/install-tunnel.sh's direct mode against stand-ins for nmcli,
//! ss, ip and qrencode that log what they were asked. wg is the real one, so
//! the keys that end up in NM and in the phone's QR can be checked to match.

use std::os::unix::fs::PermissionsExt;
use std::path::{Path, PathBuf};
use std::process::{Command, Output, Stdio};
use std::sync::atomic::{AtomicUsize, Ordering};

fn script(name: &str) -> PathBuf {
    Path::new(env!("CARGO_MANIFEST_DIR")).join("../../infra/laptop").join(name)
}

const NMCLI: &str = r#"#!/bin/bash
printf '%s\n' "$*" >> "$LAB/nmcli.argv"
case "$*" in
  "-t -f NAME connection show") cat "$LAB/connections" ;;
  "-t -f NAME connection show --active") cat "$LAB/active" ;;
  "connection edit rmt0-direct") cat > "$LAB/nmcli.stdin" ;;
  "-g wireguard.listen-port connection show rmt0-direct") cat "$LAB/port" ;;
  connection\ add*)
    for a; do case "$prev" in wireguard.listen-port) echo "$a" > "$LAB/port" ;; esac; prev="$a"; done
    echo rmt0-direct >> "$LAB/connections" ;;
esac
"#;

struct Lab {
    dir: PathBuf,
}

impl Lab {
    fn new() -> Lab {
        static N: AtomicUsize = AtomicUsize::new(0);
        let dir = PathBuf::from(env!("CARGO_TARGET_TMPDIR")).join(format!("tunnel-{}-{}", std::process::id(), N.fetch_add(1, Ordering::Relaxed)));
        let _ = std::fs::remove_dir_all(&dir);
        for d in ["bin", "run", "sys/class/net/eno1/device", "sys/class/net/wg0"] {
            std::fs::create_dir_all(dir.join(d)).expect("mkdir");
        }
        let lab = Lab { dir };
        lab.stub("nmcli", NMCLI);
        lab.stub("ss", "#!/bin/sh\ncat \"$LAB/ss\"\n");
        lab.stub("ip", "#!/bin/sh\ncat \"$LAB/route\"\n");
        lab.stub("qrencode", "#!/bin/sh\ncat > \"$LAB/qr\"\n");
        lab.stub("clear", "#!/bin/sh\n");
        lab.write("connections", "rmt0\n");
        lab.write("active", "rmt0\n");
        lab.write("ss", "UNCONN 0 0 0.0.0.0:46625 0.0.0.0:*\nUNCONN 0 0 [::]:51000 [::]:*\n");
        lab.route("eno1");
        lab
    }

    fn stub(&self, name: &str, body: &str) {
        let p = self.dir.join("bin").join(name);
        std::fs::write(&p, body).expect("stub");
        std::fs::set_permissions(&p, std::fs::Permissions::from_mode(0o755)).expect("chmod");
    }

    fn write(&self, name: &str, text: &str) {
        std::fs::write(self.dir.join(name), text).expect("write");
    }

    fn read(&self, name: &str) -> String {
        std::fs::read_to_string(self.dir.join(name)).unwrap_or_default()
    }

    fn route(&self, dev: &str) {
        self.write("route", &format!("1.1.1.1 via 192.168.0.1 dev {dev} src 192.168.0.68 uid 1000\n    cache\n"));
    }

    fn run(&self, name: &str, args: &[&str], stdin: &str) -> Output {
        let path = format!("{}:/usr/bin:/bin", self.dir.join("bin").display());
        let mut child = Command::new("bash")
            .arg(script(name))
            .args(args)
            .env_clear()
            .env("PATH", path)
            .env("LAB", &self.dir)
            .env("XDG_RUNTIME_DIR", self.dir.join("run"))
            .env("REMOTER_SYSFS", self.dir.join("sys"))
            .stdin(Stdio::piped())
            .stdout(Stdio::piped())
            .stderr(Stdio::piped())
            .spawn()
            .expect("bash");
        use std::io::Write;
        child.stdin.take().expect("stdin").write_all(stdin.as_bytes()).expect("stdin");
        child.wait_with_output().expect("wait")
    }

    fn refused(&self, args: &[&str], why: &str) {
        let out = self.run("install-tunnel.sh", args, "y\n\n");
        let err = String::from_utf8_lossy(&out.stderr);
        assert!(!out.status.success(), "{args:?} went through");
        assert!(err.contains(why), "{args:?}: {err}");
        assert!(!self.read("nmcli.argv").contains("connection add"), "{args:?} touched NM");
    }
}

impl Drop for Lab {
    fn drop(&mut self) {
        let _ = std::fs::remove_dir_all(&self.dir);
    }
}

fn pubkey(private: &str) -> String {
    let mut c = Command::new("wg").arg("pubkey").stdin(Stdio::piped()).stdout(Stdio::piped()).spawn().expect("wg");
    use std::io::Write;
    c.stdin.take().expect("stdin").write_all(private.as_bytes()).expect("write");
    String::from_utf8(c.wait_with_output().expect("wg").stdout).expect("utf8").trim().to_owned()
}

fn field<'a>(text: &'a str, key: &str) -> &'a str {
    text.lines().find_map(|l| l.strip_prefix(&format!("{key} = "))).unwrap_or_else(|| panic!("no {key} in {text}"))
}

#[test]
fn direct_makes_a_switched_off_profile_and_a_matching_qr() {
    let lab = Lab::new();
    let out = lab.run("install-tunnel.sh", &["--direct", "home.example.net", "47913"], "y\n\n");
    assert!(out.status.success(), "{}", String::from_utf8_lossy(&out.stderr));
    let stdout = String::from_utf8_lossy(&out.stdout);
    assert!(stdout.contains("forward UDP 47913 to 192.168.0.68"), "{stdout}");

    let argv = lab.read("nmcli.argv");
    let add = argv.lines().find(|l| l.starts_with("connection add")).expect("add");
    for want in ["con-name rmt0-direct", "ifname rmt0", "connection.autoconnect no", "ipv4.addresses 10.66.66.3/32", "wireguard.listen-port 47913"] {
        assert!(add.contains(want), "{add}");
    }
    assert!(!argv.lines().any(|l| ["connection modify", "connection up", "connection down"].iter().any(|c| l.starts_with(c))), "the hub was touched: {argv}");

    let edit = lab.read("nmcli.stdin");
    let laptop_key = edit.lines().find_map(|l| l.strip_prefix("set wireguard.private-key ")).expect("private key");
    let peer = edit.lines().find_map(|l| l.strip_prefix("set wireguard.peers ")).expect("peer");
    assert!(!argv.contains(laptop_key), "a private key in argv");

    let qr = lab.read("qr");
    assert_eq!(field(&qr, "Endpoint"), "home.example.net:47913");
    assert_eq!(field(&qr, "AllowedIPs"), "10.66.66.3/32");
    assert_eq!(field(&qr, "Address"), "10.66.66.2/32");
    assert_eq!(field(&qr, "PublicKey"), pubkey(laptop_key));
    let psk = field(&qr, "PresharedKey");
    assert_eq!(peer, format!("{} allowed-ips=10.66.66.2/32 preshared-key={psk}", pubkey(field(&qr, "PrivateKey"))));
    assert!(!argv.contains(psk), "the PSK in argv");
    assert!(std::fs::read_dir(lab.dir.join("run")).expect("run").next().is_none(), "keys left in tmpfs");
}

#[test]
fn direct_brackets_an_ipv6_endpoint_and_picks_a_free_high_port() {
    let lab = Lab::new();
    let out = lab.run("install-tunnel.sh", &["--direct", "2a02:830a:f044:2600::fbfb"], "y\n\n");
    assert!(out.status.success(), "{}", String::from_utf8_lossy(&out.stderr));
    let endpoint = field(&lab.read("qr"), "Endpoint").to_owned();
    let port: u32 = endpoint.strip_prefix("[2a02:830a:f044:2600::fbfb]:").expect(&endpoint).parse().expect("port");
    assert!((49152..=65535).contains(&port) && port != 51000, "{port}");
}

#[test]
fn direct_refusals() {
    let lab = Lab::new();
    lab.refused(&["--direct"], "usage");
    for bad in ["-rf", "a;b", "$(id)", "home name", "a/b"] {
        lab.refused(&["--direct", bad], "plain host name");
    }
    for bad in ["0", "70000", "x1"] {
        lab.refused(&["--direct", "home.example.net", bad], "port must be");
    }
    lab.refused(&["--direct", "home.example.net", "46625"], "already in use");
    lab.route("wg0");
    lab.refused(&["--direct", "home.example.net", "47913"], "leaves through wg0");
    lab.route("eno1");
    lab.write("connections", "rmt0\nrmt0-direct\n");
    lab.refused(&["--direct", "home.example.net", "47913"], "already exists");
}

#[test]
fn direct_stops_at_no() {
    let lab = Lab::new();
    let out = lab.run("install-tunnel.sh", &["--direct", "home.example.net", "47913"], "n\n");
    assert!(!out.status.success());
    assert!(!lab.read("nmcli.argv").contains("connection add"));
    assert!(lab.read("qr").is_empty());
}

#[test]
fn use_switches_one_off_then_the_other_on() {
    let lab = Lab::new();
    lab.write("connections", "rmt0\nrmt0-direct\n");
    let out = lab.run("install-tunnel.sh", &["--use", "direct"], "");
    assert!(out.status.success(), "{}", String::from_utf8_lossy(&out.stderr));
    let argv = lab.read("nmcli.argv");
    let steps: Vec<&str> = argv.lines().filter(|l| !l.starts_with("-t")).collect();
    assert_eq!(
        steps,
        [
            "connection modify rmt0 connection.autoconnect no",
            "connection down rmt0",
            "connection modify rmt0-direct connection.autoconnect yes",
            "connection up rmt0-direct",
        ]
    );
    lab.route("wg0");
    let out = lab.run("install-tunnel.sh", &["--use", "direct"], "");
    assert!(!out.status.success() && String::from_utf8_lossy(&out.stderr).contains("wg0"));
    let back = lab.run("install-tunnel.sh", &["--use", "hub"], "");
    assert!(back.status.success(), "going back to the hub needs no outer route check");
    assert!(!lab.run("install-tunnel.sh", &["--use", "carrier-pigeon"], "").status.success());
    lab.write("connections", "rmt0\n");
    assert!(!lab.run("install-tunnel.sh", &["--use", "direct"], "").status.success(), "no direct profile");
}

#[test]
fn remove_direct_keeps_a_way_back() {
    let lab = Lab::new();
    lab.write("connections", "rmt0-direct\n");
    let out = lab.run("install-tunnel.sh", &["--remove-direct"], "");
    assert!(!out.status.success() && String::from_utf8_lossy(&out.stderr).contains("no hub"));
    lab.write("connections", "rmt0\nrmt0-direct\n");
    lab.write("active", "rmt0-direct\n");
    let out = lab.run("install-tunnel.sh", &["--remove-direct"], "");
    assert!(!out.status.success() && String::from_utf8_lossy(&out.stderr).contains("--use hub"));
    lab.write("active", "rmt0\n");
    assert!(lab.run("install-tunnel.sh", &["--remove-direct"], "").status.success());
    assert!(lab.read("nmcli.argv").contains("connection delete rmt0-direct"));
}

#[test]
fn phone_tunnel_points_direct_elsewhere() {
    let lab = Lab::new();
    let out = lab.run("phone-tunnel.sh", &["--direct"], "");
    assert!(!out.status.success() && String::from_utf8_lossy(&out.stderr).contains("install-tunnel.sh --direct"));
}
