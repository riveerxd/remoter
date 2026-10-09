//! `SO_PEERCRED` and passwd lookups.

use std::io;
use std::os::fd::AsRawFd;

pub fn peer_uid(sock: &impl AsRawFd) -> io::Result<u32> {
    // SAFETY: ucred is plain data; len matches its size.
    let mut cred: libc::ucred = unsafe { std::mem::zeroed() };
    let mut len = size_of::<libc::ucred>() as libc::socklen_t;
    let r = unsafe {
        libc::getsockopt(
            sock.as_raw_fd(),
            libc::SOL_SOCKET,
            libc::SO_PEERCRED,
            (&mut cred as *mut libc::ucred).cast(),
            &mut len,
        )
    };
    if r != 0 {
        return Err(io::Error::last_os_error());
    }
    Ok(cred.uid)
}

pub fn uid_of(user: &str) -> Option<u32> {
    let c = std::ffi::CString::new(user).ok()?;
    let mut buf = vec![0u8; 16 * 1024];
    // SAFETY: pwd and buf outlive the call; out is set by getpwnam_r.
    let mut pwd: libc::passwd = unsafe { std::mem::zeroed() };
    let mut out: *mut libc::passwd = std::ptr::null_mut();
    let r = unsafe { libc::getpwnam_r(c.as_ptr(), &mut pwd, buf.as_mut_ptr().cast(), buf.len(), &mut out) };
    (r == 0 && !out.is_null()).then_some(pwd.pw_uid)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn socketpair_peer_is_us() {
        let (a, _b) = std::os::unix::net::UnixStream::pair().expect("pair");
        // SAFETY: getuid has no preconditions.
        assert_eq!(peer_uid(&a).expect("cred"), unsafe { libc::getuid() });
    }

    #[test]
    fn root_is_uid_zero() {
        assert_eq!(uid_of("root"), Some(0));
        assert_eq!(uid_of("no-such-user-remoter"), None);
    }
}
