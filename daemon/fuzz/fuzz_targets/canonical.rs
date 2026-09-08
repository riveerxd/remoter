#![no_main]

use libfuzzer_sys::fuzz_target;
use remoter_proto::canonical::{SignInput, canonical};

// Fields are split out of the input at NUL bytes, so the fuzzer can put
// newlines anywhere, including inside fields.
fuzz_target!(|data: &[u8]| {
    let parts: Vec<&[u8]> = data.splitn(6, |b| *b == 0).collect();
    let [method, target, device, nonce, ts, body] = parts.as_slice() else { return };
    let (Ok(method), Ok(target), Ok(device), Ok(nonce)) =
        (std::str::from_utf8(method), std::str::from_utf8(target), std::str::from_utf8(device), std::str::from_utf8(nonce))
    else {
        return;
    };
    let ts = ts.iter().fold(0i64, |a, b| a.wrapping_mul(31).wrapping_add(*b as i64));
    let input = SignInput { method, target, device, timestamp_ms: ts, nonce, body };
    let s = canonical(&input);
    let lines: Vec<&str> = s.split('\n').collect();
    let fields_have_no_newline = [method, target, device, nonce].iter().all(|f| !f.contains('\n'));
    if fields_have_no_newline {
        // Exactly seven lines in a fixed order: nothing can be moved from one
        // field into another.
        assert_eq!(lines.len(), 7);
        assert_eq!(lines[0], "remoter-sig-v1");
        assert_eq!((lines[1], lines[2], lines[3], lines[5]), (method, target, device, nonce));
        assert_eq!(lines[4], ts.to_string());
        assert_eq!(lines[6].len(), 64);
    }
    assert!(!s.ends_with('\n') || target.ends_with('\n') || nonce.ends_with('\n') || device.ends_with('\n') || method.ends_with('\n'));
});
