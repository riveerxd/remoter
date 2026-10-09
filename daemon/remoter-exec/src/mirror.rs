//! claude on a pty of our own, for terminals that can't be asked for their
//! text. Everything passes through unchanged; on the way it is rendered, and
//! the screen with some scrollback is kept in `SCREEN_FILE`. A snapshot, not
//! a log: a session that runs for days still costs one screen in /run.

use std::fs::File;
use std::io::{Read, Write};
use std::os::fd::{AsRawFd, FromRawFd, OwnedFd};
use std::os::unix::process::CommandExt;
use std::path::{Path, PathBuf};
use std::process::{Command, Stdio};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};

use remoter_proto::local::{self, SCREEN_FILE};

const TICK: Duration = Duration::from_millis(200);
/// The phone shows the last 40 lines, whatever size the window is tiled to.
pub const SCROLLBACK: usize = 200;

pub struct Mirror {
    master: OwnedFd,
    parser: Arc<Mutex<vt100::Parser>>,
    dirty: Arc<AtomicBool>,
    drained: Arc<AtomicBool>,
    file: PathBuf,
    saved: Option<libc::termios>,
}

fn last_os_error<T>() -> std::io::Result<T> {
    Err(std::io::Error::last_os_error())
}

fn size_of(fd: i32) -> Option<libc::winsize> {
    // SAFETY: TIOCGWINSZ writes one winsize into ws.
    let mut ws: libc::winsize = unsafe { std::mem::zeroed() };
    let ok = unsafe { libc::ioctl(fd, libc::TIOCGWINSZ, &mut ws) } == 0;
    (ok && ws.ws_row > 0 && ws.ws_col > 0).then_some(ws)
}

fn open_pty() -> std::io::Result<(OwnedFd, OwnedFd)> {
    // SAFETY: plain libc calls on a fd we own; ptsname_r writes at most buf.len() bytes.
    unsafe {
        let m = libc::posix_openpt(libc::O_RDWR | libc::O_NOCTTY | libc::O_CLOEXEC);
        if m < 0 {
            return last_os_error();
        }
        let master = OwnedFd::from_raw_fd(m);
        if libc::grantpt(m) != 0 || libc::unlockpt(m) != 0 {
            return last_os_error();
        }
        let mut buf = [0 as libc::c_char; 128];
        if libc::ptsname_r(m, buf.as_mut_ptr(), buf.len()) != 0 {
            return last_os_error();
        }
        let s = libc::open(buf.as_ptr(), libc::O_RDWR | libc::O_NOCTTY | libc::O_CLOEXEC);
        if s < 0 {
            return last_os_error();
        }
        Ok((master, OwnedFd::from_raw_fd(s)))
    }
}

impl Mirror {
    /// Points `cmd` at a new pty sized like our own terminal. Call before spawn.
    pub fn attach(cmd: &mut Command, dir: &Path) -> std::io::Result<Mirror> {
        let (master, slave) = open_pty()?;
        let ws = size_of(0).unwrap_or(libc::winsize { ws_row: 24, ws_col: 80, ws_xpixel: 0, ws_ypixel: 0 });
        // SAFETY: TIOCSWINSZ reads one winsize.
        unsafe { libc::ioctl(master.as_raw_fd(), libc::TIOCSWINSZ, &ws) };
        cmd.stdin(Stdio::from(slave.try_clone()?)).stdout(Stdio::from(slave.try_clone()?)).stderr(Stdio::from(slave));
        // SAFETY: setsid and ioctl are async signal safe. claude needs the pty
        // as its controlling terminal for Ctrl-C and resizes to reach it.
        unsafe {
            cmd.pre_exec(|| {
                if libc::setsid() < 0 || libc::ioctl(0, libc::TIOCSCTTY, 0) < 0 {
                    return Err(std::io::Error::last_os_error());
                }
                Ok(())
            });
        }
        let parser = vt100::Parser::new(ws.ws_row, ws.ws_col, SCROLLBACK);
        Ok(Mirror {
            master,
            parser: Arc::new(Mutex::new(parser)),
            dirty: Arc::new(AtomicBool::new(true)),
            drained: Arc::new(AtomicBool::new(false)),
            file: dir.join(SCREEN_FILE),
            saved: None,
        })
    }

    /// After spawn, once `cmd` (and with it our copies of the pty's other end) is dropped.
    pub fn start(&mut self) -> std::io::Result<()> {
        // SAFETY: tcgetattr fills t; cfmakeraw only edits our copy.
        unsafe {
            let mut t: libc::termios = std::mem::zeroed();
            if libc::tcgetattr(0, &mut t) == 0 {
                self.saved = Some(t);
                libc::cfmakeraw(&mut t);
                libc::tcsetattr(0, libc::TCSANOW, &t);
            }
        }

        let mut to_pty = File::from(self.master.try_clone()?);
        std::thread::spawn(move || {
            let mut stdin = std::io::stdin().lock();
            let mut buf = [0u8; 4096];
            while let Ok(n) = stdin.read(&mut buf) {
                if n == 0 || to_pty.write_all(&buf[..n]).is_err() {
                    break;
                }
            }
        });

        let mut from_pty = File::from(self.master.try_clone()?);
        let (parser, dirty, drained) = (self.parser.clone(), self.dirty.clone(), self.drained.clone());
        std::thread::spawn(move || {
            let mut out = std::io::stdout().lock();
            let mut buf = [0u8; 16384];
            // EIO once claude and everything it started let go of the pty
            while let Ok(n) = from_pty.read(&mut buf) {
                if n == 0 {
                    break;
                }
                let _ = out.write_all(&buf[..n]);
                let _ = out.flush();
                if let Ok(mut p) = parser.lock() {
                    p.process(&buf[..n]);
                }
                dirty.store(true, Ordering::Release);
            }
            drained.store(true, Ordering::Release);
        });

        let master = self.master.try_clone()?;
        let (parser, dirty, file) = (self.parser.clone(), self.dirty.clone(), self.file.clone());
        std::thread::spawn(move || {
            let mut last = size_of(0);
            loop {
                std::thread::sleep(TICK);
                let now = size_of(0);
                if let Some(ws) = now
                    && last.is_none_or(|l| (l.ws_row, l.ws_col) != (ws.ws_row, ws.ws_col))
                {
                    // SAFETY: TIOCSWINSZ reads one winsize. The kernel signals claude.
                    unsafe { libc::ioctl(master.as_raw_fd(), libc::TIOCSWINSZ, &ws) };
                    if let Ok(mut p) = parser.lock() {
                        resize(&mut p, ws.ws_row, ws.ws_col);
                    }
                    dirty.store(true, Ordering::Release);
                    last = now;
                }
                save(&parser, &dirty, &file);
            }
        });
        Ok(())
    }

    /// claude has exited: take in what it printed last, then hand the terminal back.
    pub fn finish(self) {
        let deadline = Instant::now() + Duration::from_millis(500);
        // something claude started may still hold the pty, so don't wait for it
        while !self.drained.load(Ordering::Acquire) && Instant::now() < deadline {
            std::thread::sleep(Duration::from_millis(20));
        }
        self.dirty.store(true, Ordering::Release);
        save(&self.parser, &self.dirty, &self.file);
        if let Some(t) = self.saved {
            // SAFETY: restores the settings tcgetattr gave us.
            unsafe { libc::tcsetattr(0, libc::TCSANOW, &t) };
        }
    }
}

fn save(parser: &Mutex<vt100::Parser>, dirty: &AtomicBool, file: &Path) {
    if !dirty.swap(false, Ordering::AcqRel) {
        return;
    }
    let text = match parser.lock() {
        Ok(mut p) => text_of(p.screen_mut()),
        Err(_) => return,
    };
    let _ = local::write_atomic(file, text.as_bytes());
}

/// vt100 cuts rows off the bottom when the screen shrinks, which is where
/// the cursor and the newest output are. A terminal scrolls the top away
/// instead, and so does this. A new window tiling in shrinks the rest.
fn resize(p: &mut vt100::Parser, rows: u16, cols: u16) {
    let (old_rows, _) = p.screen().size();
    let (row, col) = p.screen().cursor_position();
    let shift = (row + 1).saturating_sub(rows);
    if shift > 0 {
        let mut scroll = format!("\x1b[{old_rows};1H");
        scroll.extend(std::iter::repeat_n('\n', shift as usize));
        p.process(scroll.as_bytes());
    }
    p.screen_mut().set_size(rows, cols);
    p.process(format!("\x1b[{};{}H", row - shift + 1, col + 1).as_bytes());
}

/// Scrollback, oldest first, then the screen. A line the terminal wrapped
/// comes out whole, as kitty's get-text gives it, so long messages still
/// match. vt100 only shows scrollback by scrolling the view back, so it is
/// read a screenful at a time.
fn text_of(screen: &mut vt100::Screen) -> String {
    let (rows, cols) = screen.size();
    let mut out: Vec<(String, bool)> = Vec::new();
    let mut take_view = |screen: &vt100::Screen, n: usize| {
        for (i, text) in screen.rows(0, cols).take(n).enumerate() {
            out.push((text, screen.row_wrapped(i as u16)));
        }
    };
    screen.set_scrollback(usize::MAX);
    let mut back = screen.scrollback();
    while back > 0 {
        let take = back.min(rows as usize);
        take_view(screen, take);
        back -= take;
        screen.set_scrollback(back);
    }
    take_view(screen, rows as usize);
    let mut text = String::new();
    for (row, wrapped) in out {
        if wrapped {
            text.push_str(&row);
        } else {
            text.push_str(row.trim_end());
            text.push('\n');
        }
    }
    text
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn scrollback_then_screen_in_order() {
        let mut p = vt100::Parser::new(5, 20, SCROLLBACK);
        for i in 0..12 {
            p.process(format!("l{i}\r\n").as_bytes());
        }
        p.process(b"\x1b[1;1Htop");
        let text = text_of(p.screen_mut());
        let lines: Vec<&str> = text.lines().filter(|l| !l.is_empty()).collect();
        assert_eq!(lines, ["l0", "l1", "l2", "l3", "l4", "l5", "l6", "l7", "top", "l9", "l10", "l11"]);
        assert_eq!(p.screen().scrollback(), 0, "the view is left at the bottom");
    }

    #[test]
    fn a_shrink_keeps_the_bottom() {
        let mut p = vt100::Parser::new(10, 20, SCROLLBACK);
        for i in 0..10 {
            p.process(format!("\r\nl{i}").as_bytes());
        }
        resize(&mut p, 4, 20);
        p.process(b"\r\nnext");
        let text = text_of(p.screen_mut());
        let lines: Vec<&str> = text.lines().filter(|l| !l.is_empty()).collect();
        assert_eq!(lines.last(), Some(&"next"), "{text:?}");
        assert_eq!(lines.iter().filter(|l| l.starts_with('l')).count(), 10, "{text:?}");
    }

    #[test]
    fn a_wrapped_line_comes_out_whole() {
        let mut p = vt100::Parser::new(6, 20, SCROLLBACK);
        let long = "Error: Workspace not trusted. Please run `claude` in /home/r/x first";
        p.process(format!("{long}\r\nnext\r\n").as_bytes());
        for i in 0..8 {
            p.process(format!("fill {i}\r\n").as_bytes());
        }
        let text = text_of(p.screen_mut());
        assert!(text.lines().any(|l| l == long), "{text:?}");
        assert!(text.contains("\nnext\n"));
    }

    #[test]
    fn scrollback_is_capped() {
        let mut p = vt100::Parser::new(24, 80, SCROLLBACK);
        for i in 0..5000 {
            p.process(format!("line {i}\r\n").as_bytes());
        }
        let text = text_of(p.screen_mut());
        assert!(text.lines().count() <= SCROLLBACK + 24);
        assert!(text.contains("line 4999"));
    }
}
