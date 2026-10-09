//! The string a request signature covers, and strict parsing of the signing
//! headers. The target is taken exactly as it went over the wire, never decoded
//! and re-encoded, because OkHttp may encode it differently from the app.

use sha2::{Digest, Sha256};

use crate::b64;

pub const VERSION_TAG: &str = "remoter-sig-v1";
pub const NONCE_LEN: usize = 16;

pub const HDR_DEVICE: &str = "remoter-device";
pub const HDR_TIMESTAMP: &str = "remoter-timestamp";
pub const HDR_NONCE: &str = "remoter-nonce";
pub const HDR_SIGNATURE: &str = "remoter-signature";

/// A P-256 DER signature is at most 72 bytes. Anything longer is not ours.
const MAX_SIG_DER: usize = 72;

pub struct SignInput<'a> {
    pub method: &'a str,
    /// Path plus `?query` exactly as on the wire, no `?` without a query.
    pub target: &'a str,
    pub device: &'a str,
    pub timestamp_ms: i64,
    pub nonce: &'a str,
    pub body: &'a [u8],
}

pub fn body_hash_hex(body: &[u8]) -> String {
    b64::hex(&Sha256::digest(body))
}

pub fn canonical(input: &SignInput<'_>) -> String {
    format!(
        "{VERSION_TAG}\n{}\n{}\n{}\n{}\n{}\n{}",
        input.method,
        input.target,
        input.device,
        input.timestamp_ms,
        input.nonce,
        body_hash_hex(input.body)
    )
}

pub fn canonical_hash(input: &SignInput<'_>) -> [u8; 32] {
    Sha256::digest(canonical(input).as_bytes()).into()
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct SigHeaders {
    pub device: String,
    pub timestamp_ms: i64,
    pub nonce: String,
    pub signature_der: Vec<u8>,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum HeaderError {
    Missing(&'static str),
    Malformed(&'static str),
}

/// Parses the four headers with no leniency: every field has one spelling, so
/// the canonical string the verifier rebuilds is the one the phone signed.
pub fn parse_headers(
    device: Option<&str>,
    timestamp: Option<&str>,
    nonce: Option<&str>,
    signature: Option<&str>,
) -> Result<SigHeaders, HeaderError> {
    let device = device.ok_or(HeaderError::Missing(HDR_DEVICE))?;
    let timestamp = timestamp.ok_or(HeaderError::Missing(HDR_TIMESTAMP))?;
    let nonce = nonce.ok_or(HeaderError::Missing(HDR_NONCE))?;
    let signature = signature.ok_or(HeaderError::Missing(HDR_SIGNATURE))?;

    if !is_device_id(device) {
        return Err(HeaderError::Malformed(HDR_DEVICE));
    }
    let timestamp_ms = parse_timestamp(timestamp).ok_or(HeaderError::Malformed(HDR_TIMESTAMP))?;
    match b64::decode(nonce) {
        Some(raw) if raw.len() == NONCE_LEN => {}
        _ => return Err(HeaderError::Malformed(HDR_NONCE)),
    }
    let signature_der = match b64::decode(signature) {
        Some(der) if !der.is_empty() && der.len() <= MAX_SIG_DER => der,
        _ => return Err(HeaderError::Malformed(HDR_SIGNATURE)),
    };
    Ok(SigHeaders {
        device: device.to_owned(),
        timestamp_ms,
        nonce: nonce.to_owned(),
        signature_der,
    })
}

/// Device ids are ULIDs in Crockford base32, uppercase, 26 characters.
pub fn is_device_id(s: &str) -> bool {
    s.len() == 26
        && s.bytes().enumerate().all(|(i, b)| {
            let crockford = matches!(b, b'0'..=b'9' | b'A'..=b'H' | b'J' | b'K' | b'M' | b'N' | b'P'..=b'T' | b'V'..=b'Z');
            // The first character carries only 3 bits of the 128.
            crockford && (i != 0 || b <= b'7')
        })
}

/// Decimal unix milliseconds, no sign, no leading zeros, positive.
pub fn parse_timestamp(s: &str) -> Option<i64> {
    if s.is_empty() || s.len() > 15 || !s.bytes().all(|b| b.is_ascii_digit()) || s.starts_with('0') {
        return None;
    }
    s.parse().ok()
}

#[cfg(test)]
mod tests {
    use super::*;

    const DEV: &str = "01K6B7Y3M4N5P6Q7R8S9T0V1W2";
    const NONCE: &str = "AAECAwQFBgcICQoLDA0ODw";

    #[test]
    fn exact_bytes() {
        let s = canonical(&SignInput {
            method: "POST",
            target: "/v1/fs/mkdir",
            device: DEV,
            timestamp_ms: 1_790_611_106_000,
            nonce: NONCE,
            body: b"{}",
        });
        assert_eq!(
            s,
            "remoter-sig-v1\nPOST\n/v1/fs/mkdir\n01K6B7Y3M4N5P6Q7R8S9T0V1W2\n1790611106000\nAAECAwQFBgcICQoLDA0ODw\n44136fa355b3678a1146ad16f7e8649e94fb4fc21fe77e8310c060f61caaff8a"
        );
        assert!(!s.ends_with('\n'));
    }

    #[test]
    fn empty_body_hash_is_sha256_of_nothing() {
        assert_eq!(body_hash_hex(b""), "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");
    }

    #[test]
    fn different_paths_give_different_strings() {
        let mk = |target| {
            canonical(&SignInput { method: "DELETE", target, device: DEV, timestamp_ms: 1, nonce: NONCE, body: b"" })
        };
        assert_ne!(mk("/v1/sessions/rc-a"), mk("/v1/sessions/rc-b"));
    }

    #[test]
    fn headers_parse_when_well_formed() {
        let sig = b64::encode(&[0x30; 70]);
        let h = parse_headers(Some(DEV), Some("1790611106000"), Some(NONCE), Some(&sig)).expect("parses");
        assert_eq!(h.timestamp_ms, 1_790_611_106_000);
    }

    #[test]
    fn missing_headers_are_named() {
        let sig = b64::encode(&[0x30; 70]);
        assert_eq!(parse_headers(None, Some("1"), Some(NONCE), Some(&sig)), Err(HeaderError::Missing(HDR_DEVICE)));
        assert_eq!(parse_headers(Some(DEV), None, Some(NONCE), Some(&sig)), Err(HeaderError::Missing(HDR_TIMESTAMP)));
        assert_eq!(parse_headers(Some(DEV), Some("1"), None, Some(&sig)), Err(HeaderError::Missing(HDR_NONCE)));
        assert_eq!(parse_headers(Some(DEV), Some("1"), Some(NONCE), None), Err(HeaderError::Missing(HDR_SIGNATURE)));
    }

    #[test]
    fn malformed_fields_are_rejected() {
        let sig = b64::encode(&[0x30; 70]);
        let bad = |d: &str, t: &str, n: &str, s: &str| parse_headers(Some(d), Some(t), Some(n), Some(s)).is_err();
        assert!(bad("01k6b7y3m4n5p6q7r8s9t0v1w2", "1", NONCE, &sig), "lowercase device");
        assert!(bad("81K6B7Y3M4N5P6Q7R8S9T0V1W2", "1", NONCE, &sig), "device overflows 128 bits");
        assert!(bad("01K6B7Y3M4N5P6Q7R8S9T0V1WI", "1", NONCE, &sig), "I is not Crockford");
        assert!(bad(DEV, "01", NONCE, &sig), "leading zero");
        assert!(bad(DEV, "+1", NONCE, &sig), "sign");
        assert!(bad(DEV, "-1", NONCE, &sig), "negative");
        assert!(bad(DEV, "1.0", NONCE, &sig), "fraction");
        assert!(bad(DEV, "99999999999999999999", NONCE, &sig), "overflow");
        assert!(bad(DEV, "1", "AAECAwQFBgcICQoLDA0ODw==", &sig), "padded nonce");
        assert!(bad(DEV, "1", "AAECAwQFBgcICQoLDA0O", &sig), "short nonce");
        assert!(bad(DEV, "1", NONCE, ""), "empty signature");
        assert!(bad(DEV, "1", NONCE, &b64::encode(&[0x30; 73])), "oversized signature");
    }
}
