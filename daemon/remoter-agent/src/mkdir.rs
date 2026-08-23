//! One folder via `mkdirat` under a guarded parent. Never `create_dir_all`, never merges.

use std::ffi::CString;
use std::os::fd::AsRawFd;
use std::os::unix::process::CommandExt;
use std::process::{Command, Stdio};

use remoter_proto::ErrorCode;
use remoter_proto::api::{MkdirRequest, MkdirResponse};
use remoter_proto::names::is_valid_folder_name;

use crate::guard::{Home, parse_rel};
use crate::{AgentError, policy, sys};

pub fn mkdir(home: &Home, cwd_deny: &[String], git_bin: &std::path::Path, req: &MkdirRequest) -> Result<MkdirResponse, AgentError> {
    if !is_valid_folder_name(&req.name) {
        return Err(AgentError::new(ErrorCode::NameInvalid, "folder name breaks the rules"));
    }
    let parent = home.open_dir(&parse_rel(req.parent.as_bytes())?)?;
    let target = parent.canonical.join(&req.name);
    if policy::deny_reason(&target, cwd_deny) == Some(remoter_proto::api::DenyReason::Denied) {
        return Err(AgentError::new(ErrorCode::PathDenied, "inside a denied folder"));
    }
    let name = CString::new(req.name.as_bytes()).map_err(|_| AgentError::new(ErrorCode::NameInvalid, "NUL"))?;
    // SAFETY: name is NUL terminated and the fd is live for the call.
    let r = unsafe { libc::mkdirat(parent.fd.as_raw_fd(), name.as_ptr(), 0o755) };
    if r != 0 {
        let e = std::io::Error::last_os_error();
        let code = match e.raw_os_error() {
            Some(libc::EEXIST) => ErrorCode::Exists,
            Some(libc::EACCES) | Some(libc::EPERM) | Some(libc::EROFS) => ErrorCode::PathDenied,
            _ => ErrorCode::Internal,
        };
        return Err(AgentError::new(code, e.to_string()));
    }
    let made = sys::openat2(
        parent.fd.as_raw_fd(),
        req.name.as_bytes(),
        libc::O_PATH | libc::O_DIRECTORY,
        sys::RESOLVE_BENEATH | sys::RESOLVE_NO_SYMLINKS,
    )
    .map_err(|e| AgentError::new(ErrorCode::Internal, e.to_string()))?;
    if req.git_init {
        git_init(git_bin, &made)?;
    }
    Ok(MkdirResponse { path: home.canonical_of(&made)?.as_string() })
}

/// `fchdir` in the child instead of `current_dir(path)`, so the path can't be swapped under us.
fn git_init(git_bin: &std::path::Path, dir: &std::os::fd::OwnedFd) -> Result<(), AgentError> {
    let fd = dir.as_raw_fd();
    let mut cmd = Command::new(git_bin);
    cmd.args(["init", "-q"]).stdin(Stdio::null()).stdout(Stdio::null()).stderr(Stdio::piped());
    // SAFETY: fchdir is async signal safe, and fd stays open until wait.
    unsafe {
        cmd.pre_exec(move || if libc::fchdir(fd) == 0 { Ok(()) } else { Err(std::io::Error::last_os_error()) });
    }
    let out = cmd.output().map_err(|e| AgentError::new(ErrorCode::Internal, format!("git: {e}")))?;
    if !out.status.success() {
        return Err(AgentError::new(ErrorCode::Internal, format!("git init: {}", String::from_utf8_lossy(&out.stderr))));
    }
    Ok(())
}
