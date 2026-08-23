//! Folder search: breadth first, depth 4, no symlinks, no other mounts, 50 hits
//! or 800 ms. A query with `/` in it is completed component by component.

use std::collections::{HashMap, VecDeque};
use std::sync::mpsc;
use std::time::{Duration, Instant};

use remoter_proto::api::{SearchHit, SearchResponse};
use remoter_proto::names::is_unsupported_name;

use crate::guard::{Home, RelPath};
use crate::{AgentError, natural, sys};

pub const MAX_DEPTH: u32 = 4;
pub const MAX_HITS: usize = 50;
pub const TIME_BUDGET: Duration = Duration::from_millis(800);
// per dir, so one giant folder can't eat the whole budget
const READ_CAP: usize = 20_000;

pub struct Searcher<'a> {
    pub home: &'a Home,
    pub skip: &'a [String],
    /// most recent first
    pub recent: &'a [String],
    pub budget: Duration,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord)]
enum Match {
    Exact,
    Prefix,
    Substring,
    Subsequence,
}

fn match_kind(name: &str, q: &str) -> Option<Match> {
    let n = name.to_lowercase();
    let q = q.to_lowercase();
    if n == q {
        Some(Match::Exact)
    } else if n.starts_with(&q) {
        Some(Match::Prefix)
    } else if n.contains(&q) {
        Some(Match::Substring)
    } else {
        let mut it = n.chars();
        q.chars().all(|c| it.any(|x| x == c)).then_some(Match::Subsequence)
    }
}

struct Found {
    hit: SearchHit,
    kind: Match,
}

impl Searcher<'_> {
    pub fn search(&self, from: &RelPath, query: &str) -> Result<SearchResponse, AgentError> {
        let q = query.trim();
        let start = self.home.open_dir(from)?.canonical;
        if q.is_empty() {
            return Ok(SearchResponse { query: query.to_owned(), hits: Vec::new(), capped: false });
        }
        let (found, capped) = if q.contains('/') || q.starts_with('~') {
            self.complete(&start, q)?
        } else {
            self.walk(start, q.to_owned())?
        };
        Ok(SearchResponse { query: query.to_owned(), hits: self.rank(found), capped })
    }

    fn rank(&self, mut found: Vec<Found>) -> Vec<SearchHit> {
        let recency: HashMap<&str, usize> = self.recent.iter().enumerate().map(|(i, p)| (p.as_str(), i)).collect();
        found.sort_by(|a, b| {
            a.kind
                .cmp(&b.kind)
                .then_with(|| b.hit.is_git.cmp(&a.hit.is_git))
                .then_with(|| {
                    let ra = recency.get(a.hit.path.as_str()).copied().unwrap_or(usize::MAX);
                    let rb = recency.get(b.hit.path.as_str()).copied().unwrap_or(usize::MAX);
                    ra.cmp(&rb)
                })
                .then_with(|| a.hit.depth.cmp(&b.hit.depth))
                .then_with(|| natural::cmp(&a.hit.path, &b.hit.path))
        });
        found.into_iter().map(|f| f.hit).collect()
    }

    /// On a thread, so a hung mount only costs the budget.
    fn walk(&self, start: RelPath, q: String) -> Result<(Vec<Found>, bool), AgentError> {
        let home = self.home.try_clone().map_err(|e| AgentError::new(remoter_proto::ErrorCode::Internal, e.to_string()))?;
        let skip = self.skip.to_vec();
        let (tx, rx) = mpsc::channel::<Option<Found>>();
        std::thread::spawn(move || {
            let mut queue = VecDeque::from([(start, 0u32)]);
            let hidden_ok = q.starts_with('.');
            while let Some((dir, depth)) = queue.pop_front() {
                let Ok(opened) = home.open_dir_with(&dir, sys::RESOLVE_NO_XDEV | sys::RESOLVE_NO_SYMLINKS) else {
                    continue;
                };
                let Ok(names) = sys::read_dir(&opened.fd, READ_CAP) else { continue };
                let mut children = Vec::new();
                for (raw, _) in names {
                    if is_unsupported_name(&raw) || (!hidden_ok && raw.first() == Some(&b'.')) {
                        continue;
                    }
                    let Ok(st) = sys::statat(&opened.fd, &raw, false) else { continue };
                    if !st.is_dir() || st.dev != opened.dev {
                        continue;
                    }
                    let Ok(name) = String::from_utf8(raw) else { continue };
                    if skip.contains(&name) {
                        continue;
                    }
                    children.push(name);
                }
                children.sort_by(|a, b| natural::cmp(a, b));
                for name in children {
                    let child = dir.join(&name);
                    if let Some(kind) = match_kind(&name, &q) {
                        let is_git = home
                            .open_dir(&child)
                            .ok()
                            .map(|o| sys::statat(&o.fd, b".git", false).is_ok())
                            .unwrap_or(false);
                        let hit = SearchHit { path: child.as_string(), name: name.clone(), is_git, depth: depth + 1 };
                        if tx.send(Some(Found { hit, kind })).is_err() {
                            return;
                        }
                    }
                    if depth + 1 < MAX_DEPTH {
                        queue.push_back((child, depth + 1));
                    }
                }
            }
            let _ = tx.send(None);
        });
        let deadline = Instant::now() + self.budget;
        let mut found = Vec::new();
        loop {
            if found.len() >= MAX_HITS {
                return Ok((found, true));
            }
            match rx.recv_timeout(deadline.saturating_duration_since(Instant::now())) {
                Ok(Some(f)) => found.push(f),
                Ok(None) => return Ok((found, false)),
                Err(_) => return Ok((found, true)),
            }
        }
    }

    /// `Pro/rem` or `~/Pro`: leading parts are prefixes, the last matches normally.
    fn complete(&self, from: &RelPath, q: &str) -> Result<(Vec<Found>, bool), AgentError> {
        let (base, rest) = match q.strip_prefix("~/") {
            Some(rest) => (RelPath::home(), rest),
            None if q == "~" => (RelPath::home(), ""),
            None => (from.clone(), q),
        };
        let parts: Vec<&str> = rest.split('/').collect();
        let (last, leading) = parts.split_last().map(|(l, r)| (*l, r)).unwrap_or(("", &[]));
        let deadline = Instant::now() + self.budget;
        let mut frontier = vec![base];
        for part in leading.iter().filter(|p| !p.is_empty()) {
            let mut next = Vec::new();
            for dir in &frontier {
                for name in self.child_dirs(dir)? {
                    if name.to_lowercase().starts_with(&part.to_lowercase()) {
                        next.push(dir.join(&name));
                    }
                }
                if Instant::now() > deadline {
                    return Ok((Vec::new(), true));
                }
            }
            frontier = next;
        }
        let mut found = Vec::new();
        for dir in &frontier {
            for name in self.child_dirs(dir)? {
                let kind = if last.is_empty() { Some(Match::Prefix) } else { match_kind(&name, last) };
                if let Some(kind) = kind {
                    let path = dir.join(&name);
                    let depth = path.components().len() as u32;
                    let is_git = self
                        .home
                        .open_dir(&path)
                        .ok()
                        .map(|o| sys::statat(&o.fd, b".git", false).is_ok())
                        .unwrap_or(false);
                    found.push(Found { hit: SearchHit { path: path.as_string(), name, is_git, depth }, kind });
                    if found.len() >= MAX_HITS {
                        return Ok((found, true));
                    }
                }
            }
        }
        Ok((found, false))
    }

    fn child_dirs(&self, dir: &RelPath) -> Result<Vec<String>, AgentError> {
        let Ok(opened) = self.home.open_dir_with(dir, sys::RESOLVE_NO_XDEV | sys::RESOLVE_NO_SYMLINKS) else {
            return Ok(Vec::new());
        };
        let names = sys::read_dir(&opened.fd, READ_CAP)
            .map_err(|e| AgentError::new(remoter_proto::ErrorCode::Internal, e.to_string()))?;
        let mut out: Vec<String> = names
            .into_iter()
            .filter(|(raw, _)| !is_unsupported_name(raw))
            .filter(|(raw, _)| sys::statat(&opened.fd, raw, false).map(|s| s.is_dir() && s.dev == opened.dev).unwrap_or(false))
            .filter_map(|(raw, _)| String::from_utf8(raw).ok())
            .filter(|n| !self.skip.iter().any(|s| s == n))
            .collect();
        out.sort_by(|a, b| natural::cmp(a, b));
        Ok(out)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn match_kinds() {
        assert_eq!(match_kind("remoter", "remoter"), Some(Match::Exact));
        assert_eq!(match_kind("Remoter", "rem"), Some(Match::Prefix));
        assert_eq!(match_kind("my-remoter", "remo"), Some(Match::Substring));
        assert_eq!(match_kind("remoter", "rmtr"), Some(Match::Subsequence));
        assert_eq!(match_kind("remoter", "xyz"), None);
        assert!(Match::Exact < Match::Prefix && Match::Substring < Match::Subsequence);
    }
}
