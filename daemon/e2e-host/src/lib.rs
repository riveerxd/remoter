//! End to end harness: the real daemons, built with `e2e-test`, with the agent
//! on the host and remoterd in a user+net+mount namespace bound to a veth `rmt0`.
//! The phone sits in its own netns at 10.66.66.2.
//!
//! remoterd is chrooted into a root the test owns, because inside a user
//! namespace `/` and `/home` show up as uid 65534 and the owner check on the
//! trust files would fail.

use std::io::{Read, Write};
use std::net::{Ipv4Addr, SocketAddr};
use std::os::unix::fs::{MetadataExt, PermissionsExt};
use std::os::unix::process::CommandExt;
use std::path::{Path, PathBuf};
use std::process::{Child, Command, Stdio};
use std::sync::atomic::{AtomicUsize, Ordering};
use std::sync::{Arc, OnceLock};
use std::time::{Duration, Instant};

use axum::body::Body;
use hyper::http::{HeaderMap, Request, StatusCode};
use hyper_util::rt::{TokioExecutor, TokioIo};
use p256::ecdsa::signature::Signer;
use p256::ecdsa::{Signature, SigningKey};
use p256::pkcs8::{DecodePrivateKey, EncodePublicKey};
use rcgen::{KeyPair, PKCS_ECDSA_P256_SHA256};
use remoter_attest::testkit::{ChainOpts, Pki, Spec};
use remoter_auth::devices::DeviceRecord;
use remoter_proto::admin::{AdminReply, AdminRequest, PairStarted, PairStatus};
use remoter_proto::canonical::{SignInput, canonical};
use remoter_proto::{b64, local, pair};
use rustls::client::danger::{HandshakeSignatureValid, ServerCertVerified, ServerCertVerifier};
use rustls::pki_types::{CertificateDer, PrivateKeyDer, PrivatePkcs8KeyDer, ServerName, UnixTime};
use rustls::sign::CertifiedKey;
use rustls::{ClientConfig, DigitallySignedStruct, SignatureScheme};
use sha2::{Digest, Sha256};
use tokio::sync::mpsc;

pub const LAPTOP: Ipv4Addr = Ipv4Addr::new(10, 66, 66, 3);
pub const PHONE: Ipv4Addr = Ipv4Addr::new(10, 66, 66, 2);
pub const API: SocketAddr = SocketAddr::new(std::net::IpAddr::V4(LAPTOP), 8443);
// the agent refuses anything stamped before its start plus this, 30s would
// make every world sit idle that long
pub const WINDOW_S: i64 = 5;
pub const MAX_SESSIONS: u32 = 2;

const ENV_WORLD: &str = "REMOTER_E2E_WORLD";
const ENV_AGENT_START: &str = "REMOTER_E2E_AGENT_START";
const ENV_BINS: &str = "REMOTER_E2E_BINS";

pub fn uid() -> u32 {
    // SAFETY: getuid has no preconditions.
    unsafe { libc::getuid() }
}

fn gid() -> u32 {
    // SAFETY: getgid has no preconditions.
    unsafe { libc::getgid() }
}

pub fn now() -> i64 {
    local::now_ms()
}

fn workspace() -> PathBuf {
    Path::new(env!("CARGO_MANIFEST_DIR")).parent().expect("workspace").to_path_buf()
}

fn username() -> String {
    let out = Command::new("id").arg("-un").output().expect("id");
    String::from_utf8_lossy(&out.stdout).trim().to_owned()
}

fn sh(cmd: &str) {
    let st = Command::new("sh").args(["-ec", cmd]).status().expect("sh");
    assert!(st.success(), "failed: {cmd}");
}

fn write_mode(p: &Path, text: &[u8], mode: u32) {
    std::fs::write(p, text).unwrap_or_else(|e| panic!("{}: {e}", p.display()));
    std::fs::set_permissions(p, std::fs::Permissions::from_mode(mode)).expect("chmod");
}

fn mkdir_mode(p: &Path, mode: u32) {
    std::fs::create_dir_all(p).unwrap_or_else(|e| panic!("{}: {e}", p.display()));
    std::fs::set_permissions(p, std::fs::Permissions::from_mode(mode)).expect("chmod");
}

/// Built once per test process, into the same target dir the release marker test uses.
pub fn bins() -> &'static Path {
    static DIR: OnceLock<PathBuf> = OnceLock::new();
    DIR.get_or_init(|| {
        if let Some(d) = std::env::var_os(ENV_BINS) {
            return PathBuf::from(d);
        }
        let target = workspace().join("target/e2e-marker");
        let cargo = std::env::var("CARGO").unwrap_or_else(|_| "cargo".into());
        let out = Command::new(cargo)
            .args(["build", "--locked", "--target-dir"])
            .arg(&target)
            .args(["-p", "remoterd", "-p", "remoter-agent", "-p", "remoter-exec", "--features", "remoterd/e2e-test,remoter-agent/e2e-test"])
            .current_dir(workspace())
            .env("CARGO_INCREMENTAL", "0")
            .output()
            .expect("cargo");
        assert!(out.status.success(), "e2e build failed:\n{}", String::from_utf8_lossy(&out.stderr));
        target.join("debug")
    })
}

pub fn contains(file: &Path, needle: &[u8]) -> bool {
    let bytes = std::fs::read(file).unwrap_or_else(|e| panic!("{}: {e}", file.display()));
    bytes.windows(needle.len()).any(|w| w == needle)
}

// behaviour picked by folder name, so one config serves every scenario
const FAKE_CLAUDE: &str = r#"#!/bin/sh
for last; do :; done
dbg="$last"
log() { echo "2026-09-28T00:00:00.000Z [DEBUG] $1" >> "$dbg"; }
case "$(basename "$PWD")" in
  hang*)
    log "[bridge:init] bridgeId=fake dir=$PWD"
    echo hanging here
    exec sleep 600 ;;
  exit1*)
    for i in $(seq 1 50); do echo "output line $i"; done
    echo something broke
    exit 1 ;;
  untrusted*)
    echo "Error: Workspace not trusted. Please run \`claude\` in $PWD first to review and accept the workspace trust dialog."
    exit 1 ;;
  asks*)
    # the dialog as 2.1.295 draws it, unless this exact folder has its own true entry
    if ! /usr/bin/jq -e --arg p "$PWD" '.projects[$p].hasTrustDialogAccepted == true' "$(dirname "$(dirname "$PWD")")/.claude.json" >/dev/null; then
      printf ' Accessing workspace:\n\n %s\n\n ❯ No, exit\n   Yes, I trust this folder\n' "$PWD"
      exec sleep 600
    fi
    log "[bridge:init] bridgeId=fake dir=$PWD"
    log "[bridge:init] Registered, server environmentId=env_01Kd3fPzQw8nVb2sLxRt6uYm"
    log "[bridge:init] Created initial session session_01Hq7cXv2mTnR4bWkYe9pLsA"
    exec sleep 600 ;;
  dialog*)
    printf ' Accessing workspace:\n\n %s\n\n ❯ No, exit\n   Yes, I trust this folder\n' "$PWD"
    exec sleep 600 ;;
  resume*)
    # 2.1.292 reattaching a never archived cloud session
    echo "argv: $*"
    log "[remote-bridge] Reattaching to session cse_01Pm4tWq9zHc2vNe7gRb5kDy"
    log "[bridge:repl] handleStateChange state=connected detail=x"
    exec sleep 600 ;;
  *)
    log "[bridge:init] bridgeId=fake dir=$PWD"
    log "[bridge:init] Registered, server environmentId=env_01Kd3fPzQw8nVb2sLxRt6uYm"
    log "[bridge:init] Created initial session session_01Hq7cXv2mTnR4bWkYe9pLsA"
    echo "Connected to fake remote control"
    echo "Continue coding in the Claude mobile app or https://claude.ai/code?environment=env_01Kd3fPzQw8nVb2sLxRt6uYm"
    exec sleep 600 ;;
esac
"#;

pub const FOLDERS: &[&str] = &[
    "Projects/remoter/.git",
    "Projects/ready-a",
    "Projects/ready-b",
    "Projects/ready-c",
    "Projects/hang",
    "Projects/exit1",
    "Projects/untrusted-sim",
    "Projects/asks-sim",
    "Projects/dialog-sim",
    "Projects/plain",
    "Projects/resume-me",
    "Projects/.ssh",
    "Documents/notes",
];

pub struct World {
    pub dir: PathBuf,
    pub runtime: PathBuf,
    agent: Option<Child>,
    agent_start: i64,
    keep: bool,
}

impl World {
    pub fn new(name: &str) -> World {
        static N: AtomicUsize = AtomicUsize::new(0);
        let n = N.fetch_add(1, Ordering::Relaxed);
        let bins = bins();
        // short: sun_path is 108 bytes and the chroot repeats this path
        let dir = workspace().join("target/tmp").join(format!("e2e{}{n}", std::process::id() % 100_000));
        let _ = std::fs::remove_dir_all(&dir);
        mkdir_mode(&dir, 0o755);
        let run = std::env::var_os("XDG_RUNTIME_DIR").map(PathBuf::from).expect("XDG_RUNTIME_DIR");
        let runtime = run.join(format!("rmt-e2e{}{n}", std::process::id() % 100_000));
        let _ = std::fs::remove_dir_all(&runtime);

        for d in ["bin", "etc", "power/BAT0", "power/AC"] {
            mkdir_mode(&dir.join(d), 0o755);
        }
        mkdir_mode(&dir.join("state"), 0o711);
        for b in ["remoterd", "remoter-agent", "remoter-exec"] {
            // hard links, so the chroot can see them
            std::fs::hard_link(bins.join(b), dir.join("bin").join(b)).unwrap_or_else(|e| panic!("link {b}: {e}"));
        }
        write_mode(&dir.join("bin/claude"), FAKE_CLAUDE.as_bytes(), 0o755);
        write_mode(&dir.join("bin/notify"), format!("#!/bin/sh\nprintf '%s\\n' \"$*\" >> {}/notify.log\n", dir.display()).as_bytes(), 0o755);
        for (f, v) in [("BAT0/type", "Battery"), ("BAT0/capacity", "12"), ("AC/type", "Mains"), ("AC/online", "0")] {
            write_mode(&dir.join("power").join(f), format!("{v}\n").as_bytes(), 0o644);
        }

        let home = dir.join("home");
        for f in FOLDERS {
            mkdir_mode(&home.join(f), 0o755);
        }
        mkdir_mode(&home, 0o755);
        write_mode(
            &home.join(".claude.json"),
            format!(r#"{{"projects":{{"{}/Projects":{{"hasTrustDialogAccepted":true}}}}}}"#, home.display()).as_bytes(),
            0o644,
        );

        let kp = KeyPair::generate_for(&PKCS_ECDSA_P256_SHA256).expect("key");
        let mut params = rcgen::CertificateParams::new(Vec::<String>::new()).expect("params");
        params.distinguished_name.push(rcgen::DnType::CommonName, "remoterd");
        params.subject_alt_names = vec![rcgen::SanType::IpAddress(LAPTOP.into())];
        let cert = params.self_signed(&kp).expect("cert");
        write_mode(&dir.join("etc/server.key"), pem("PRIVATE KEY", &kp.serialize_der()).as_bytes(), 0o640);
        write_mode(&dir.join("etc/server.crt"), pem("CERTIFICATE", cert.der()).as_bytes(), 0o644);
        write_mode(&dir.join("etc/devices.json"), b"{\"devices\":[]}\n", 0o644);
        write_mode(&dir.join("etc/config.toml"), config(&dir, &runtime).as_bytes(), 0o644);

        let log = std::fs::File::create(dir.join("agent.log")).expect("log");
        let agent = Command::new(dir.join("bin/remoter-agent"))
            .arg("--config")
            .arg(dir.join("etc/config.toml"))
            .env("REMOTER_E2E_TRUSTED_OWNER", uid().to_string())
            .env("REMOTER_E2E_HEADLESS", "1")
            .stdin(Stdio::null())
            .stdout(log.try_clone().expect("log"))
            .stderr(log)
            .spawn()
            .expect("agent");
        let agent_start = now();
        let mut w = World { dir, runtime, agent: Some(agent), agent_start, keep: false };
        w.wait_for_agent(name);
        w
    }

    fn wait_for_agent(&mut self, name: &str) {
        let deadline = Instant::now() + Duration::from_secs(10);
        while !self.dir.join("agent.sock").exists() {
            if let Some(Ok(Some(st))) = self.agent.as_mut().map(|c| c.try_wait()) {
                panic!("{name}: agent exited {st}:\n{}", self.log("agent.log"));
            }
            assert!(Instant::now() < deadline, "{name}: agent never opened its socket:\n{}", self.log("agent.log"));
            std::thread::sleep(Duration::from_millis(50));
        }
    }

    pub fn log(&self, f: &str) -> String {
        std::fs::read_to_string(self.dir.join(f)).unwrap_or_default()
    }

    /// Reruns this binary's ignored test `inner` inside the lab namespaces.
    pub fn run_inner(&mut self, inner: &str) {
        let exe = std::env::current_exe().expect("exe");
        let out = Command::new("unshare")
            .args(["--user", "--map-user", &uid().to_string(), "--map-group", &gid().to_string(), "--keep-caps", "--net", "--mount", "--fork", "--"])
            .arg(exe)
            .args(["--ignored", "--exact", inner, "--nocapture", "--test-threads", "1"])
            .env(ENV_WORLD, &self.dir)
            .env(ENV_AGENT_START, self.agent_start.to_string())
            .env(ENV_BINS, bins())
            .output()
            .expect("unshare");
        let text = format!("{}\n{}", String::from_utf8_lossy(&out.stdout), String::from_utf8_lossy(&out.stderr));
        if !(out.status.success() && text.contains("1 passed")) {
            self.keep = true;
            panic!(
                "{inner} failed inside the lab:\n{text}\n--- remoterd.log\n{}\n--- agent.log\n{}",
                self.log("remoterd.log"),
                self.log("agent.log")
            );
        }
        println!("{text}");
    }
}

impl Drop for World {
    fn drop(&mut self) {
        if let Some(mut a) = self.agent.take() {
            let _ = a.kill();
            let _ = a.wait();
        }
        if let Ok(rd) = std::fs::read_dir(&self.runtime) {
            for e in rd.flatten() {
                let id = e.file_name().to_string_lossy().into_owned();
                let _ = Command::new("systemctl").args(["--user", "kill", "--signal=SIGKILL", &format!("{id}.scope")]).output();
            }
        }
        let _ = std::fs::remove_dir_all(&self.runtime);
        if !self.keep && !std::thread::panicking() {
            let _ = std::fs::remove_dir_all(&self.dir);
        }
    }
}

/// Same PEM `remoterctl init` writes.
fn pem(label: &str, der: &[u8]) -> String {
    const T: &[u8; 64] = b"ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
    let mut b = String::new();
    for c in der.chunks(3) {
        let n = (c[0] as u32) << 16 | (*c.get(1).unwrap_or(&0) as u32) << 8 | *c.get(2).unwrap_or(&0) as u32;
        for i in 0..4 {
            b.push(if i <= c.len() { T[(n >> (18 - 6 * i) & 63) as usize] as char } else { '=' });
        }
    }
    let lines: Vec<&str> = b.as_bytes().chunks(64).map(|l| std::str::from_utf8(l).expect("ascii")).collect();
    format!("-----BEGIN {label}-----\n{}\n-----END {label}-----\n", lines.join("\n"))
}

fn config(dir: &Path, runtime: &Path) -> String {
    let d = dir.display();
    format!(
        r#"[net]
listen_device   = "rmt0"
listen_addr     = "10.66.66.3"
port            = 8443
pair_port       = 8444
phone_addr      = "10.66.66.2"
clock_window_s  = {WINDOW_S}

[agent]
socket          = "{d}/agent.sock"
home            = "{d}/home"
claude_bin      = "{d}/bin/claude"
kitty_bin       = "/usr/bin/kitty"
hyprctl_bin     = "/usr/bin/hyprctl"
exec_bin        = "{d}/bin/remoter-exec"
workspace       = 9
max_sessions    = {MAX_SESSIONS}
# the hang scenario waits for this
ready_timeout_s = 20
cwd_deny        = [".ssh", ".gnupg", ".claude", ".config", ".local", ".password-store"]
search_skip     = ["node_modules", ".git", "target", "build", ".gradle", ".venv", ".cache"]
trusted_roots   = ["Projects"]
remoterd_user   = "{user}"
devices         = "{d}/etc/devices.json"
lock_dir        = "{d}/state"
runtime_dir     = "{r}"
notify_bin      = "{d}/bin/notify"

[remoterd]
devices          = "{d}/etc/devices.json"
server_key       = "{d}/etc/server.key"
server_cert      = "{d}/etc/server.crt"
state_dir        = "{d}/state"
agent_user       = "{user}"
power_supply_dir = "{d}/power"
admin_socket     = "{d}/admin.sock"

[attestation]
app_package     = "{pkg}"
app_cert_sha256 = "{digest}"
max_patch_age_months = 6
fresh_hours     = 24
"#,
        user = username(),
        r = runtime.display(),
        pkg = remoter_attest::testkit::PACKAGE,
        digest = b64::hex(&remoter_attest::testkit::APP_DIGEST),
    )
}

pub struct Lab {
    pub dir: PathBuf,
    pub agent_start: i64,
    pub pki: Pki,
    phone_ns: Child,
    remoterd: Option<Child>,
    rt: tokio::runtime::Runtime,
}

impl Lab {
    /// Only inside `World::run_inner`.
    pub fn start() -> Lab {
        Lab::start_on(false)
    }

    /// Like [`Lab::start`], but rmt0 is a real WireGuard link with the phone
    /// as its only peer, dialing in across a veth internet.
    pub fn start_direct() -> Lab {
        Lab::start_on(true)
    }

    fn start_on(direct: bool) -> Lab {
        let dir = PathBuf::from(std::env::var_os(ENV_WORLD).expect("run through World::run_inner"));
        let agent_start: i64 = std::env::var(ENV_AGENT_START).expect("agent start").parse().expect("ms");
        let phone_ns = Command::new("unshare").args(["-n", "--", "sleep", "600"]).stdin(Stdio::null()).spawn().expect("phone ns");
        std::thread::sleep(Duration::from_millis(100));
        let pid = phone_ns.id();
        sh("ip link set lo up");
        if direct {
            let k = dir.join("wgkeys");
            mkdir_mode(&k, 0o700);
            let d = k.display();
            sh(&format!("umask 077; for n in laptop phone; do wg genkey > {d}/$n; wg pubkey < {d}/$n > {d}/$n.pub; done; wg genpsk > {d}/psk"));
            sh(&format!("ip link add wan0 type veth peer name wan0 netns {pid}"));
            sh("ip addr add 198.51.100.1/24 dev wan0 && ip link set wan0 up");
            sh(&format!("ip link add rmt0 type wireguard && wg set rmt0 private-key {d}/laptop listen-port 47913 peer $(cat {d}/phone.pub) preshared-key {d}/psk allowed-ips 10.66.66.2/32"));
            sh("ip addr add 10.66.66.3/32 dev rmt0 && ip link set rmt0 up && ip route add 10.66.66.2/32 dev rmt0");
            sh(&format!(
                "nsenter -t {pid} -n -- sh -ec 'ip link set lo up; ip addr add 198.51.100.2/24 dev wan0; ip link set wan0 up; \
                 ip link add rmt0 type wireguard; wg set rmt0 private-key {d}/phone peer $(cat {d}/laptop.pub) preshared-key {d}/psk endpoint 198.51.100.1:47913 allowed-ips 10.66.66.3/32 persistent-keepalive 25; \
                 ip addr add 10.66.66.2/32 dev rmt0; ip link set rmt0 mtu 1280 up; ip route add 10.66.66.3/32 dev rmt0'"
            ));
        } else {
            sh(&format!("ip link add rmt0 type veth peer name ph0 netns {pid}"));
            sh("ip addr add 10.66.66.3/32 dev rmt0 && ip link set rmt0 up && ip route add 10.66.66.2/32 dev rmt0");
            sh(&format!(
                "nsenter -t {pid} -n -- sh -ec 'ip link set lo up; ip addr add 10.66.66.2/32 dev ph0; ip link set ph0 up; ip route add 10.66.66.3/32 dev ph0'"
            ));
        }
        let rt = tokio::runtime::Builder::new_multi_thread().enable_all().build().expect("rt");
        let mut lab = Lab { dir, agent_start, pki: Pki::new("testroot0000"), phone_ns, remoterd: None, rt };
        lab.build_root();
        lab.start_remoterd();
        lab.assert_real();
        lab
    }

    fn assert_real(&self) {
        let pid = self.remoterd_pid();
        let status = std::fs::read_to_string(format!("/proc/{pid}/status")).expect("status");
        for cap in ["CapEff", "CapPrm", "CapBnd", "CapAmb"] {
            let line = status.lines().find(|l| l.starts_with(cap)).expect("cap line");
            assert!(line.ends_with("0000000000000000"), "remoterd runs with no capabilities: {line}");
        }
        let root = std::fs::read_link(format!("/proc/{pid}/root")).expect("root");
        assert_eq!(root, self.dir.join("root"), "remoterd is chrooted");
        let log = std::fs::read_to_string(self.dir.join("remoterd.log")).unwrap_or_default();
        assert!(log.contains("remoter-e2e-test-build-marker: test knobs on"), "the e2e build: {log}");
        // a local connect would get in regardless (linux gives it the
        // address's device), so check the binding itself. weak_host.rs does the attack.
        let ss = Command::new("ss").args(["-Hltn"]).output().expect("ss");
        let ss = String::from_utf8_lossy(&ss.stdout);
        assert!(ss.contains("10.66.66.3%rmt0:8443"), "the API listener is bound to rmt0: {ss}");
        assert!(self.phone_tcp(API).is_ok(), "the phone reaches it over rmt0");
    }

    pub fn block_on<F: std::future::Future>(&self, f: F) -> F::Output {
        self.rt.block_on(f)
    }

    /// `/usr`, `/etc`, `/proc`, `/dev` and the world dir. No `/run`, so no journal or bus.
    fn build_root(&self) {
        let root = self.dir.join("root");
        mkdir_mode(&root, 0o755);
        for d in ["usr", "etc", "proc", "dev"] {
            mkdir_mode(&root.join(d), 0o755);
        }
        for (link, to) in [("bin", "usr/bin"), ("sbin", "usr/bin"), ("lib", "usr/lib"), ("lib64", "usr/lib")] {
            std::os::unix::fs::symlink(to, root.join(link)).expect("symlink");
        }
        let inside = root.join(self.dir.strip_prefix("/").expect("absolute"));
        // every level ours and 0755, so the trust check walks only our dirs
        let mut cur = root.clone();
        for c in self.dir.strip_prefix("/").expect("absolute").components() {
            cur = cur.join(c);
            mkdir_mode(&cur, 0o755);
        }
        for (from, to, rec) in [
            (PathBuf::from("/usr"), root.join("usr"), true),
            (PathBuf::from("/etc"), root.join("etc"), false),
            (PathBuf::from("/proc"), root.join("proc"), true),
            (PathBuf::from("/dev"), root.join("dev"), true),
            (self.dir.clone(), inside, false),
        ] {
            bind(&from, &to, rec);
        }
    }

    fn start_remoterd(&mut self) {
        let log = std::fs::File::create(self.dir.join("remoterd.log")).expect("log");
        let root = std::ffi::CString::new(self.dir.join("root").into_os_string().into_encoded_bytes()).expect("cstr");
        let mut cmd = Command::new("/usr/bin/setpriv");
        cmd.args(["--inh-caps=-all", "--ambient-caps=-all", "--bounding-set=-all", "--"])
            .arg(self.dir.join("bin/remoterd"))
            .arg("--config")
            .arg(self.dir.join("etc/config.toml"))
            .env_clear()
            .env("PATH", "/usr/bin")
            .env("REMOTER_E2E_TRUSTED_OWNER", uid().to_string())
            .env("REMOTER_E2E_ADMIN_UID", uid().to_string())
            .env("REMOTER_E2E_EXTRA_ROOT_PIN", b64::hex(&self.pki.root_pin()))
            .stdin(Stdio::null())
            .stdout(log.try_clone().expect("log"))
            .stderr(log);
        // SAFETY: only async-signal-safe calls between fork and exec.
        unsafe {
            cmd.pre_exec(move || {
                if libc::chroot(root.as_ptr()) != 0 || libc::chdir(c"/".as_ptr()) != 0 {
                    return Err(std::io::Error::last_os_error());
                }
                Ok(())
            });
        }
        self.remoterd = Some(cmd.spawn().expect("remoterd"));
        let deadline = Instant::now() + Duration::from_secs(15);
        loop {
            if let Some(Ok(Some(st))) = self.remoterd.as_mut().map(|c| c.try_wait()) {
                panic!("remoterd exited {st}:\n{}", std::fs::read_to_string(self.dir.join("remoterd.log")).unwrap_or_default());
            }
            if self.dir.join("admin.sock").exists() && self.phone_tcp(API).is_ok() {
                return;
            }
            assert!(Instant::now() < deadline, "remoterd never listened:\n{}", std::fs::read_to_string(self.dir.join("remoterd.log")).unwrap_or_default());
            std::thread::sleep(Duration::from_millis(100));
        }
    }

    pub fn remoterd_pid(&self) -> u32 {
        self.remoterd.as_ref().expect("running").id()
    }

    /// Connects from the phone's netns, so it really arrives on `rmt0`.
    pub fn phone_tcp(&self, to: SocketAddr) -> std::io::Result<std::net::TcpStream> {
        let ns = format!("/proc/{}/ns/net", self.phone_ns.id());
        std::thread::spawn(move || {
            let f = std::fs::File::open(ns)?;
            use std::os::fd::AsRawFd;
            // SAFETY: setns on an fd we just opened, in a throwaway thread.
            if unsafe { libc::setns(f.as_raw_fd(), libc::CLONE_NEWNET) } != 0 {
                return Err(std::io::Error::last_os_error());
            }
            std::net::TcpStream::connect_timeout(&to, Duration::from_secs(3))
        })
        .join()
        .expect("thread")
    }

    pub fn admin(&self, req: &AdminRequest) -> Result<serde_json::Value, String> {
        let mut s = std::os::unix::net::UnixStream::connect(self.dir.join("admin.sock")).map_err(|e| e.to_string())?;
        s.set_read_timeout(Some(Duration::from_secs(70))).map_err(|e| e.to_string())?;
        s.write_all(&serde_json::to_vec(req).expect("json")).map_err(|e| e.to_string())?;
        s.shutdown(std::net::Shutdown::Write).map_err(|e| e.to_string())?;
        let mut out = Vec::new();
        s.read_to_end(&mut out).map_err(|e| e.to_string())?;
        match serde_json::from_slice::<AdminReply>(&out) {
            Ok(AdminReply::Ok(v)) => Ok(v),
            Ok(AdminReply::Err(e)) => Err(e),
            Err(_) => Err(format!("no reply ({} bytes)", out.len())),
        }
    }

    /// `remoterctl refresh`, with the test root.
    pub fn push_roots(&self) {
        let roots = String::from_utf8(self.pki.roots_json()).expect("utf8");
        self.admin(&AdminRequest::SetAttestationData { roots, status: r#"{"entries":{}}"#.into() }).expect("attestation data");
    }

    pub fn wait_agent_window(&self) {
        let ready_at = self.agent_start + WINDOW_S * 1000 + 300;
        let left = ready_at - now();
        if left > 0 {
            std::thread::sleep(Duration::from_millis(left as u64));
        }
    }

    pub fn devices_file(&self) -> PathBuf {
        self.dir.join("etc/devices.json")
    }

    pub fn agent_state(&self) -> PathBuf {
        self.dir.join("home/.local/state/remoter")
    }

    /// Full pairing, both sides, code compared.
    pub fn pair(&self, name: &str) -> Phone {
        self.push_roots();
        let started: PairStarted = serde_json::from_value(self.admin(&AdminRequest::PairStart { name: name.into(), ttl_s: 60 }).expect("pair start")).expect("started");
        let link = pair::Link::parse(&started.link).expect("the link parses");
        assert_eq!(link.host, LAPTOP);
        let keys = PhoneKeys::new();
        let attempt = keys.pair_request(&self.pki, &link, name, &link.secret);
        let body = attempt.body.clone();
        let post = self.rt.spawn(post_pair(self.phone_tcp((link.host, link.pair_port).into()), link.clone(), body));
        let candidate = loop {
            let st: PairStatus = serde_json::from_value(self.admin(&AdminRequest::PairWait { wait_ms: 5000 }).expect("wait")).expect("status");
            match st {
                PairStatus::Waiting {} => continue,
                PairStatus::Received { candidate } => break candidate,
                other => panic!("pairing: {other:?}"),
            }
        };
        assert!(remoterctl::code_matches(&attempt.code, &candidate.code), "the laptop and the phone show the same code");
        assert_eq!((candidate.sig_level, candidate.tls_level), (2, 1), "sig StrongBox, tls TEE");
        let id = new_device_id();
        remoterctl::add_device(
            &self.devices_file(),
            DeviceRecord {
                id: id.clone(),
                name: candidate.name.clone(),
                tls_spki_sha256: candidate.tls_spki_sha256.clone(),
                sig_pub: candidate.sig_pub.clone(),
                verified_boot_key: candidate.verified_boot_key.clone(),
                attestation: candidate.attestation.clone(),
                paired_at: now(),
                weaknesses: candidate.weaknesses.clone(),
            },
        )
        .expect("device file");
        self.admin(&AdminRequest::PairConfirm { device_id: id.clone() }).expect("confirm");
        let (status, answer) = self.block_on(post).expect("join").expect("pair post");
        assert_eq!(status, 200, "{answer}");
        assert_eq!(answer["device_id"], id);
        Phone::from_keys(id, keys, attempt.tls_chain, link.server_fp)
    }

    pub async fn client(&self, phone: &Phone) -> Client {
        self.try_client(phone).await.expect("mTLS connect")
    }

    pub async fn try_client(&self, phone: &Phone) -> Result<Client, String> {
        let tcp = self.phone_tcp(API).map_err(|e| e.to_string())?;
        Client::connect(tcp, phone.tls_config()).await
    }
}

impl Drop for Lab {
    fn drop(&mut self) {
        if let Some(mut r) = self.remoterd.take() {
            let _ = r.kill();
            let _ = r.wait();
        }
        let _ = self.phone_ns.kill();
        let _ = self.phone_ns.wait();
    }
}

fn bind(from: &Path, to: &Path, recursive: bool) {
    let f = std::ffi::CString::new(from.as_os_str().as_encoded_bytes()).expect("cstr");
    let t = std::ffi::CString::new(to.as_os_str().as_encoded_bytes()).expect("cstr");
    let flags = libc::MS_BIND | if recursive { libc::MS_REC } else { 0 };
    // SAFETY: bind mount of two existing paths in our own mount ns.
    let r = unsafe { libc::mount(f.as_ptr(), t.as_ptr(), std::ptr::null(), flags, std::ptr::null()) };
    assert_eq!(r, 0, "bind {} -> {}: {}", from.display(), to.display(), std::io::Error::last_os_error());
}

fn new_device_id() -> String {
    // ULID, crockford
    const A: &[u8; 32] = b"0123456789ABCDEFGHJKMNPQRSTVWXYZ";
    let mut r = [0u8; 10];
    std::fs::File::open("/dev/urandom").and_then(|mut f| f.read_exact(&mut r)).expect("urandom");
    let v: u128 = ((now() as u128) << 80) | r.iter().fold(0u128, |a, b| (a << 8) | *b as u128);
    (0..26).rev().map(|i| A[((v >> (i * 5)) & 31) as usize] as char).collect()
}

pub struct PhoneKeys {
    pub tls: KeyPair,
    pub sig: KeyPair,
}

pub struct PairAttempt {
    pub body: serde_json::Value,
    pub code: String,
    pub tls_chain: Vec<Vec<u8>>,
}

fn leaf_spki(der: &[u8]) -> Vec<u8> {
    x509_parser::parse_x509_certificate(der).expect("cert").1.public_key().raw.to_vec()
}

impl PhoneKeys {
    pub fn new() -> PhoneKeys {
        PhoneKeys { tls: KeyPair::generate_for(&PKCS_ECDSA_P256_SHA256).expect("k"), sig: KeyPair::generate_for(&PKCS_ECDSA_P256_SHA256).expect("k") }
    }

    /// Body the app POSTs to 8444: both chains attest the challenge, MAC over the leaf SPKIs.
    pub fn pair_request(&self, pki: &Pki, link: &pair::Link, name: &str, secret: &[u8; 32]) -> PairAttempt {
        let tls_chain = pki.chain(&Spec::tls(&link.challenge), &self.tls, &ChainOpts::default());
        let sig_chain = pki.chain(&Spec::sig(&link.challenge), &self.sig, &ChainOpts::default());
        let (ts, ss) = (leaf_spki(&tls_chain[0]), leaf_spki(&sig_chain[0]));
        let t = pair::Transcript { server_fp: &link.server_fp, tls_spki: &ts, sig_spki: &ss, device_name: name };
        let body = serde_json::json!({
            "device_name": name,
            "tls_chain": tls_chain.iter().map(|c| b64::encode(c)).collect::<Vec<_>>(),
            "sig_chain": sig_chain.iter().map(|c| b64::encode(c)).collect::<Vec<_>>(),
            "mac": b64::encode(&pair::mac(secret, &t)),
        });
        PairAttempt { body, code: pair::confirmation_code(&link.secret, &t), tls_chain }
    }
}

impl Default for PhoneKeys {
    fn default() -> Self {
        PhoneKeys::new()
    }
}

/// No client cert here, server key pinned to the link's `fp`.
pub async fn post_pair(tcp: std::io::Result<std::net::TcpStream>, link: pair::Link, body: serde_json::Value) -> Result<(u16, serde_json::Value), String> {
    let tcp = tcp.map_err(|e| e.to_string())?;
    tcp.set_nonblocking(true).map_err(|e| e.to_string())?;
    let tcp = tokio::net::TcpStream::from_std(tcp).map_err(|e| e.to_string())?;
    let mut cfg = ClientConfig::builder_with_provider(Arc::new(rustls::crypto::aws_lc_rs::default_provider()))
        .with_protocol_versions(&[&rustls::version::TLS13])
        .map_err(|e| e.to_string())?
        .dangerous()
        .with_custom_certificate_verifier(Arc::new(PinServer(link.server_fp)))
        .with_no_client_auth();
    cfg.alpn_protocols = vec![b"h2".to_vec()];
    let tls = tokio_rustls::TlsConnector::from(Arc::new(cfg)).connect(ServerName::IpAddress(link.host.into()), tcp).await.map_err(|e| e.to_string())?;
    let (mut send, conn) = hyper::client::conn::http2::handshake(TokioExecutor::new(), TokioIo::new(tls)).await.map_err(|e| e.to_string())?;
    tokio::spawn(conn);
    let req = Request::builder()
        .method("POST")
        .uri(format!("https://{}/pair", link.host))
        .body(Body::from(serde_json::to_vec(&body).map_err(|e| e.to_string())?))
        .map_err(|e| e.to_string())?;
    let res = send.send_request(req).await.map_err(|e| e.to_string())?;
    let status = res.status().as_u16();
    let bytes = axum::body::to_bytes(Body::new(res.into_body()), 1 << 20).await.map_err(|e| e.to_string())?;
    Ok((status, serde_json::from_slice(&bytes).unwrap_or_default()))
}

pub struct Phone {
    pub id: String,
    pub sig: SigningKey,
    pub tls_chain: Vec<Vec<u8>>,
    tls_key: PrivatePkcs8KeyDer<'static>,
    pub server_fp: [u8; 32],
}

/// Exact bytes, so a retry can resend them unchanged.
#[derive(Debug, Clone)]
pub struct Signed {
    pub method: String,
    pub target: String,
    pub body: Vec<u8>,
    pub headers: Vec<(&'static str, String)>,
}

impl Phone {
    fn from_keys(id: String, keys: PhoneKeys, tls_chain: Vec<Vec<u8>>, server_fp: [u8; 32]) -> Phone {
        Phone {
            id,
            sig: SigningKey::from_pkcs8_der(&keys.sig.serialize_der()).expect("sig key"),
            tls_chain,
            tls_key: PrivatePkcs8KeyDer::from(keys.tls.serialize_der()),
            server_fp,
        }
    }

    pub fn sig_pub_b64(&self) -> String {
        b64::encode(self.sig.verifying_key().to_public_key_der().expect("spki").as_bytes())
    }

    fn tls_config(&self) -> Arc<ClientConfig> {
        let provider = rustls::crypto::aws_lc_rs::default_provider();
        let signer = provider.key_provider.load_private_key(PrivateKeyDer::Pkcs8(self.tls_key.clone_key())).expect("key");
        // whole attestation chain, like the keystore gives the app
        let chain = self.tls_chain.iter().map(|c| CertificateDer::from(c.clone())).collect();
        let ck = Arc::new(CertifiedKey::new(chain, signer));
        let mut cfg = ClientConfig::builder_with_provider(Arc::new(provider))
            .with_protocol_versions(&[&rustls::version::TLS13])
            .expect("versions")
            .dangerous()
            .with_custom_certificate_verifier(Arc::new(PinServer(self.server_fp)))
            .with_client_cert_resolver(Arc::new(FixedCert(ck)));
        cfg.alpn_protocols = vec![b"h2".to_vec()];
        Arc::new(cfg)
    }

    pub fn sign(&self, method: &str, target: &str, body: &[u8]) -> Signed {
        self.sign_at(method, target, body, now(), &random16())
    }

    pub fn sign_at(&self, method: &str, target: &str, body: &[u8], ts: i64, nonce: &[u8; 16]) -> Signed {
        self.sign_with(&self.sig, method, target, body, ts, nonce)
    }

    pub fn sign_with(&self, key: &SigningKey, method: &str, target: &str, body: &[u8], ts: i64, nonce: &[u8; 16]) -> Signed {
        let nonce = b64::encode(nonce);
        let canon = canonical(&SignInput { method, target, device: &self.id, timestamp_ms: ts, nonce: &nonce, body });
        let sig: Signature = key.sign(canon.as_bytes());
        Signed {
            method: method.into(),
            target: target.into(),
            body: body.to_vec(),
            headers: vec![
                ("remoter-device", self.id.clone()),
                ("remoter-timestamp", ts.to_string()),
                ("remoter-nonce", nonce),
                ("remoter-signature", b64::encode(sig.to_der().as_bytes())),
            ],
        }
    }
}

pub fn random16() -> [u8; 16] {
    let mut r = [0u8; 16];
    std::fs::File::open("/dev/urandom").and_then(|mut f| f.read_exact(&mut r)).expect("urandom");
    r
}

#[derive(Debug)]
struct PinServer([u8; 32]);

impl ServerCertVerifier for PinServer {
    fn verify_server_cert(&self, end: &CertificateDer<'_>, _: &[CertificateDer<'_>], _: &ServerName<'_>, _: &[u8], _: UnixTime) -> Result<ServerCertVerified, rustls::Error> {
        let spki = x509_parser::parse_x509_certificate(end).map_err(|_| rustls::Error::General("bad cert".into()))?.1.public_key().raw.to_vec();
        if <[u8; 32]>::from(Sha256::digest(&spki)) == self.0 {
            Ok(ServerCertVerified::assertion())
        } else {
            Err(rustls::Error::General("server key pin mismatch".into()))
        }
    }
    fn verify_tls12_signature(&self, _: &[u8], _: &CertificateDer<'_>, _: &DigitallySignedStruct) -> Result<HandshakeSignatureValid, rustls::Error> {
        Err(rustls::Error::General("TLS 1.2 is never used".into()))
    }
    fn verify_tls13_signature(&self, m: &[u8], c: &CertificateDer<'_>, d: &DigitallySignedStruct) -> Result<HandshakeSignatureValid, rustls::Error> {
        rustls::crypto::verify_tls13_signature(m, c, d, &rustls::crypto::aws_lc_rs::default_provider().signature_verification_algorithms)
    }
    fn supported_verify_schemes(&self) -> Vec<SignatureScheme> {
        rustls::crypto::aws_lc_rs::default_provider().signature_verification_algorithms.supported_schemes()
    }
}

#[derive(Debug)]
struct FixedCert(Arc<CertifiedKey>);

impl rustls::client::ResolvesClientCert for FixedCert {
    fn resolve(&self, _: &[&[u8]], _: &[SignatureScheme]) -> Option<Arc<CertifiedKey>> {
        Some(self.0.clone())
    }
    fn has_certs(&self) -> bool {
        true
    }
}

pub struct Client {
    sender: hyper::client::conn::http2::SendRequest<Body>,
}

pub struct Resp {
    pub status: StatusCode,
    pub headers: HeaderMap,
    pub body: Vec<u8>,
}

impl Resp {
    pub fn json(&self) -> serde_json::Value {
        serde_json::from_slice(&self.body).unwrap_or_else(|_| panic!("not json ({}): {:?}", self.status, String::from_utf8_lossy(&self.body)))
    }
    pub fn code(&self) -> String {
        self.json()["code"].as_str().unwrap_or("").to_owned()
    }
}

impl std::fmt::Debug for Resp {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        write!(f, "{} {}", self.status, String::from_utf8_lossy(&self.body))
    }
}

impl Client {
    async fn connect(tcp: std::net::TcpStream, cfg: Arc<ClientConfig>) -> Result<Client, String> {
        tcp.set_nonblocking(true).map_err(|e| e.to_string())?;
        let tcp = tokio::net::TcpStream::from_std(tcp).map_err(|e| e.to_string())?;
        let tls = tokio_rustls::TlsConnector::from(cfg).connect(ServerName::IpAddress(LAPTOP.into()), tcp).await.map_err(|e| e.to_string())?;
        let (mut sender, conn) = hyper::client::conn::http2::handshake(TokioExecutor::new(), TokioIo::new(tls)).await.map_err(|e| e.to_string())?;
        tokio::spawn(conn);
        // with TLS 1.3 a rejected client cert only shows up after the handshake
        sender.ready().await.map_err(|e| e.to_string())?;
        Ok(Client { sender })
    }

    pub async fn send(&mut self, method: &str, target: &str, headers: &[(&str, String)], body: Vec<u8>) -> Result<Resp, String> {
        let mut rb = Request::builder().method(method).uri(format!("https://10.66.66.3{target}"));
        for (k, v) in headers {
            rb = rb.header(*k, v);
        }
        let req = rb.body(Body::from(body)).map_err(|e| e.to_string())?;
        self.sender.ready().await.map_err(|e| e.to_string())?;
        let res = self.sender.send_request(req).await.map_err(|e| e.to_string())?;
        let status = res.status();
        let headers = res.headers().clone();
        let body = axum::body::to_bytes(Body::new(res.into_body()), 1 << 20).await.map_err(|e| e.to_string())?.to_vec();
        Ok(Resp { status, headers, body })
    }

    pub async fn get(&mut self, target: &str) -> Resp {
        self.send("GET", target, &[], vec![]).await.expect("request")
    }

    pub async fn get_with(&mut self, target: &str, headers: &[(&str, String)]) -> Resp {
        self.send("GET", target, headers, vec![]).await.expect("request")
    }

    pub async fn signed(&mut self, s: &Signed) -> Resp {
        let hs: Vec<(&str, String)> = s.headers.iter().map(|(k, v)| (*k, v.clone())).collect();
        self.send(&s.method, &s.target, &hs, s.body.clone()).await.expect("request")
    }

    pub async fn stream(&mut self, target: &str, headers: &[(&str, String)]) -> (StatusCode, Sse) {
        let mut rb = Request::builder().method("GET").uri(format!("https://10.66.66.3{target}"));
        for (k, v) in headers {
            rb = rb.header(*k, v);
        }
        self.sender.ready().await.expect("ready");
        let res = self.sender.send_request(rb.body(Body::empty()).expect("req")).await.expect("send");
        let status = res.status();
        let (tx, rx) = mpsc::unbounded_channel();
        tokio::spawn(async move {
            use futures_util::StreamExt;
            let mut s = Body::new(res.into_body()).into_data_stream();
            while let Some(chunk) = s.next().await {
                match chunk {
                    Ok(b) => {
                        if tx.send(Some(String::from_utf8_lossy(&b).into_owned())).is_err() {
                            return;
                        }
                    }
                    Err(_) => break,
                }
            }
            let _ = tx.send(None);
        });
        (status, Sse { rx, buf: String::new(), ended: false })
    }
}

#[derive(Debug, Clone)]
pub struct SseEvent {
    pub event: String,
    pub data: serde_json::Value,
    pub id: Option<u64>,
}

pub struct Sse {
    rx: mpsc::UnboundedReceiver<Option<String>>,
    buf: String,
    pub ended: bool,
}

impl Sse {
    /// Skips pings. `None` on end of stream or timeout.
    pub async fn next(&mut self, timeout: Duration) -> Option<SseEvent> {
        let deadline = tokio::time::Instant::now() + timeout;
        loop {
            if let Some(i) = self.buf.find("\n\n") {
                let block: String = self.buf.drain(..i + 2).collect();
                let (mut event, mut data, mut id) = (String::new(), String::new(), None);
                for line in block.lines() {
                    if let Some(v) = line.strip_prefix("event:") {
                        event = v.trim().to_owned();
                    } else if let Some(v) = line.strip_prefix("data:") {
                        data.push_str(v.trim_start());
                    } else if let Some(v) = line.strip_prefix("id:") {
                        id = v.trim().parse().ok();
                    }
                }
                if event.is_empty() {
                    continue;
                }
                return Some(SseEvent { event, data: serde_json::from_str(&data).unwrap_or(serde_json::Value::Null), id });
            }
            if self.ended {
                return None;
            }
            match tokio::time::timeout_at(deadline, self.rx.recv()).await {
                Ok(Some(Some(chunk))) => self.buf.push_str(&chunk),
                Ok(Some(None)) | Ok(None) => self.ended = true,
                Err(_) => return None,
            }
        }
    }

    /// Returns the match and everything before it.
    pub async fn until(&mut self, timeout: Duration, pred: impl Fn(&SseEvent) -> bool) -> (Option<SseEvent>, Vec<SseEvent>) {
        let deadline = tokio::time::Instant::now() + timeout;
        let mut seen = Vec::new();
        loop {
            let left = deadline.saturating_duration_since(tokio::time::Instant::now());
            match self.next(left).await {
                Some(e) if pred(&e) => return (Some(e), seen),
                Some(e) => seen.push(e),
                None => return (None, seen),
            }
        }
    }

    pub async fn closes_within(&mut self, timeout: Duration) -> bool {
        let deadline = tokio::time::Instant::now() + timeout;
        loop {
            let left = deadline.saturating_duration_since(tokio::time::Instant::now());
            if left.is_zero() {
                return self.ended;
            }
            if self.next(left).await.is_none() {
                return self.ended;
            }
        }
    }
}

pub fn mode_of(p: &Path) -> (u32, u32) {
    let m = std::fs::metadata(p).unwrap_or_else(|e| panic!("{}: {e}", p.display()));
    (m.uid(), m.mode() & 0o7777)
}
