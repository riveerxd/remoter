//! Path guard. Phone paths are relative to home and the kernel resolves them
//! with `RESOLVE_BENEATH`, which refuses anything leaving home. The string
//! checks in `parse_rel` only make nicer errors, they are not the boundary.

use std::io;
use std::os::fd::{AsRawFd, OwnedFd};
use std::os::unix::ffi::OsStrExt;
use std::path::{Path, PathBuf};

use remoter_proto::ErrorCode;
use remoter_proto::names::is_unsupported_name;

use crate::AgentError;
use crate::sys;

/// Empty means home.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct RelPath {
    components: Vec<String>,
}

impl RelPath {
    pub fn home() -> RelPath {
        RelPath { components: Vec::new() }
    }

    pub fn components(&self) -> &[String] {
        &self.components
    }

    pub fn is_home(&self) -> bool {
        self.components.is_empty()
    }

    pub fn as_string(&self) -> String {
        self.components.join("/")
    }

    pub fn join(&self, name: &str) -> RelPath {
        let mut components = self.components.clone();
        components.push(name.to_owned());
        RelPath { components }
    }
}

/// Bytes, not `&str`, so non UTF-8 gets a clear error instead of a lossy decode.
pub fn parse_rel(raw: &[u8]) -> Result<RelPath, AgentError> {
    if raw.contains(&0) {
        return Err(AgentError::new(ErrorCode::PathUnsupported, "NUL in path"));
    }
    let Ok(text) = std::str::from_utf8(raw) else {
        return Err(AgentError::new(ErrorCode::PathUnsupported, "path is not UTF-8"));
    };
    if text.starts_with('/') {
        return Err(AgentError::new(ErrorCode::PathOutsideHome, "path must be relative to home"));
    }
    if text.len() > 4096 {
        return Err(AgentError::new(ErrorCode::PathUnsupported, "path too long"));
    }
    let mut components = Vec::new();
    for part in text.split('/') {
        match part {
            "" | "." => continue,
            ".." => return Err(AgentError::new(ErrorCode::PathOutsideHome, "`..` in path")),
            _ if is_unsupported_name(part.as_bytes()) => {
                return Err(AgentError::new(ErrorCode::PathUnsupported, "control, bidi or zero width character"));
            }
            // first real component, so `./~` and `~` get the same answer
            _ if components.is_empty() && part.starts_with('~') => {
                return Err(AgentError::new(ErrorCode::PathOutsideHome, "path must be relative to home"));
            }
            _ => components.push(part.to_owned()),
        }
    }
    Ok(RelPath { components })
}

/// for the fuzz target
pub fn is_unsupported_component(raw: &[u8]) -> bool {
    is_unsupported_name(raw)
}

/// `O_PATH` dirfd every lookup starts from.
pub struct Home {
    fd: OwnedFd,
    path: PathBuf,
}

pub struct Opened {
    pub fd: OwnedFd,
    /// from `/proc/self/fd`, so it's the real folder even through a symlink
    pub canonical: RelPath,
    pub dev: u64,
    pub ino: u64,
}

impl Home {
    pub fn open(path: &Path) -> io::Result<Home> {
        let fd = sys::open_home(path)?;
        // the kernel's spelling of home, so a symlinked home still matches fd paths
        let path = sys::fd_path(&fd)?;
        Ok(Home { fd, path })
    }

    /// For worker threads that may outlive the request.
    pub fn try_clone(&self) -> io::Result<Home> {
        Ok(Home { fd: self.fd.try_clone()?, path: self.path.clone() })
    }

    pub fn path(&self) -> &Path {
        &self.path
    }

    pub fn fd(&self) -> &OwnedFd {
        &self.fd
    }

    pub fn open_dir(&self, rel: &RelPath) -> Result<Opened, AgentError> {
        self.open_dir_with(rel, 0)
    }

    pub fn open_dir_with(&self, rel: &RelPath, extra_resolve: u64) -> Result<Opened, AgentError> {
        let fd = self.resolve(rel, extra_resolve).map_err(|e| map_open_error(&e))?;
        let canonical = self.canonical_of(&fd)?;
        let st = sys::statat(&fd, b"", true).map_err(|e| AgentError::new(ErrorCode::Internal, e.to_string()))?;
        Ok(Opened { fd, canonical, dev: st.dev, ino: st.ino })
    }

    /// Kernel lookup only. Public so tests can show it holds on its own.
    pub fn resolve(&self, rel: &RelPath, extra_resolve: u64) -> io::Result<OwnedFd> {
        let target = if rel.is_home() { ".".to_owned() } else { rel.as_string() };
        let flags = libc::O_PATH | libc::O_DIRECTORY;
        let resolve = sys::RESOLVE_BENEATH | sys::RESOLVE_NO_MAGICLINKS | extra_resolve;
        // EAGAIN on a concurrent rename during `..` resolution, safe to retry
        let mut attempt = 0;
        loop {
            match sys::openat2(self.fd.as_raw_fd(), target.as_bytes(), flags, resolve) {
                Err(e) if e.raw_os_error() == Some(libc::EAGAIN) && attempt < 8 => attempt += 1,
                other => return other,
            }
        }
    }

    /// Belt and braces on top of the kernel check.
    pub fn canonical_of(&self, fd: &OwnedFd) -> Result<RelPath, AgentError> {
        let real = sys::fd_path(fd).map_err(|e| AgentError::new(ErrorCode::Internal, e.to_string()))?;
        let rest = if real == self.path {
            Path::new("")
        } else {
            real.strip_prefix(&self.path)
                .map_err(|_| AgentError::new(ErrorCode::PathOutsideHome, "resolved outside home"))?
        };
        let bytes = rest.as_os_str().as_bytes();
        // may have been renamed to something nasty since we opened it
        let parsed = parse_rel(bytes)?;
        Ok(parsed)
    }
}

fn map_open_error(e: &io::Error) -> AgentError {
    let code = match e.raw_os_error() {
        // RESOLVE_BENEATH gives EXDEV for every escape, absolute symlinks too
        Some(libc::EXDEV) => ErrorCode::PathOutsideHome,
        Some(libc::ENOENT) | Some(libc::ELOOP) => ErrorCode::NotFound,
        Some(libc::ENOTDIR) => ErrorCode::NotADirectory,
        Some(libc::EACCES) | Some(libc::EPERM) => ErrorCode::PathDenied,
        Some(libc::ENAMETOOLONG) => ErrorCode::PathUnsupported,
        _ => ErrorCode::Internal,
    };
    AgentError::new(code, e.to_string())
}

#[cfg(test)]
mod tests {
    use super::*;

    fn code(raw: &[u8]) -> Option<ErrorCode> {
        parse_rel(raw).err().map(|e| e.code)
    }

    #[test]
    fn parse_accepts_plain_paths() {
        assert_eq!(parse_rel(b"").map(|r| r.as_string()).ok().as_deref(), Some(""));
        assert_eq!(parse_rel(b"Projects/remoter").map(|r| r.as_string()).ok().as_deref(), Some("Projects/remoter"));
        assert_eq!(parse_rel(b"Projects//remoter/").map(|r| r.as_string()).ok().as_deref(), Some("Projects/remoter"));
        assert_eq!(parse_rel(b"./Projects").map(|r| r.as_string()).ok().as_deref(), Some("Projects"));
        // already decoded once by the HTTP layer, twice would turn `%2e%2e` into `..`
        assert_eq!(parse_rel(b"%2e%2e").map(|r| r.as_string()).ok().as_deref(), Some("%2e%2e"));
    }

    #[test]
    fn parse_refuses_escapes_and_hostile_bytes() {
        assert_eq!(code(b".."), Some(ErrorCode::PathOutsideHome));
        assert_eq!(code(b"Projects/../.."), Some(ErrorCode::PathOutsideHome));
        assert_eq!(code(b"/etc"), Some(ErrorCode::PathOutsideHome));
        assert_eq!(code(b"~/Projects"), Some(ErrorCode::PathOutsideHome));
        assert_eq!(code(b"a\0b"), Some(ErrorCode::PathUnsupported));
        assert_eq!(code(b"bad\xff"), Some(ErrorCode::PathUnsupported));
        assert_eq!(code("a\u{1b}[31m".as_bytes()), Some(ErrorCode::PathUnsupported));
        assert_eq!(code("safe\u{202e}txt".as_bytes()), Some(ErrorCode::PathUnsupported));
        assert_eq!(code("a\u{200b}b".as_bytes()), Some(ErrorCode::PathUnsupported));
        assert_eq!(code("a\u{feff}".as_bytes()), Some(ErrorCode::PathUnsupported));
        assert_eq!(code(&[b'a'; 5000]), Some(ErrorCode::PathUnsupported));
    }

    // fuzzer: `././~` used to parse to `~`, whose own spelling was then refused
    #[test]
    fn tilde_is_judged_on_the_first_component() {
        assert_eq!(code(b"././~"), Some(ErrorCode::PathOutsideHome));
        assert_eq!(code(b"./~river/x"), Some(ErrorCode::PathOutsideHome));
        assert_eq!(parse_rel(b"Projects/~backup").map(|r| r.as_string()).ok().as_deref(), Some("Projects/~backup"));
    }
}
