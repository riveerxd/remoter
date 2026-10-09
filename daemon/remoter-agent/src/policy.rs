//! Where sessions may start. Stops a bypass session in `~/.ssh` by mistake, not a
//! security boundary: a session in `~/Projects` can still read `~/.ssh`.

use remoter_proto::api::DenyReason;

use crate::guard::RelPath;

/// Per component on the canonical path: `.ssh-notes` passes, a symlink into `.ssh` doesn't.
pub fn deny_reason(canonical: &RelPath, cwd_deny: &[String]) -> Option<DenyReason> {
    if canonical.is_home() {
        return Some(DenyReason::Home);
    }
    canonical.components().iter().any(|c| cwd_deny.iter().any(|d| d == c)).then_some(DenyReason::Denied)
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::guard::parse_rel;

    fn deny() -> Vec<String> {
        [".ssh", ".gnupg", ".claude", ".config", ".local", ".password-store"].map(String::from).to_vec()
    }

    fn reason(p: &str) -> Option<DenyReason> {
        deny_reason(&parse_rel(p.as_bytes()).expect("valid"), &deny())
    }

    #[test]
    fn home_itself_is_refused() {
        assert_eq!(reason(""), Some(DenyReason::Home));
    }

    #[test]
    fn denied_at_any_depth() {
        for d in deny() {
            assert_eq!(reason(&d), Some(DenyReason::Denied), "{d}");
            assert_eq!(reason(&format!("{d}/inner")), Some(DenyReason::Denied), "{d}/inner");
            assert_eq!(reason(&format!("Projects/{d}")), Some(DenyReason::Denied), "Projects/{d}");
        }
    }

    #[test]
    fn similar_names_pass() {
        for ok in [".ssh-notes", "ssh", "Projects/.sshx", "my.config", ".local2", "Projects/remoter"] {
            assert_eq!(reason(ok), None, "{ok}");
        }
    }
}
