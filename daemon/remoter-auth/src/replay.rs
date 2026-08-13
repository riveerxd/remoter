//! The nonce store. Keyed by (device, nonce), holding the hash of the signed
//! string and, once the request has run, its response.

use std::collections::HashMap;

#[derive(Debug, Clone, PartialEq, Eq, Hash)]
pub struct ReplayKey {
    pub device: String,
    pub nonce: String,
}

#[derive(Debug, Clone, PartialEq, Eq, serde::Serialize, serde::Deserialize)]
pub struct Cached {
    pub status: u16,
    pub body: String,
    /// Kept so a replay reports exactly what the first answer did.
    #[serde(default)]
    pub audit_path: Option<String>,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Admit {
    /// First time: run it, then `complete`.
    Fresh,
    /// The same signed bytes again, already answered: send this, run nothing.
    Replay(Cached),
    /// The same signed bytes while the first copy is still running.
    InFlight,
    /// The same nonce on a different request.
    Conflict,
}

struct Entry {
    hash: [u8; 32],
    expires_ms: i64,
    response: Option<Cached>,
}

pub struct ReplayStore {
    map: HashMap<ReplayKey, Entry>,
    max: usize,
}

impl ReplayStore {
    pub fn new(max: usize) -> ReplayStore {
        ReplayStore { map: HashMap::new(), max }
    }

    /// `None` when the store is full of live entries. Nothing live is ever
    /// evicted to make room: an evicted nonce could be replayed.
    pub fn admit(&mut self, key: &ReplayKey, hash: [u8; 32], expires_ms: i64, now_ms: i64) -> Option<Admit> {
        self.map.retain(|_, e| e.expires_ms >= now_ms);
        if let Some(e) = self.map.get(key) {
            return Some(if e.hash != hash {
                Admit::Conflict
            } else {
                match &e.response {
                    Some(r) => Admit::Replay(r.clone()),
                    None => Admit::InFlight,
                }
            });
        }
        if self.map.len() >= self.max {
            return None;
        }
        self.map.insert(key.clone(), Entry { hash, expires_ms, response: None });
        Some(Admit::Fresh)
    }

    pub fn complete(&mut self, key: &ReplayKey, hash: [u8; 32], response: Cached) {
        if let Some(e) = self.map.get_mut(key)
            && e.hash == hash
        {
            e.response = Some(response);
        }
    }

    pub fn forget(&mut self, key: &ReplayKey, hash: [u8; 32]) {
        if self.map.get(key).is_some_and(|e| e.hash == hash && e.response.is_none()) {
            self.map.remove(key);
        }
    }

    pub fn cached(&self, key: &ReplayKey, hash: [u8; 32]) -> Option<Cached> {
        self.map.get(key).filter(|e| e.hash == hash).and_then(|e| e.response.clone())
    }

    pub fn len(&self) -> usize {
        self.map.len()
    }

    pub fn is_empty(&self) -> bool {
        self.map.is_empty()
    }
}
