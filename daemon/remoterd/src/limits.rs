//! Per device rate limits.

use std::collections::HashMap;
use std::time::Instant;


#[derive(Debug, Clone, Copy)]
struct Bucket {
    tokens: f64,
    at: Instant,
}

fn take(b: &mut Bucket, per_min: f64, now: Instant) -> Result<(), u32> {
    let refill = now.duration_since(b.at).as_secs_f64() * per_min / 60.0;
    b.tokens = (b.tokens + refill).min(per_min);
    b.at = now;
    if b.tokens >= 1.0 {
        b.tokens -= 1.0;
        Ok(())
    } else {
        Err(((1.0 - b.tokens) * 60.0 / per_min).ceil().max(1.0) as u32)
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub enum Kind {
    Read,
    Mutation,
    Spawn,
}

impl Kind {
    fn per_min(self) -> f64 {
        match self {
            Kind::Read => 60.0,
            Kind::Mutation => 10.0,
            Kind::Spawn => 3.0,
        }
    }
}

#[derive(Default)]
pub struct Limits {
    buckets: HashMap<(String, Kind), Bucket>,
}

impl Limits {
    /// `Err(retry_after_s)`
    pub fn take(&mut self, device: &str, kind: Kind, now: Instant) -> Result<(), u32> {
        let per = kind.per_min();
        let b = self.buckets.entry((device.to_owned(), kind)).or_insert(Bucket { tokens: per, at: now });
        take(b, per, now)
    }
}

pub use remoter_auth::autolock::{AutoLock, BAD_SIGS_TO_LOCK, LOCK_WINDOW};

#[cfg(test)]
mod tests {
    use super::*;
    use std::time::Duration;

    #[test]
    fn buckets_limit_and_refill() {
        let t0 = Instant::now();
        let mut l = Limits::default();
        for (kind, n) in [(Kind::Read, 60), (Kind::Mutation, 10), (Kind::Spawn, 3)] {
            for i in 0..n {
                assert!(l.take("A", kind, t0).is_ok(), "{kind:?} {i}");
            }
            let wait = l.take("A", kind, t0).expect_err("over");
            assert!(wait >= 1, "{kind:?}");
            assert!(l.take("B", kind, t0).is_ok(), "per device");
        }
        assert_eq!(l.take("A", Kind::Spawn, t0 + Duration::from_secs(20)), Ok(()), "one every 20s");
    }
}
