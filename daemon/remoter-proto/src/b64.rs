use base64::Engine;
use base64::engine::general_purpose::URL_SAFE_NO_PAD;

pub fn encode(bytes: &[u8]) -> String {
    URL_SAFE_NO_PAD.encode(bytes)
}

/// Strict: rejects padding, the standard alphabet, and any non canonical
/// trailing bits, so one byte string has exactly one accepted spelling. A nonce
/// with two spellings would be two nonces to the replay store.
pub fn decode(text: &str) -> Option<Vec<u8>> {
    let bytes = URL_SAFE_NO_PAD.decode(text).ok()?;
    (encode(&bytes) == text).then_some(bytes)
}

pub fn hex(bytes: &[u8]) -> String {
    const DIGITS: &[u8; 16] = b"0123456789abcdef";
    let mut out = String::with_capacity(bytes.len() * 2);
    for b in bytes {
        out.push(DIGITS[(b >> 4) as usize] as char);
        out.push(DIGITS[(b & 0x0f) as usize] as char);
    }
    out
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn round_trips() {
        let data = [0u8, 1, 2, 250, 251, 252, 253, 254, 255];
        assert_eq!(decode(&encode(&data)).as_deref(), Some(&data[..]));
    }

    #[test]
    fn rejects_padding_and_standard_alphabet() {
        assert_eq!(decode("AA=="), None);
        assert_eq!(decode("+/8"), None);
        assert!(decode("-_8").is_some());
    }

    #[test]
    fn rejects_non_canonical_trailing_bits() {
        // "AB" and "AA" both decode to [0x00] if trailing bits are ignored.
        assert_eq!(decode("AA").as_deref(), Some(&[0u8][..]));
        assert_eq!(decode("AB"), None);
    }

    #[test]
    fn hex_is_lowercase() {
        assert_eq!(hex(&[0x00, 0xab, 0xff]), "00abff");
    }
}
