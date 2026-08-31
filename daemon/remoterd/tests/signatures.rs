//! Signature checks at remoterd. The fake agent counts what reaches it, so
//! every refusal provably stops here.

mod common;

use std::sync::atomic::Ordering;

use common::*;

const MKDIR: &str = "/v1/fs/mkdir";

fn body(n: &str) -> Vec<u8> {
    format!(r#"{{"parent":"Projects","name":"{n}","git_init":false}}"#).into_bytes()
}

#[tokio::test]
async fn valid_request_runs_once_and_retry_replays() {
    let h = start().await;
    let mut c = h.client(&h.a).await;
    let b = body("x");
    let sig = h.a.phone.sign("POST", MKDIR, &b, now(), &[1; 16]);
    let first = c.signed("POST", MKDIR, &sig, &b).await;
    assert_eq!(first.status, 202, "{:?}", String::from_utf8_lossy(&first.body));
    // retry on a new connection, like after a network drop
    let mut c2 = h.client(&h.a).await;
    let again = c2.signed("POST", MKDIR, &sig, &b).await;
    assert_eq!((again.status, again.body.clone()), (first.status, first.body.clone()));
    assert_eq!(h.agent.mutations.load(Ordering::SeqCst), 1, "ran exactly once");
    assert_ne!(first.headers.get("remoter-request-id"), again.headers.get("remoter-request-id"));
}

#[tokio::test]
async fn forgeries_stop_at_remoterd() {
    let h = start().await;
    let mut c = h.client(&h.a).await;
    let b = body("x");
    let t = now();
    let cases = [
        ("wrong key", h.a.phone.sign_as(A, &h.b.phone.sig, "POST", MKDIR, &b, t, &[2; 16]), b.clone(), MKDIR, "sig_invalid"),
        ("B's headers on A's connection", h.b.phone.sign("POST", MKDIR, &b, t, &[3; 16]), b.clone(), MKDIR, "sig_invalid"),
    ];
    for (why, sig, sent, target, want) in cases {
        let r = c.signed("POST", target, &sig, &sent).await;
        assert_eq!(r.code(), want, "{why}");
    }
    // two bad sigs so far, a third would lock, so start fresh
    let h = start().await;
    let mut c = h.client(&h.a).await;
    let t = now();
    let sig = h.a.phone.sign("POST", MKDIR, &b, t, &[4; 16]);
    assert_eq!(c.signed("POST", MKDIR, &sig, &body("changed")).await.code(), "sig_invalid", "body changed");
    let h = start().await;
    let mut c = h.client(&h.a).await;
    let sig = h.a.phone.sign("POST", MKDIR, &b, t, &[5; 16]);
    assert_eq!(c.signed("POST", "/v1/fs/mkdir?x=1", &sig, &b).await.code(), "sig_invalid", "path changed");
    for (off, n) in [(-31_000i64, 6u8), (31_000, 7)] {
        let sig = h.a.phone.sign("POST", MKDIR, &b, now() + off, &[n; 16]);
        let r = c.signed("POST", MKDIR, &sig, &b).await;
        assert_eq!(r.code(), "clock_skew", "{off}");
        assert!(r.json()["server_time"].as_i64().is_some(), "clock_skew carries server_time");
    }
    assert_eq!(h.agent.mutations.load(Ordering::SeqCst), 0, "nothing forged reached the agent");
    assert!(!h.app.is_locked(), "clock skew doesn't count toward the lock");
}

#[tokio::test]
async fn okhttp_encoded_target_passes_as_sent() {
    let h = start().await;
    let mut c = h.client(&h.a).await;
    let target = "/v1/fs/mkdir?path=Projects%2Fx%20y%2Fcaf%C3%A9&q=a%2Bb%26c%3Dd%23e";
    let b = body("x");
    let sig = h.a.phone.sign("POST", target, &b, now(), &[8; 16]);
    assert_eq!(c.signed("POST", target, &sig, &b).await.status, 202);
}

#[tokio::test]
async fn nonce_reuse_locks_at_once_and_reads_keep_working() {
    let h = start().await;
    let mut c = h.client(&h.a).await;
    let t = now();
    let s1 = h.a.phone.sign("POST", MKDIR, &body("a"), t, &[9; 16]);
    assert_eq!(c.signed("POST", MKDIR, &s1, &body("a")).await.status, 202);
    let s2 = h.a.phone.sign("POST", MKDIR, &body("b"), t, &[9; 16]);
    assert_eq!(c.signed("POST", MKDIR, &s2, &body("b")).await.code(), "nonce_reused");
    assert!(h.app.is_locked());
    assert!(h.app.settings.locked_flag.exists(), "the agent's flag is written");
    use std::os::unix::fs::PermissionsExt;
    assert_eq!(std::fs::metadata(&h.app.settings.locked_flag).expect("m").permissions().mode() & 0o777, 0o644);
    let s3 = h.a.phone.sign("POST", MKDIR, &body("c"), now(), &[10; 16]);
    assert_eq!(c.signed("POST", MKDIR, &s3, &body("c")).await.code(), "locked", "mutations refused");
    assert_eq!(c.get("/v1/sessions").await.status, 200, "reads still work");
    assert_eq!(c.get("/v1/health").await.json()["locked"], true);
    assert_eq!(h.agent.mutations.load(Ordering::SeqCst), 1);
}

#[tokio::test]
async fn two_empty_deletes_with_one_nonce() {
    let h = start().await;
    let mut c = h.client(&h.a).await;
    let t = now();
    let p1 = "/v1/sessions/rc-01k6b7y3m4n5p6q7r8s9t0v1w2";
    let p2 = "/v1/sessions/rc-01k6b7y3m4n5p6q7r8s9t0v1w3";
    let s1 = h.a.phone.sign("DELETE", p1, b"", t, &[11; 16]);
    assert_eq!(c.signed("DELETE", p1, &s1, b"").await.status, 202);
    let s2 = h.a.phone.sign("DELETE", p2, b"", t, &[11; 16]);
    let r = c.signed("DELETE", p2, &s2, b"").await;
    assert_eq!(r.code(), "nonce_reused", "not served the first one's reply");
}

#[tokio::test]
async fn three_bad_signatures_lock_and_bad_headers_never_count() {
    let h = start().await;
    let mut c = h.client(&h.a).await;
    let b = body("x");
    let mut stripped = h.a.phone.sign("POST", MKDIR, &b, now(), &[12; 16]);
    stripped.signature = None;
    for _ in 0..5 {
        assert_eq!(c.signed("POST", MKDIR, &stripped, &b).await.code(), "bad_request", "missing headers");
    }
    assert!(!h.app.is_locked(), "junk without a signature can't lock anyone out");
    for n in 0..2u8 {
        let bad = h.a.phone.sign_as(A, &h.b.phone.sig, "POST", MKDIR, &b, now(), &[20 + n; 16]);
        assert_eq!(c.signed("POST", MKDIR, &bad, &b).await.code(), "sig_invalid");
        assert!(!h.app.is_locked(), "{} bad so far", n + 1);
    }
    let bad = h.a.phone.sign_as(A, &h.b.phone.sig, "POST", MKDIR, &b, now(), &[22; 16]);
    assert_eq!(c.signed("POST", MKDIR, &bad, &b).await.code(), "sig_invalid");
    assert!(h.app.is_locked(), "the third locks");
    let audit = std::fs::read_to_string(h.dir.join("state/audit.jsonl")).expect("audit");
    assert!(audit.contains("\"action\":\"lock\""), "{audit}");
}

#[tokio::test]
async fn phone_can_lock_with_mtls_alone() {
    let h = start().await;
    let mut c = h.client(&h.a).await;
    let r = c.send("POST", "/v1/lock", &[], vec![]).await.expect("lock");
    assert_eq!(r.json()["locked"], true);
    assert!(h.app.is_locked());
    let b = body("x");
    let sig = h.a.phone.sign("POST", MKDIR, &b, now(), &[30; 16]);
    assert_eq!(c.signed("POST", MKDIR, &sig, &b).await.code(), "locked");
    let v = h.a.phone.sign("POST", "/v1/view-token", b"{}", now(), &[31; 16]);
    assert_eq!(c.signed("POST", "/v1/view-token", &v, b"{}").await.status, 202, "view token while locked");
}

#[tokio::test]
async fn agent_down_is_not_cached() {
    let h = start().await;
    let mut c = h.client(&h.a).await;
    let b = body("x");
    let sig = h.a.phone.sign("POST", MKDIR, &b, now(), &[32; 16]);
    h.agent.down.store(true, Ordering::SeqCst);
    assert_eq!(c.signed("POST", MKDIR, &sig, &b).await.code(), "agent_down");
    h.agent.down.store(false, Ordering::SeqCst);
    assert_eq!(c.signed("POST", MKDIR, &sig, &b).await.status, 202, "same bytes, now it runs");
    assert_eq!(h.agent.mutations.load(Ordering::SeqCst), 1);
}

#[tokio::test]
async fn spawn_rate_limit_and_mutation_rate_limit() {
    let h = start().await;
    let mut c = h.client(&h.a).await;
    let spawn = br#"{"path":"Projects/remoter","name":"x","mode":"same-dir"}"#;
    for n in 0..3u8 {
        let s = h.a.phone.sign("POST", "/v1/sessions", spawn, now(), &[40 + n; 16]);
        assert_eq!(c.signed("POST", "/v1/sessions", &s, spawn).await.status, 202);
    }
    let s = h.a.phone.sign("POST", "/v1/sessions", spawn, now(), &[43; 16]);
    let r = c.signed("POST", "/v1/sessions", &s, spawn).await;
    assert_eq!(r.code(), "rate_limited");
    assert!(r.json()["retry_after_s"].as_u64().is_some_and(|s| s >= 1));
    // refused spawn burned its nonce, resending replays the refusal
    assert_eq!(c.signed("POST", "/v1/sessions", &s, spawn).await.code(), "rate_limited");
    let mut n = 50u8;
    let mut last = 0;
    for _ in 0..8 {
        n += 1;
        let s = h.a.phone.sign("POST", MKDIR, &body("m"), now(), &[n; 16]);
        last = c.signed("POST", MKDIR, &s, &body("m")).await.status.as_u16();
    }
    assert_eq!(last, 429, "10 mutations a minute");
}
