//! Auto-lock: 3 invalid signatures or 1 reused nonce within 10 minutes, from a
//! device that passed mTLS. Both daemons count for themselves, so neither
//! depends on the other having noticed.

use std::collections::{HashMap, VecDeque};
use std::time::{Duration, Instant};

use crate::LockWeight;

pub const LOCK_WINDOW: Duration = Duration::from_secs(600);
pub const BAD_SIGS_TO_LOCK: usize = 3;

#[derive(Default)]
pub struct AutoLock {
    bad: HashMap<String, VecDeque<Instant>>,
}

impl AutoLock {
    /// True when this failure should lock the laptop.
    pub fn record(&mut self, device: &str, w: LockWeight, now: Instant) -> bool {
        match w {
            LockWeight::None => false,
            LockWeight::Immediate => true,
            LockWeight::BadSignature => {
                let q = self.bad.entry(device.to_owned()).or_default();
                q.push_back(now);
                while q.front().is_some_and(|t| now.duration_since(*t) > LOCK_WINDOW) {
                    q.pop_front();
                }
                q.len() >= BAD_SIGS_TO_LOCK
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn three_bad_sigs_lock() {
        let t0 = Instant::now();
        let mut a = AutoLock::default();
        assert!(!a.record("A", LockWeight::BadSignature, t0));
        assert!(!a.record("A", LockWeight::BadSignature, t0 + Duration::from_secs(60)));
        assert!(!a.record("B", LockWeight::BadSignature, t0), "per device");
        assert!(a.record("A", LockWeight::BadSignature, t0 + Duration::from_secs(120)));
        let mut spread = AutoLock::default();
        for m in [0, 6, 12] {
            let locked = spread.record("A", LockWeight::BadSignature, t0 + Duration::from_secs(m * 60));
            assert!(!locked, "spread over 12 minutes never reaches 3 in 10");
        }
        assert!(AutoLock::default().record("A", LockWeight::Immediate, t0), "one reused nonce locks");
        assert!(!AutoLock::default().record("A", LockWeight::None, t0));
    }
}
