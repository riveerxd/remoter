//! Workspace trust the way claude decides it: the nearest ancestor (or the
//! folder itself) with an entry in `~/.claude.json` wins. Before every start
//! we give the folder its own entry, the only write we ever make.

use std::collections::HashMap;
use std::path::{Path, PathBuf};
use std::sync::Mutex;
use std::time::SystemTime;

/// (mtime, size, parsed)
type Cached = (SystemTime, u64, HashMap<String, bool>);

pub struct Trust {
    file: PathBuf,
    cache: Mutex<Option<Cached>>,
}

impl Trust {
    pub fn new(claude_json: PathBuf) -> Trust {
        Trust { file: claude_json, cache: Mutex::new(None) }
    }

    /// `abs` must be canonical.
    pub fn is_trusted(&self, abs: &Path) -> bool {
        let map = self.projects();
        let mut cur = Some(abs);
        while let Some(p) = cur {
            if let Some(&accepted) = p.to_str().and_then(|s| map.get(s)) {
                return accepted;
            }
            cur = p.parent();
        }
        false
    }

    /// Own entry only, not inherited.
    pub fn is_trusted_here(&self, abs: &Path) -> bool {
        abs.to_str().and_then(|s| self.projects().get(s).copied()).unwrap_or(false)
    }

    /// Running claudes rewrite the whole file from memory now and then, so our
    /// write can get clobbered. Read it back and retry a few times.
    pub fn grant(&self, abs: &Path) -> Result<(), String> {
        let key = abs.to_str().ok_or("folder path isn't UTF-8")?;
        for _ in 0..4 {
            let bytes = std::fs::read(&self.file).map_err(|e| format!("read {}: {e}", self.file.display()))?;
            let mut v: serde_json::Value = serde_json::from_slice(&bytes).map_err(|e| format!("{} isn't JSON: {e}", self.file.display()))?;
            let root = v.as_object_mut().ok_or("~/.claude.json isn't an object")?;
            let projects = root.entry("projects").or_insert_with(|| serde_json::json!({}));
            let projects = projects.as_object_mut().ok_or("projects isn't an object")?;
            let entry = projects.entry(key.to_owned()).or_insert_with(|| serde_json::json!({}));
            entry.as_object_mut().ok_or("project entry isn't an object")?.insert("hasTrustDialogAccepted".into(), serde_json::Value::Bool(true));
            write_replace(&self.file, &serde_json::to_vec_pretty(&v).map_err(|e| e.to_string())?)?;
            std::thread::sleep(std::time::Duration::from_millis(150));
            if self.is_trusted_here(abs) {
                return Ok(());
            }
        }
        Err("trust kept getting overwritten".into())
    }

    fn projects(&self) -> HashMap<String, bool> {
        let meta = std::fs::metadata(&self.file).ok();
        let key = meta.as_ref().and_then(|m| Some((m.modified().ok()?, m.len())));
        let mut guard = self.cache.lock().unwrap_or_else(|p| p.into_inner());
        if let (Some((mtime, len)), Some((cm, cl, map))) = (key, guard.as_ref())
            && mtime == *cm
            && len == *cl
        {
            return map.clone();
        }
        // unreadable means nothing trusted, fail closed
        let map = std::fs::read(&self.file).ok().map(|b| parse(&b)).unwrap_or_default();
        *guard = key.map(|(m, l)| (m, l, map.clone()));
        map
    }
}

fn write_replace(file: &Path, bytes: &[u8]) -> Result<(), String> {
    use std::io::Write;
    use std::os::unix::fs::PermissionsExt;
    let mode = std::fs::metadata(file).map(|m| m.permissions().mode() & 0o777).unwrap_or(0o600);
    let tmp = file.with_extension(format!("json.remoter-{}", std::process::id()));
    let mut f = std::fs::OpenOptions::new().write(true).create_new(true).open(&tmp).map_err(|e| format!("create {}: {e}", tmp.display()))?;
    let done = f
        .write_all(bytes)
        .and_then(|()| f.set_permissions(std::fs::Permissions::from_mode(mode)))
        .and_then(|()| f.sync_all())
        .and_then(|()| std::fs::rename(&tmp, file));
    if let Err(e) = done {
        let _ = std::fs::remove_file(&tmp);
        return Err(format!("write {}: {e}", file.display()));
    }
    Ok(())
}

pub fn parse(bytes: &[u8]) -> HashMap<String, bool> {
    let Ok(v) = serde_json::from_slice::<serde_json::Value>(bytes) else {
        return HashMap::new();
    };
    let Some(projects) = v.get("projects").and_then(|p| p.as_object()) else {
        return HashMap::new();
    };
    projects
        .iter()
        .map(|(k, e)| (k.clone(), e.get("hasTrustDialogAccepted").and_then(|t| t.as_bool()).unwrap_or(false)))
        .collect()
}

#[cfg(test)]
mod tests {
    use super::*;

    fn trust_with(json: &str) -> (Trust, PathBuf) {
        let dir = std::env::temp_dir().join(format!("remoter-trust-{}-{}", std::process::id(), json.len()));
        std::fs::create_dir_all(&dir).expect("dir");
        let f = dir.join("claude.json");
        std::fs::write(&f, json).expect("write");
        (Trust::new(f), dir)
    }

    #[test]
    fn nearest_entry_decides() {
        let (t, dir) = trust_with(
            r#"{"projects":{"/h":{"hasTrustDialogAccepted":false},"/h/Projects":{"hasTrustDialogAccepted":true},"/h/Projects/remoter":{"hasTrustDialogAccepted":false},"/h/Projects/nokey":{}}}"#,
        );
        assert!(t.is_trusted(Path::new("/h/Projects/app")), "inherits from Projects");
        assert!(t.is_trusted(Path::new("/h/Projects")), "exact entry");
        assert!(!t.is_trusted(Path::new("/h/Projects/remoter/child")), "explicit false blocks inheritance");
        assert!(!t.is_trusted(Path::new("/h/Projects/nokey/x")), "entry without the key counts as false");
        assert!(!t.is_trusted(Path::new("/h/Documents")), "home is false");
        assert!(!t.is_trusted(Path::new("/elsewhere")), "no entry at all");
        assert!(t.is_trusted_here(Path::new("/h/Projects")));
        assert!(!t.is_trusted_here(Path::new("/h/Projects/app")), "inherited is not its own");
        assert!(!t.is_trusted_here(Path::new("/h/Projects/remoter")), "an explicit false");
        std::fs::remove_dir_all(dir).ok();
    }

    #[test]
    fn bad_file_trusts_nothing() {
        let t = Trust::new(PathBuf::from("/nonexistent/remoter/claude.json"));
        assert!(!t.is_trusted(Path::new("/h/Projects/x")));
        let (t, dir) = trust_with("{not json");
        assert!(!t.is_trusted(Path::new("/h/Projects/x")));
        std::fs::remove_dir_all(dir).ok();
    }

    #[test]
    fn grant_keeps_the_rest() {
        let (t, dir) = trust_with(r#"{"numStartups":7,"projects":{"/h/p":{"hasTrustDialogAccepted":true},"/h/p/x":{"hasTrustDialogAccepted":false,"history":[1]}},"oauth":{"k":"v"}}"#);
        assert!(!t.is_trusted(Path::new("/h/p/x/sub")), "an explicit false blocks the trusted parent");
        t.grant(Path::new("/h/p/x")).expect("grant");
        assert!(t.is_trusted(Path::new("/h/p/x/sub")));
        let v: serde_json::Value = serde_json::from_slice(&std::fs::read(dir.join("claude.json")).expect("read")).expect("json");
        assert_eq!(v["numStartups"], 7);
        assert_eq!(v["oauth"]["k"], "v");
        assert_eq!(v["projects"]["/h/p/x"]["history"][0], 1, "the folder's other settings survive");
        t.grant(Path::new("/h/new")).expect("a folder with no entry yet");
        assert!(t.is_trusted(Path::new("/h/new")));
        let left: Vec<_> = std::fs::read_dir(&dir).expect("dir").flatten().map(|e| e.file_name()).collect();
        assert_eq!(left.len(), 1, "no temp files left behind: {left:?}");
        let _ = std::fs::remove_dir_all(dir);
    }

    #[test]
    fn grant_leaves_broken_json_alone() {
        let (t, dir) = trust_with("not json at all");
        assert!(t.grant(Path::new("/h/p")).is_err());
        assert_eq!(std::fs::read_to_string(dir.join("claude.json")).expect("read"), "not json at all");
        let _ = std::fs::remove_dir_all(dir);
    }
}
