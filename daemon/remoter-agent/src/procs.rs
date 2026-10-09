//! The laptop's load and its processes, read from /proc, and the two signals
//! the phone can send. CPU only exists as the difference of two readings, so
//! the last ones stay around between calls.

use std::collections::HashMap;
use std::io::Read;
use std::os::fd::{AsRawFd, FromRawFd, OwnedFd};
use std::path::PathBuf;
use std::sync::Mutex;
use std::time::{Duration, Instant};

use remoter_proto::ErrorCode;
use remoter_proto::api::{Proc, ProcSession, ProcsResponse, Resources, Signal};
use remoter_proto::names::display_text;

use crate::AgentError;

pub const MAX_PROCS: usize = 800;
const NAME_MAX: usize = 64;
const CMD_MAX: usize = 240;
const CMDLINE_READ: u64 = 4096;
// two phones polling must not cut one second into slivers, so a reading this young is handed out again
const REUSE: Duration = Duration::from_secs(1);
// older than this the last reading says nothing about now, take a fresh pair instead
const STALE: Duration = Duration::from_secs(10);
const SETTLE: Duration = Duration::from_millis(250);
const PF_KTHREAD: u64 = 0x0020_0000;

#[derive(Clone, Copy, Debug, Default, PartialEq)]
pub struct CpuTimes {
    pub busy: u64,
    pub total: u64,
}

/// The `cpu` line of /proc/stat and the number of `cpuN` lines.
pub fn parse_cpu(stat: &str) -> Option<(CpuTimes, u32)> {
    let mut times = None;
    let mut cores = 0;
    for l in stat.lines() {
        let mut it = l.split_whitespace();
        match it.next() {
            Some("cpu") => {
                // guest time is already counted in user, so only the first eight
                let v: Vec<u64> = it.take(8).map(|x| x.parse().ok()).collect::<Option<_>>()?;
                if v.len() < 5 {
                    return None;
                }
                let total: u64 = v.iter().sum();
                times = Some(CpuTimes { busy: total.saturating_sub(v[3] + v[4]), total });
            }
            Some(c) if c.starts_with("cpu") => cores += 1,
            _ => {}
        }
    }
    Some((times?, cores.max(1)))
}

pub fn cpu_pct(before: CpuTimes, after: CpuTimes) -> f64 {
    let total = after.total.saturating_sub(before.total);
    if total == 0 {
        return 0.0;
    }
    let busy = after.busy.saturating_sub(before.busy).min(total);
    round1(busy as f64 * 100.0 / total as f64)
}

fn round1(x: f64) -> f64 {
    (x * 10.0).round() / 10.0
}

/// Bytes for the fields asked for, from /proc/meminfo's kB.
pub fn parse_meminfo(text: &str) -> HashMap<String, u64> {
    text.lines()
        .filter_map(|l| {
            let (k, v) = l.split_once(':')?;
            let kb: u64 = v.split_whitespace().next()?.parse().ok()?;
            Some((k.to_owned(), kb * 1024))
        })
        .collect()
}

#[derive(Debug, PartialEq)]
pub struct Stat {
    pub comm: String,
    pub ppid: i32,
    pub flags: u64,
    /// utime plus stime.
    pub ticks: u64,
    pub start: u64,
    pub rss_pages: u64,
}

/// One /proc/<pid>/stat. comm can hold spaces and parens, so fields count from the last `)`.
pub fn parse_stat(s: &str) -> Option<Stat> {
    let open = s.find('(')?;
    let close = s.rfind(')')?;
    let comm = s.get(open + 1..close)?.to_owned();
    let f: Vec<&str> = s.get(close + 1..)?.split_whitespace().collect();
    let n = |i: usize| f.get(i).and_then(|x| x.parse::<u64>().ok());
    Some(Stat {
        comm,
        ppid: f.get(1)?.parse().ok()?,
        flags: n(6)?,
        ticks: n(11)? + n(12)?,
        start: n(19)?,
        rss_pages: n(21)?,
    })
}

fn real_uid(status: &str) -> Option<u32> {
    status.lines().find_map(|l| l.strip_prefix("Uid:"))?.split_whitespace().next()?.parse().ok()
}

fn cgroup_unit(text: &str) -> Option<String> {
    let line = text.lines().find(|l| l.starts_with("0::"))?;
    Some(line.rsplit('/').next()?.to_owned())
}

pub fn parse_passwd(text: &str) -> HashMap<u32, String> {
    text.lines()
        .filter_map(|l| {
            let mut f = l.split(':');
            let name = f.next()?;
            let uid = f.nth(1)?.parse().ok()?;
            Some((uid, name.to_owned()))
        })
        .collect()
}

#[derive(Clone, Debug)]
struct Raw {
    pid: i32,
    ppid: i32,
    start: u64,
    uid: u32,
    comm: String,
    cmd: String,
    ticks: u64,
    rss: u64,
    unit: Option<String>,
}

/// Ticks per process, keyed by pid and start so a reused pid starts from nothing.
type Ticks = HashMap<(i32, u64), u64>;

#[derive(Default)]
struct State {
    cpu: Option<(Instant, CpuTimes)>,
    resources: Option<(Instant, Resources)>,
    ticks: Option<(Instant, Ticks)>,
    procs: Option<(Instant, ProcsResponse)>,
}

pub struct Procs {
    root: PathBuf,
    passwd: PathBuf,
    me: u32,
    /// The agent and whatever started it. Killing either takes every session's watcher down.
    protected: Vec<i32>,
    hz: f64,
    page: u64,
    state: Mutex<State>,
}

impl Procs {
    pub fn system() -> Procs {
        Procs::new(PathBuf::from("/proc"), PathBuf::from("/etc/passwd"))
    }

    pub fn new(root: PathBuf, passwd: PathBuf) -> Procs {
        // SAFETY: plain getters with no arguments.
        let (me, ppid, hz, page) = unsafe { (libc::getuid(), libc::getppid(), libc::sysconf(libc::_SC_CLK_TCK), libc::sysconf(libc::_SC_PAGESIZE)) };
        Procs {
            root,
            passwd,
            me,
            protected: vec![std::process::id() as i32, ppid],
            hz: if hz > 0 { hz as f64 } else { 100.0 },
            page: if page > 0 { page as u64 } else { 4096 },
            state: Mutex::default(),
        }
    }

    fn read(&self, rel: &str) -> Option<String> {
        std::fs::read_to_string(self.root.join(rel)).ok()
    }

    fn cpu_now(&self) -> Option<(CpuTimes, u32)> {
        parse_cpu(&self.read("stat")?)
    }

    /// `home` is any fd on the filesystem whose space counts.
    pub fn resources(&self, home: &OwnedFd) -> Resources {
        let mut st = self.state.lock().unwrap_or_else(|p| p.into_inner());
        if let Some((at, r)) = &st.resources
            && at.elapsed() < REUSE
        {
            return r.clone();
        }
        let before = match st.cpu.filter(|(at, _)| at.elapsed() < STALE) {
            Some((_, t)) => Some(t),
            None => {
                let t = self.cpu_now().map(|c| c.0);
                std::thread::sleep(SETTLE);
                t
            }
        };
        let now = self.cpu_now();
        let cores = now.map_or(1, |c| c.1);
        let cpu = match (before, now) {
            (Some(b), Some((a, _))) => cpu_pct(b, a),
            _ => 0.0,
        };
        if let Some((t, _)) = now {
            st.cpu = Some((Instant::now(), t));
        }
        let mem = self.read("meminfo").map(|m| parse_meminfo(&m)).unwrap_or_default();
        let m = |k: &str| mem.get(k).copied().unwrap_or(0);
        let (disk_total, disk_free) = disk(home);
        let r = Resources {
            cpu_pct: cpu,
            cores,
            mem_total: m("MemTotal"),
            mem_available: m("MemAvailable"),
            swap_total: m("SwapTotal"),
            swap_free: m("SwapFree"),
            disk_total,
            disk_free,
        };
        st.resources = Some((Instant::now(), r.clone()));
        r
    }

    fn snapshot(&self) -> Vec<Raw> {
        let Ok(rd) = std::fs::read_dir(&self.root) else { return Vec::new() };
        let mut out = Vec::new();
        for e in rd.flatten() {
            let Some(pid) = e.file_name().to_str().and_then(|n| n.parse::<i32>().ok()) else { continue };
            let base = e.path();
            let Some(stat) = std::fs::read_to_string(base.join("stat")).ok().and_then(|s| parse_stat(&s)) else { continue };
            if stat.flags & PF_KTHREAD != 0 || pid == 2 || stat.ppid == 2 {
                continue;
            }
            let Some(uid) = std::fs::read_to_string(base.join("status")).ok().and_then(|s| real_uid(&s)) else { continue };
            let mut raw = Vec::new();
            if let Ok(f) = std::fs::File::open(base.join("cmdline")) {
                let _ = f.take(CMDLINE_READ).read_to_end(&mut raw);
            }
            let cmd = String::from_utf8_lossy(&raw).replace('\0', " ");
            out.push(Raw {
                pid,
                ppid: stat.ppid,
                start: stat.start,
                uid,
                comm: stat.comm,
                cmd,
                ticks: stat.ticks,
                rss: stat.rss_pages * self.page,
                unit: std::fs::read_to_string(base.join("cgroup")).ok().and_then(|c| cgroup_unit(&c)),
            });
        }
        out
    }

    /// `sessions` maps a live session id to its name.
    pub fn procs(&self, home: &OwnedFd, sessions: &HashMap<String, String>) -> ProcsResponse {
        let resources = self.resources(home);
        let mut st = self.state.lock().unwrap_or_else(|p| p.into_inner());
        if let Some((at, r)) = &st.procs
            && at.elapsed() < REUSE
        {
            return ProcsResponse { resources, ..r.clone() };
        }
        let ticks_of = |raws: &[Raw]| raws.iter().map(|r| ((r.pid, r.start), r.ticks)).collect::<Ticks>();
        let (before, since) = match st.ticks.take().filter(|(at, _)| at.elapsed() < STALE) {
            Some((at, m)) => (m, at),
            None => {
                let m = ticks_of(&self.snapshot());
                let at = Instant::now();
                std::thread::sleep(SETTLE);
                (m, at)
            }
        };
        let raws = self.snapshot();
        let now = Instant::now();
        let secs = now.duration_since(since).as_secs_f64().max(0.05);
        st.ticks = Some((now, ticks_of(&raws)));
        let users = std::fs::read_to_string(&self.passwd).map(|t| parse_passwd(&t)).unwrap_or_default();
        let (procs, truncated) = self.build(&raws, &before, secs, &users, sessions);
        let r = ProcsResponse { resources, procs, truncated };
        st.procs = Some((now, r.clone()));
        r
    }

    fn build(&self, raws: &[Raw], before: &Ticks, secs: f64, users: &HashMap<u32, String>, sessions: &HashMap<String, String>) -> (Vec<Proc>, bool) {
        let by_pid: HashMap<i32, &Raw> = raws.iter().map(|r| (r.pid, r)).collect();
        let session_of = |r: &Raw| {
            let mut p = r;
            // kitty moves the shell under it into a scope of its own, so climb to the one remoter made
            for _ in 0..64 {
                if let Some(id) = p.unit.as_deref().and_then(|u| u.strip_suffix(".scope"))
                    && let Some(name) = sessions.get(id)
                {
                    return Some(ProcSession { id: id.to_owned(), name: name.clone() });
                }
                p = by_pid.get(&p.ppid).filter(|_| p.ppid > 1)?;
            }
            None
        };
        let mut procs: Vec<Proc> = raws
            .iter()
            .map(|r| {
                let used = before.get(&(r.pid, r.start)).map_or(0, |b| r.ticks.saturating_sub(*b));
                let cmd = display_text(&r.cmd, CMD_MAX);
                Proc {
                    pid: r.pid,
                    ppid: r.ppid,
                    start: r.start,
                    user: users.get(&r.uid).cloned().unwrap_or_else(|| r.uid.to_string()),
                    name: display_text(&r.comm, NAME_MAX),
                    cmd: if cmd.is_empty() { display_text(&r.comm, NAME_MAX) } else { cmd },
                    cpu_pct: round1(used as f64 / self.hz / secs * 100.0),
                    rss: r.rss,
                    killable: r.uid == self.me && r.pid > 1 && !self.protected.contains(&r.pid),
                    session: session_of(r),
                }
            })
            .collect();
        procs.sort_by(|a, b| b.cpu_pct.total_cmp(&a.cpu_pct).then(b.rss.cmp(&a.rss)).then(a.pid.cmp(&b.pid)));
        let truncated = procs.len() > MAX_PROCS;
        procs.truncate(MAX_PROCS);
        (procs, truncated)
    }

    /// Returns what the audit line shows. The pidfd holds on to the process
    /// the pid named when it was opened, so once its start time checks out the
    /// signal can only reach that one, whatever reuses the pid afterwards.
    pub fn signal(&self, pid: i32, start: u64, sig: Signal) -> Result<String, AgentError> {
        let denied = |m: &str| AgentError::new(ErrorCode::ProcessDenied, m.to_owned());
        let gone = || AgentError::new(ErrorCode::NotFound, "no such process");
        if pid <= 1 || self.protected.contains(&pid) {
            return Err(denied("remoter needs that one"));
        }
        let fd = pidfd_open(pid).map_err(|_| gone())?;
        let base = self.root.join(pid.to_string());
        let stat = std::fs::read_to_string(base.join("stat")).ok().and_then(|s| parse_stat(&s)).ok_or_else(gone)?;
        if stat.start != start {
            return Err(gone());
        }
        let uid = std::fs::read_to_string(base.join("status")).ok().and_then(|s| real_uid(&s)).ok_or_else(gone)?;
        if uid != self.me || stat.flags & PF_KTHREAD != 0 {
            return Err(denied("not yours"));
        }
        let signo = match sig {
            Signal::Term => libc::SIGTERM,
            Signal::Kill => libc::SIGKILL,
        };
        // SAFETY: fd is a live pidfd, the info pointer may be null.
        let rc = unsafe { libc::syscall(libc::SYS_pidfd_send_signal, fd.as_raw_fd(), signo, std::ptr::null::<libc::siginfo_t>(), 0) };
        if rc != 0 {
            let e = std::io::Error::last_os_error();
            return Err(match e.raw_os_error() {
                Some(libc::ESRCH) => gone(),
                Some(libc::EPERM) => denied("not yours"),
                _ => AgentError::new(ErrorCode::Internal, e.to_string()),
            });
        }
        let word = if sig == Signal::Term { "term" } else { "kill" };
        Ok(format!("{} {pid} {word}", display_text(&stat.comm, NAME_MAX)))
    }
}

fn pidfd_open(pid: i32) -> std::io::Result<OwnedFd> {
    // SAFETY: plain syscall; the fd is owned right after.
    let fd = unsafe { libc::syscall(libc::SYS_pidfd_open, pid, 0) };
    if fd < 0 {
        return Err(std::io::Error::last_os_error());
    }
    Ok(unsafe { OwnedFd::from_raw_fd(fd as i32) })
}

fn disk(fd: &OwnedFd) -> (u64, u64) {
    let mut s = std::mem::MaybeUninit::<libc::statvfs>::uninit();
    // SAFETY: s is writable for one statvfs.
    if unsafe { libc::fstatvfs(fd.as_raw_fd(), s.as_mut_ptr()) } != 0 {
        return (0, 0);
    }
    // SAFETY: fstatvfs filled it.
    let s = unsafe { s.assume_init() };
    let f = s.f_frsize;
    (s.f_blocks * f, s.f_bavail * f)
}

#[cfg(test)]
mod tests {
    use super::*;

    const STAT: &str = "cpu  100 5 50 800 20 3 2 0 7 0\ncpu0 50 2 25 400 10 1 1 0 0 0\ncpu1 50 3 25 400 10 2 1 0 0 0\nintr 1 2 3\n";

    fn stat_line(pid: i32, comm: &str, ppid: i32, flags: u64, utime: u64, start: u64, rss: u64) -> String {
        format!("{pid} ({comm}) S {ppid} {pid} {pid} 0 -1 {flags} 0 0 0 0 {utime} 1 0 0 20 0 1 0 {start} 1000 {rss} 18446744073709551615 0")
    }

    fn uid() -> u32 {
        // SAFETY: plain getter.
        unsafe { libc::getuid() }
    }

    #[test]
    fn cpu_line_and_cores() {
        let (t, cores) = parse_cpu(STAT).expect("parses");
        assert_eq!(cores, 2);
        assert_eq!(t, CpuTimes { busy: 160, total: 980 });
        assert_eq!(parse_cpu("intr 1\n"), None);
    }

    #[test]
    fn cpu_share_between_readings() {
        let a = CpuTimes { busy: 100, total: 1000 };
        assert_eq!(cpu_pct(a, CpuTimes { busy: 150, total: 1200 }), 25.0);
        assert_eq!(cpu_pct(a, a), 0.0);
        // counters that went backwards are no load rather than a wrap
        assert_eq!(cpu_pct(a, CpuTimes { busy: 50, total: 900 }), 0.0);
    }

    #[test]
    fn meminfo_in_bytes() {
        let m = parse_meminfo("MemTotal:       16000000 kB\nMemAvailable:    4000000 kB\nHugePages_Total:       0\n");
        assert_eq!(m.get("MemTotal"), Some(&(16_000_000 * 1024)));
        assert_eq!(m.get("MemAvailable"), Some(&(4_000_000 * 1024)));
    }

    #[test]
    fn comm_with_spaces_and_parens() {
        let s = parse_stat(&stat_line(42, "a) (b c", 7, 0, 30, 999, 12)).expect("parses");
        assert_eq!(s.comm, "a) (b c");
        assert_eq!((s.ppid, s.ticks, s.start, s.rss_pages), (7, 31, 999, 12));
        assert_eq!(parse_stat("42 (x) S"), None);
    }

    #[test]
    fn reads_this_machine() {
        let home = crate::guard::Home::open(&std::env::temp_dir()).expect("home");
        let r = Procs::system().procs(home.fd(), &HashMap::new());
        assert!(r.resources.mem_total > 0 && r.resources.cores > 0 && r.resources.disk_total > 0);
        let me = r.procs.iter().find(|x| x.pid == std::process::id() as i32).expect("this test is listed");
        assert!(!me.killable, "the agent never offers itself");
        assert!(r.procs.iter().any(|x| x.user == "root" && !x.killable));
    }

    struct Fake {
        root: PathBuf,
    }

    impl Fake {
        fn new(tag: &str) -> Fake {
            let root = PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("../target/tmp").join(format!("procs-{tag}-{}", std::process::id()));
            let _ = std::fs::remove_dir_all(&root);
            std::fs::create_dir_all(root.join("proc")).expect("mk");
            std::fs::write(root.join("proc/stat"), STAT).expect("stat");
            std::fs::write(root.join("proc/meminfo"), "MemTotal: 1000 kB\nMemAvailable: 400 kB\n").expect("mem");
            std::fs::write(root.join("passwd"), format!("root:x:0:0::/root:/bin/sh\nriver:x:{}:1000::/home/river:/bin/sh\n", uid())).expect("pw");
            Fake { root }
        }

        #[allow(clippy::too_many_arguments)]
        fn add(&self, pid: i32, comm: &str, ppid: i32, uid: u32, unit: &str, cmd: &[u8], flags: u64, utime: u64) {
            let d = self.root.join("proc").join(pid.to_string());
            std::fs::create_dir_all(&d).expect("mk");
            std::fs::write(d.join("stat"), stat_line(pid, comm, ppid, flags, utime, 500 + pid as u64, 10)).expect("stat");
            std::fs::write(d.join("status"), format!("Name:\t{comm}\nUid:\t{uid}\t{uid}\t{uid}\t{uid}\n")).expect("status");
            std::fs::write(d.join("cgroup"), format!("0::/user.slice/user@1000.service/app.slice/{unit}\n")).expect("cg");
            std::fs::write(d.join("cmdline"), cmd).expect("cmd");
        }

        fn procs(&self) -> Procs {
            Procs::new(self.root.join("proc"), self.root.join("passwd"))
        }
    }

    impl Drop for Fake {
        fn drop(&mut self) {
            let _ = std::fs::remove_dir_all(&self.root);
        }
    }

    #[test]
    fn sessions_are_found_through_kittys_own_scope() {
        let me = uid();
        let f = Fake::new("tag");
        f.add(100, "kitty", 1, me, "rc-01k6b7y3m4n5p6q7r8s9t0v1w2.scope", b"/usr/bin/kitty\0--class\0remoter-rc", 0, 0);
        f.add(101, "remoter-exec", 100, me, "kitty-100-0.scope", b"remoter-exec", 0, 0);
        f.add(102, "claude", 101, me, "kitty-100-0.scope", b"claude\0--remote-control=x\0", 0, 0);
        f.add(200, "kitty", 1, me, "kitty-200-0.scope", b"kitty", 0, 0);
        f.add(201, "zsh", 200, me, "kitty-200-0.scope", b"zsh", 0, 0);
        f.add(300, "kworker/0:1", 2, 0, "init.scope", b"", PF_KTHREAD, 0);
        f.add(400, "sshd", 1, 0, "sshd.service", b"sshd: \x1b[31m\nroot\xff", 0, 0);
        let sessions = HashMap::from([("rc-01k6b7y3m4n5p6q7r8s9t0v1w2".to_owned(), "remoter".to_owned())]);
        let home = crate::guard::Home::open(&f.root).expect("home");
        let r = f.procs().procs(home.fd(), &sessions);
        let get = |pid: i32| r.procs.iter().find(|p| p.pid == pid);
        for pid in [100, 101, 102] {
            assert_eq!(get(pid).and_then(|p| p.session.clone()).map(|s| s.name).as_deref(), Some("remoter"), "{pid}");
        }
        assert_eq!(get(201).map(|p| p.session.is_none()), Some(true));
        assert!(get(300).is_none(), "kernel threads stay out");
        let sshd = get(400).expect("listed");
        assert_eq!((sshd.user.as_str(), sshd.killable), ("root", false));
        assert!(!sshd.cmd.contains('\x1b') && !sshd.cmd.contains('\n'), "{:?}", sshd.cmd);
        assert_eq!(get(102).map(|p| p.cmd.as_str()), Some("claude --remote-control=x"));
        assert_eq!(get(102).map(|p| p.killable), Some(true));
        assert_eq!(r.resources.mem_total, 1000 * 1024);
    }

    #[test]
    fn cpu_counts_against_the_last_reading_of_the_same_process() {
        let me = uid();
        let f = Fake::new("cpu");
        f.add(10, "busy", 1, me, "a.scope", b"busy", 0, 0);
        f.add(11, "idle", 1, me, "a.scope", b"idle", 0, 0);
        let p = f.procs();
        let home = crate::guard::Home::open(&f.root).expect("home");
        p.procs(home.fd(), &HashMap::new());
        std::thread::sleep(REUSE + Duration::from_millis(50));
        // a full second of ticks on one of them, over a bit more than a second
        f.add(10, "busy", 1, me, "a.scope", b"busy", 0, p.hz as u64);
        let r = p.procs(home.fd(), &HashMap::new());
        assert_eq!(r.procs.first().map(|x| x.pid), Some(10), "busiest first");
        let busy = r.procs[0].cpu_pct;
        assert!(busy > 50.0 && busy <= 100.0, "{busy}");
        assert_eq!(r.procs.iter().find(|x| x.pid == 11).map(|x| x.cpu_pct), Some(0.0));
    }

    fn sleeper() -> (std::process::Child, u64) {
        let child = std::process::Command::new("sleep").arg("30").spawn().expect("sleep");
        let s = std::fs::read_to_string(format!("/proc/{}/stat", child.id())).ok().and_then(|s| parse_stat(&s)).expect("stat");
        (child, s.start)
    }

    #[test]
    fn a_stale_start_time_hits_nothing() {
        let (mut child, start) = sleeper();
        let r = Procs::system().signal(child.id() as i32, start + 1, Signal::Kill);
        assert_eq!(r.map_err(|e| e.code), Err(ErrorCode::NotFound));
        assert!(child.try_wait().expect("wait").is_none(), "still running");
        let _ = child.kill();
        let _ = child.wait();
    }

    #[test]
    fn term_and_kill_land() {
        use std::os::unix::process::ExitStatusExt;
        for (sig, want) in [(Signal::Term, libc::SIGTERM), (Signal::Kill, libc::SIGKILL)] {
            let (mut child, start) = sleeper();
            let what = Procs::system().signal(child.id() as i32, start, sig).expect("signalled");
            assert_eq!(what, format!("sleep {} {}", child.id(), if sig == Signal::Term { "term" } else { "kill" }));
            assert_eq!(child.wait().expect("wait").signal(), Some(want));
        }
    }

    #[test]
    fn never_init_itself_or_someone_elses() {
        let p = Procs::system();
        let stat_of = |pid: i32| std::fs::read_to_string(format!("/proc/{pid}/stat")).ok().and_then(|s| parse_stat(&s));
        let start_of = |pid: i32| stat_of(pid).map_or(0, |s| s.start);
        for pid in [0, 1, -5, std::process::id() as i32] {
            assert_eq!(p.signal(pid, start_of(pid), Signal::Term).map_err(|e| e.code), Err(ErrorCode::ProcessDenied), "{pid}");
        }
        let root_owned = std::fs::read_dir("/proc").expect("proc").flatten().filter_map(|e| e.file_name().to_str()?.parse::<i32>().ok()).find(|&pid| {
            pid > 1
                && std::fs::read_to_string(format!("/proc/{pid}/status")).ok().and_then(|s| real_uid(&s)) == Some(0)
                && stat_of(pid).is_some_and(|s| s.flags & PF_KTHREAD == 0)
        });
        let pid = root_owned.expect("some root process runs");
        assert_eq!(p.signal(pid, start_of(pid), Signal::Term).map_err(|e| e.code), Err(ErrorCode::ProcessDenied), "{pid}");
    }
}
