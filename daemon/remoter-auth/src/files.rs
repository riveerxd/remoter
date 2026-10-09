//! Startup check on the trust files. Both daemons refuse to start if any of
//! them, or any directory above them, could be changed by someone other than
//! the trusted owner (root in production).

use std::os::unix::fs::MetadataExt;
use std::path::Path;

/// Returns every problem found, empty when all is well. Missing files are
/// reported too: a trust file that should exist and doesn't is a problem.
pub fn check_trust_files(paths: &[&Path], trusted_owner: u32) -> Vec<String> {
    let mut problems = Vec::new();
    for p in paths {
        let mut cur = Some(*p);
        let mut first = true;
        while let Some(c) = cur {
            match std::fs::symlink_metadata(c) {
                Ok(m) => {
                    if m.file_type().is_symlink() {
                        problems.push(format!("{} is a symlink", c.display()));
                    } else if m.uid() != trusted_owner && m.uid() != 0 {
                        problems.push(format!("{} is owned by uid {}", c.display(), m.uid()));
                    } else if m.mode() & 0o022 != 0 {
                        problems.push(format!("{} is writable by group or others", c.display()));
                    }
                }
                Err(e) if first => problems.push(format!("{}: {e}", c.display())),
                Err(_) => {}
            }
            first = false;
            cur = c.parent();
        }
    }
    problems.sort();
    problems.dedup();
    problems
}

/// `/var/lib/remoterd` belongs to the daemon, not root, since it writes there.
/// Nobody else may write it, and the dirs above it get the trust file rule.
pub fn check_state_dir(dir: &Path, self_uid: u32, trusted_owner: u32) -> Vec<String> {
    let mut problems = Vec::new();
    match std::fs::symlink_metadata(dir) {
        Ok(m) if m.file_type().is_symlink() => problems.push(format!("{} is a symlink", dir.display())),
        Ok(m) if !m.is_dir() => problems.push(format!("{} is not a directory", dir.display())),
        Ok(m) if m.uid() != self_uid && m.uid() != 0 => problems.push(format!("{} is owned by uid {}", dir.display(), m.uid())),
        Ok(m) if m.mode() & 0o022 != 0 => problems.push(format!("{} is writable by group or others", dir.display())),
        Ok(_) => {}
        Err(e) => problems.push(format!("{}: {e}", dir.display())),
    }
    if let Some(parent) = dir.parent() {
        problems.extend(check_trust_files(&[parent], trusted_owner));
    }
    problems
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::os::unix::fs::PermissionsExt;

    fn uid() -> u32 {
        // SAFETY: getuid has no preconditions.
        unsafe { libc::getuid() }
    }

    #[test]
    fn flags_loose_trust_files() {
        let root = std::env::temp_dir().join(format!("remoter-trust-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&root);
        std::fs::create_dir_all(&root).expect("mkdir");
        std::fs::set_permissions(&root, std::fs::Permissions::from_mode(0o755)).expect("chmod");
        let good = root.join("good");
        std::fs::write(&good, "x").expect("w");
        std::fs::set_permissions(&good, std::fs::Permissions::from_mode(0o644)).expect("chmod");
        let loose = root.join("loose");
        std::fs::write(&loose, "x").expect("w");
        std::fs::set_permissions(&loose, std::fs::Permissions::from_mode(0o666)).expect("chmod");
        let link = root.join("link");
        std::os::unix::fs::symlink(&good, &link).expect("ln");
        let missing = root.join("missing");

        // temp_dir is /tmp, world writable and sticky: point the check below it.
        let only = |p: &Path| check_trust_files(&[p], uid()).into_iter().filter(|m| m.contains(&*root.to_string_lossy())).collect::<Vec<_>>();
        assert!(only(&good).is_empty(), "{:?}", only(&good));
        assert_eq!(only(&loose).len(), 1);
        assert_eq!(only(&link).len(), 1);
        assert_eq!(only(&missing).len(), 1);
        let foreign = check_trust_files(&[&good], uid().wrapping_add(4242));
        assert!(foreign.iter().any(|m| m.contains("owned by uid")), "{foreign:?}");
        std::fs::set_permissions(&root, std::fs::Permissions::from_mode(0o777)).expect("chmod");
        assert!(only(&good).iter().any(|m| m.contains("writable")), "a loose parent dir is caught");
        std::fs::set_permissions(&root, std::fs::Permissions::from_mode(0o755)).expect("chmod");
        let _ = std::fs::remove_dir_all(&root);
    }

    #[test]
    fn state_dir_may_be_daemons() {
        // the first real install refused its own /var/lib/remoterd, which systemd
        // creates owned by remoterd. not under /tmp, its 1777 fails the parent check
        let root = std::path::Path::new(env!("CARGO_MANIFEST_DIR")).join("../target").join(format!("remoter-state-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&root);
        std::fs::create_dir_all(&root).expect("mkdir");
        std::fs::set_permissions(&root, std::fs::Permissions::from_mode(0o755)).expect("chmod");
        let state = root.join("state");
        std::fs::create_dir(&state).expect("mkdir");
        std::fs::set_permissions(&state, std::fs::Permissions::from_mode(0o711)).expect("chmod");
        let me = uid();
        if me != 0 {
            // the old behaviour
            assert!(!check_trust_files(&[state.as_path()], 0).is_empty(), "the old check refused it");
        }
        assert!(check_state_dir(&state, me, me).is_empty(), "{:?}", check_state_dir(&state, me, me));
        std::fs::set_permissions(&state, std::fs::Permissions::from_mode(0o731)).expect("chmod");
        assert!(check_state_dir(&state, me, me).iter().any(|p| p.contains("writable")));
        std::fs::set_permissions(&state, std::fs::Permissions::from_mode(0o711)).expect("chmod");
        assert!(check_state_dir(&state, me + 1, me + 1).iter().any(|p| p.contains("owned by uid")), "someone else's dir is refused");
        assert!(!check_state_dir(&root.join("missing"), me, me).is_empty());
        let _ = std::fs::remove_dir_all(&root);
    }
}
