//! View tokens, wire compat only. Nothing checks them any more, older app builds
//! still ask for one and get a random value that opens nothing.

use remoter_proto::b64;

pub const TTL_MS: i64 = 15 * 60 * 1000;

pub fn mint(now: i64) -> Option<(String, i64)> {
    let mut raw = [0u8; 32];
    getrandom::fill(&mut raw).ok()?;
    Some((b64::encode(&raw), now + TTL_MS))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn random_and_dated() {
        let (a, exp) = mint(1000).expect("mint");
        let (b, _) = mint(1000).expect("mint");
        assert_eq!(exp, 1000 + TTL_MS);
        assert_eq!(b64::decode(&a).map(|v| v.len()), Some(32));
        assert_ne!(a, b);
    }
}
