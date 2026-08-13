//! `/etc/remoter/devices.json`: paired phones, public keys only. Written by
//! `sudo remoterctl pair`, read by both daemons.

use std::collections::HashMap;
use std::path::Path;

use p256::ecdsa::VerifyingKey;
use p256::pkcs8::DecodePublicKey;
use remoter_proto::{b64, canonical};
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct DeviceRecord {
    pub id: String,
    pub name: String,
    /// base64url SHA-256 of the DER SPKI of the phone's `tls` key.
    pub tls_spki_sha256: String,
    /// base64url DER SPKI of the phone's `sig` key.
    pub sig_pub: String,
    /// Hex, from the attestation's root of trust.
    pub verified_boot_key: String,
    #[serde(default)]
    pub attestation: serde_json::Value,
    pub paired_at: i64,
}

#[derive(Debug, Clone, Default, PartialEq, Eq, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct DeviceFile {
    pub devices: Vec<DeviceRecord>,
}

#[derive(Debug, Clone)]
pub struct Device {
    pub record: DeviceRecord,
    pub tls_spki_sha256: [u8; 32],
    pub sig_key: VerifyingKey,
}

#[derive(Debug, Clone, Default)]
pub struct Devices {
    by_id: HashMap<String, Device>,
    by_tls: HashMap<[u8; 32], String>,
}

impl Devices {
    /// Refuses the whole file on any bad record, rather than quietly dropping
    /// one: a half loaded device list is a confusing way to fail.
    pub fn parse(bytes: &[u8]) -> Result<Devices, String> {
        let file: DeviceFile = serde_json::from_slice(bytes).map_err(|e| format!("devices file: {e}"))?;
        let mut d = Devices::default();
        for r in file.devices {
            if !canonical::is_device_id(&r.id) {
                return Err(format!("device id {:?} is not a ULID", r.id));
            }
            let tls: [u8; 32] = b64::decode(&r.tls_spki_sha256)
                .and_then(|v| v.try_into().ok())
                .ok_or_else(|| format!("{}: bad tls_spki_sha256", r.id))?;
            let der = b64::decode(&r.sig_pub).ok_or_else(|| format!("{}: bad sig_pub", r.id))?;
            let sig_key = VerifyingKey::from_public_key_der(&der).map_err(|_| format!("{}: sig_pub isn't P-256", r.id))?;
            if d.by_tls.insert(tls, r.id.clone()).is_some() {
                return Err(format!("{}: tls key already belongs to another device", r.id));
            }
            let id = r.id.clone();
            if d.by_id.insert(id.clone(), Device { record: r, tls_spki_sha256: tls, sig_key }).is_some() {
                return Err(format!("{id}: listed twice"));
            }
        }
        Ok(d)
    }

    pub fn load(path: &Path) -> Result<Devices, String> {
        match std::fs::read(path) {
            Ok(b) => Devices::parse(&b),
            // nothing paired yet
            Err(e) if e.kind() == std::io::ErrorKind::NotFound => Ok(Devices::default()),
            Err(e) => Err(format!("{}: {e}", path.display())),
        }
    }

    pub fn get(&self, id: &str) -> Option<&Device> {
        self.by_id.get(id)
    }

    pub fn by_tls_spki(&self, spki_der: &[u8]) -> Option<&Device> {
        let h: [u8; 32] = Sha256::digest(spki_der).into();
        self.by_tls.get(&h).and_then(|id| self.by_id.get(id))
    }

    pub fn by_tls_hash(&self, h: &[u8; 32]) -> Option<&Device> {
        self.by_tls.get(h).and_then(|id| self.by_id.get(id))
    }

    pub fn has_tls_hash(&self, h: &[u8; 32]) -> bool {
        self.by_tls.contains_key(h)
    }

    /// The same list without the given ids, for devices that unpaired
    /// themselves and wait for `remoterctl` to tidy the file.
    pub fn without(&self, ids: &std::collections::HashSet<String>) -> Devices {
        let mut d = self.clone();
        d.by_id.retain(|id, _| !ids.contains(id));
        d.by_tls.retain(|_, id| !ids.contains(id));
        d
    }

    pub fn len(&self) -> usize {
        self.by_id.len()
    }

    pub fn is_empty(&self) -> bool {
        self.by_id.is_empty()
    }

    pub fn ids(&self) -> impl Iterator<Item = &String> {
        self.by_id.keys()
    }
}
