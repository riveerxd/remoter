//! Pairing MAC and confirmation code.
//!
//! MAC input is `"remoter-pair-v1" ‖ fp ‖ tls_spki ‖ sig_spki ‖ device_name`,
//! each part with a 4 byte big endian length in front, so no device name can
//! shift bytes between fields and give two transcripts the same MAC.

use hmac::{Hmac, KeyInit, Mac};
use sha2::{Digest, Sha256};

pub const PAIR_TAG: &[u8] = b"remoter-pair-v1";

pub struct Transcript<'a> {
    /// SHA-256 of the laptop's server SPKI, raw 32 bytes.
    pub server_fp: &'a [u8; 32],
    /// DER SPKI of the leaf of the phone's `tls` chain.
    pub tls_spki: &'a [u8],
    /// DER SPKI of the leaf of the phone's `sig` chain.
    pub sig_spki: &'a [u8],
    pub device_name: &'a str,
}

impl Transcript<'_> {
    pub fn bytes(&self) -> Vec<u8> {
        let parts: [&[u8]; 5] = [PAIR_TAG, self.server_fp, self.tls_spki, self.sig_spki, self.device_name.as_bytes()];
        let mut out = Vec::with_capacity(parts.iter().map(|p| p.len() + 4).sum());
        for p in parts {
            out.extend_from_slice(&(p.len() as u32).to_be_bytes());
            out.extend_from_slice(p);
        }
        out
    }
}

pub fn mac(secret: &[u8; 32], t: &Transcript<'_>) -> [u8; 32] {
    let mut m = <Hmac<Sha256> as KeyInit>::new_from_slice(secret).expect("HMAC takes any key length");
    m.update(&t.bytes());
    m.finalize().into_bytes().into()
}

/// Constant time check, so timing can't reveal how much of a guessed MAC
/// was right.
pub fn mac_matches(secret: &[u8; 32], t: &Transcript<'_>, candidate: &[u8]) -> bool {
    let mut m = <Hmac<Sha256> as KeyInit>::new_from_slice(secret).expect("HMAC takes any key length");
    m.update(&t.bytes());
    m.verify_slice(candidate).is_ok()
}

/// Six digits from `SHA-256(S ‖ transcript)`, zero padded.
pub fn confirmation_code(secret: &[u8; 32], t: &Transcript<'_>) -> String {
    let mut h = Sha256::new();
    h.update(secret);
    h.update(t.bytes());
    let d = h.finalize();
    let n = u32::from_be_bytes([d[0], d[1], d[2], d[3]]) % 1_000_000;
    format!("{n:06}")
}

/// The `remoter://pair` link shown as a QR and as text.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Link {
    pub host: std::net::Ipv4Addr,
    pub port: u16,
    pub pair_port: u16,
    pub server_fp: [u8; 32],
    pub secret: [u8; 32],
    pub challenge: [u8; 16],
    pub expires_unix: i64,
}

impl Link {
    pub fn to_uri(&self) -> String {
        use crate::b64::encode;
        format!(
            "remoter://pair?v=1&h={}&p={}&pp={}&fp={}&s={}&c={}&exp={}",
            self.host,
            self.port,
            self.pair_port,
            encode(&self.server_fp),
            encode(&self.secret),
            encode(&self.challenge),
            self.expires_unix
        )
    }

    /// Exact field order and spelling, nothing extra. A pasted link that is
    /// even slightly off is refused rather than guessed at.
    pub fn parse(uri: &str) -> Option<Link> {
        use crate::b64::decode;
        let rest = uri.strip_prefix("remoter://pair?")?;
        let mut it = rest.split('&');
        let mut field = |key: &str| it.next()?.strip_prefix(key)?.strip_prefix('=');
        if field("v")? != "1" {
            return None;
        }
        let host = field("h")?.parse().ok()?;
        let port = field("p")?.parse().ok()?;
        let pair_port = field("pp")?.parse().ok()?;
        let server_fp = decode(field("fp")?)?.try_into().ok()?;
        let secret = decode(field("s")?)?.try_into().ok()?;
        let challenge = decode(field("c")?)?.try_into().ok()?;
        let expires_unix = crate::canonical::parse_timestamp(field("exp")?)?;
        if it.next().is_some() {
            return None;
        }
        let link = Link { host, port, pair_port, server_fp, secret, challenge, expires_unix };
        (link.to_uri() == uri).then_some(link)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn t<'a>(fp: &'a [u8; 32], name: &'a str) -> Transcript<'a> {
        Transcript { server_fp: fp, tls_spki: b"tls-spki", sig_spki: b"sig-spki", device_name: name }
    }

    #[test]
    fn mac_verifies_and_rejects_changes() {
        let s = [7u8; 32];
        let fp = [1u8; 32];
        let good = mac(&s, &t(&fp, "S25 Ultra"));
        assert!(mac_matches(&s, &t(&fp, "S25 Ultra"), &good));
        assert!(!mac_matches(&s, &t(&fp, "S25 Ultrb"), &good));
        assert!(!mac_matches(&[8u8; 32], &t(&fp, "S25 Ultra"), &good));
        assert!(!mac_matches(&s, &t(&[2u8; 32], "S25 Ultra"), &good));
    }

    #[test]
    fn field_boundaries_are_unambiguous() {
        let fp = [1u8; 32];
        let a = Transcript { server_fp: &fp, tls_spki: b"ab", sig_spki: b"c", device_name: "x" };
        let b = Transcript { server_fp: &fp, tls_spki: b"a", sig_spki: b"bc", device_name: "x" };
        assert_ne!(a.bytes(), b.bytes());
    }

    #[test]
    fn link_round_trips_and_is_strict() {
        let l = Link {
            host: std::net::Ipv4Addr::new(10, 66, 66, 3),
            port: 8443,
            pair_port: 8444,
            server_fp: [0xa1; 32],
            secret: [0x5a; 32],
            challenge: [3; 16],
            expires_unix: 1_790_611_406,
        };
        let uri = l.to_uri();
        assert_eq!(Link::parse(&uri), Some(l));
        assert_eq!(Link::parse(&format!("{uri}&x=1")), None);
        assert_eq!(Link::parse(&uri.replace("v=1", "v=2")), None);
        assert_eq!(Link::parse(&uri.replace("p=8443", "p=08443")), None);
        assert_eq!(Link::parse(&uri.replacen("&c=", "&c=A", 1)), None);
        assert_eq!(Link::parse(&uri.replace("remoter://", "https://")), None);
    }

    #[test]
    fn code_is_six_digits() {
        let code = confirmation_code(&[9u8; 32], &t(&[1u8; 32], "S25 Ultra"));
        assert_eq!(code.len(), 6);
        assert!(code.bytes().all(|b| b.is_ascii_digit()));
    }
}
