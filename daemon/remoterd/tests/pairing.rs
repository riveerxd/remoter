//! Pairing and daily re-attestation. The test plays both the phone and remoterctl.

mod common;

use std::sync::Arc;
use std::time::Duration;

use axum::body::Body;
use common::*;
use hyper::http::Request;
use hyper_util::rt::{TokioExecutor, TokioIo};
use rcgen::{KeyPair, PKCS_ECDSA_P256_SHA256};
use remoter_attest::testkit::{ChainOpts, Spec};
use remoter_auth::devices::{DeviceFile, DeviceRecord};
use remoter_proto::admin::{AdminReply, AdminRequest, PairCandidate, PairStarted, PairStatus};
use remoter_proto::{b64, pair};
use rustls::ClientConfig;
use rustls::pki_types::ServerName;
use tokio::io::{AsyncReadExt, AsyncWriteExt};

async fn admin(sock: &std::path::Path, req: &AdminRequest) -> Result<serde_json::Value, String> {
    let mut s = tokio::net::UnixStream::connect(sock).await.map_err(|e| e.to_string())?;
    s.write_all(&serde_json::to_vec(req).expect("json")).await.map_err(|e| e.to_string())?;
    s.shutdown().await.map_err(|e| e.to_string())?;
    let mut out = Vec::new();
    s.read_to_end(&mut out).await.map_err(|e| e.to_string())?;
    match serde_json::from_slice::<AdminReply>(&out) {
        Ok(AdminReply::Ok(v)) => Ok(v),
        Ok(AdminReply::Err(e)) => Err(e),
        Err(_) => Err(format!("no reply ({} bytes)", out.len())),
    }
}

async fn start_admin(h: &Harness, uid: u32) -> std::path::PathBuf {
    let sock = h.dir.join("admin.sock");
    let l = remoterd::admin::bind(&sock).expect("bind admin");
    tokio::spawn(remoterd::admin::serve(h.app.clone(), l, uid));
    sock
}

struct PhoneKeys {
    tls: KeyPair,
    sig: KeyPair,
}

fn keys() -> PhoneKeys {
    PhoneKeys {
        tls: KeyPair::generate_for(&PKCS_ECDSA_P256_SHA256).expect("k"),
        sig: KeyPair::generate_for(&PKCS_ECDSA_P256_SHA256).expect("k"),
    }
}

fn spki(der: &[u8]) -> Vec<u8> {
    x509_parser::parse_x509_certificate(der).expect("cert").1.public_key().raw.to_vec()
}

/// (body, code the phone would show, tls chain)
fn phone_request(h: &Harness, k: &PhoneKeys, link: &pair::Link, name: &str, secret: &[u8; 32], tls_spec: Spec, sig_spec: Spec) -> (serde_json::Value, String, Vec<Vec<u8>>) {
    let tls_chain = h.pki.chain(&tls_spec, &k.tls, &ChainOpts::default());
    let sig_chain = h.pki.chain(&sig_spec, &k.sig, &ChainOpts::default());
    let (ts, ss) = (spki(&tls_chain[0]), spki(&sig_chain[0]));
    let t = pair::Transcript { server_fp: &link.server_fp, tls_spki: &ts, sig_spki: &ss, device_name: name };
    let body = serde_json::json!({
        "device_name": name,
        "tls_chain": tls_chain.iter().map(|c| b64::encode(c)).collect::<Vec<_>>(),
        "sig_chain": sig_chain.iter().map(|c| b64::encode(c)).collect::<Vec<_>>(),
        "mac": b64::encode(&pair::mac(secret, &t)),
    });
    (body, pair::confirmation_code(&link.secret, &t), tls_chain)
}

/// No client cert, server key pinned to the link's `fp`.
async fn post_pair(link: &pair::Link, body: &serde_json::Value) -> Result<(u16, serde_json::Value), String> {
    let mut cfg = ClientConfig::builder_with_provider(Arc::new(rustls::crypto::aws_lc_rs::default_provider()))
        .with_protocol_versions(&[&rustls::version::TLS13])
        .expect("v")
        .dangerous()
        .with_custom_certificate_verifier(Arc::new(PinServer(link.server_fp)))
        .with_no_client_auth();
    cfg.alpn_protocols = vec![b"h2".to_vec()];
    let tcp = tokio::net::TcpStream::connect((link.host, link.pair_port)).await.map_err(|e| e.to_string())?;
    let tls = tokio_rustls::TlsConnector::from(Arc::new(cfg)).connect(ServerName::IpAddress(link.host.into()), tcp).await.map_err(|e| e.to_string())?;
    let (mut send, conn) = hyper::client::conn::http2::handshake(TokioExecutor::new(), TokioIo::new(tls)).await.map_err(|e| e.to_string())?;
    tokio::spawn(conn);
    let req = Request::builder().method("POST").uri(format!("https://{}/pair", link.host)).body(Body::from(serde_json::to_vec(body).expect("j"))).expect("req");
    let res = send.send_request(req).await.map_err(|e| e.to_string())?;
    let status = res.status().as_u16();
    let bytes = axum::body::to_bytes(Body::new(res.into_body()), 1 << 20).await.map_err(|e| e.to_string())?;
    Ok((status, serde_json::from_slice(&bytes).unwrap_or_default()))
}

async fn open_window(sock: &std::path::Path, ttl_s: u32) -> pair::Link {
    let started: PairStarted = serde_json::from_value(admin(sock, &AdminRequest::PairStart { name: "S25 Ultra".into(), ttl_s }).await.expect("start")).expect("started");
    pair::Link::parse(&started.link).expect("link parses")
}

async fn wait_status(sock: &std::path::Path) -> PairStatus {
    serde_json::from_value(admin(sock, &AdminRequest::PairWait { wait_ms: 5000 }).await.expect("wait")).expect("status")
}

fn add_device(h: &Harness, c: &PairCandidate, id: &str) {
    let mut f: DeviceFile = serde_json::from_slice(&std::fs::read(h.dir.join("devices.json")).expect("read")).expect("parse");
    f.devices.push(DeviceRecord {
        id: id.into(),
        name: c.name.clone(),
        tls_spki_sha256: c.tls_spki_sha256.clone(),
        sig_pub: c.sig_pub.clone(),
        verified_boot_key: c.verified_boot_key.clone(),
        attestation: c.attestation.clone(),
        paired_at: 1,
    });
    std::fs::write(h.dir.join("devices.json"), serde_json::to_vec(&f).expect("j")).expect("w");
}

const NEW: &str = "01K6B7Y3M4N5P6Q7R8S9T0V1WA";

#[tokio::test]
async fn pairs_end_to_end_then_uses_the_paired_keys() {
    let h = start().await;
    let sock = start_admin(&h, uid()).await;
    let link = open_window(&sock, 60).await;
    assert_eq!(link.server_fp, h.server_spki_sha256, "the link pins the real server key");
    let k = keys();
    let (body, phone_code, tls_chain) = phone_request(&h, &k, &link, "S25 Ultra", &link.secret, Spec::tls(&link.challenge), Spec::sig(&link.challenge));
    let phone = tokio::spawn({
        let link = link.clone();
        async move { post_pair(&link, &body).await }
    });
    let PairStatus::Received { candidate } = wait_status(&sock).await else { panic!("no attempt received") };
    assert_eq!(candidate.code, phone_code, "laptop and phone agree on the code");
    assert_eq!((candidate.sig_level, candidate.tls_level), (2, 1));
    assert_eq!(candidate.boot_key_prefix, "a1a1a1a1");
    assert_eq!(candidate.model.as_deref(), Some("SM-S938B"));
    add_device(&h, &candidate, NEW);
    admin(&sock, &AdminRequest::PairConfirm { device_id: NEW.into() }).await.expect("confirm");
    let (status, answer) = phone.await.expect("join").expect("post");
    assert_eq!(status, 200, "{answer}");
    assert_eq!(answer["device_id"], NEW);
    assert_eq!(answer["hostname"], "r1v3r");

    // the attestation leaf is the client cert from here on
    let cert = rustls::pki_types::CertificateDer::from(tls_chain[0].clone());
    let key = rustls::pki_types::PrivatePkcs8KeyDer::from(k.tls.serialize_der());
    let cfg = client_config(h.server_spki_sha256, &cert, &key, &[&rustls::version::TLS13], rustls::crypto::aws_lc_rs::default_provider());
    let mut c = Client::connect(h.addr, cfg).await.expect("mTLS with the paired key");
    let health = c.get("/v1/health").await.json();
    assert!(health["fresh_until"].as_i64().is_some_and(|t| t > now()), "pairing counts as today's attestation");

    use p256::ecdsa::signature::Signer;
    use p256::pkcs8::DecodePrivateKey;
    let sig_key = p256::ecdsa::SigningKey::from_pkcs8_der(&k.sig.serialize_der()).expect("sig key");
    let target = "/v1/fs/mkdir";
    let b = br#"{"parent":"Projects","name":"x","git_init":false}"#;
    let (ts, nonce) = (now(), b64::encode(&[9u8; 16]));
    let canon = remoter_proto::canonical::canonical(&remoter_proto::canonical::SignInput { method: "POST", target, device: NEW, timestamp_ms: ts, nonce: &nonce, body: b });
    let sig: p256::ecdsa::Signature = sig_key.sign(canon.as_bytes());
    let hdr = remoter_auth::RawSigHeaders { device: Some(NEW.into()), timestamp: Some(ts.to_string()), nonce: Some(nonce), signature: Some(b64::encode(sig.to_der().as_bytes())) };
    assert_eq!(c.signed("POST", target, &hdr, b).await.status, 202, "the attested sig key signs mutations");
}

#[tokio::test]
async fn one_attempt_only_and_the_port_closes() {
    let h = start().await;
    let sock = start_admin(&h, uid()).await;
    let link = open_window(&sock, 60).await;
    let k = keys();
    let wrong_secret = [0u8; 32];
    let (bad, _, _) = phone_request(&h, &k, &link, "S25 Ultra", &wrong_secret, Spec::tls(&link.challenge), Spec::sig(&link.challenge));
    let (status, _) = post_pair(&link, &bad).await.expect("first");
    assert_eq!(status, 403, "wrong S is refused");
    assert!(matches!(wait_status(&sock).await, PairStatus::Failed { reason } if reason == "mac"));
    tokio::time::sleep(Duration::from_millis(300)).await;
    let (good, _, _) = phone_request(&h, &k, &link, "S25 Ultra", &link.secret, Spec::tls(&link.challenge), Spec::sig(&link.challenge));
    let second = post_pair(&link, &good).await;
    assert!(second.is_err(), "{second:?}");
}

#[tokio::test]
async fn the_window_expires() {
    let h = start().await;
    let sock = start_admin(&h, uid()).await;
    let link = open_window(&sock, 1).await;
    tokio::time::sleep(Duration::from_millis(1500)).await;
    assert!(matches!(wait_status(&sock).await, PairStatus::Expired {}));
    let k = keys();
    let (good, _, _) = phone_request(&h, &k, &link, "S25 Ultra", &link.secret, Spec::tls(&link.challenge), Spec::sig(&link.challenge));
    assert!(post_pair(&link, &good).await.is_err(), "nothing listens after expiry");
    open_window(&sock, 60).await;
}

#[tokio::test]
async fn a_wrong_typed_code_adds_nothing() {
    let h = start().await;
    let sock = start_admin(&h, uid()).await;
    let before = std::fs::read(h.dir.join("devices.json")).expect("r");
    let link = open_window(&sock, 60).await;
    let k = keys();
    let (body, _, _) = phone_request(&h, &k, &link, "S25 Ultra", &link.secret, Spec::tls(&link.challenge), Spec::sig(&link.challenge));
    let phone = tokio::spawn({
        let link = link.clone();
        async move { post_pair(&link, &body).await }
    });
    assert!(matches!(wait_status(&sock).await, PairStatus::Received { .. }));
    // typed digits didn't match
    admin(&sock, &AdminRequest::PairReject {}).await.expect("reject");
    let (status, answer) = phone.await.expect("join").expect("post");
    assert_eq!((status, answer["code"].as_str()), (403, Some("pair_rejected")));
    assert_eq!(std::fs::read(h.dir.join("devices.json")).expect("r"), before);
    assert!(admin(&sock, &AdminRequest::PairConfirm { device_id: NEW.into() }).await.is_err(), "nothing left to confirm");
}

#[tokio::test]
async fn attestation_failures_refuse_the_pairing() {
    for (why, tls, sig) in [
        ("wrong challenge", Spec::tls(b"other"), None),
        ("sig in the TEE", Spec::tls(b""), Some(Spec { attestation_security_level: 1, keymint_security_level: 1, ..Spec::sig(b"") })),
        ("two different phones", Spec::tls(b""), Some(Spec { boot_key: vec![0x77; 32], ..Spec::sig(b"") })),
    ] {
        let h = start().await;
        let sock = start_admin(&h, uid()).await;
        let link = open_window(&sock, 60).await;
        let fix = |mut s: Spec| {
            if s.challenge.is_empty() {
                s.challenge = link.challenge.to_vec();
            }
            s
        };
        let k = keys();
        let (body, _, _) = phone_request(&h, &k, &link, "S25 Ultra", &link.secret, fix(tls), fix(sig.unwrap_or_else(|| Spec::sig(&link.challenge))));
        let (status, _) = post_pair(&link, &body).await.expect("post");
        assert_eq!(status, 403, "{why}");
        assert!(matches!(wait_status(&sock).await, PairStatus::Failed { reason } if reason.starts_with("attestation")), "{why}");
    }
}

#[tokio::test]
async fn the_admin_socket_answers_root_only() {
    let h = start().await;
    let sock = start_admin(&h, uid() + 4242).await;
    let r = admin(&sock, &AdminRequest::Lock {}).await;
    assert!(r.is_err(), "{r:?}");
    assert!(!h.app.is_locked());
}

#[tokio::test]
async fn lock_off_goes_through_the_admin_socket() {
    let h = start().await;
    let sock = start_admin(&h, uid()).await;
    admin(&sock, &AdminRequest::Lock {}).await.expect("lock");
    assert!(h.app.is_locked());
    admin(&sock, &AdminRequest::Unlock {}).await.expect("unlock");
    assert!(!h.app.is_locked() && !h.app.settings.locked_flag.exists());
    let head = admin(&sock, &AdminRequest::AuditHead {}).await.expect("head");
    assert_eq!(head["head"].as_str().map(str::to_owned), remoterd::audit::disk_head(&h.dir.join("state/audit.jsonl")).ok());
}

#[tokio::test]
async fn daily_reattestation_gates_mutations_until_it_passes() {
    let h = start().await;
    // A's boot key is a1f309ce, swap in the test kit's so the throwaway key matches
    let mut f: DeviceFile = serde_json::from_slice(&std::fs::read(h.dir.join("devices.json")).expect("r")).expect("p");
    f.devices[0].verified_boot_key = "a1".repeat(32);
    std::fs::write(h.dir.join("devices.json"), serde_json::to_vec(&f).expect("j")).expect("w");
    h.app.reload_devices().expect("reload");
    remoterd::state::lock(&h.app.attest).mark_fresh(A, now() - 1);

    let mut c = h.client(&h.a).await;
    let b = br#"{"parent":"Projects","name":"x","git_init":false}"#;
    let sig = h.a.phone.sign("POST", "/v1/fs/mkdir", b, now(), &[1; 16]);
    let r = c.signed("POST", "/v1/fs/mkdir", &sig, b).await;
    assert_eq!(r.code(), "reattest_required");
    assert!(c.get("/v1/health").await.json()["fresh_until"].as_i64().is_some_and(|t| t < now()));

    let ch = c.get("/v1/attest/challenge").await.json();
    let challenge = b64::decode(ch["challenge"].as_str().expect("c")).expect("b64");
    let chain = h.pki.chain(&Spec::reattest(&challenge), &KeyPair::generate_for(&PKCS_ECDSA_P256_SHA256).expect("k"), &ChainOpts::default());
    let body = serde_json::to_vec(&serde_json::json!({ "chain": chain.iter().map(|x| b64::encode(x)).collect::<Vec<_>>() })).expect("j");
    let ok = c.send("POST", "/v1/attest", &[], body.clone()).await.expect("attest");
    assert_eq!(ok.status, 200, "{:?}", String::from_utf8_lossy(&ok.body));
    assert!(ok.json()["fresh_until"].as_i64().is_some_and(|t| t > now()));
    // same bytes now run, the refusal wasn't cached
    assert_eq!(c.signed("POST", "/v1/fs/mkdir", &sig, b).await.status, 202);
    assert_eq!(c.send("POST", "/v1/attest", &[], body).await.expect("again").json()["code"], "reattest_required");

    // other phone's boot key fails, so does a post without a challenge
    let ch = c.get("/v1/attest/challenge").await.json();
    let challenge = b64::decode(ch["challenge"].as_str().expect("c")).expect("b64");
    let other = h.pki.chain(&Spec { boot_key: vec![0x77; 32], ..Spec::reattest(&challenge) }, &KeyPair::generate_for(&PKCS_ECDSA_P256_SHA256).expect("k"), &ChainOpts::default());
    let body = serde_json::to_vec(&serde_json::json!({ "chain": other.iter().map(|x| b64::encode(x)).collect::<Vec<_>>() })).expect("j");
    assert_eq!(c.send("POST", "/v1/attest", &[], body).await.expect("attest").json()["code"], "reattest_required");
}
