//! Recent spawn folders and start times (the "~6 s" on the mode cards).

use std::path::PathBuf;
use std::sync::Mutex;

use remoter_proto::api::{RecentEntry, RecentResponse};
use serde::{Deserialize, Serialize};

use crate::guard::{Home, parse_rel};
use crate::sys;

const KEEP_SPAWNS: usize = 50;
const SHOW: usize = 20;
const KEEP_STARTS: usize = 20;

#[derive(Default, Serialize, Deserialize)]
struct Data {
    spawns: Vec<(String, i64)>,
    starts_ms: Vec<u32>,
}

pub struct History {
    file: PathBuf,
    data: Mutex<Data>,
}

impl History {
    pub fn open(file: PathBuf) -> History {
        let data = std::fs::read(&file).ok().and_then(|b| serde_json::from_slice(&b).ok()).unwrap_or_default();
        History { file, data: Mutex::new(data) }
    }

    fn with<R>(&self, f: impl FnOnce(&mut Data) -> R) -> R {
        let mut g = self.data.lock().unwrap_or_else(|p| p.into_inner());
        let r = f(&mut g);
        if let Some(dir) = self.file.parent() {
            let _ = std::fs::create_dir_all(dir);
        }
        if let Ok(json) = serde_json::to_vec(&*g) {
            let _ = remoter_proto::local::write_atomic(&self.file, &json);
        }
        r
    }

    pub fn record_spawn(&self, rel: &str, at: i64) {
        self.with(|d| {
            d.spawns.retain(|(p, _)| p != rel);
            d.spawns.insert(0, (rel.to_owned(), at));
            d.spawns.truncate(KEEP_SPAWNS);
        });
    }

    pub fn record_start(&self, ms: u32) {
        self.with(|d| {
            d.starts_ms.push(ms);
            let n = d.starts_ms.len();
            if n > KEEP_STARTS {
                d.starts_ms.drain(..n - KEEP_STARTS);
            }
        });
    }

    pub fn paths(&self) -> Vec<String> {
        self.data.lock().unwrap_or_else(|p| p.into_inner()).spawns.iter().map(|(p, _)| p.clone()).collect()
    }

    /// Only folders that still open through the path guard.
    pub fn recent(&self, home: &Home) -> RecentResponse {
        let g = self.data.lock().unwrap_or_else(|p| p.into_inner());
        let mut entries = Vec::new();
        for (path, at) in &g.spawns {
            if entries.len() >= SHOW {
                break;
            }
            let Ok(rel) = parse_rel(path.as_bytes()) else { continue };
            let Ok(opened) = home.open_dir(&rel) else { continue };
            let canonical = opened.canonical.as_string();
            let name = canonical.rsplit('/').next().unwrap_or("").to_owned();
            let is_git = sys::statat(&opened.fd, b".git", false).is_ok();
            entries.push(RecentEntry { path: canonical, name, is_git, last_spawn: *at });
        }
        let typical_start_ms = (g.starts_ms.len() >= 3).then(|| {
            let mut v = g.starts_ms.clone();
            v.sort_unstable();
            v[v.len() / 2]
        });
        RecentResponse { entries, typical_start_ms }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn order_dedupe_and_median() {
        let root = std::env::temp_dir().join(format!("remoter-recent-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&root);
        std::fs::create_dir_all(root.join("home/Projects/a/.git")).expect("mk");
        std::fs::create_dir_all(root.join("home/Projects/b")).expect("mk");
        let home = Home::open(&root.join("home")).expect("home");
        let h = History::open(root.join("state/recent.json"));
        h.record_spawn("Projects/a", 1);
        h.record_spawn("Projects/b", 2);
        h.record_spawn("Projects/a", 3);
        h.record_spawn("Projects/gone", 4);
        let r = h.recent(&home);
        let paths: Vec<_> = r.entries.iter().map(|e| e.path.as_str()).collect();
        assert_eq!(paths, vec!["Projects/a", "Projects/b"], "most recent first, missing folders hidden");
        assert!(r.entries[0].is_git);
        assert_eq!(r.typical_start_ms, None);
        for ms in [6000, 9000, 5000] {
            h.record_start(ms);
        }
        assert_eq!(History::open(root.join("state/recent.json")).recent(&home).typical_start_ms, Some(6000), "saved");
        let _ = std::fs::remove_dir_all(&root);
    }
}
