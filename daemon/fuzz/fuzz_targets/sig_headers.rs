#![no_main]

use libfuzzer_sys::fuzz_target;
use remoter_proto::b64;
use remoter_proto::canonical::{NONCE_LEN, is_device_id, parse_headers};

fuzz_target!(|data: &[u8]| {
    let parts: Vec<&[u8]> = data.splitn(4, |b| *b == 0).collect();
    let get = |i: usize| parts.get(i).and_then(|p| std::str::from_utf8(p).ok());
    let Ok(h) = parse_headers(get(0), get(1), get(2), get(3)) else { return };
    // Whatever parses has exactly one spelling, so it can't be two keys in
    // the replay store.
    assert!(is_device_id(&h.device));
    assert_eq!(get(0), Some(h.device.as_str()));
    assert_eq!(get(1), Some(h.timestamp_ms.to_string().as_str()));
    assert!(h.timestamp_ms > 0);
    assert_eq!(b64::decode(&h.nonce).map(|n| n.len()), Some(NONCE_LEN));
    assert_eq!(b64::encode(&b64::decode(&h.nonce).expect("nonce")), h.nonce);
    assert_eq!(get(3), Some(b64::encode(&h.signature_der).as_str()));
    assert!(!h.signature_der.is_empty() && h.signature_der.len() <= 72);
});
