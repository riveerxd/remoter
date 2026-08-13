//! Signature checking shared by remoterd and remoter-agent. One implementation,
//! but each process builds its own `Verifier` with its own replay store and
//! runs every check itself, so a bug or a compromise in remoterd can't vouch
//! for a request on the agent's behalf.

pub mod autolock;
pub mod devices;
pub mod files;
pub mod replay;
pub mod unpaired;
#[cfg(any(test, feature = "testkit"))]
pub mod testkit;

use std::sync::Mutex;

use p256::ecdsa::signature::Verifier as _;
use p256::ecdsa::{Signature, VerifyingKey};
use remoter_proto::ErrorCode;
use remoter_proto::canonical::{self, HeaderError, SigHeaders, SignInput};

pub use devices::{Device, Devices};
pub use replay::{Admit, Cached, ReplayKey, ReplayStore};

pub use remoter_proto::ipc::RawSigHeaders;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Rejection {
    /// Missing or malformed headers. Never counts toward auto-lock: it proves
    /// nothing about holding the signing key.
    BadHeaders(HeaderError),
    /// `Remoter-Device` isn't the device that passed mTLS.
    DeviceMismatch,
    /// Not in the device file.
    DeviceUnknown,
    ClockSkew { server_time: i64 },
    /// Agent only: signed before this verifier started, so its replay store
    /// can't know whether it already ran.
    BeforeStart { server_time: i64 },
    /// The body hash is part of the signed string, so a changed body lands
    /// here too.
    SigInvalid,
    /// Same nonce, different request.
    NonceReused,
    /// The replay store is full. Bounded on purpose: evicting would reopen a
    /// replay.
    Busy,
}

impl Rejection {
    pub fn code(self) -> ErrorCode {
        match self {
            Rejection::BadHeaders(_) => ErrorCode::BadRequest,
            Rejection::DeviceMismatch | Rejection::SigInvalid => ErrorCode::SigInvalid,
            Rejection::DeviceUnknown => ErrorCode::DeviceUnknown,
            // Right after an agent restart the app shows a tiny skew. Close
            // enough: the request is too old for this verifier, and a fresh
            // fingerprint goes through.
            Rejection::ClockSkew { .. } | Rejection::BeforeStart { .. } => ErrorCode::ClockSkew,
            Rejection::NonceReused => ErrorCode::NonceReused,
            Rejection::Busy => ErrorCode::RateLimited,
        }
    }

    /// Only a device that passed mTLS and then failed to sign, or reused a
    /// nonce, counts toward auto-lock.
    pub fn lock_weight(self) -> LockWeight {
        match self {
            Rejection::DeviceMismatch | Rejection::SigInvalid => LockWeight::BadSignature,
            Rejection::NonceReused => LockWeight::Immediate,
            _ => LockWeight::None,
        }
    }

    pub fn server_time(self) -> Option<i64> {
        match self {
            Rejection::ClockSkew { server_time } | Rejection::BeforeStart { server_time } => Some(server_time),
            _ => None,
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum LockWeight {
    None,
    BadSignature,
    Immediate,
}

pub struct Verifier {
    window_ms: i64,
    /// Requests signed before this are refused (agent only).
    not_before_ms: Option<i64>,
    replay: Mutex<ReplayStore>,
}

/// A request that passed every check up to the nonce.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Admitted {
    pub key: ReplayKey,
    pub hash: [u8; 32],
    pub device: String,
    pub admit: Admit,
}

pub struct Request<'a> {
    /// The device mTLS proved.
    pub mtls_device: &'a str,
    pub headers: &'a RawSigHeaders,
    pub method: &'a str,
    /// Path plus query exactly as on the wire.
    pub target: &'a str,
    pub body: &'a [u8],
    pub now_ms: i64,
}

impl Verifier {
    pub fn new(window_ms: i64, not_before_ms: Option<i64>, max_entries: usize) -> Verifier {
        Verifier { window_ms, not_before_ms, replay: Mutex::new(ReplayStore::new(max_entries)) }
    }

    /// Replay store starts empty at `start_ms`. A request passes the clock
    /// check until `ts + window` and a phone clock may be `window` ahead, so
    /// anything with `ts < start + window` could have run in the old process.
    /// Costs a fresh fingerprint for one window after a restart.
    pub fn for_agent(window_ms: i64, start_ms: i64, max_entries: usize) -> Verifier {
        Verifier::new(window_ms, Some(start_ms + window_ms), max_entries)
    }

    /// The nonce is looked at only after the signature proved the request came
    /// from the key.
    pub fn check(&self, devices: &Devices, r: &Request<'_>) -> Result<Admitted, Rejection> {
        let h = r.headers;
        let parsed: SigHeaders = canonical::parse_headers(
            h.device.as_deref(),
            h.timestamp.as_deref(),
            h.nonce.as_deref(),
            h.signature.as_deref(),
        )
        .map_err(Rejection::BadHeaders)?;

        if parsed.device != r.mtls_device {
            return Err(Rejection::DeviceMismatch);
        }
        let device = devices.get(&parsed.device).ok_or(Rejection::DeviceUnknown)?;

        if (r.now_ms - parsed.timestamp_ms).abs() > self.window_ms {
            return Err(Rejection::ClockSkew { server_time: r.now_ms });
        }
        if self.not_before_ms.is_some_and(|nb| parsed.timestamp_ms < nb) {
            return Err(Rejection::BeforeStart { server_time: r.now_ms });
        }

        let input = SignInput {
            method: r.method,
            target: r.target,
            device: &parsed.device,
            timestamp_ms: parsed.timestamp_ms,
            nonce: &parsed.nonce,
            body: r.body,
        };
        let canon = canonical::canonical(&input);
        if !verify_sig(&device.sig_key, canon.as_bytes(), &parsed.signature_der) {
            return Err(Rejection::SigInvalid);
        }

        let hash = canonical::canonical_hash(&input);
        let key = ReplayKey { device: parsed.device.clone(), nonce: parsed.nonce.clone() };
        let expires = parsed.timestamp_ms + self.window_ms + 1000;
        let admit = self.lock_replay().admit(&key, hash, expires, r.now_ms).ok_or(Rejection::Busy)?;
        if admit == Admit::Conflict {
            return Err(Rejection::NonceReused);
        }
        Ok(Admitted { key, hash, device: parsed.device, admit })
    }

    /// Stores the response for an admitted request, for the idempotent retry.
    pub fn complete(&self, a: &Admitted, response: Cached) {
        self.lock_replay().complete(&a.key, a.hash, response);
    }

    /// Drops an admitted request that never ran, so a retry may run it.
    pub fn forget(&self, a: &Admitted) {
        self.lock_replay().forget(&a.key, a.hash);
    }

    /// The stored response once the first copy of a request has finished.
    pub fn cached(&self, a: &Admitted) -> Option<Cached> {
        self.lock_replay().cached(&a.key, a.hash)
    }

    fn lock_replay(&self) -> std::sync::MutexGuard<'_, ReplayStore> {
        self.replay.lock().unwrap_or_else(|p| p.into_inner())
    }
}

fn verify_sig(key: &VerifyingKey, msg: &[u8], der: &[u8]) -> bool {
    match Signature::from_der(der) {
        Ok(sig) => key.verify(msg, &sig).is_ok(),
        Err(_) => false,
    }
}

#[cfg(test)]
mod tests;
