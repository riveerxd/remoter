//! Attestation state: roots and revocation status pushed by remoterctl (we have
//! no internet), per device freshness, and re-attestation challenges.

use std::collections::HashMap;
use std::path::PathBuf;

use remoter_attest::{Policy, Revoked, Roots, UnlockedList};
use remoter_proto::local;
use serde::{Deserialize, Serialize};

#[derive(Debug, Clone, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct AttestConfig {
    pub app_package: String,
    /// hex, colons allowed since keytool prints them
    pub app_cert_sha256: String,
    pub max_patch_age_months: u32,
    pub fresh_hours: u32,
    /// which authorization list UNLOCKED_DEVICE_REQUIRED has to show up in
    #[serde(default = "d_unlocked")]
    pub unlocked_device_required_in: UnlockedList,
    /// older pushed data stops counting until `remoterctl refresh`
    #[serde(default = "d_status_age")]
    pub status_max_age_days: u32,
}

fn d_unlocked() -> UnlockedList {
    UnlockedList::Hardware
}
fn d_status_age() -> u32 {
    7
}

pub fn parse_digest(s: &str) -> Result<[u8; 32], String> {
    let hex: String = s.chars().filter(|c| *c != ':').collect();
    if hex.len() != 64 || !hex.bytes().all(|b| b.is_ascii_hexdigit()) {
        return Err("app_cert_sha256 must be 64 hex digits".into());
    }
    let mut out = [0u8; 32];
    for (i, o) in out.iter_mut().enumerate() {
        *o = u8::from_str_radix(&hex[2 * i..2 * i + 2], 16).map_err(|e| e.to_string())?;
    }
    Ok(out)
}

impl AttestConfig {
    pub fn policy(&self) -> Result<Policy, String> {
        Ok(Policy {
            app_package: self.app_package.clone(),
            app_cert_sha256: parse_digest(&self.app_cert_sha256)?,
            max_patch_age_months: self.max_patch_age_months,
            unlocked_device_required_in: self.unlocked_device_required_in,
        })
    }
}

#[derive(Default, Serialize, Deserialize)]
struct DataFile {
    roots: String,
    status: String,
    fetched_at: i64,
}

pub struct AttestState {
    data_file: PathBuf,
    fresh_file: PathBuf,
    roots: Option<Roots>,
    revoked: Option<Revoked>,
    fetched_at: i64,
    max_age_ms: i64,
    pins: Vec<[u8; 32]>,
    fresh: HashMap<String, i64>,
    challenges: HashMap<String, (Vec<u8>, i64)>,
}

pub const CHALLENGE_TTL_MS: i64 = 5 * 60 * 1000;

impl AttestState {
    /// Only roots in `pins` are ever taken from pushed data.
    pub fn open(state_dir: &std::path::Path, max_age_days: u32, pins: Vec<[u8; 32]>) -> AttestState {
        let data_file = state_dir.join("attest-data.json");
        let fresh_file = state_dir.join("attest-fresh.json");
        let mut s = AttestState {
            data_file,
            fresh_file,
            roots: None,
            revoked: None,
            fetched_at: 0,
            max_age_ms: max_age_days as i64 * 86_400_000,
            pins,
            fresh: HashMap::new(),
            challenges: HashMap::new(),
        };
        if let Some(d) = std::fs::read(&s.data_file).ok().and_then(|b| serde_json::from_slice::<DataFile>(&b).ok()) {
            let _ = s.load(&d.roots, &d.status, d.fetched_at);
        }
        s.fresh = std::fs::read(&s.fresh_file).ok().and_then(|b| serde_json::from_slice(&b).ok()).unwrap_or_default();
        s
    }

    fn load(&mut self, roots: &str, status: &str, fetched_at: i64) -> Result<(), String> {
        let r = Roots::from_json(roots.as_bytes()).and_then(|r| r.pinned(&self.pins)).map_err(|e| e.to_string())?;
        let v = Revoked::from_json(status.as_bytes()).map_err(|e| e.to_string())?;
        self.roots = Some(r);
        self.revoked = Some(v);
        self.fetched_at = fetched_at;
        Ok(())
    }

    /// All or nothing.
    pub fn set_data(&mut self, roots: &str, status: &str) -> Result<usize, String> {
        let now = local::now_ms();
        self.load(roots, status, now)?;
        let json = serde_json::to_vec(&DataFile { roots: roots.into(), status: status.into(), fetched_at: now }).map_err(|e| e.to_string())?;
        local::write_atomic(&self.data_file, &json).map_err(|e| e.to_string())?;
        Ok(self.roots.as_ref().map_or(0, |r| r.len()))
    }

    pub fn data(&self) -> Result<(&Roots, &Revoked), String> {
        match (&self.roots, &self.revoked) {
            (Some(r), Some(v)) if local::now_ms() - self.fetched_at <= self.max_age_ms => Ok((r, v)),
            (Some(_), Some(_)) => Err("attestation data is stale, run sudo remoterctl refresh".into()),
            _ => Err("no attestation data yet, run sudo remoterctl refresh".into()),
        }
    }

    pub fn fresh_until(&self, device: &str) -> Option<i64> {
        self.fresh.get(device).copied()
    }

    pub fn is_fresh(&self, device: &str) -> bool {
        self.fresh_until(device).is_some_and(|t| t > local::now_ms())
    }

    pub fn mark_fresh(&mut self, device: &str, until: i64) {
        self.fresh.insert(device.to_owned(), until);
        if let Ok(json) = serde_json::to_vec(&self.fresh) {
            let _ = local::write_atomic(&self.fresh_file, &json);
        }
    }

    pub fn new_challenge(&mut self, device: &str) -> Result<(Vec<u8>, i64), String> {
        let mut c = vec![0u8; 16];
        getrandom::fill(&mut c).map_err(|e| e.to_string())?;
        let expires = local::now_ms() + CHALLENGE_TTL_MS;
        self.challenges.insert(device.to_owned(), (c.clone(), expires));
        Ok((c, expires))
    }

    /// Single use, gone whether or not the chain passes.
    pub fn take_challenge(&mut self, device: &str) -> Option<Vec<u8>> {
        let (c, exp) = self.challenges.remove(device)?;
        (exp > local::now_ms()).then_some(c)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn digest_forms() {
        let colons = "5A:".repeat(31) + "5A";
        assert_eq!(parse_digest(&colons), Ok([0x5a; 32]));
        assert_eq!(parse_digest(&"5a".repeat(32)), Ok([0x5a; 32]));
        assert!(parse_digest("5a").is_err());
        assert!(parse_digest(&"zz".repeat(32)).is_err());
    }

    #[test]
    fn challenges_single_use() {
        let dir = std::env::temp_dir().join(format!("remoter-attest-state-{}", std::process::id()));
        std::fs::create_dir_all(&dir).expect("mk");
        let mut s = AttestState::open(&dir, 7, remoter_attest::GOOGLE_ROOT_SPKI_SHA256.to_vec());
        let (c, _) = s.new_challenge("A").expect("c");
        assert_eq!(s.take_challenge("A"), Some(c));
        assert_eq!(s.take_challenge("A"), None, "used once");
        assert!(s.data().is_err(), "no data yet means no decisions");
        s.mark_fresh("A", local::now_ms() + 1000);
        assert!(AttestState::open(&dir, 7, remoter_attest::GOOGLE_ROOT_SPKI_SHA256.to_vec()).is_fresh("A"), "kept across a restart");
        let _ = std::fs::remove_dir_all(&dir);
    }

    #[test]
    fn only_googles_roots_are_taken() {
        let dir = std::env::temp_dir().join(format!("remoter-attest-pins-{}", std::process::id()));
        std::fs::create_dir_all(&dir).expect("mk");
        let mut s = AttestState::open(&dir, 7, remoter_attest::GOOGLE_ROOT_SPKI_SHA256.to_vec());
        let stand_in = remoter_attest::testkit::Pki::new("testroot0000");
        let roots = String::from_utf8(stand_in.roots_json()).expect("utf8");
        assert!(s.set_data(&roots, r#"{"entries":{}}"#).is_err(), "unpinned root");
        assert!(s.data().is_err(), "and nothing was taken");
        // same data with the root pinned goes through
        let mut t = AttestState::open(&dir, 7, vec![stand_in.root_pin()]);
        assert_eq!(t.set_data(&roots, r#"{"entries":{}}"#), Ok(1));
        let _ = std::fs::remove_dir_all(&dir);
    }
}
