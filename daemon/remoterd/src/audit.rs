//! Audit log, JSON lines, each with the SHA-256 of the line before it so an
//! edit or delete breaks the chain. Rotates at 10 MB, carrying the hash over.

use std::io::{BufRead, Write};
use std::os::unix::fs::OpenOptionsExt;
use std::path::{Path, PathBuf};

use remoter_proto::api::{AuditEntry, AuditPage};
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};

pub const ROTATE_BYTES: u64 = 10 * 1024 * 1024;
pub const KEEP_ROTATED: usize = 5;
pub const PAGE: usize = 50;
pub const GENESIS: &str = "0000000000000000000000000000000000000000000000000000000000000000";

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct Line {
    pub ts: i64,
    pub device: Option<String>,
    pub action: String,
    pub path: Option<String>,
    pub result: String,
    pub request_id: String,
    pub prev: String,
}

pub struct Audit {
    file: PathBuf,
    rotate_at: u64,
    last: String,
}

fn hex_hash(line: &[u8]) -> String {
    remoter_proto::b64::hex(&Sha256::digest(line))
}

fn rotated(file: &Path, n: usize) -> PathBuf {
    file.with_extension(format!("jsonl.{n}"))
}

impl Audit {
    pub fn open(file: PathBuf, rotate_at: u64) -> std::io::Result<Audit> {
        let last = last_hash(&file)?
            .or(last_hash(&rotated(&file, 1))?)
            .unwrap_or_else(|| GENESIS.to_owned());
        Ok(Audit { file, rotate_at, last })
    }

    pub fn append(&mut self, e: AuditEntry) -> std::io::Result<()> {
        if std::fs::metadata(&self.file).map(|m| m.len()).unwrap_or(0) >= self.rotate_at {
            self.rotate()?;
        }
        let line = Line { ts: e.ts, device: e.device, action: e.action, path: e.path, result: e.result, request_id: e.request_id, prev: self.last.clone() };
        let text = serde_json::to_string(&line).map_err(std::io::Error::other)?;
        let mut f = std::fs::OpenOptions::new().append(true).create(true).mode(0o600).open(&self.file)?;
        f.write_all(text.as_bytes())?;
        f.write_all(b"\n")?;
        f.sync_data()?;
        self.last = hex_hash(text.as_bytes());
        Ok(())
    }

    fn rotate(&mut self) -> std::io::Result<()> {
        for n in (1..KEEP_ROTATED).rev() {
            let from = rotated(&self.file, n);
            if from.exists() {
                std::fs::rename(&from, rotated(&self.file, n + 1))?;
            }
        }
        std::fs::rename(&self.file, rotated(&self.file, 1))
    }

    pub fn head(&self) -> String {
        self.last.clone()
    }

    /// Newest first.
    pub fn page(&self, device: &str, before: i64) -> AuditPage {
        let mut all = Vec::new();
        for f in std::iter::once(self.file.clone()).chain((1..=KEEP_ROTATED).map(|n| rotated(&self.file, n))) {
            for l in read_lines(&f).unwrap_or_default() {
                if let Ok(line) = serde_json::from_str::<Line>(&l)
                    && line.device.as_deref() == Some(device)
                    && line.ts < before
                {
                    all.push(line);
                }
            }
        }
        all.sort_by(|a, b| b.ts.cmp(&a.ts));
        let more = all.len() > PAGE;
        all.truncate(PAGE);
        let next_before = if more { all.last().map(|l| l.ts) } else { None };
        let entries = all
            .into_iter()
            .map(|l| AuditEntry { ts: l.ts, device: l.device, action: l.action, path: l.path, result: l.result, request_id: l.request_id })
            .collect();
        AuditPage { entries, next_before }
    }
}

fn read_lines(f: &Path) -> std::io::Result<Vec<String>> {
    match std::fs::File::open(f) {
        Ok(h) => std::io::BufReader::new(h).lines().collect(),
        Err(e) if e.kind() == std::io::ErrorKind::NotFound => Ok(Vec::new()),
        Err(e) => Err(e),
    }
}

fn last_hash(f: &Path) -> std::io::Result<Option<String>> {
    Ok(read_lines(f)?.last().map(|l| hex_hash(l.as_bytes())))
}

/// Oldest rotated file first. Number of lines checked, or where it broke.
pub fn verify(file: &Path) -> Result<usize, String> {
    let mut files: Vec<PathBuf> = (1..=KEEP_ROTATED).rev().map(|n| rotated(file, n)).filter(|p| p.exists()).collect();
    files.push(file.to_path_buf());
    let mut prev: Option<String> = None;
    let mut count = 0;
    for f in files {
        for (i, l) in read_lines(&f).map_err(|e| format!("{}: {e}", f.display()))?.into_iter().enumerate() {
            let line: Line = serde_json::from_str(&l).map_err(|e| format!("{}:{}: not an audit line: {e}", f.display(), i + 1))?;
            // oldest kept line may point into a file that rotated away
            if let Some(p) = &prev
                && &line.prev != p
            {
                return Err(format!("{}:{}: chain broken, a line before it was changed or removed", f.display(), i + 1));
            }
            prev = Some(hex_hash(l.as_bytes()));
            count += 1;
        }
    }
    Ok(count)
}

/// Compared with `head()` by `remoterctl log --verify`: the chain alone can't show a cut tail.
pub fn disk_head(file: &Path) -> Result<String, String> {
    match last_hash(file).map_err(|e| e.to_string())? {
        Some(h) => Ok(h),
        None => Ok(last_hash(&rotated(file, 1)).map_err(|e| e.to_string())?.unwrap_or_else(|| GENESIS.to_owned())),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn entry(n: i64) -> AuditEntry {
        AuditEntry {
            ts: n,
            device: Some("01K6B7Y3M4N5P6Q7R8S9T0V1W2".into()),
            action: "spawn".into(),
            path: Some(format!("Projects/p{n}")),
            result: "ok".into(),
            request_id: format!("r{n}"),
        }
    }

    fn fresh(tag: &str, rotate_at: u64) -> (PathBuf, Audit) {
        let dir = std::env::temp_dir().join(format!("remoter-audit-{tag}-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&dir);
        std::fs::create_dir_all(&dir).expect("mk");
        let f = dir.join("audit.jsonl");
        let a = Audit::open(f.clone(), rotate_at).expect("open");
        (f, a)
    }

    #[test]
    fn chain_verifies_and_survives_reopen() {
        let (f, mut a) = fresh("ok", ROTATE_BYTES);
        for n in 0..5 {
            a.append(entry(n)).expect("append");
        }
        drop(a);
        let mut a = Audit::open(f.clone(), ROTATE_BYTES).expect("reopen");
        a.append(entry(5)).expect("append");
        assert_eq!(verify(&f), Ok(6));
        let first: Line = serde_json::from_str(read_lines(&f).expect("read")[0].as_str()).expect("line");
        assert_eq!(first.prev, GENESIS);
        use std::os::unix::fs::PermissionsExt;
        assert_eq!(std::fs::metadata(&f).expect("meta").permissions().mode() & 0o777, 0o600);
    }

    #[test]
    fn detects_an_edited_line() {
        let (f, mut a) = fresh("edit", ROTATE_BYTES);
        for n in 0..4 {
            a.append(entry(n)).expect("append");
        }
        let text = std::fs::read_to_string(&f).expect("read").replacen("\"result\":\"ok\"", "\"result\":\"refused\"", 2);
        std::fs::write(&f, text).expect("write");
        let e = verify(&f).expect_err("edit must be caught");
        assert!(e.contains(":2:"), "{e}");
    }

    #[test]
    fn detects_a_deleted_line() {
        let (f, mut a) = fresh("del", ROTATE_BYTES);
        for n in 0..4 {
            a.append(entry(n)).expect("append");
        }
        let lines = read_lines(&f).expect("read");
        let kept: Vec<&String> = lines.iter().enumerate().filter(|(i, _)| *i != 1).map(|(_, l)| l).collect();
        std::fs::write(&f, kept.iter().map(|l| format!("{l}\n")).collect::<String>()).expect("write");
        assert!(verify(&f).is_err());
    }

    #[test]
    fn rotation_keeps_chain() {
        let (f, mut a) = fresh("rot", 400);
        for n in 0..20 {
            a.append(entry(n)).expect("append");
        }
        assert!(rotated(&f, 1).exists(), "rotated at least once");
        assert_eq!(verify(&f), Ok(read_lines(&f).expect("r").len() + (1..=KEEP_ROTATED).map(|n| read_lines(&rotated(&f, n)).expect("r").len()).sum::<usize>()));
        let newest_first: Line = serde_json::from_str(read_lines(&f).expect("r")[0].as_str()).expect("line");
        let older_last = read_lines(&rotated(&f, 1)).expect("r").last().cloned().expect("line");
        assert_eq!(newest_first.prev, hex_hash(older_last.as_bytes()), "carried over");
        let old = std::fs::read_to_string(rotated(&f, 1)).expect("r").replacen("\"ok\"", "\"no\"", 1);
        std::fs::write(rotated(&f, 1), old).expect("w");
        assert!(verify(&f).is_err());
    }

    #[test]
    fn pages_newest_first_for_one_device() {
        let (_, mut a) = fresh("page", ROTATE_BYTES);
        for n in 0..60 {
            a.append(entry(n)).expect("append");
        }
        let mut other = entry(100);
        other.device = Some("01K6B7Y3M4N5P6Q7R8S9T0V1W3".into());
        a.append(other).expect("append");
        let p = a.page("01K6B7Y3M4N5P6Q7R8S9T0V1W2", i64::MAX);
        assert_eq!(p.entries.len(), PAGE);
        assert_eq!(p.entries[0].ts, 59);
        assert_eq!(p.next_before, Some(10));
        let p2 = a.page("01K6B7Y3M4N5P6Q7R8S9T0V1W2", 10);
        assert_eq!(p2.entries.len(), 10);
        assert_eq!(p2.next_before, None);
    }
}
