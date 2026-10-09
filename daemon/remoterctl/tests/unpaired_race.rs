//! `remoterctl revoke` runs as root and edits the agent's unpaired list, which
//! lives in a directory your uid owns. A session running as you can swap that
//! file for a symlink at any moment. Whatever it races, root must never change
//! the mode or contents of the file the link points at.

use std::os::unix::fs::{OpenOptionsExt, PermissionsExt};
use std::path::{Path, PathBuf};
use std::sync::Arc;
use std::sync::atomic::{AtomicBool, Ordering};

fn dir(tag: &str) -> PathBuf {
    let d = PathBuf::from(env!("CARGO_TARGET_TMPDIR")).join(format!("{tag}-{}", std::process::id()));
    let _ = std::fs::remove_dir_all(&d);
    std::fs::create_dir_all(&d).expect("mk");
    d
}

/// A regular file put in place atomically, mode set on the fd, so the test
/// itself never writes or chmods through the attacker's link.
fn plant(path: &Path, text: &[u8], mode: u32) {
    use std::io::Write;
    let tmp = path.with_extension("plant");
    let _ = std::fs::remove_file(&tmp);
    let mut f = std::fs::OpenOptions::new().write(true).create_new(true).mode(0o600).open(&tmp).expect("plant");
    f.write_all(text).expect("w");
    f.set_permissions(std::fs::Permissions::from_mode(mode)).expect("fchmod");
    std::fs::rename(&tmp, path).expect("rename");
}

#[test]
fn swapped_symlink_untouched() {
    let d = dir("unpaired-race");
    let victim = d.join("victim");
    plant(&victim, b"secret\n", 0o600);
    let path = d.join("unpaired.json");
    let stop = Arc::new(AtomicBool::new(false));
    let swapper = {
        let (d, victim, path, stop) = (d.clone(), victim.clone(), path.clone(), stop.clone());
        std::thread::spawn(move || {
            let link = d.join("swap-link");
            while !stop.load(Ordering::Relaxed) {
                let _ = std::fs::remove_file(&link);
                if std::os::unix::fs::symlink(&victim, &link).is_ok() {
                    let _ = std::fs::rename(&link, &path);
                }
            }
        })
    };
    let mut hit = None;
    for i in 0..20_000 {
        plant(&path, br#"{"devices":["01K6B7Y3M4N5P6Q7R8S9T0V1W2","01K6B7Y3M4N5P6Q7R8S9T0V1W3"]}"#, 0o666);
        let _ = remoterctl::forget_unpaired(&path, "01K6B7Y3M4N5P6Q7R8S9T0V1W2");
        let mode = std::fs::metadata(&victim).expect("victim").permissions().mode() & 0o777;
        let text = std::fs::read(&victim).expect("victim");
        if mode != 0o600 || text != b"secret\n" {
            hit = Some((i, mode, String::from_utf8_lossy(&text).into_owned()));
            break;
        }
    }
    stop.store(true, Ordering::Relaxed);
    swapper.join().expect("swapper");
    let _ = std::fs::remove_dir_all(&d);
    assert_eq!(hit, None, "(iteration, victim mode, victim contents) after the race");
}
