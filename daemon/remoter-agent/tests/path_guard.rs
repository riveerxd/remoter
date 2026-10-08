//! Path guard, against a throwaway home under the target dir.

use std::collections::HashMap;
use std::ffi::OsStr;
use std::os::unix::ffi::OsStrExt;
use std::os::unix::fs::symlink;
use std::path::{Path, PathBuf};
use std::sync::Arc;
use std::sync::atomic::{AtomicBool, AtomicUsize, Ordering};
use std::time::Duration;

use remoter_agent::guard::{Home, parse_rel};
use remoter_agent::list::Lister;
use remoter_agent::search::Searcher;
use remoter_agent::trust::Trust;
use remoter_proto::ErrorCode;
use remoter_proto::api::{DenyReason, SymlinkKind};

const DENY: [&str; 6] = [".ssh", ".gnupg", ".claude", ".config", ".local", ".password-store"];

struct World {
    root: PathBuf,
    home: PathBuf,
}

impl World {
    fn new(tag: &str) -> World {
        static N: AtomicUsize = AtomicUsize::new(0);
        let root = PathBuf::from(env!("CARGO_TARGET_TMPDIR"))
            .join(format!("guard-{tag}-{}-{}", std::process::id(), N.fetch_add(1, Ordering::Relaxed)));
        let _ = std::fs::remove_dir_all(&root);
        let home = root.join("home");
        for d in ["Projects/remoter/.git", "Projects/v2", "Projects/v10", "Projects/.hidden", "outside-ish"] {
            std::fs::create_dir_all(home.join(d)).expect("mkdir");
        }
        std::fs::write(home.join("Projects/remoter/CLAUDE.md"), "x").expect("write");
        std::fs::write(home.join("file.txt"), "x").expect("write");
        for d in DENY.iter().chain([".ssh-notes"].iter()) {
            std::fs::create_dir_all(home.join(d)).expect("mkdir");
        }
        std::fs::create_dir_all(root.join("home2/secret")).expect("mkdir");
        std::fs::create_dir_all(root.join("outside/secret")).expect("mkdir");
        symlink("Projects/remoter", home.join("link_rel_inside")).expect("ln");
        symlink("../outside", home.join("link_rel_out")).expect("ln");
        symlink("../home2", home.join("link_river2")).expect("ln");
        symlink(root.join("home2"), home.join("link_abs_river2")).expect("ln");
        symlink(home.join("Projects/remoter"), home.join("link_abs_inside")).expect("ln");
        symlink("/etc", home.join("link_abs_etc")).expect("ln");
        symlink("loop_b", home.join("loop_a")).expect("ln");
        symlink("loop_a", home.join("loop_b")).expect("ln");
        symlink(".ssh", home.join("notes")).expect("ln");
        std::fs::create_dir(home.join(OsStr::from_bytes(b"bad\xffname"))).expect("mkdir");
        std::fs::create_dir(home.join("safe\u{202e}txt")).expect("mkdir");
        std::fs::create_dir(home.join("zero\u{200b}width")).expect("mkdir");
        let json = format!(
            r#"{{"projects":{{"{h}":{{"hasTrustDialogAccepted":false}},"{h}/Projects":{{"hasTrustDialogAccepted":true}}}}}}"#,
            h = home.display()
        );
        std::fs::write(root.join("claude.json"), json).expect("write");
        World { root, home }
    }

    fn guard(&self) -> Home {
        Home::open(&self.home).expect("open home")
    }

    fn trust(&self) -> Trust {
        Trust::new(self.root.join("claude.json"))
    }
}

impl Drop for World {
    fn drop(&mut self) {
        let _ = std::fs::remove_dir_all(&self.root);
    }
}

fn deny() -> Vec<String> {
    DENY.map(String::from).to_vec()
}

fn open_code(h: &Home, p: &str) -> Result<String, ErrorCode> {
    let rel = parse_rel(p.as_bytes()).map_err(|e| e.code)?;
    h.open_dir(&rel).map(|o| o.canonical.as_string()).map_err(|e| e.code)
}

#[test]
fn dotdot_and_absolute_input_are_refused() {
    let w = World::new("dotdot");
    let h = w.guard();
    assert_eq!(open_code(&h, ".."), Err(ErrorCode::PathOutsideHome));
    assert_eq!(open_code(&h, "Projects/../../outside"), Err(ErrorCode::PathOutsideHome));
    assert_eq!(open_code(&h, "/etc"), Err(ErrorCode::PathOutsideHome));
    assert_eq!(open_code(&h, &w.root.join("outside").display().to_string()), Err(ErrorCode::PathOutsideHome));
    assert_eq!(open_code(&h, "../home2"), Err(ErrorCode::PathOutsideHome));
}

#[test]
fn encoded_dots_stay_literal() {
    let w = World::new("encoded");
    let h = w.guard();
    assert_eq!(open_code(&h, "%2e%2e"), Err(ErrorCode::NotFound));
    assert_eq!(open_code(&h, "%2e%2e/outside"), Err(ErrorCode::NotFound));
}

#[test]
fn escaping_symlinks_are_refused_by_the_kernel() {
    let w = World::new("escape");
    let h = w.guard();
    assert_eq!(open_code(&h, "link_abs_etc"), Err(ErrorCode::PathOutsideHome));
    assert_eq!(open_code(&h, "link_rel_out"), Err(ErrorCode::PathOutsideHome));
    assert_eq!(open_code(&h, "link_rel_out/secret"), Err(ErrorCode::PathOutsideHome));
    assert_eq!(open_code(&h, "link_river2"), Err(ErrorCode::PathOutsideHome));
    assert_eq!(open_code(&h, "link_abs_river2"), Err(ErrorCode::PathOutsideHome));
}

/// openat2 alone, without the fd path check behind it: every escape is EXDEV.
#[test]
fn kernel_lookup_alone_refuses_every_escape() {
    let w = World::new("kernel");
    let h = w.guard();
    for p in ["link_abs_etc", "link_rel_out", "link_rel_out/secret", "link_river2", "link_abs_river2", "link_abs_inside"] {
        let err = h.resolve(&parse_rel(p.as_bytes()).expect("rel"), 0).err();
        assert_eq!(err.and_then(|e| e.raw_os_error()), Some(libc::EXDEV), "{p}");
    }
    assert!(h.resolve(&parse_rel(b"link_rel_inside").expect("rel"), 0).is_ok());
}

#[test]
fn symlink_loop_is_not_found() {
    let w = World::new("loop");
    assert_eq!(open_code(&w.guard(), "loop_a"), Err(ErrorCode::NotFound));
}

#[test]
fn relative_symlink_resolves_to_real_path() {
    let w = World::new("relin");
    assert_eq!(open_code(&w.guard(), "link_rel_inside"), Ok("Projects/remoter".into()));
}

#[test]
fn absolute_symlink_inside_home_is_refused_and_flagged() {
    let w = World::new("absin");
    let h = w.guard();
    assert_eq!(open_code(&h, "link_abs_inside"), Err(ErrorCode::PathOutsideHome));
    let t = w.trust();
    let s = HashMap::new();
    let d = deny();
    let l = Lister { home: &h, trust: &t, cwd_deny: &d, sessions: &s, budget: Duration::from_secs(2) };
    let resp = l.list(&parse_rel(b"").expect("home"), true).expect("list");
    let e = resp.entries.iter().find(|e| e.name == "link_abs_inside").expect("listed");
    assert_eq!(e.symlink, SymlinkKind::Absolute);
    assert_eq!(e.symlink_target.as_deref(), Some("Projects/remoter"));
    assert!(!e.spawn_allowed);
    assert_eq!(e.deny_reason, Some(DenyReason::SymlinkAbsolute));
    let etc = resp.entries.iter().find(|e| e.name == "link_abs_etc").expect("listed");
    assert_eq!(etc.symlink_target, None, "targets outside home are never offered");
    let r2 = resp.entries.iter().find(|e| e.name == "link_abs_river2").expect("listed");
    assert_eq!(r2.symlink_target, None, "a sibling that shares home's prefix is outside");
    assert!(resp.entries.iter().all(|e| e.name != "link_rel_out" && e.name != "link_river2"), "escaping links are dropped");
    assert!(resp.entries.iter().all(|e| e.name != "file.txt"), "directories only");
}

#[test]
fn home_itself_opens_but_never_allows_a_spawn() {
    let w = World::new("home");
    let h = w.guard();
    assert_eq!(open_code(&h, ""), Ok(String::new()));
    let t = w.trust();
    let s = HashMap::new();
    let d = deny();
    let l = Lister { home: &h, trust: &t, cwd_deny: &d, sessions: &s, budget: Duration::from_secs(2) };
    let resp = l.list(&parse_rel(b"").expect("home"), false).expect("list");
    assert_eq!(resp.path, "");
    assert!(!resp.spawn_allowed);
    assert_eq!(resp.deny_reason, Some(DenyReason::Home));
}

#[test]
fn hostile_input_bytes_are_refused() {
    for (raw, code) in [
        (&b"a\0b"[..], ErrorCode::PathUnsupported),
        (&b"bad\xffname"[..], ErrorCode::PathUnsupported),
        ("a\u{7}b".as_bytes(), ErrorCode::PathUnsupported),
        ("safe\u{202e}txt".as_bytes(), ErrorCode::PathUnsupported),
        ("zero\u{200b}width".as_bytes(), ErrorCode::PathUnsupported),
    ] {
        assert_eq!(parse_rel(raw).err().map(|e| e.code), Some(code), "{raw:?}");
    }
}

#[test]
fn listing_marks_unsafe_names_unsupported_and_unactionable() {
    let w = World::new("names");
    let h = w.guard();
    let t = w.trust();
    let s = HashMap::new();
    let d = deny();
    let l = Lister { home: &h, trust: &t, cwd_deny: &d, sessions: &s, budget: Duration::from_secs(2) };
    let resp = l.list(&parse_rel(b"").expect("home"), true).expect("list");
    let weird: Vec<_> = resp.entries.iter().filter(|e| e.unsupported).collect();
    assert_eq!(weird.len(), 3, "{:?}", weird.iter().map(|e| &e.name).collect::<Vec<_>>());
    for e in weird {
        assert!(!e.spawn_allowed);
        assert_eq!(e.deny_reason, Some(DenyReason::Unsupported));
    }
}

#[test]
fn deny_list_blocks_spawn_not_lookalikes() {
    let w = World::new("deny");
    let h = w.guard();
    // trust everything so only the deny list decides
    std::fs::write(
        w.root.join("claude.json"),
        format!(r#"{{"projects":{{"{}":{{"hasTrustDialogAccepted":true}}}}}}"#, w.home.display()),
    )
    .expect("write");
    let t = w.trust();
    let s = HashMap::new();
    let d = deny();
    let l = Lister { home: &h, trust: &t, cwd_deny: &d, sessions: &s, budget: Duration::from_secs(2) };
    let resp = l.list(&parse_rel(b"").expect("home"), true).expect("list");
    for name in DENY {
        let e = resp.entries.iter().find(|e| e.name == name).expect("listed");
        assert_eq!(e.deny_reason, Some(DenyReason::Denied), "{name}");
        assert!(!e.spawn_allowed, "{name}");
    }
    let notes = resp.entries.iter().find(|e| e.name == ".ssh-notes").expect("listed");
    assert!(notes.spawn_allowed, ".ssh-notes is not .ssh");
    let via_link = resp.entries.iter().find(|e| e.name == "notes").expect("listed");
    assert_eq!(via_link.symlink, SymlinkKind::Relative);
    assert_eq!(via_link.deny_reason, Some(DenyReason::Denied), "judged by the real path");
}

#[test]
fn listing_facts_sort_and_trust() {
    let w = World::new("facts");
    let h = w.guard();
    let t = w.trust();
    let mut s = HashMap::new();
    s.insert("Projects/remoter".to_owned(), 2u32);
    let d = deny();
    let l = Lister { home: &h, trust: &t, cwd_deny: &d, sessions: &s, budget: Duration::from_secs(2) };
    let resp = l.list(&parse_rel(b"Projects").expect("rel"), false).expect("list");
    let names: Vec<_> = resp.entries.iter().map(|e| e.name.as_str()).collect();
    assert_eq!(names, vec!["remoter", "v2", "v10"], "hidden skipped, natural order");
    let r = &resp.entries[0];
    assert!(r.is_git && r.has_claude_md && r.trusted && r.spawn_allowed);
    assert_eq!(r.session_count, 2);
    assert_eq!(r.file_count, Some(1), "the .git folder");
    assert!(resp.trusted && !resp.partial && !resp.truncated);
    let with_hidden = l.list(&parse_rel(b"Projects").expect("rel"), true).expect("list");
    assert!(with_hidden.entries.iter().any(|e| e.name == ".hidden"));
    let home_list = l.list(&parse_rel(b"").expect("home"), false).expect("list");
    let other = home_list.entries.iter().find(|e| e.name == "outside-ish").expect("listed");
    assert!(!other.trusted, "home is explicitly untrusted");
    assert!(other.spawn_allowed && other.deny_reason.is_none(), "the agent trusts it at start");
}

#[test]
fn listing_caps_at_2000_and_times_out() {
    let w = World::new("cap");
    let big = w.home.join("Projects/big");
    for i in 0..2001 {
        std::fs::create_dir_all(big.join(format!("d{i}"))).expect("mkdir");
    }
    let h = w.guard();
    let t = w.trust();
    let s = HashMap::new();
    let d = deny();
    let l = Lister { home: &h, trust: &t, cwd_deny: &d, sessions: &s, budget: Duration::from_secs(10) };
    let resp = l.list(&parse_rel(b"Projects/big").expect("rel"), false).expect("list");
    assert_eq!(resp.entries.len(), 2000);
    assert!(resp.truncated);
    let rushed = Lister { budget: Duration::ZERO, ..l };
    let resp = rushed.list(&parse_rel(b"Projects/big").expect("rel"), false).expect("list");
    assert!(resp.partial);
    assert!(resp.entries.iter().any(|e| e.file_count.is_none()));
}

#[test]
fn not_found_and_not_a_directory() {
    let w = World::new("nf");
    let h = w.guard();
    assert_eq!(open_code(&h, "nope"), Err(ErrorCode::NotFound));
    assert_eq!(open_code(&h, "file.txt"), Err(ErrorCode::NotADirectory));
}

/// 10k lookups while another thread atomically swaps a folder for a symlink out of home.
#[test]
fn race_swapping_dir_for_symlink_never_escapes() {
    let w = World::new("race");
    std::fs::create_dir_all(w.home.join("swap/inner")).expect("mkdir");
    symlink(w.root.join("outside"), w.home.join("alt_abs")).expect("ln");
    symlink("../outside", w.home.join("alt_rel")).expect("ln");
    let h = w.guard();
    let home_real = h.path().to_path_buf();
    let stop = Arc::new(AtomicBool::new(false));
    let swapper = {
        let stop = stop.clone();
        let dir = w.home.clone();
        std::thread::spawn(move || {
            let (swap, abs, rel) = (cpath(&dir.join("swap")), cpath(&dir.join("alt_abs")), cpath(&dir.join("alt_rel")));
            let mut n = 0u64;
            while !stop.load(Ordering::Relaxed) {
                let other = if n.is_multiple_of(2) { &abs } else { &rel };
                // SAFETY: both are valid NUL terminated paths for this call.
                unsafe {
                    libc::renameat2(libc::AT_FDCWD, swap.as_ptr(), libc::AT_FDCWD, other.as_ptr(), libc::RENAME_EXCHANGE)
                };
                n += 1;
            }
            n
        })
    };
    let mut landed = 0;
    for i in 0..10_000 {
        let p = if i % 2 == 0 { "swap" } else { "swap/inner" };
        // raw kernel lookup, so the fd path check can't hide an escape
        if let Ok(fd) = h.resolve(&parse_rel(p.as_bytes()).expect("rel"), 0) {
            let real = std::fs::read_link(format!("/proc/self/fd/{}", std::os::fd::AsRawFd::as_raw_fd(&fd)))
                .expect("fd path");
            assert!(real.starts_with(&home_real), "escaped to {}", real.display());
            assert!(!real.starts_with(w.root.join("outside")), "escaped to {}", real.display());
            landed += 1;
        }
    }
    stop.store(true, Ordering::Relaxed);
    let swaps = swapper.join().expect("swapper");
    assert!(swaps > 100, "the swapper barely ran ({swaps}), the race proved nothing");
    assert!(landed > 0, "no lookup ever succeeded, the race proved nothing");
    eprintln!("race: {swaps} swaps, {landed} of 10000 lookups landed, none outside");
}

fn cpath(p: &Path) -> std::ffi::CString {
    std::ffi::CString::new(p.as_os_str().as_bytes()).expect("no NUL")
}

#[test]
fn search_walks_breadth_first_within_limits() {
    let w = World::new("search");
    for d in ["a/b/c/target4", "a/b/c/d/target5", "Projects/node_modules/target_skip", "Projects/remoter-two"] {
        std::fs::create_dir_all(w.home.join(d)).expect("mkdir");
    }
    let h = w.guard();
    let skip = vec!["node_modules".to_owned(), ".git".to_owned()];
    let recent = vec!["Projects/remoter-two".to_owned()];
    let s = Searcher { home: &h, skip: &skip, recent: &recent, budget: Duration::from_secs(5) };
    let home = parse_rel(b"").expect("home");

    let r = s.search(&home, "target").expect("search");
    let paths: Vec<_> = r.hits.iter().map(|x| x.path.as_str()).collect();
    assert!(paths.contains(&"a/b/c/target4"), "{paths:?}");
    assert!(!paths.iter().any(|p| p.ends_with("target5")), "depth 5 is past the limit: {paths:?}");
    assert!(!paths.iter().any(|p| p.contains("node_modules")), "skipped: {paths:?}");

    let r = s.search(&home, "remoter").expect("search");
    let paths: Vec<_> = r.hits.iter().map(|x| x.path.as_str()).collect();
    assert_eq!(paths.first(), Some(&"Projects/remoter"), "exact match first: {paths:?}");
    assert!(!paths.iter().any(|p| p.starts_with("link_")), "symlinks are never followed: {paths:?}");

    let r = s.search(&home, "Pro/rem").expect("search");
    let paths: Vec<_> = r.hits.iter().map(|x| x.path.as_str()).collect();
    assert_eq!(paths, vec!["Projects/remoter", "Projects/remoter-two"], "prefix, then recency");

    let r = s.search(&parse_rel(b"Projects").expect("rel"), "~/Pro").expect("search");
    assert_eq!(r.hits.first().map(|x| x.path.as_str()), Some("Projects"));
}

#[test]
fn search_stops_at_fifty_hits() {
    let w = World::new("fifty");
    for i in 0..60 {
        std::fs::create_dir_all(w.home.join(format!("Projects/match{i}"))).expect("mkdir");
    }
    let h = w.guard();
    let skip = Vec::new();
    let s = Searcher { home: &h, skip: &skip, recent: &[], budget: Duration::from_secs(5) };
    let r = s.search(&parse_rel(b"").expect("home"), "match").expect("search");
    assert_eq!(r.hits.len(), 50);
    assert!(r.capped);
}
