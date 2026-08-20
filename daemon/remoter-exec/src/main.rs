//! remoter-exec: runs inside a session's window, before claude.
//!
//! It takes one thing, `--spec <path>`, and trusts the spec only if it sits in
//! a 0700 dir and is a 0600 file, both owned by us. It moves into the folder
//! the agent checked, proves by dev and inode that it is the same folder, and
//! only then starts claude. When claude exits it records why and holds the
//! window open until the session is ended, with no shell left behind.

use std::io::{Read, Write};
use std::os::unix::ffi::OsStrExt;
use std::os::unix::fs::{MetadataExt, OpenOptionsExt};
use std::os::unix::process::{CommandExt, ExitStatusExt};
use std::path::{Path, PathBuf};
use std::process::{Command, ExitCode};
use std::sync::atomic::{AtomicI32, Ordering};

use remoter_proto::api::StuckReason;
use remoter_proto::local::{self, DEBUG_FILE, ExecState, HANDOFF_FILE, HANDOFF_LEAD, HANDOFF_SOURCE_FILE, SPEC_MAX_BYTES, STATUS_FILE, Spec, Status};
use remoter_proto::names::is_valid_session_name;

fn main() -> ExitCode {
    let args: Vec<std::ffi::OsString> = std::env::args_os().skip(1).collect();
    let path = match args.as_slice() {
        [flag, p] if flag == "--spec" => PathBuf::from(p),
        _ => return refuse("usage: remoter-exec --spec <path>"),
    };
    let spec = match load_spec(&path) {
        Ok(s) => s,
        Err(why) => return refuse(&why),
    };
    let dir = PathBuf::from(&spec.dir);

    // Ctrl-C in the window reaches the whole foreground group. It is meant
    // for claude; this process has to outlive claude to report on it.
    // SAFETY: setting a disposition has no memory safety preconditions.
    unsafe { libc::signal(libc::SIGINT, libc::SIG_IGN) };

    if !same_folder(&spec) {
        println!("Folder changed while starting. Nothing was started.");
        write_status(&dir, ExecState::Stuck, None, None, Some(StuckReason::FolderChanged));
        hold();
    }

    // The name passed the session name rules, so it has no control bytes that
    // could end the escape early.
    print!("\x1b]2;{}\x07", spec.name);
    let _ = std::io::stdout().flush();

    // SAFETY: the handler only calls async signal safe functions.
    unsafe {
        libc::signal(libc::SIGTERM, on_end as *const () as libc::sighandler_t);
        libc::signal(libc::SIGHUP, on_end as *const () as libc::sighandler_t);
    }
    let mut argv = spec.argv.clone();
    if let Some(h) = &spec.handoff {
        match handoff(&dir, h) {
            Ok(text) => argv.push(format!("{HANDOFF_LEAD}\n\n{text}\n\n{}", local::handoff_source_note(&h.from, &h.transcript))),
            Err(why) => {
                println!("Couldn't write the handoff: {why}");
                write_status(&dir, ExecState::Stuck, None, None, Some(StuckReason::HandoffFailed));
                hold();
            }
        }
    }

    let mut cmd = Command::new(&argv[0]);
    cmd.args(&argv[1..]).arg("--debug-file").arg(dir.join(DEBUG_FILE)).env("REMOTER_ID", &spec.id);
    // SAFETY: only async signal safe calls between fork and exec. An ignored
    // signal stays ignored across exec, so claude must get SIGINT back.
    unsafe {
        cmd.pre_exec(|| {
            libc::signal(libc::SIGINT, libc::SIG_DFL);
            Ok(())
        });
    }
    let mut child = match cmd.spawn() {
        Ok(c) => c,
        Err(e) => {
            println!("claude didn't start: {e}");
            write_status(&dir, ExecState::Exited, None, Some(127), None);
            hold();
        }
    };
    CLAUDE_PID.store(child.id() as i32, Ordering::SeqCst);
    write_status(&dir, ExecState::Running, Some(child.id() as i32), None, None);
    let code = match child.wait() {
        Ok(st) => st.code().unwrap_or_else(|| 128 + st.signal().unwrap_or(0)),
        Err(_) => 255,
    };
    CLAUDE_PID.store(0, Ordering::SeqCst);
    write_status(&dir, ExecState::Exited, None, Some(code), None);
    println!("\nclaude exited with code {code}. This window stays open until the session is ended.");
    hold();
}

/// How long the summarizer may take. A 300 KiB conversation took 16 s.
const HANDOFF_DEADLINE: std::time::Duration = std::time::Duration::from_secs(240);
/// Its answer goes on claude's argv, and one argument tops out at 128 KiB.
const HANDOFF_MAX: usize = 32 * 1024;

/// Runs the summarizer over the condensed conversation and keeps what it wrote.
/// Also printed in the window, so you can see what the new session was told.
fn handoff(dir: &Path, h: &remoter_proto::local::HandoffSpec) -> Result<String, String> {
    if Path::new(&h.source) != dir.join(HANDOFF_SOURCE_FILE) {
        return Err("the source isn't this session's".into());
    }
    match h.summarizer.first() {
        Some(bin) if Path::new(bin).is_absolute() => {}
        _ => return Err("the summarizer must start with an absolute path".into()),
    }
    write_status(dir, ExecState::Handoff, None, None, None);
    println!("Writing a handoff from the earlier session...");
    let _ = std::io::stdout().flush();
    let source = std::fs::File::open(&h.source).map_err(|e| format!("source: {e}"))?;
    let mut cmd = Command::new(&h.summarizer[0]);
    cmd.args(&h.summarizer[1..])
        .stdin(source)
        .stdout(std::process::Stdio::piped())
        .stderr(std::process::Stdio::piped());
    // SAFETY: only async signal safe calls between fork and exec.
    unsafe {
        cmd.pre_exec(|| {
            libc::signal(libc::SIGINT, libc::SIG_DFL);
            Ok(())
        });
    }
    let mut child = cmd.spawn().map_err(|e| format!("summarizer didn't start: {e}"))?;
    CLAUDE_PID.store(child.id() as i32, Ordering::SeqCst);
    // Read on threads so a chatty summarizer can't fill a pipe and stall while we wait.
    let mut out_pipe = child.stdout.take().ok_or("no stdout")?;
    let mut err_pipe = child.stderr.take().ok_or("no stderr")?;
    let out = std::thread::spawn(move || {
        let mut b = Vec::new();
        let _ = out_pipe.read_to_end(&mut b);
        b
    });
    let err = std::thread::spawn(move || {
        let mut b = Vec::new();
        let _ = err_pipe.read_to_end(&mut b);
        b
    });
    let deadline = std::time::Instant::now() + HANDOFF_DEADLINE;
    let status = loop {
        match child.try_wait() {
            Ok(Some(st)) => break st,
            Ok(None) if std::time::Instant::now() < deadline => std::thread::sleep(std::time::Duration::from_millis(200)),
            _ => {
                let _ = child.kill();
                let _ = child.wait();
                CLAUDE_PID.store(0, Ordering::SeqCst);
                return Err(format!("no answer within {} s", HANDOFF_DEADLINE.as_secs()));
            }
        }
    };
    CLAUDE_PID.store(0, Ordering::SeqCst);
    let text = String::from_utf8_lossy(&out.join().unwrap_or_default()).trim().to_owned();
    if !status.success() || text.is_empty() {
        let e = String::from_utf8_lossy(&err.join().unwrap_or_default()).into_owned();
        let tail: Vec<&str> = e.lines().rev().take(5).collect();
        return Err(format!("summarizer exited {status}: {}", tail.into_iter().rev().collect::<Vec<_>>().join(" / ")));
    }
    let mut text = text;
    if text.len() > HANDOFF_MAX {
        let mut cut = HANDOFF_MAX;
        while !text.is_char_boundary(cut) {
            cut -= 1;
        }
        text.truncate(cut);
    }
    local::write_atomic(&dir.join(HANDOFF_FILE), text.as_bytes()).map_err(|e| format!("keeping it: {e}"))?;
    println!("\n{text}\n");
    Ok(text)
}

/// claude's pid while it runs, 0 otherwise. Read from the signal handler.
static CLAUDE_PID: AtomicI32 = AtomicI32::new(0);

/// Ending the session (SIGTERM) or closing the window (SIGHUP) takes claude
/// down with this process. kitty moves its child into a scope of its own some
/// time after starting it, so claude may sit in either scope, and neither
/// scope stop alone is sure to reach it.
extern "C" fn on_end(sig: libc::c_int) {
    let pid = CLAUDE_PID.load(Ordering::SeqCst);
    // SAFETY: kill, signal and raise are async signal safe.
    unsafe {
        if pid > 0 {
            libc::kill(pid, libc::SIGKILL);
        }
        libc::signal(sig, libc::SIG_DFL);
        libc::raise(sig);
    }
}

fn refuse(why: &str) -> ExitCode {
    eprintln!("remoter-exec: refused: {why}");
    ExitCode::from(2)
}

/// Waits for the SIGTERM that ending the session sends. The default action
/// for SIGTERM and SIGHUP ends the process, which is what we want.
fn hold() -> ! {
    loop {
        // SAFETY: pause has no preconditions.
        unsafe { libc::pause() };
    }
}

fn write_status(dir: &Path, state: ExecState, pid: Option<i32>, exit_code: Option<i32>, reason: Option<StuckReason>) {
    let s = Status { state, pid, exec_pid: std::process::id() as i32, exit_code, reason, at: local::now_ms() };
    if let Ok(json) = serde_json::to_vec(&s) {
        let _ = local::write_atomic(&dir.join(STATUS_FILE), &json);
    }
}

fn same_folder(spec: &Spec) -> bool {
    if std::env::set_current_dir(&spec.cwd).is_err() {
        return false;
    }
    // "." after the chdir, not the path again: this is the folder we are in.
    match std::fs::metadata(".") {
        Ok(m) => m.dev() == spec.dev && m.ino() == spec.ino,
        Err(_) => false,
    }
}

fn uid() -> u32 {
    // SAFETY: geteuid can't fail and has no preconditions.
    unsafe { libc::geteuid() }
}

fn load_spec(path: &Path) -> Result<Spec, String> {
    if !path.is_absolute() {
        return Err("spec path must be absolute".into());
    }
    let dir = path.parent().ok_or("spec has no parent dir")?;
    let dmeta = std::fs::symlink_metadata(dir).map_err(|e| format!("spec dir: {e}"))?;
    if !dmeta.is_dir() || dmeta.uid() != uid() || dmeta.mode() & 0o7777 != 0o700 {
        return Err("spec dir must be a 0700 directory owned by this user".into());
    }
    let mut f = std::fs::OpenOptions::new()
        .read(true)
        .custom_flags(libc::O_NOFOLLOW | libc::O_CLOEXEC)
        .open(path)
        .map_err(|e| format!("spec: {e}"))?;
    // Checked on the open fd, so a swap after the open can't change the answer.
    let m = f.metadata().map_err(|e| format!("spec: {e}"))?;
    if !m.is_file() || m.uid() != uid() || m.mode() & 0o7777 != 0o600 || m.nlink() != 1 {
        return Err("spec must be a 0600 file owned by this user".into());
    }
    if m.len() > SPEC_MAX_BYTES {
        return Err("spec too large".into());
    }
    let mut buf = Vec::new();
    Read::by_ref(&mut f).take(SPEC_MAX_BYTES + 1).read_to_end(&mut buf).map_err(|e| format!("spec: {e}"))?;
    let spec: Spec = serde_json::from_slice(&buf).map_err(|e| format!("spec: {e}"))?;
    if Path::new(&spec.dir) != dir {
        return Err("spec names a different dir".into());
    }
    if dir.file_name().map(|n| n.as_bytes()) != Some(spec.id.as_bytes()) {
        return Err("spec id doesn't match its dir".into());
    }
    if !is_valid_session_name(&spec.name) {
        return Err("session name breaks the name rules".into());
    }
    match spec.argv.first() {
        Some(bin) if Path::new(bin).is_absolute() => {}
        _ => return Err("argv must start with an absolute path".into()),
    }
    if !Path::new(&spec.cwd).is_absolute() {
        return Err("cwd must be absolute".into());
    }
    Ok(spec)
}
