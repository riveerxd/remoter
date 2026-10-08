//! Folder listing, directories only.

use std::collections::HashMap;
use std::os::fd::OwnedFd;
use std::sync::mpsc;
use std::time::{Duration, Instant};

use remoter_proto::ErrorCode;
use remoter_proto::api::{DenyReason, FsEntry, ListResponse, SymlinkKind};
use remoter_proto::names::is_unsupported_name;

use crate::guard::{Home, RelPath};
use crate::trust::Trust;
use crate::{AgentError, natural, policy, sys};

pub const MAX_ENTRIES: usize = 2000;
pub const STAT_BUDGET: Duration = Duration::from_secs(2);
// past this the badge is noise anyway
const COUNT_CAP: usize = 10_000;
const READ_CAP: usize = 200_000;

pub struct Lister<'a> {
    pub home: &'a Home,
    pub trust: &'a Trust,
    pub cwd_deny: &'a [String],
    pub sessions: &'a HashMap<String, u32>,
    pub budget: Duration,
}

struct Row {
    entry: FsEntry,
    /// `None` when it can't or mustn't be opened
    stat_target: Option<RelPath>,
}

impl Lister<'_> {
    pub fn list(&self, rel: &RelPath, hidden: bool) -> Result<ListResponse, AgentError> {
        let dir = self.home.open_dir(rel)?;
        let here = dir.canonical.clone();
        let names = sys::read_dir(&dir.fd, READ_CAP).map_err(|e| AgentError::new(ErrorCode::Internal, e.to_string()))?;

        let mut rows = Vec::new();
        for (raw, _) in names {
            if !hidden && raw.first() == Some(&b'.') {
                continue;
            }
            if let Some(row) = self.row(&dir.fd, &here, &raw) {
                rows.push(row);
            }
        }
        rows.sort_by(|a, b| natural::cmp(&a.entry.name, &b.entry.name));
        let truncated = rows.len() > MAX_ENTRIES;
        rows.truncate(MAX_ENTRIES);

        let partial = self.fill_badges(&mut rows);

        let deny = policy::deny_reason(&here, self.cwd_deny);
        let trusted = self.trust.is_trusted(&self.abs(&here));
        let here_facts = folder_facts(&dir.fd);
        Ok(ListResponse {
            path: here.as_string(),
            is_git: here_facts.is_git,
            trusted,
            spawn_allowed: deny.is_none(),
            deny_reason: deny,
            entries: rows.into_iter().map(|r| r.entry).collect(),
            truncated,
            partial,
        })
    }

    fn abs(&self, rel: &RelPath) -> std::path::PathBuf {
        if rel.is_home() { self.home.path().to_path_buf() } else { self.home.path().join(rel.as_string()) }
    }

    fn row(&self, parent: &OwnedFd, here: &RelPath, raw: &[u8]) -> Option<Row> {
        let lst = sys::statat(parent, raw, false).ok()?;
        let unsupported = is_unsupported_name(raw);
        let name = String::from_utf8_lossy(raw).into_owned();

        let mut entry = FsEntry {
            name: name.clone(),
            mtime: lst.mtime_ms,
            is_git: false,
            has_claude_md: false,
            symlink: SymlinkKind::None,
            symlink_target: None,
            file_count: None,
            session_count: 0,
            trusted: false,
            spawn_allowed: false,
            deny_reason: None,
            unsupported,
        };

        if lst.is_symlink() {
            let target = sys::readlinkat(parent, raw).ok()?;
            if target.first() == Some(&b'/') {
                return self.absolute_link_row(parent, raw, entry, &target);
            }
            entry.symlink = SymlinkKind::Relative;
        } else if !lst.is_dir() {
            return None;
        }

        if unsupported {
            // shown, but nothing can act on it
            let st = sys::statat(parent, raw, true).ok()?;
            if !st.is_dir() {
                return None;
            }
            entry.deny_reason = Some(DenyReason::Unsupported);
            return Some(Row { entry, stat_target: None });
        }

        let canonical = if entry.symlink == SymlinkKind::Relative {
            // judged by where it lands, one that climbs out gets dropped
            let opened = self.home.open_dir(&here.join(&name)).ok()?;
            entry.mtime = sys::statat(&opened.fd, b"", true).ok()?.mtime_ms;
            opened.canonical
        } else {
            here.join(&name)
        };
        self.apply_policy(&mut entry, &canonical);
        Some(Row { entry, stat_target: Some(canonical) })
    }

    /// Absolute symlinks never resolve under `RESOLVE_BENEATH`, even into home,
    /// so they're only offered as a jump.
    fn absolute_link_row(&self, parent: &OwnedFd, raw: &[u8], mut entry: FsEntry, target: &[u8]) -> Option<Row> {
        let st = sys::statat(parent, raw, true).ok()?;
        if !st.is_dir() {
            return None;
        }
        entry.symlink = SymlinkKind::Absolute;
        entry.mtime = st.mtime_ms;
        entry.deny_reason = Some(if entry.unsupported { DenyReason::Unsupported } else { DenyReason::SymlinkAbsolute });
        let target = std::path::Path::new(sys::os(target));
        if let Ok(rest) = target.strip_prefix(self.home.path())
            && let Ok(rel) = crate::guard::parse_rel(rest.as_os_str().as_encoded_bytes())
            && let Ok(opened) = self.home.open_dir(&rel)
        {
            entry.symlink_target = Some(opened.canonical.as_string());
        }
        Some(Row { entry, stat_target: None })
    }

    fn apply_policy(&self, entry: &mut FsEntry, canonical: &RelPath) {
        let deny = policy::deny_reason(canonical, self.cwd_deny);
        entry.trusted = self.trust.is_trusted(&self.abs(canonical));
        entry.session_count = self.sessions.get(&canonical.as_string()).copied().unwrap_or(0);
        entry.spawn_allowed = deny.is_none();
        entry.deny_reason = deny;
    }

    /// On a thread with a budget: a hung network mount just makes it `partial`.
    fn fill_badges(&self, rows: &mut [Row]) -> bool {
        let jobs: Vec<(usize, RelPath)> =
            rows.iter().enumerate().filter_map(|(i, r)| Some((i, r.stat_target.clone()?))).collect();
        if jobs.is_empty() {
            return false;
        }
        let Ok(home) = self.home.try_clone() else {
            return true;
        };
        let expected = jobs.len();
        let (tx, rx) = mpsc::channel();
        std::thread::spawn(move || {
            for (i, rel) in jobs {
                let facts = home.open_dir(&rel).ok().map(|o| folder_facts(&o.fd));
                if tx.send((i, facts)).is_err() {
                    return;
                }
            }
        });
        let deadline = Instant::now() + self.budget;
        let mut done = 0;
        while done < expected {
            let left = deadline.saturating_duration_since(Instant::now());
            match rx.recv_timeout(left) {
                Ok((i, facts)) => {
                    if let Some(facts) = facts {
                        let e = &mut rows[i].entry;
                        e.is_git = facts.is_git;
                        e.has_claude_md = facts.has_claude_md;
                        e.file_count = facts.subfolders;
                    }
                    done += 1;
                }
                Err(_) => break,
            }
        }
        done < expected
    }
}

struct Facts {
    is_git: bool,
    has_claude_md: bool,
    subfolders: Option<u32>,
}

fn folder_facts(fd: &OwnedFd) -> Facts {
    let exists = |name: &[u8]| sys::statat(fd, name, false).is_ok();
    let is_git = exists(b".git");
    let has_claude_md = exists(b"CLAUDE.md") || exists(b".claude");
    let subfolders = sys::read_dir(fd, COUNT_CAP).ok().map(|entries| {
        entries
            .iter()
            .filter(|(name, dtype)| match *dtype {
                libc::DT_DIR => true,
                libc::DT_UNKNOWN => sys::statat(fd, name, false).map(|s| s.is_dir()).unwrap_or(false),
                _ => false,
            })
            .count() as u32
    });
    Facts { is_git, has_claude_md, subfolders }
}
