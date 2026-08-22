//! Syscall wrappers for the path guard. Most of the agent's `unsafe` is here.

use std::ffi::{CString, OsStr};
use std::io;
use std::os::fd::{AsRawFd, FromRawFd, OwnedFd, RawFd};
use std::os::unix::ffi::OsStrExt;
use std::path::PathBuf;

pub const RESOLVE_NO_XDEV: u64 = libc::RESOLVE_NO_XDEV;
pub const RESOLVE_NO_MAGICLINKS: u64 = libc::RESOLVE_NO_MAGICLINKS;
pub const RESOLVE_NO_SYMLINKS: u64 = libc::RESOLVE_NO_SYMLINKS;
pub const RESOLVE_BENEATH: u64 = libc::RESOLVE_BENEATH;

fn cstr(bytes: &[u8]) -> io::Result<CString> {
    CString::new(bytes).map_err(|_| io::Error::from_raw_os_error(libc::EINVAL))
}

pub fn open_home(path: &std::path::Path) -> io::Result<OwnedFd> {
    let c = cstr(path.as_os_str().as_bytes())?;
    // SAFETY: c is a valid NUL terminated string for the duration of the call.
    let fd = unsafe { libc::open(c.as_ptr(), libc::O_PATH | libc::O_DIRECTORY | libc::O_CLOEXEC) };
    if fd < 0 {
        return Err(io::Error::last_os_error());
    }
    // SAFETY: open just returned this fd and nothing else owns it.
    Ok(unsafe { OwnedFd::from_raw_fd(fd) })
}

pub fn openat2(dir: RawFd, path: &[u8], flags: i32, resolve: u64) -> io::Result<OwnedFd> {
    let c = cstr(path)?;
    // open_how is non_exhaustive in libc, so zero it and fill it in
    // SAFETY: open_how is plain integers, all zero is a valid value.
    let mut how: libc::open_how = unsafe { std::mem::zeroed() };
    how.flags = (flags | libc::O_CLOEXEC) as u64;
    how.resolve = resolve;
    // SAFETY: c and how outlive the call, and size matches the struct passed.
    let fd = unsafe {
        libc::syscall(libc::SYS_openat2, dir, c.as_ptr(), &how as *const libc::open_how, size_of::<libc::open_how>())
    };
    if fd < 0 {
        return Err(io::Error::last_os_error());
    }
    // SAFETY: the kernel just returned this fd and nothing else owns it.
    Ok(unsafe { OwnedFd::from_raw_fd(fd as RawFd) })
}

/// For display and matching only, never to open again.
pub fn fd_path(fd: &OwnedFd) -> io::Result<PathBuf> {
    std::fs::read_link(format!("/proc/self/fd/{}", fd.as_raw_fd()))
}

pub fn readlinkat(dir: &OwnedFd, name: &[u8]) -> io::Result<Vec<u8>> {
    let c = cstr(name)?;
    let mut buf = vec![0u8; libc::PATH_MAX as usize];
    // SAFETY: buf is writable for buf.len() bytes and c is NUL terminated.
    let n = unsafe { libc::readlinkat(dir.as_raw_fd(), c.as_ptr(), buf.as_mut_ptr().cast(), buf.len()) };
    if n < 0 {
        return Err(io::Error::last_os_error());
    }
    buf.truncate(n as usize);
    Ok(buf)
}

#[derive(Debug, Clone, Copy)]
pub struct Stat {
    pub mode: u32,
    pub dev: u64,
    pub ino: u64,
    pub mtime_ms: i64,
}

impl Stat {
    pub fn is_dir(&self) -> bool {
        self.mode & libc::S_IFMT == libc::S_IFDIR
    }
    pub fn is_symlink(&self) -> bool {
        self.mode & libc::S_IFMT == libc::S_IFLNK
    }
}

/// Empty `name` stats the fd itself.
pub fn statat(dir: &OwnedFd, name: &[u8], follow: bool) -> io::Result<Stat> {
    let c = cstr(name)?;
    let mut flags = if follow { 0 } else { libc::AT_SYMLINK_NOFOLLOW };
    if name.is_empty() {
        flags |= libc::AT_EMPTY_PATH;
    }
    // SAFETY: st is plain data the kernel fills in; c is NUL terminated.
    let mut st: libc::stat = unsafe { std::mem::zeroed() };
    let r = unsafe { libc::fstatat(dir.as_raw_fd(), c.as_ptr(), &mut st, flags) };
    if r < 0 {
        return Err(io::Error::last_os_error());
    }
    Ok(Stat {
        mode: st.st_mode,
        dev: st.st_dev,
        ino: st.st_ino,
        mtime_ms: st.st_mtime.saturating_mul(1000).saturating_add(st.st_mtime_nsec / 1_000_000),
    })
}

/// (name, d_type), no `.` or `..`
pub fn read_dir(dir: &OwnedFd, limit: usize) -> io::Result<Vec<(Vec<u8>, u8)>> {
    // can't list an O_PATH fd, so reopen "." through it
    let readable = openat2(dir.as_raw_fd(), b".", libc::O_RDONLY | libc::O_DIRECTORY, RESOLVE_BENEATH)?;
    let raw = readable.as_raw_fd();
    // SAFETY: fdopendir takes ownership of raw, so the OwnedFd is forgotten.
    let dirp = unsafe { libc::fdopendir(raw) };
    if dirp.is_null() {
        return Err(io::Error::last_os_error());
    }
    std::mem::forget(readable);
    let mut out = Vec::new();
    loop {
        if out.len() >= limit {
            break;
        }
        // SAFETY: dirp is a live DIR* until closedir below.
        let ent = unsafe { libc::readdir64(dirp) };
        if ent.is_null() {
            break;
        }
        // SAFETY: readdir returned a valid entry whose d_name is NUL terminated.
        let (name, dtype) = unsafe {
            let e = &*ent;
            (std::ffi::CStr::from_ptr(e.d_name.as_ptr()).to_bytes().to_vec(), e.d_type)
        };
        if name == b"." || name == b".." {
            continue;
        }
        out.push((name, dtype));
    }
    // SAFETY: dirp came from fdopendir and is closed exactly once.
    unsafe { libc::closedir(dirp) };
    Ok(out)
}

pub fn os(bytes: &[u8]) -> &OsStr {
    OsStr::from_bytes(bytes)
}
