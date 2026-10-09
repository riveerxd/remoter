mod common;

use std::time::Duration;

use common::*;
use rustls::crypto::aws_lc_rs as aws;
use tokio::io::AsyncWriteExt;

async fn refused(h: &Harness, cfg: std::sync::Arc<rustls::ClientConfig>) -> bool {
    // TLS 1.3: a refused client cert can show up only on the first request
    match Client::connect(h.addr, cfg).await {
        Err(_) => true,
        Ok(mut c) => c.send("GET", "/v1/health", &[], vec![]).await.is_err(),
    }
}

#[tokio::test]
async fn paired_key_gets_the_right_suites() {
    let h = start().await;
    let mut c = h.client(&h.a).await;
    assert_eq!(c.get("/v1/health").await.status, 200);
    let suite = c.suite.expect("suite").suite();
    assert!(
        [rustls::CipherSuite::TLS13_AES_256_GCM_SHA384, rustls::CipherSuite::TLS13_CHACHA20_POLY1305_SHA256].contains(&suite),
        "{suite:?}"
    );
    assert_eq!(c.group, Some(rustls::NamedGroup::X25519MLKEM768), "hybrid preferred");
}

#[tokio::test]
async fn x25519_works_nothing_weaker() {
    let h = start().await;
    let mut p = aws::default_provider();
    p.kx_groups = vec![aws::kx_group::X25519];
    let cfg = client_config(h.server_spki_sha256, &h.a.cert, &h.a.key, &[&rustls::version::TLS13], p);
    let mut c = Client::connect(h.addr, cfg).await.expect("x25519");
    assert_eq!(c.get("/v1/health").await.status, 200);
    assert_eq!(c.group, Some(rustls::NamedGroup::X25519));

    let mut p = aws::default_provider();
    p.kx_groups = vec![aws::kx_group::SECP256R1];
    let cfg = client_config(h.server_spki_sha256, &h.a.cert, &h.a.key, &[&rustls::version::TLS13], p);
    assert!(refused(&h, cfg).await, "secp256r1");

    let mut p = aws::default_provider();
    p.cipher_suites = vec![aws::cipher_suite::TLS13_AES_128_GCM_SHA256];
    let cfg = client_config(h.server_spki_sha256, &h.a.cert, &h.a.key, &[&rustls::version::TLS13], p);
    assert!(refused(&h, cfg).await, "AES-128");
}

#[tokio::test]
async fn unknown_key_is_refused() {
    let h = start().await;
    let stranger = make_phone("01K6B7Y3M4N5P6Q7R8S9T0V1W9", 9);
    assert!(refused(&h, h.config_for(&stranger)).await);
}

#[tokio::test]
async fn replayed_cert_without_key_is_refused() {
    let h = start().await;
    // A's cert isn't secret, signing with another key must fail in verify_tls13_signature
    let other = make_phone("01K6B7Y3M4N5P6Q7R8S9T0V1W9", 9);
    let cfg = client_config(h.server_spki_sha256, &h.a.cert, &other.key, &[&rustls::version::TLS13], aws::default_provider());
    assert!(refused(&h, cfg).await);
}

#[tokio::test]
async fn tls12_is_refused() {
    let h = start().await;
    let cfg = client_config(h.server_spki_sha256, &h.a.cert, &h.a.key, &[&rustls::version::TLS12], aws::default_provider());
    assert!(refused(&h, cfg).await);
}

#[tokio::test]
async fn no_resumption_is_offered() {
    let h = start().await;
    let cfg = h.config_for(&h.a);
    for _ in 0..3 {
        let mut c = Client::connect(h.addr, cfg.clone()).await.expect("connect");
        assert_eq!(c.get("/v1/health").await.status, 200);
        assert_eq!(c.kind, Some(rustls::HandshakeKind::Full), "resumed");
    }
}

#[tokio::test]
async fn wrong_server_key_refused() {
    let h = start().await;
    let cfg = client_config([0u8; 32], &h.a.cert, &h.a.key, &[&rustls::version::TLS13], aws::default_provider());
    assert!(Client::connect(h.addr, cfg).await.is_err());
}

#[tokio::test]
async fn revoke_cuts_connections() {
    let h = start().await;
    let mut c = h.client(&h.a).await;
    let (status, mut rx) = c.stream("/v1/sessions/rc-x/events", &[]).await;
    assert_eq!(status, 200);
    let first = tokio::time::timeout(Duration::from_secs(5), rx.recv()).await.expect("first chunk").expect("open");
    assert!(first.expect("data").contains("event: phase"));
    // what `remoterctl revoke` does before the SIGHUP
    h.write_devices(&[&h.b]);
    h.app.reload_devices().expect("reload");
    let deadline = tokio::time::Instant::now() + Duration::from_secs(3);
    loop {
        match tokio::time::timeout_at(deadline, rx.recv()).await {
            Ok(Some(Ok(_))) => continue,
            Ok(Some(Err(_))) | Ok(None) => break,
            Err(_) => panic!("the stream outlived the revoke"),
        }
    }
    assert!(refused(&h, h.config_for(&h.a)).await, "came back");
    let mut cb = h.client(&h.b).await;
    assert_eq!(cb.get("/v1/health").await.status, 200, "B should be fine");
}

#[tokio::test]
async fn handshake_garbage_never_locks() {
    let h = start().await;
    for i in 0..30u8 {
        let mut s = tokio::net::TcpStream::connect(h.addr).await.expect("tcp");
        let _ = s.write_all(&vec![i; 512]).await;
        let _ = s.write_all(b"\x16\x03\x01\x00\x05hello").await;
    }
    let stranger = make_phone("01K6B7Y3M4N5P6Q7R8S9T0V1W9", 9);
    for _ in 0..10 {
        assert!(refused(&h, h.config_for(&stranger)).await);
    }
    tokio::time::sleep(Duration::from_millis(300)).await;
    assert!(!h.app.is_locked());
    assert!(!h.app.settings.locked_flag.exists());
    let audit = std::fs::read_to_string(h.dir.join("state/audit.jsonl")).unwrap_or_default();
    assert!(audit.is_empty(), "{audit}");
    let mut c = h.client(&h.a).await;
    assert_eq!(c.get("/v1/health").await.json()["locked"], false);
}
