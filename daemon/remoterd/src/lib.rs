//! The network facing half of the laptop daemon. Own user, no home, no desktop.
//! Does TLS, HTTP/2, auth, limits and the audit log, and asks the agent to act.

pub mod admin;
pub mod agent;
pub mod attest;
pub mod audit;
pub mod config;
pub mod http;
pub mod limits;
pub mod listener;
pub mod pair;
pub mod serve;
pub mod state;
pub mod tls;

/// Printed at start so it survives optimisation. A test checks release builds lack it.
#[cfg(feature = "e2e-test")]
pub const E2E_MARKER: &str = "remoter-e2e-test-build-marker";

pub fn uid_of(user: &str) -> Option<u32> {
    let c = std::ffi::CString::new(user).ok()?;
    let mut buf = vec![0u8; 16 * 1024];
    // SAFETY: pwd and buf outlive the call; out is set by getpwnam_r.
    let mut pwd: libc::passwd = unsafe { std::mem::zeroed() };
    let mut out: *mut libc::passwd = std::ptr::null_mut();
    let r = unsafe { libc::getpwnam_r(c.as_ptr(), &mut pwd, buf.as_mut_ptr().cast(), buf.len(), &mut out) };
    (r == 0 && !out.is_null()).then_some(pwd.pw_uid)
}

/// Not from /proc: the unit's `ProcSubset=pid` hides /proc/sys.
pub fn hostname() -> String {
    let mut buf = [0u8; 256];
    // SAFETY: buf is writable for its length; the result is NUL terminated
    // or fills the buffer, and both cases are handled below.
    if unsafe { libc::gethostname(buf.as_mut_ptr().cast(), buf.len()) } != 0 {
        return String::new();
    }
    let n = buf.iter().position(|b| *b == 0).unwrap_or(buf.len());
    String::from_utf8_lossy(&buf[..n]).into_owned()
}

#[cfg(test)]
mod tests {
    #[test]
    fn hostname_matches_the_kernel() {
        let proc = std::fs::read_to_string("/proc/sys/kernel/hostname").map(|h| h.trim().to_owned()).unwrap_or_default();
        assert_eq!(super::hostname(), proc);
        assert!(!super::hostname().is_empty());
    }
}
