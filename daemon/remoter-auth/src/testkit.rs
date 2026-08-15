//! Software stand ins for the phone, for tests in this crate and the
//! daemons. Never used outside tests.

use p256::ecdsa::signature::Signer;
use p256::ecdsa::{Signature, SigningKey};
use p256::pkcs8::EncodePublicKey;
use remoter_proto::b64;
use remoter_proto::canonical::{SignInput, canonical};
use sha2::{Digest, Sha256};

use crate::RawSigHeaders;
use crate::devices::{DeviceFile, DeviceRecord};

pub struct Phone {
    pub id: String,
    pub sig: SigningKey,
    /// DER SPKI of the phone's TLS key.
    pub tls_spki: Vec<u8>,
}

impl Phone {
    /// Keys from fixed seeds, so failures reproduce.
    pub fn new(id: &str, seed: u8, tls_spki: Vec<u8>) -> Phone {
        let mut scalar = [seed; 32];
        scalar[0] = 0x11;
        Phone { id: id.into(), sig: SigningKey::from_bytes(&scalar.into()).expect("scalar"), tls_spki }
    }

    pub fn record(&self) -> DeviceRecord {
        DeviceRecord {
            id: self.id.clone(),
            name: format!("phone {}", &self.id[..4]),
            tls_spki_sha256: b64::encode(&Sha256::digest(&self.tls_spki)),
            sig_pub: b64::encode(self.sig.verifying_key().to_public_key_der().expect("spki").as_bytes()),
            verified_boot_key: "a1f309ce".into(),
            attestation: serde_json::Value::Null,
            paired_at: 1,
        }
    }

    pub fn sign(&self, method: &str, target: &str, body: &[u8], ts: i64, nonce: &[u8; 16]) -> RawSigHeaders {
        self.sign_as(&self.id, &self.sig, method, target, body, ts, nonce)
    }

    #[allow(clippy::too_many_arguments)]
    pub fn sign_as(&self, device: &str, key: &SigningKey, method: &str, target: &str, body: &[u8], ts: i64, nonce: &[u8; 16]) -> RawSigHeaders {
        let nonce = b64::encode(nonce);
        let canon = canonical(&SignInput { method, target, device, timestamp_ms: ts, nonce: &nonce, body });
        let sig: Signature = key.sign(canon.as_bytes());
        RawSigHeaders {
            device: Some(device.into()),
            timestamp: Some(ts.to_string()),
            nonce: Some(nonce),
            signature: Some(b64::encode(sig.to_der().as_bytes())),
        }
    }
}

pub fn device_file(phones: &[&Phone]) -> Vec<u8> {
    serde_json::to_vec(&DeviceFile { devices: phones.iter().map(|p| p.record()).collect() }).expect("json")
}
