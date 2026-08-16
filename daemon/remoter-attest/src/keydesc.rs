//! The key description extension (OID 1.3.6.1.4.1.11129.2.1.17), ported from
//! android/keyattestation's Extension.kt. Unlike that library, a malformed
//! field here fails the whole parse instead of being logged and dropped: a
//! field we can't read is a field we can't check.

use std::collections::BTreeSet;

use crate::der::{self, DerError, Tlv};

pub const OID: &str = "1.3.6.1.4.1.11129.2.1.17";

pub mod tag {
    pub const PURPOSE: u32 = 1;
    pub const ALGORITHM: u32 = 2;
    pub const KEY_SIZE: u32 = 3;
    pub const DIGEST: u32 = 5;
    pub const EC_CURVE: u32 = 10;
    pub const NO_AUTH_REQUIRED: u32 = 503;
    pub const USER_AUTH_TYPE: u32 = 504;
    pub const AUTH_TIMEOUT: u32 = 505;
    pub const UNLOCKED_DEVICE_REQUIRED: u32 = 509;
    pub const CREATION_DATE_TIME: u32 = 701;
    pub const ORIGIN: u32 = 702;
    pub const ROOT_OF_TRUST: u32 = 704;
    pub const OS_VERSION: u32 = 705;
    pub const OS_PATCH_LEVEL: u32 = 706;
    pub const ATTESTATION_APPLICATION_ID: u32 = 709;
    pub const ATTESTATION_ID_BRAND: u32 = 710;
    pub const ATTESTATION_ID_DEVICE: u32 = 711;
    pub const ATTESTATION_ID_MANUFACTURER: u32 = 716;
    pub const ATTESTATION_ID_MODEL: u32 = 717;
    pub const VENDOR_PATCH_LEVEL: u32 = 718;
    pub const BOOT_PATCH_LEVEL: u32 = 719;
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct RootOfTrust {
    pub verified_boot_key: Vec<u8>,
    pub device_locked: bool,
    /// 0 Verified, 1 SelfSigned, 2 Unverified, 3 Failed.
    pub verified_boot_state: i64,
    pub verified_boot_hash: Option<Vec<u8>>,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct AppId {
    pub packages: Vec<(String, i64)>,
    pub signatures: Vec<Vec<u8>>,
}

#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct AuthList {
    /// Every tag present, whether or not it's read below.
    pub tags: BTreeSet<u32>,
    pub purposes: Option<BTreeSet<i64>>,
    pub algorithm: Option<i64>,
    pub key_size: Option<i64>,
    pub digests: Option<BTreeSet<i64>>,
    pub ec_curve: Option<i64>,
    pub user_auth_type: Option<i64>,
    pub auth_timeout: Option<i64>,
    pub creation_date_time: Option<i64>,
    pub origin: Option<i64>,
    pub root_of_trust: Option<RootOfTrust>,
    pub os_version: Option<i64>,
    pub os_patch_level: Option<i64>,
    pub app_id: Option<AppId>,
    pub brand: Option<String>,
    pub device: Option<String>,
    pub manufacturer: Option<String>,
    pub model: Option<String>,
    pub vendor_patch_level: Option<i64>,
    pub boot_patch_level: Option<i64>,
}

impl AuthList {
    pub fn has(&self, t: u32) -> bool {
        self.tags.contains(&t)
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct KeyDescription {
    pub attestation_version: i64,
    /// 0 Software, 1 TrustedEnvironment, 2 StrongBox.
    pub attestation_security_level: i64,
    pub keymint_version: i64,
    pub keymint_security_level: i64,
    pub challenge: Vec<u8>,
    pub unique_id: Vec<u8>,
    pub software: AuthList,
    pub hardware: AuthList,
}

/// App ids list at most this many packages and digests, as in the library's
/// input limits. Anything bigger is refused before it's walked.
const MAX_PACKAGES: usize = 32;
const MAX_SIGNATURES: usize = 10;

fn int_set(t: &Tlv<'_>) -> der::Result<BTreeSet<i64>> {
    let mut s = BTreeSet::new();
    for i in t.set()? {
        if !s.insert(i.integer()?) {
            return Err(DerError("duplicate in a set"));
        }
    }
    Ok(s)
}

fn utf8(t: &Tlv<'_>) -> der::Result<String> {
    String::from_utf8(t.octets()?.to_vec()).map_err(|_| DerError("not UTF-8"))
}

fn root_of_trust(t: &Tlv<'_>) -> der::Result<RootOfTrust> {
    let f = t.sequence()?;
    if f.len() != 3 && f.len() != 4 {
        return Err(DerError("rootOfTrust has 3 or 4 fields"));
    }
    Ok(RootOfTrust {
        verified_boot_key: f[0].octets()?.to_vec(),
        device_locked: f[1].boolean()?,
        verified_boot_state: f[2].enumerated()?,
        verified_boot_hash: f.get(3).map(|h| h.octets().map(|o| o.to_vec())).transpose()?,
    })
}

fn app_id(t: &Tlv<'_>) -> der::Result<AppId> {
    let inner = der::read_all(t.octets()?)?;
    let f = inner.sequence()?;
    if f.len() != 2 {
        return Err(DerError("attestationApplicationId has 2 fields"));
    }
    let pkgs = f[0].set()?;
    let sigs = f[1].set()?;
    if pkgs.len() > MAX_PACKAGES || sigs.len() > MAX_SIGNATURES {
        return Err(DerError("attestationApplicationId too big"));
    }
    let mut packages = Vec::new();
    for p in pkgs {
        let pf = p.sequence()?;
        if pf.len() != 2 {
            return Err(DerError("package info has 2 fields"));
        }
        packages.push((utf8(&pf[0])?, pf[1].integer()?));
    }
    let signatures = sigs.iter().map(|s| s.octets().map(|o| o.to_vec())).collect::<der::Result<_>>()?;
    Ok(AppId { packages, signatures })
}

fn auth_list(t: &Tlv<'_>) -> der::Result<AuthList> {
    let mut a = AuthList::default();
    let mut last = None;
    for item in t.sequence()? {
        // Ascending order also rules out a tag appearing twice, so there is
        // never a question of which copy counts.
        if last.is_some_and(|l| item.tag <= l) {
            return Err(DerError("authorization list tags must be in ascending order"));
        }
        last = Some(item.tag);
        if item.tag == 0 {
            return Err(DerError("tag 0 is KeyMint's INVALID"));
        }
        let v = item.explicit()?;
        a.tags.insert(item.tag);
        use tag::*;
        match item.tag {
            PURPOSE => a.purposes = Some(int_set(&v)?),
            ALGORITHM => a.algorithm = Some(v.integer()?),
            KEY_SIZE => a.key_size = Some(v.integer()?),
            DIGEST => a.digests = Some(int_set(&v)?),
            EC_CURVE => a.ec_curve = Some(v.integer()?),
            NO_AUTH_REQUIRED | UNLOCKED_DEVICE_REQUIRED => v.null()?,
            USER_AUTH_TYPE => a.user_auth_type = Some(v.integer()?),
            AUTH_TIMEOUT => a.auth_timeout = Some(v.integer()?),
            CREATION_DATE_TIME => a.creation_date_time = Some(v.integer()?),
            ORIGIN => a.origin = Some(v.integer()?),
            ROOT_OF_TRUST => a.root_of_trust = Some(root_of_trust(&v)?),
            OS_VERSION => a.os_version = Some(v.integer()?),
            OS_PATCH_LEVEL => a.os_patch_level = Some(v.integer()?),
            ATTESTATION_APPLICATION_ID => a.app_id = Some(app_id(&v)?),
            ATTESTATION_ID_BRAND => a.brand = Some(utf8(&v)?),
            ATTESTATION_ID_DEVICE => a.device = Some(utf8(&v)?),
            ATTESTATION_ID_MANUFACTURER => a.manufacturer = Some(utf8(&v)?),
            ATTESTATION_ID_MODEL => a.model = Some(utf8(&v)?),
            VENDOR_PATCH_LEVEL => a.vendor_patch_level = Some(v.integer()?),
            BOOT_PATCH_LEVEL => a.boot_patch_level = Some(v.integer()?),
            // Tags we don't check are skipped, not refused: KeyMint adds tags
            // over time, and a new phone must not fail on one we never read.
            _ => {}
        }
    }
    Ok(a)
}

/// Parses the extension's value (the OCTET STRING contents).
pub fn parse(bytes: &[u8]) -> der::Result<KeyDescription> {
    let top = der::read_all(bytes)?;
    let f = top.sequence()?;
    if f.len() != 8 {
        return Err(DerError("key description has 8 fields"));
    }
    Ok(KeyDescription {
        attestation_version: f[0].integer()?,
        attestation_security_level: f[1].enumerated()?,
        keymint_version: f[2].integer()?,
        keymint_security_level: f[3].enumerated()?,
        challenge: f[4].octets()?.to_vec(),
        unique_id: f[5].octets()?.to_vec(),
        software: auth_list(&f[6])?,
        hardware: auth_list(&f[7])?,
    })
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::testkit::{explicit, int, octets, seq};

    fn description(hw: Vec<u8>) -> Vec<u8> {
        let sw = seq(&[]);
        seq(&[int(300), crate::testkit::enumerated(2), int(300), crate::testkit::enumerated(2), octets(b"c"), octets(b""), sw, hw])
    }

    /// Found by the attestation fuzzer: tag 0 is KeyMint's INVALID and never
    /// appears in a real authorization list.
    #[test]
    fn tag_zero_is_refused() {
        assert!(parse(&description(seq(&[explicit(0, &int(1))]))).is_err());
        assert!(parse(&description(seq(&[explicit(1, &crate::testkit::set(&[int(2)]))]))).is_ok());
    }
}
