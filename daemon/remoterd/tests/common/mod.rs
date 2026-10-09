//! Real remoterd in process on `lo`, a fake agent, and a software phone.
#![allow(dead_code)]

use std::io::{Read, Write};
use std::net::SocketAddr;
use std::os::unix::net::UnixListener;
use std::path::PathBuf;
use std::sync::atomic::{AtomicBool, AtomicU64, AtomicUsize, Ordering};
use std::sync::{Arc, Mutex};
use std::time::Duration;

use axum::body::Body;
use hyper::http::{HeaderMap, Request, StatusCode};
use hyper_util::rt::{TokioExecutor, TokioIo};
use remoter_auth::testkit::{Phone, device_file};
use remoter_auth::{Devices, RawSigHeaders, Verifier};
use remoter_proto::ipc::{AgentFailure, AgentReply, AgentRequest, MutateReply};
use remoter_proto::{ErrorCode, local};
use remoterd::agent::AgentClient;
use remoterd::audit::{Audit, ROTATE_BYTES};
use remoterd::state::{App, Settings};
use rustls::client::danger::{HandshakeSignatureValid, ServerCertVerified, ServerCertVerifier};
use rustls::pki_types::{CertificateDer, PrivateKeyDer, PrivatePkcs8KeyDer, ServerName, UnixTime};
use rustls::sign::CertifiedKey;
use rustls::{ClientConfig, DigitallySignedStruct, SignatureScheme};
use sha2::{Digest, Sha256};
use tokio::sync::{mpsc, watch};

pub const A: &str = "01K6B7Y3M4N5P6Q7R8S9T0V1W2";
pub const B: &str = "01K6B7Y3M4N5P6Q7R8S9T0V1W3";

pub struct TestPhone {
    pub phone: Phone,
    pub cert: CertificateDer<'static>,
    pub key: PrivatePkcs8KeyDer<'static>,
}

pub fn make_phone(id: &str, seed: u8) -> TestPhone {
    let kp = rcgen::KeyPair::generate_for(&rcgen::PKCS_ECDSA_P256_SHA256).expect("key");
    let cert = rcgen::CertificateParams::new(vec!["phone".into()]).expect("params").self_signed(&kp).expect("cert");
    TestPhone {
        phone: Phone::new(id, seed, rcgen::PublicKeyData::subject_public_key_info(&kp)),
        cert: cert.der().clone(),
        key: PrivatePkcs8KeyDer::from(kp.serialize_der()),
    }
}

#[derive(Default)]
pub struct FakeAgent {
    pub mutations: AtomicUsize,
    pub tails: AtomicUsize,
    pub down: AtomicBool,
    pub seen: Mutex<Vec<AgentRequest>>,
    pub live_seq: AtomicU64,
    pub live_sessions: Mutex<Vec<serde_json::Value>>,
    pub lives: AtomicUsize,
}

impl FakeAgent {
    pub fn set_live(&self, sessions: Vec<serde_json::Value>) {
        *self.live_sessions.lock().expect("lock") = sessions;
        self.live_seq.fetch_add(1, Ordering::SeqCst);
    }
}

pub fn summary_json(id: &str, state: &str) -> serde_json::Value {
    serde_json::json!({
        "id": id, "name": "x", "path": "Projects/x", "device": null, "started": 1,
        "state": state, "reason": null, "exit_code": null, "claude": null,
    })
}

pub fn resources_json() -> serde_json::Value {
    serde_json::json!({
        "cpu_pct": 12.5, "cores": 8, "mem_total": 16, "mem_available": 4,
        "swap_total": 0, "swap_free": 0, "disk_total": 100, "disk_free": 40,
    })
}

impl FakeAgent {
    fn answer(&self, req: AgentRequest) -> AgentReply {
        self.seen.lock().expect("lock").push(req.clone());
        let ok = |v: serde_json::Value| AgentReply::Ok(v);
        let err = |code| AgentReply::Err(AgentFailure { code, message: "fake".into() });
        match req {
            AgentRequest::Status {} => ok(serde_json::json!({ "sessions": 1 })),
            AgentRequest::Sessions {} => ok(serde_json::json!({ "sessions": [], "cap": 8 })),
            AgentRequest::List { path, hidden } => ok(serde_json::json!({ "echo_path": path, "hidden": hidden })),
            AgentRequest::Search { path, q } => ok(serde_json::json!({ "echo_path": path, "q": q })),
            AgentRequest::Recent {} => ok(serde_json::json!({ "entries": [], "typical_start_ms": null })),
            AgentRequest::Session { id } => ok(serde_json::json!({ "id": id, "tail": ["x"] })),
            AgentRequest::History { path } => ok(serde_json::json!({ "echo_path": path })),
            AgentRequest::Tail { id } if id == "rc-quiet" => ok(serde_json::json!({ "lines": ["idle"], "at": 1 })),
            AgentRequest::Tail { .. } => {
                let n = self.tails.fetch_add(1, Ordering::SeqCst);
                ok(serde_json::json!({ "lines": [format!("tick {}", n / 2)], "at": 1 }))
            }
            AgentRequest::Events { id, after, wait_ms } => {
                if id == "rc-missing" {
                    return err(ErrorCode::NotFound);
                }
                if after < 101 {
                    let all = [
                        serde_json::json!({ "seq": 100, "event": { "event": "phase", "data": { "step": "accepted", "at": 1 } } }),
                        serde_json::json!({ "seq": 101, "event": { "event": "state", "data": { "state": "ready", "reason": null, "exit_code": null } } }),
                    ];
                    let events: Vec<_> = all.into_iter().filter(|e| e["seq"].as_u64() > Some(after)).collect();
                    return ok(serde_json::json!({ "events": events, "gone": false }));
                }
                std::thread::sleep(Duration::from_millis((wait_ms as u64).min(300)));
                ok(serde_json::json!({ "events": [], "gone": id == "rc-gone" }))
            }
            AgentRequest::Live { after, wait_ms } => {
                self.lives.fetch_add(1, Ordering::SeqCst);
                // +1: the real agent never starts at 0, so the first call returns at once
                let seq = || self.live_seq.load(Ordering::SeqCst) + 1;
                let deadline = std::time::Instant::now() + Duration::from_millis(wait_ms as u64);
                while seq() <= after && std::time::Instant::now() < deadline {
                    std::thread::sleep(Duration::from_millis(20));
                }
                let seq = seq();
                ok(serde_json::json!({ "seq": seq, "sessions": *self.live_sessions.lock().expect("lock") }))
            }
            AgentRequest::Resources {} => ok(resources_json()),
            AgentRequest::Procs {} => ok(serde_json::json!({ "resources": resources_json(), "procs": [], "truncated": false })),
            AgentRequest::Mutate { .. } => {
                let n = self.mutations.fetch_add(1, Ordering::SeqCst) + 1;
                ok(serde_json::to_value(MutateReply { status: 202, body: format!("{{\"n\":{n}}}"), audit_path: Some("Projects/canonical".into()) }).expect("json"))
            }
        }
    }
}

pub fn start_fake_agent(socket: &std::path::Path, agent: Arc<FakeAgent>) {
    let l = UnixListener::bind(socket).expect("bind agent");
    std::thread::spawn(move || {
        for s in l.incoming() {
            let Ok(mut s) = s else { continue };
            let agent = agent.clone();
            std::thread::spawn(move || {
                let mut buf = Vec::new();
                if s.read_to_end(&mut buf).is_err() || agent.down.load(Ordering::SeqCst) {
                    return;
                }
                let reply = match serde_json::from_slice(&buf) {
                    Ok(r) => agent.answer(r),
                    Err(_) => AgentReply::Err(AgentFailure { code: ErrorCode::BadRequest, message: "fake".into() }),
                };
                // a held long poll dies with the agent too
                if agent.down.load(Ordering::SeqCst) {
                    return;
                }
                let _ = s.write_all(&serde_json::to_vec(&reply).expect("json"));
            });
        }
    });
}

pub struct Harness {
    pub addr: SocketAddr,
    pub app: Arc<App>,
    pub dir: PathBuf,
    pub server_spki_sha256: [u8; 32],
    pub a: TestPhone,
    pub b: TestPhone,
    pub agent: Arc<FakeAgent>,
    pub pki: remoter_attest::testkit::Pki,
}

impl Drop for Harness {
    fn drop(&mut self) {
        let _ = std::fs::remove_dir_all(&self.dir);
    }
}

pub fn uid() -> u32 {
    // SAFETY: getuid has no preconditions.
    unsafe { libc::getuid() }
}

pub async fn start() -> Harness {
    static N: AtomicUsize = AtomicUsize::new(0);
    let dir = PathBuf::from(env!("CARGO_TARGET_TMPDIR")).join(format!("rd-{}-{}", std::process::id(), N.fetch_add(1, Ordering::Relaxed)));
    let _ = std::fs::remove_dir_all(&dir);
    std::fs::create_dir_all(dir.join("state")).expect("mk");
    let a = make_phone(A, 1);
    let b = make_phone(B, 2);
    std::fs::write(dir.join("devices.json"), device_file(&[&a.phone, &b.phone])).expect("devices");

    let server_kp = rcgen::KeyPair::generate_for(&rcgen::PKCS_ECDSA_P256_SHA256).expect("key");
    let server_cert = rcgen::CertificateParams::new(vec!["remoterd".into()]).expect("p").self_signed(&server_kp).expect("cert");
    let server_spki_sha256: [u8; 32] = Sha256::digest(rcgen::PublicKeyData::subject_public_key_info(&server_kp)).into();

    let agent = Arc::new(FakeAgent::default());
    let socket = dir.join("agent.sock");
    start_fake_agent(&socket, agent.clone());

    let devices = Devices::load(&dir.join("devices.json")).expect("load");
    let (tx, rx) = watch::channel(Arc::new(devices));
    let tls = remoterd::tls::server_config(
        rx,
        vec![server_cert.der().clone()],
        PrivateKeyDer::Pkcs8(PrivatePkcs8KeyDer::from(server_kp.serialize_der())),
    )
    .expect("tls");
    let pairing_tls = remoterd::tls::pairing_config(vec![server_cert.der().clone()], PrivateKeyDer::Pkcs8(PrivatePkcs8KeyDer::from(server_kp.serialize_der()))).expect("pair tls");
    let pki = remoter_attest::testkit::Pki::new("testroot0000");
    let mut attest = remoterd::attest::AttestState::open(&dir.join("state"), 7, vec![pki.root_pin()]);
    attest.set_data(std::str::from_utf8(&pki.roots_json()).expect("utf8"), r#"{"entries":{}}"#).expect("data");
    for id in [A, B] {
        attest.mark_fresh(id, local::now_ms() + 86_400_000);
    }
    let power = dir.join("power");
    std::fs::create_dir_all(power.join("BAT0")).expect("mk");
    std::fs::write(power.join("BAT0/type"), "Battery\n").expect("w");
    std::fs::write(power.join("BAT0/capacity"), "12\n").expect("w");
    std::fs::create_dir_all(power.join("AC")).expect("mk");
    std::fs::write(power.join("AC/type"), "Mains\n").expect("w");
    std::fs::write(power.join("AC/online"), "0\n").expect("w");
    let app = Arc::new(App {
        attest: Mutex::new(attest),
        policy: remoter_attest::Policy {
            app_package: remoter_attest::testkit::PACKAGE.into(),
            app_cert_sha256: remoter_attest::testkit::APP_DIGEST,
            max_patch_age_months: 6,
            unlocked_device_required_in: remoter_attest::UnlockedList::Hardware,
        },
        fresh_ms: 86_400_000,
        pair: Mutex::default(),
        pairing: remoterd::state::Pairing {
            device: "lo".into(),
            addr: "127.0.0.1:0".parse().expect("addr"),
            public_host: std::net::Ipv4Addr::LOCALHOST,
            api_port: 0,
            server_fp: server_spki_sha256,
            tls: Arc::new(pairing_tls),
        },
        settings: Settings {
            devices_file: dir.join("devices.json"),
            unpaired_file: dir.join("state/unpaired.json"),
            locked_flag: dir.join("state/locked"),
            power_supply_dir: power,
            hostname: "r1v3r".into(),
            clock_window_ms: 30_000,
        },
        devices: tx,
        verifier: Verifier::new(30_000, None, 4096),
        limits: Mutex::default(),
        autolock: Mutex::default(),
        locked: AtomicBool::new(false),
        audit: Mutex::new(Audit::open(dir.join("state/audit.jsonl"), ROTATE_BYTES).expect("audit")),
        agent: AgentClient { socket, agent_uid: uid() },
        tails: Mutex::default(),
        handshake_log: Mutex::default(),
    });
    let std_listener = remoterd::listener::bind("lo", "127.0.0.1:0".parse().expect("addr")).expect("bind lo");
    let addr = std_listener.local_addr().expect("addr");
    let listener = tokio::net::TcpListener::from_std(std_listener).expect("tokio");
    let (conn_tx, conn_rx) = mpsc::channel(64);
    tokio::spawn(async move {
        while let Ok(pair) = listener.accept().await {
            if conn_tx.send(pair).await.is_err() {
                return;
            }
        }
    });
    tokio::spawn(remoterd::serve::serve(app.clone(), Arc::new(tls), conn_rx));
    Harness { addr, app, dir, server_spki_sha256, a, b, agent, pki }
}

#[derive(Debug)]
pub struct PinServer(pub [u8; 32]);

impl ServerCertVerifier for PinServer {
    fn verify_server_cert(&self, end: &CertificateDer<'_>, _: &[CertificateDer<'_>], _: &ServerName<'_>, _: &[u8], _: UnixTime) -> Result<ServerCertVerified, rustls::Error> {
        if remoterd::tls::spki_sha256(end) == Some(self.0) {
            Ok(ServerCertVerified::assertion())
        } else {
            Err(rustls::Error::General("server key pin mismatch".into()))
        }
    }
    fn verify_tls12_signature(&self, m: &[u8], c: &CertificateDer<'_>, d: &DigitallySignedStruct) -> Result<HandshakeSignatureValid, rustls::Error> {
        rustls::crypto::verify_tls12_signature(m, c, d, &rustls::crypto::aws_lc_rs::default_provider().signature_verification_algorithms)
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

/// Someone else's `cert` with your own `key` is the replayed cert attack.
pub fn client_config(
    server_pin: [u8; 32],
    cert: &CertificateDer<'static>,
    key: &PrivatePkcs8KeyDer<'static>,
    versions: &[&'static rustls::SupportedProtocolVersion],
    provider: rustls::crypto::CryptoProvider,
) -> Arc<ClientConfig> {
    let signer = provider.key_provider.load_private_key(PrivateKeyDer::Pkcs8(key.clone_key())).expect("key");
    let ck = Arc::new(CertifiedKey::new(vec![cert.clone()], signer));
    let mut cfg = ClientConfig::builder_with_provider(Arc::new(provider))
        .with_protocol_versions(versions)
        .expect("versions")
        .dangerous()
        .with_custom_certificate_verifier(Arc::new(PinServer(server_pin)))
        .with_client_cert_resolver(Arc::new(FixedCert(ck)));
    cfg.alpn_protocols = vec![b"h2".to_vec()];
    Arc::new(cfg)
}

impl Harness {
    pub fn config_for(&self, p: &TestPhone) -> Arc<ClientConfig> {
        client_config(self.server_spki_sha256, &p.cert, &p.key, &[&rustls::version::TLS13], rustls::crypto::aws_lc_rs::default_provider())
    }

    pub async fn client(&self, p: &TestPhone) -> Client {
        Client::connect(self.addr, self.config_for(p)).await.expect("connect")
    }

    pub fn write_devices(&self, phones: &[&TestPhone]) {
        let v: Vec<&Phone> = phones.iter().map(|p| &p.phone).collect();
        std::fs::write(self.dir.join("devices.json"), device_file(&v)).expect("devices");
    }
}

pub struct Client {
    sender: hyper::client::conn::http2::SendRequest<Body>,
    pub kind: Option<rustls::HandshakeKind>,
    pub suite: Option<rustls::SupportedCipherSuite>,
    pub group: Option<rustls::NamedGroup>,
}

pub struct Resp {
    pub status: StatusCode,
    pub headers: HeaderMap,
    pub body: Vec<u8>,
}

impl Resp {
    pub fn json(&self) -> serde_json::Value {
        serde_json::from_slice(&self.body).unwrap_or_else(|_| panic!("not json: {:?}", String::from_utf8_lossy(&self.body)))
    }
    pub fn code(&self) -> String {
        self.json()["code"].as_str().unwrap_or("").to_owned()
    }
}

impl Client {
    pub async fn connect(addr: SocketAddr, cfg: Arc<ClientConfig>) -> Result<Client, String> {
        let tcp = tokio::net::TcpStream::connect(addr).await.map_err(|e| e.to_string())?;
        let name = ServerName::IpAddress(addr.ip().into());
        let tls = tokio_rustls::TlsConnector::from(cfg).connect(name, tcp).await.map_err(|e| e.to_string())?;
        let (kind, suite, group) = {
            let c = tls.get_ref().1;
            (c.handshake_kind(), c.negotiated_cipher_suite(), c.negotiated_key_exchange_group().map(|g| g.name()))
        };
        let (sender, conn) = hyper::client::conn::http2::handshake(TokioExecutor::new(), TokioIo::new(tls)).await.map_err(|e| e.to_string())?;
        tokio::spawn(conn);
        Ok(Client { sender, kind, suite, group })
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

    pub async fn signed(&mut self, method: &str, target: &str, h: &RawSigHeaders, body: &[u8]) -> Resp {
        let mut hs = Vec::new();
        for (k, v) in [("remoter-device", &h.device), ("remoter-timestamp", &h.timestamp), ("remoter-nonce", &h.nonce), ("remoter-signature", &h.signature)] {
            if let Some(v) = v {
                hs.push((k, v.clone()));
            }
        }
        self.send(method, target, &hs, body.to_vec()).await.expect("request")
    }

    /// Raw body chunks.
    pub async fn stream(&mut self, target: &str, headers: &[(&str, String)]) -> (StatusCode, mpsc::UnboundedReceiver<Result<String, String>>) {
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
                let item = chunk.map(|b| String::from_utf8_lossy(&b).into_owned()).map_err(|e| e.to_string());
                let stop = item.is_err();
                if tx.send(item).is_err() || stop {
                    return;
                }
            }
            let _ = tx.send(Err("stream ended".into()));
        });
        (status, rx)
    }
}

pub fn now() -> i64 {
    local::now_ms()
}
