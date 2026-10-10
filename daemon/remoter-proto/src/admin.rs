//! What `remoterctl` (root) says to remoterd over `/run/remoterd/admin.sock`
//! (0600, peer uid 0). One request per connection, like the agent socket.

use serde::{Deserialize, Serialize};

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(tag = "op", rename_all = "snake_case", deny_unknown_fields)]
pub enum AdminRequest {
    /// Google's root list and revocation status, fetched by remoterctl,
    /// since remoterd has no internet. Raw JSON as Google serves it.
    SetAttestationData { roots: String, status: String },
    /// Opens the one-shot pairing listener.
    PairStart { name: String, ttl_s: u32 },
    /// Waits up to `wait_ms` for the phone's attempt.
    PairWait { wait_ms: u32 },
    /// The typed code matched and the device is in the device file.
    PairConfirm { device_id: String },
    PairReject {},
    Lock {},
    Unlock {},
    /// Reads the device file and the unpaired lists again.
    Reload {},
    /// The hash of the last audit line remoterd wrote, so `log --verify` can
    /// tell a removed tail from a clean file.
    AuditHead {},
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case", deny_unknown_fields)]
pub enum AdminReply {
    Ok(serde_json::Value),
    Err(String),
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct PairStarted {
    /// `remoter://pair?...`, for the QR and for pasting.
    pub link: String,
    pub expires: i64,
}

/// Pairs anyway, with a warning.
#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord, Hash, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum Weakness {
    NoStrongBox,
    BootloaderUnlocked,
    /// boot image not signed by the maker
    BootNotVerified,
}

impl Weakness {
    pub fn says(self) -> &'static str {
        match self {
            Weakness::NoStrongBox => "no security chip, the signing key is in the TEE",
            Weakness::BootloaderUnlocked => "bootloader unlocked",
            Weakness::BootNotVerified => "boot not verified",
        }
    }
}

/// What the laptop shows before you type the phone's code.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct PairCandidate {
    pub name: String,
    pub manufacturer: Option<String>,
    pub model: Option<String>,
    pub sig_level: i64,
    pub tls_level: i64,
    pub boot_state: String,
    #[serde(default)]
    pub weaknesses: Vec<Weakness>,
    /// First 8 hex characters of the attested verifiedBootKey, as the phone
    /// shows it.
    pub boot_key_prefix: String,
    /// The six digits the phone shows. Only remoterctl sees them.
    pub code: String,
    /// The device file record, ready to append.
    pub tls_spki_sha256: String,
    pub sig_pub: String,
    pub verified_boot_key: String,
    pub attestation: serde_json::Value,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(tag = "state", rename_all = "snake_case", deny_unknown_fields)]
pub enum PairStatus {
    Waiting {},
    Received { candidate: Box<PairCandidate> },
    Failed { reason: String },
    Expired {},
    Done {},
}
