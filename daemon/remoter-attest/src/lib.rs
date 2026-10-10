//! Android key attestation for pairing and daily re-attestation.
//!
//! The chain checks and the key description parser are a port of
//! github.com/android/keyattestation; its test vectors live in `testdata/`.
//! The policy on top is ours.

pub mod chain;
pub mod der;
pub mod keydesc;
#[cfg(any(test, feature = "testkit"))]
pub mod testkit;

pub use chain::{Revoked, Roots};
pub use remoter_proto::admin::Weakness;

/// SHA-256 of the DER SPKI of Google's hardware attestation roots, as served
/// by https://android.googleapis.com/attestation/root and listed in
/// github.com/android/keyattestation `roots.json` (both fetched 2026-09-28 and
/// identical). Pinned by key, so a root reissued for the same key still counts.
pub const GOOGLE_ROOT_SPKI_SHA256: [[u8; 32]; 2] = [
    // serialNumber=f92009e853b6b045, the legacy RSA root, until 2042.
    hex32("feb2ea7551ee316ed4bb443c8293b884dbfdea40b603ee3e4f4a897e4580fbae"),
    // CN=Key Attestation CA1, O=Google LLC, the ECDSA root for RKP, until 2035.
    hex32("3ee44512a1af2beb39c889490c60ea3f82e43f5d5a5532f5ab9419f676cd07ec"),
];

const fn hex32(s: &str) -> [u8; 32] {
    const fn nib(c: u8) -> u8 {
        match c {
            b'0'..=b'9' => c - b'0',
            b'a'..=b'f' => c - b'a' + 10,
            _ => panic!("not lowercase hex"),
        }
    }
    let b = s.as_bytes();
    assert!(b.len() == 64);
    let mut out = [0u8; 32];
    let mut i = 0;
    while i < 32 {
        out[i] = nib(b[2 * i]) << 4 | nib(b[2 * i + 1]);
        i += 1;
    }
    out
}
use keydesc::{AuthList, KeyDescription, tag};

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Provisioning {
    Factory,
    Remote,
    Unknown,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Error {
    Input(String),
    Chain(String),
    Revoked(String),
    KeyDescription(String),
    /// A policy rule failed. The string names the rule.
    Policy(String),
}

impl std::fmt::Display for Error {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            Error::Input(m) => write!(f, "input: {m}"),
            Error::Chain(m) => write!(f, "chain: {m}"),
            Error::Revoked(s) => write!(f, "revoked certificate {s}"),
            Error::KeyDescription(m) => write!(f, "key description: {m}"),
            Error::Policy(m) => write!(f, "policy: {m}"),
        }
    }
}

impl std::error::Error for Error {}

/// Which authorization list `UNLOCKED_DEVICE_REQUIRED` must be in. Not yet
/// checked against a real S25 chain, so hardware only for now.
#[derive(Debug, Clone, Copy, PartialEq, Eq, serde::Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum UnlockedList {
    Hardware,
    Software,
    Either,
}

#[derive(Debug, Clone)]
pub struct Policy {
    pub app_package: String,
    pub app_cert_sha256: [u8; 32],
    pub max_patch_age_months: u32,
    pub unlocked_device_required_in: UnlockedList,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Role {
    /// The fingerprint key: StrongBox if the phone has one, fingerprint for every use.
    Sig,
    /// The TLS key: TEE or StrongBox.
    Tls,
    /// The throwaway TEE key for daily re-attestation.
    Reattest,
}

pub const SECURITY_TEE: i64 = 1;
pub const SECURITY_STRONGBOX: i64 = 2;
const PURPOSE_SIGN: i64 = 2;
const ALGORITHM_EC: i64 = 3;
const CURVE_P256: i64 = 1;
const ORIGIN_GENERATED: i64 = 0;
const AUTH_FINGERPRINT: i64 = 2;
const BOOT_VERIFIED: i64 = 0;

/// What pairing and re-attestation keep from a chain.
#[derive(Debug, Clone, PartialEq, Eq, serde::Serialize, serde::Deserialize)]
pub struct Attested {
    #[serde(with = "hexbytes")]
    pub leaf_spki: Vec<u8>,
    pub attestation_security_level: i64,
    pub keymint_security_level: i64,
    #[serde(with = "hexbytes")]
    pub verified_boot_key: Vec<u8>,
    #[serde(with = "hexbytes")]
    pub verified_boot_hash: Vec<u8>,
    pub os_patch_level: i64,
    pub boot_patch_level: i64,
    pub attestation_version: i64,
    pub manufacturer: Option<String>,
    pub model: Option<String>,
    pub device: Option<String>,
    pub remotely_provisioned: bool,
    #[serde(default, skip_serializing_if = "Vec::is_empty")]
    pub weaknesses: Vec<Weakness>,
}

mod hexbytes {
    pub fn serialize<S: serde::Serializer>(v: &[u8], s: S) -> Result<S::Ok, S::Error> {
        s.serialize_str(&v.iter().map(|b| format!("{b:02x}")).collect::<String>())
    }
    pub fn deserialize<'de, D: serde::Deserializer<'de>>(d: D) -> Result<Vec<u8>, D::Error> {
        let s: String = serde::Deserialize::deserialize(d)?;
        if !s.len().is_multiple_of(2) {
            return Err(serde::de::Error::custom("odd hex"));
        }
        (0..s.len()).step_by(2).map(|i| u8::from_str_radix(&s[i..i + 2], 16).map_err(serde::de::Error::custom)).collect()
    }
}

fn rule(ok: bool, name: &str) -> Result<(), Error> {
    if ok { Ok(()) } else { Err(Error::Policy(name.into())) }
}

/// Months since year 0, from a YYYYMM or YYYYMMDD patch level.
fn patch_months(level: i64) -> Option<i64> {
    let ym = match level {
        100_000..=999_999 => level,
        10_000_000..=99_999_999 => level / 100,
        _ => return None,
    };
    let (y, m) = (ym / 100, ym % 100);
    (1..=12).contains(&m).then_some(y * 12 + m - 1)
}

fn months_now(now_unix: i64) -> i64 {
    // Civil from days (Howard Hinnant), enough for a year and a month.
    let z = now_unix.div_euclid(86_400) + 719_468;
    let era = z.div_euclid(146_097);
    let doe = z - era * 146_097;
    let yoe = (doe - doe / 1460 + doe / 36_524 - doe / 146_096) / 365;
    let doy = doe - (365 * yoe + yoe / 4 - yoe / 100);
    let mp = (5 * doy + 2) / 153;
    let m = if mp < 10 { mp + 3 } else { mp - 9 };
    let y = yoe + era * 400 + if m <= 2 { 1 } else { 0 };
    y * 12 + m - 1
}

/// The whole policy, for one chain.
pub fn check(chain: &[Vec<u8>], challenge: &[u8], role: Role, policy: &Policy, roots: &Roots, revoked: &Revoked, now_unix: i64) -> Result<Attested, Error> {
    let v = chain::verify(chain, roots, revoked, now_unix)?;
    let kd: &KeyDescription = &v.key_description;
    let hw: &AuthList = &kd.hardware;
    let sw: &AuthList = &kd.software;

    rule(kd.challenge == challenge, "attestationChallenge")?;
    let mut weaknesses = Vec::new();
    // hardware either way, a key in software is still refused
    let levels_ok = [SECURITY_TEE, SECURITY_STRONGBOX].contains(&kd.attestation_security_level) && [SECURITY_TEE, SECURITY_STRONGBOX].contains(&kd.keymint_security_level);
    rule(levels_ok, "security level")?;
    if role == Role::Sig && (kd.attestation_security_level != SECURITY_STRONGBOX || kd.keymint_security_level != SECURITY_STRONGBOX) {
        weaknesses.push(Weakness::NoStrongBox);
    }
    if let Some(chain_level) = v.chain_security_level {
        rule(chain_level == kd.attestation_security_level, "security level named by the chain")?;
    }

    // Authorization properties come from the hardware list only.
    rule(hw.origin == Some(ORIGIN_GENERATED), "origin GENERATED")?;
    rule(hw.purposes.as_ref().is_some_and(|p| p.len() == 1 && p.contains(&PURPOSE_SIGN)), "purpose SIGN only")?;
    rule(hw.algorithm == Some(ALGORITHM_EC), "algorithm EC")?;
    rule(hw.ec_curve == Some(CURVE_P256), "curve P-256")?;
    if role == Role::Sig {
        rule(!hw.has(tag::NO_AUTH_REQUIRED) && !sw.has(tag::NO_AUTH_REQUIRED), "NO_AUTH_REQUIRED absent")?;
        rule(hw.user_auth_type == Some(AUTH_FINGERPRINT), "USER_AUTH_TYPE fingerprint only")?;
        rule(!hw.has(tag::AUTH_TIMEOUT) && !sw.has(tag::AUTH_TIMEOUT), "AUTH_TIMEOUT absent")?;
    }
    if role != Role::Reattest {
        let (h, s) = (hw.has(tag::UNLOCKED_DEVICE_REQUIRED), sw.has(tag::UNLOCKED_DEVICE_REQUIRED));
        let present = match policy.unlocked_device_required_in {
            UnlockedList::Hardware => h,
            UnlockedList::Software => s,
            UnlockedList::Either => h || s,
        };
        rule(present, "UNLOCKED_DEVICE_REQUIRED")?;
    }

    let rot = hw.root_of_trust.as_ref().ok_or_else(|| Error::Policy("rootOfTrust in the hardware list".into()))?;
    if !rot.device_locked {
        weaknesses.push(Weakness::BootloaderUnlocked);
    }
    if rot.verified_boot_state != BOOT_VERIFIED {
        weaknesses.push(Weakness::BootNotVerified);
    }
    let boot_hash = rot.verified_boot_hash.clone().ok_or_else(|| Error::Policy("verifiedBootHash present".into()))?;
    rule(!rot.verified_boot_key.is_empty(), "verifiedBootKey present")?;

    let now = months_now(now_unix);
    let fresh = |lvl: Option<i64>| lvl.and_then(patch_months).is_some_and(|m| now - m <= policy.max_patch_age_months as i64);
    rule(fresh(hw.os_patch_level), "osPatchLevel fresh")?;
    rule(fresh(hw.boot_patch_level), "bootPatchLevel fresh")?;

    // The one property that lives in the software list by design.
    let app = sw.app_id.as_ref().ok_or_else(|| Error::Policy("attestationApplicationId present".into()))?;
    rule(app.packages.len() == 1 && app.packages[0].0 == policy.app_package, "exactly one package, ours")?;
    rule(app.signatures.len() == 1 && app.signatures[0] == policy.app_cert_sha256, "exactly one signing digest, ours")?;

    Ok(Attested {
        leaf_spki: v.leaf_spki,
        attestation_security_level: kd.attestation_security_level,
        keymint_security_level: kd.keymint_security_level,
        verified_boot_key: rot.verified_boot_key.clone(),
        verified_boot_hash: boot_hash,
        os_patch_level: hw.os_patch_level.unwrap_or_default(),
        boot_patch_level: hw.boot_patch_level.unwrap_or_default(),
        attestation_version: kd.attestation_version,
        manufacturer: hw.manufacturer.clone(),
        model: hw.model.clone(),
        device: hw.device.clone(),
        remotely_provisioned: v.provisioning == Provisioning::Remote,
        weaknesses,
    })
}

/// Both pairing chains, and the proof they came from one phone.
pub fn check_pair(
    tls_chain: &[Vec<u8>],
    sig_chain: &[Vec<u8>],
    challenge: &[u8],
    policy: &Policy,
    roots: &Roots,
    revoked: &Revoked,
    now_unix: i64,
) -> Result<(Attested, Attested), Error> {
    let tls = check(tls_chain, challenge, Role::Tls, policy, roots, revoked, now_unix)?;
    let sig = check(sig_chain, challenge, Role::Sig, policy, roots, revoked, now_unix)?;
    rule(tls.verified_boot_key == sig.verified_boot_key && tls.verified_boot_hash == sig.verified_boot_hash, "both keys from one device")?;
    Ok((tls, sig))
}

pub fn weaknesses(tls: &Attested, sig: &Attested) -> Vec<Weakness> {
    let mut w: Vec<Weakness> = tls.weaknesses.iter().chain(&sig.weaknesses).copied().collect();
    w.sort();
    w.dedup();
    w
}

/// The daily check: a throwaway key from the same phone, with fresh patches,
/// and nothing weaker than at pairing.
#[allow(clippy::too_many_arguments)]
pub fn check_reattest(
    chain: &[Vec<u8>],
    challenge: &[u8],
    boot_key: &[u8],
    paired_with: &[Weakness],
    policy: &Policy,
    roots: &Roots,
    revoked: &Revoked,
    now_unix: i64,
) -> Result<Attested, Error> {
    let a = check(chain, challenge, Role::Reattest, policy, roots, revoked, now_unix)?;
    rule(a.verified_boot_key == boot_key, "same verifiedBootKey as at pairing")?;
    for w in &a.weaknesses {
        let new = match w {
            Weakness::BootloaderUnlocked => "device locked",
            Weakness::BootNotVerified => "verified boot state Verified",
            Weakness::NoStrongBox => continue,
        };
        rule(paired_with.contains(w), new)?;
    }
    Ok(a)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn patch_ages() {
        assert_eq!(patch_months(202511), Some(2025 * 12 + 10));
        assert_eq!(patch_months(20251105), Some(2025 * 12 + 10));
        assert_eq!(patch_months(202513), None);
        assert_eq!(patch_months(2025), None);
        // 2026-09-28 is September.
        assert_eq!(months_now(1_790_611_106), 2026 * 12 + 8);
        assert_eq!(months_now(0), 1970 * 12);
        assert_eq!(months_now(951_782_400), 2000 * 12 + 1, "2000-02-29");
    }
}
