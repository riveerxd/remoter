//! Signature cases at the verifier. The daemons each run the same cases again
//! through their own front doors.

use super::*;
use remoter_proto::b64;
use crate::testkit::{Phone, device_file};

const A: &str = "01K6B7Y3M4N5P6Q7R8S9T0V1W2";
const B: &str = "01K6B7Y3M4N5P6Q7R8S9T0V1W3";
const NOW: i64 = 1_790_611_106_000;

fn setup() -> (Phone, Phone, Devices) {
    let a = Phone::new(A, 1, b"tls-a".to_vec());
    let b = Phone::new(B, 2, b"tls-b".to_vec());
    let devices = Devices::parse(&device_file(&[&a, &b])).expect("devices");
    (a, b, devices)
}

fn check(v: &Verifier, d: &Devices, h: &RawSigHeaders, method: &str, target: &str, body: &[u8]) -> Result<Admitted, Rejection> {
    v.check(d, &Request { mtls_device: A, headers: h, method, target, body, now_ms: NOW })
}

fn fresh() -> Verifier {
    Verifier::new(30_000, None, 1000)
}

#[test]
fn valid_request_is_admitted_fresh() {
    let (a, _, d) = setup();
    let h = a.sign("POST", "/v1/fs/mkdir", b"{}", NOW, &[1; 16]);
    assert_eq!(check(&fresh(), &d, &h, "POST", "/v1/fs/mkdir", b"{}").map(|x| x.admit), Ok(Admit::Fresh));
}

#[test]
fn wrong_key_is_sig_invalid() {
    let (a, b, d) = setup();
    let h = a.sign_as(A, &b.sig, "POST", "/v1/fs/mkdir", b"{}", NOW, &[1; 16]);
    assert_eq!(check(&fresh(), &d, &h, "POST", "/v1/fs/mkdir", b"{}"), Err(Rejection::SigInvalid));
}

#[test]
fn right_key_for_another_device_is_refused() {
    let (_, b, d) = setup();
    // B signs correctly as B, but the connection proved A.
    let h = b.sign("POST", "/v1/fs/mkdir", b"{}", NOW, &[1; 16]);
    assert_eq!(check(&fresh(), &d, &h, "POST", "/v1/fs/mkdir", b"{}"), Err(Rejection::DeviceMismatch));
    assert_eq!(Rejection::DeviceMismatch.lock_weight(), LockWeight::BadSignature);
}

#[test]
fn unknown_device_is_device_unknown() {
    let (a, _, _) = setup();
    let empty = Devices::default();
    let h = a.sign("POST", "/v1/fs/mkdir", b"{}", NOW, &[1; 16]);
    assert_eq!(check(&fresh(), &empty, &h, "POST", "/v1/fs/mkdir", b"{}"), Err(Rejection::DeviceUnknown));
}

#[test]
fn changed_body_path_query_or_method_is_sig_invalid() {
    let (a, _, d) = setup();
    let h = a.sign("POST", "/v1/fs/mkdir?x=1", b"{\"a\":1}", NOW, &[1; 16]);
    let v = fresh();
    assert_eq!(check(&v, &d, &h, "POST", "/v1/fs/mkdir?x=1", b"{\"a\":2}"), Err(Rejection::SigInvalid), "body");
    assert_eq!(check(&v, &d, &h, "POST", "/v1/fs/mkdir?x=2", b"{\"a\":1}"), Err(Rejection::SigInvalid), "query");
    assert_eq!(check(&v, &d, &h, "POST", "/v1/fs/other?x=1", b"{\"a\":1}"), Err(Rejection::SigInvalid), "path");
    assert_eq!(check(&v, &d, &h, "PUT", "/v1/fs/mkdir?x=1", b"{\"a\":1}"), Err(Rejection::SigInvalid), "method");
    assert!(check(&v, &d, &h, "POST", "/v1/fs/mkdir?x=1", b"{\"a\":1}").is_ok());
}

#[test]
fn okhttp_encoded_target_verifies_exactly_as_sent() {
    let (a, _, d) = setup();
    let target = "/v1/fs/mkdir?path=Projects%2Fx%20y%2Fcaf%C3%A9&q=a%2Bb%26c%3Dd%23e";
    let h = a.sign("POST", target, b"{}", NOW, &[1; 16]);
    let v = fresh();
    assert!(check(&v, &d, &h, "POST", target, b"{}").is_ok());
    // The same URL decoded, or encoded another way, is a different string.
    let other = "/v1/fs/mkdir?path=Projects/x%20y/caf%C3%A9&q=a%2Bb%26c%3Dd%23e";
    let h2 = a.sign("POST", target, b"{}", NOW, &[2; 16]);
    assert_eq!(check(&v, &d, &h2, "POST", other, b"{}"), Err(Rejection::SigInvalid));
}

#[test]
fn timestamp_window_is_thirty_seconds_each_way() {
    let (a, _, d) = setup();
    let v = fresh();
    for (i, off) in [-30_000i64, 30_000].into_iter().enumerate() {
        let h = a.sign("POST", "/v1/x", b"", NOW + off, &[10 + i as u8; 16]);
        assert!(check(&v, &d, &h, "POST", "/v1/x", b"").is_ok(), "{off}");
    }
    for (i, off) in [-31_000i64, 31_000].into_iter().enumerate() {
        let h = a.sign("POST", "/v1/x", b"", NOW + off, &[20 + i as u8; 16]);
        assert_eq!(check(&v, &d, &h, "POST", "/v1/x", b""), Err(Rejection::ClockSkew { server_time: NOW }), "{off}");
        assert_eq!(Rejection::ClockSkew { server_time: NOW }.code(), ErrorCode::ClockSkew);
    }
}

#[test]
fn same_nonce_same_request_replays_the_stored_answer() {
    let (a, _, d) = setup();
    let v = fresh();
    let h = a.sign("POST", "/v1/sessions", b"{}", NOW, &[3; 16]);
    let first = check(&v, &d, &h, "POST", "/v1/sessions", b"{}").expect("first");
    assert_eq!(first.admit, Admit::Fresh);
    assert_eq!(check(&v, &d, &h, "POST", "/v1/sessions", b"{}").map(|x| x.admit), Ok(Admit::InFlight));
    let answer = Cached { status: 202, body: "{\"id\":\"rc-x\"}".into(), audit_path: None };
    v.complete(&first, answer.clone());
    assert_eq!(check(&v, &d, &h, "POST", "/v1/sessions", b"{}").map(|x| x.admit), Ok(Admit::Replay(answer)));
}

#[test]
fn nonce_reuse_on_another_request_locks() {
    let (a, _, d) = setup();
    let v = fresh();
    let h1 = a.sign("POST", "/v1/sessions", b"{\"a\":1}", NOW, &[4; 16]);
    let h2 = a.sign("POST", "/v1/sessions", b"{\"a\":2}", NOW, &[4; 16]);
    assert!(check(&v, &d, &h1, "POST", "/v1/sessions", b"{\"a\":1}").is_ok());
    assert_eq!(check(&v, &d, &h2, "POST", "/v1/sessions", b"{\"a\":2}"), Err(Rejection::NonceReused));
    assert_eq!(Rejection::NonceReused.lock_weight(), LockWeight::Immediate);
}

#[test]
fn two_empty_deletes_one_nonce() {
    let (a, _, d) = setup();
    let v = fresh();
    let h1 = a.sign("DELETE", "/v1/sessions/rc-a", b"", NOW, &[5; 16]);
    let first = check(&v, &d, &h1, "DELETE", "/v1/sessions/rc-a", b"").expect("first");
    v.complete(&first, Cached { status: 202, body: "{}".into(), audit_path: None });
    let h2 = a.sign("DELETE", "/v1/sessions/rc-b", b"", NOW, &[5; 16]);
    // Refused, not served the first one's cached reply: the hash covers the path.
    assert_eq!(check(&v, &d, &h2, "DELETE", "/v1/sessions/rc-b", b""), Err(Rejection::NonceReused));
}

#[test]
fn nonce_is_only_looked_at_after_the_signature() {
    let (a, b, d) = setup();
    let v = fresh();
    let good = a.sign("POST", "/v1/sessions", b"{}", NOW, &[6; 16]);
    let first = check(&v, &d, &good, "POST", "/v1/sessions", b"{}").expect("first");
    v.complete(&first, Cached { status: 202, body: "secret".into(), audit_path: None });
    // Holding only the TLS key: same headers, forged signature. Must not see
    // the cached reply, and must not count as a reused nonce.
    let mut forged = a.sign_as(A, &b.sig, "POST", "/v1/sessions", b"{}", NOW, &[6; 16]);
    assert_eq!(check(&v, &d, &forged, "POST", "/v1/sessions", b"{}"), Err(Rejection::SigInvalid));
    forged.signature = Some(b64::encode(&[0x30, 0x06, 0x02, 0x01, 0x01, 0x02, 0x01, 0x01]));
    assert_eq!(check(&v, &d, &forged, "POST", "/v1/sessions", b"{}"), Err(Rejection::SigInvalid));
}

#[test]
fn bad_headers_never_lock() {
    let (a, _, d) = setup();
    let v = fresh();
    let full = a.sign("POST", "/v1/x", b"", NOW, &[7; 16]);
    for strip in 0..4 {
        let mut h = full.clone();
        match strip {
            0 => h.device = None,
            1 => h.timestamp = None,
            2 => h.nonce = None,
            _ => h.signature = None,
        }
        let r = check(&v, &d, &h, "POST", "/v1/x", b"");
        assert!(matches!(r, Err(Rejection::BadHeaders(_))), "{strip}: {r:?}");
        assert_eq!(r.err().map(|e| e.lock_weight()), Some(LockWeight::None));
    }
    let mut h = full.clone();
    h.timestamp = Some("01790611106000".into());
    assert!(matches!(check(&v, &d, &h, "POST", "/v1/x", b""), Err(Rejection::BadHeaders(_))));
}

#[test]
fn timestamp_before_start_is_refused() {
    let (a, _, d) = setup();
    let v = Verifier::new(30_000, Some(NOW - 5_000), 1000);
    let h = a.sign("POST", "/v1/x", b"", NOW - 6_000, &[8; 16]);
    assert_eq!(check(&v, &d, &h, "POST", "/v1/x", b""), Err(Rejection::BeforeStart { server_time: NOW }));
    let ok = a.sign("POST", "/v1/x", b"", NOW - 4_000, &[9; 16]);
    assert!(check(&v, &d, &ok, "POST", "/v1/x", b"").is_ok());
}

#[test]
fn store_is_bounded_without_eviction() {
    let (a, _, d) = setup();
    let v = Verifier::new(30_000, None, 2);
    for n in 0..2u8 {
        let h = a.sign("POST", "/v1/x", b"", NOW, &[n; 16]);
        assert!(check(&v, &d, &h, "POST", "/v1/x", b"").is_ok());
    }
    let h = a.sign("POST", "/v1/x", b"", NOW, &[2; 16]);
    assert_eq!(check(&v, &d, &h, "POST", "/v1/x", b""), Err(Rejection::Busy));
    // The first nonce is still remembered, so it still can't be replayed with
    // other content.
    let again = a.sign("POST", "/v1/y", b"", NOW, &[0; 16]);
    assert_eq!(check(&v, &d, &again, "POST", "/v1/y", b""), Err(Rejection::NonceReused));
}

#[test]
fn forget_lets_it_run_again() {
    let (a, _, d) = setup();
    let v = fresh();
    let h = a.sign("POST", "/v1/x", b"", NOW, &[11; 16]);
    let first = check(&v, &d, &h, "POST", "/v1/x", b"").expect("first");
    v.forget(&first);
    assert_eq!(check(&v, &d, &h, "POST", "/v1/x", b"").map(|x| x.admit), Ok(Admit::Fresh));
}

#[test]
fn device_file_rejects_bad_records() {
    let (a, _, _) = setup();
    let mut r = a.record();
    r.id = "lower-case".into();
    let bad = serde_json::to_vec(&devices::DeviceFile { devices: vec![r] }).expect("json");
    assert!(Devices::parse(&bad).is_err());
    let dup = device_file(&[&a, &a]);
    assert!(Devices::parse(&dup).is_err());
    assert!(Devices::parse(b"{\"devices\":[],\"extra\":1}").is_err());
    assert!(Devices::load(std::path::Path::new("/nonexistent/devices.json")).expect("missing is empty").is_empty());
}

/// Phone clock 30 s ahead signs `ts = start + 20 s`, the old agent runs it, then
/// a new one starts at `start` with an empty store and must refuse it.
#[test]
fn restart_refuses_what_the_old_agent_could_run() {
    let (a, _, d) = setup();
    let start = NOW - 1_000;
    let v = Verifier::for_agent(30_000, start, 1000);
    let ahead = a.sign("POST", "/v1/x", b"", start + 20_000, &[30; 16]);
    let r = v.check(&d, &Request { mtls_device: A, headers: &ahead, method: "POST", target: "/v1/x", body: b"", now_ms: NOW });
    assert!(matches!(r, Err(Rejection::BeforeStart { .. })), "{r:?}");
    let later = a.sign("POST", "/v1/x", b"", start + 30_000, &[31; 16]);
    let r = v.check(&d, &Request { mtls_device: A, headers: &later, method: "POST", target: "/v1/x", body: b"", now_ms: start + 31_000 });
    assert!(r.is_ok(), "a full window after start: {r:?}");
}
