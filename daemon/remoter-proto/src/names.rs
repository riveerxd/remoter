//! Folder and session name rules, shared so the phone validates with the same
//! rules the laptop enforces.

/// `^[A-Za-z0-9._][A-Za-z0-9._-]{0,99}$`, and not `.` or `..`. It can never
/// start with `-`, so no tool downstream can read it as a flag.
pub fn is_valid_folder_name(name: &str) -> bool {
    let b = name.as_bytes();
    if b.is_empty() || b.len() > 100 || name == "." || name == ".." {
        return false;
    }
    is_lead(b[0]) && b[1..].iter().all(|&c| is_lead(c) || c == b'-')
}

/// `^[A-Za-z0-9._][A-Za-z0-9 ._-]{0,47}$`, checked after trimming spaces.
pub fn is_valid_session_name(name: &str) -> bool {
    let b = name.trim_matches(' ').as_bytes();
    if b.is_empty() || b.len() > 48 {
        return false;
    }
    is_lead(b[0]) && b[1..].iter().all(|&c| is_lead(c) || c == b'-' || c == b' ')
}

fn is_lead(c: u8) -> bool {
    c.is_ascii_alphanumeric() || c == b'.' || c == b'_'
}

/// A default session name from a folder name: unsupported characters become
/// `-`, then it is trimmed to something `is_valid_session_name` accepts.
pub fn session_name_from_folder(folder: &str) -> String {
    let mapped: String = folder
        .chars()
        .map(|c| if c.is_ascii_alphanumeric() || matches!(c, '.' | '_' | '-' | ' ') { c } else { '-' })
        .collect();
    let trimmed = mapped.trim_start_matches(['-', ' ']);
    let mut out: String = trimmed.chars().take(48).collect();
    while out.ends_with(' ') {
        out.pop();
    }
    if out.is_empty() { "session".to_owned() } else { out }
}

/// True when a directory entry's raw name bytes can't be shown faithfully, so
/// the entry is listed as `unsupported` and nothing may act on it. That keeps
/// what the fingerprint prompt shows equal to what gets signed.
pub fn is_unsupported_name(raw: &[u8]) -> bool {
    let Ok(s) = std::str::from_utf8(raw) else {
        return true;
    };
    s.chars().any(is_hostile_char)
}

/// Text read off the laptop for display only, such as a past conversation's title: hostile
/// characters and line breaks become spaces, runs of space fold into one, and it stops at
/// `max` characters.
pub fn display_text(raw: &str, max: usize) -> String {
    let mut out = String::new();
    let mut n = 0;
    for word in raw.split(|c: char| c.is_whitespace() || is_hostile_char(c)).filter(|w| !w.is_empty()) {
        for c in (if out.is_empty() { "" } else { " " }).chars().chain(word.chars()) {
            if n == max {
                return out.trim_end().to_owned();
            }
            out.push(c);
            n += 1;
        }
    }
    out
}

fn is_hostile_char(c: char) -> bool {
    let u = c as u32;
    u < 0x20                            // C0
        || u == 0x7f                    // DEL
        || (0x80..=0x9f).contains(&u)   // C1
        || (0x202a..=0x202e).contains(&u)
        || (0x2066..=0x2069).contains(&u)
        // not just the embedding and isolate controls: these marks reorder
        // text just as well
        || u == 0x200e || u == 0x200f || u == 0x061c
        || (0x200b..=0x200d).contains(&u)
        || u == 0xfeff
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn display_text_is_one_clean_line() {
        assert_eq!(display_text("  fix\n\tthe   banner  ", 80), "fix the banner");
        assert_eq!(display_text("a\u{202e}b\u{200b}c\u{7}d", 80), "a b c d");
        assert_eq!(display_text("abcdef", 3), "abc");
        assert_eq!(display_text("ab cd", 3), "ab", "no space left hanging at the cut");
        assert_eq!(display_text("\u{1F600}\u{1F600}\u{1F600}", 2), "\u{1F600}\u{1F600}", "counts characters, not bytes");
        assert_eq!(display_text(" \n\u{feff} ", 80), "");
    }

    #[test]
    fn folder_name_edges() {
        for ok in ["a", "A9", ".env", "_x", "v1.2-rc_3", &"a".repeat(100)] {
            assert!(is_valid_folder_name(ok), "{ok}");
        }
        for bad in ["", ".", "..", "-rf", "a b", "a/b", "é", "a;", &"a".repeat(101), "a\0", "~"] {
            assert!(!is_valid_folder_name(bad), "{bad:?}");
        }
    }

    #[test]
    fn session_name_edges() {
        for ok in ["remoter", "my proj", "  padded  ", "a-b.c_d", &"x".repeat(48)] {
            assert!(is_valid_session_name(ok), "{ok}");
        }
        for bad in ["", "   ", "-x", " -x", "a;b", "a$b", "a/b", &"x".repeat(49), "tab\tname"] {
            assert!(!is_valid_session_name(bad), "{bad:?}");
        }
    }

    #[test]
    fn session_name_from_folder_is_always_valid() {
        for folder in ["remoter", "C#{x}", "--rf", "  ", "🚀 launch", "a$HOME", "ß", &"y".repeat(80), "my folder "] {
            let n = session_name_from_folder(folder);
            assert!(is_valid_session_name(&n), "{folder:?} -> {n:?}");
        }
        assert_eq!(session_name_from_folder("C#{x}"), "C--x-");
        assert_eq!(session_name_from_folder("--rf"), "rf");
        assert_eq!(session_name_from_folder("🚀 launch"), "launch");
    }

    #[test]
    fn unsupported_names() {
        assert!(!is_unsupported_name(b"normal"));
        assert!(!is_unsupported_name("caf\u{e9}".as_bytes()));
        assert!(!is_unsupported_name("C#{x} $HOME;".as_bytes()));
        assert!(is_unsupported_name(b"bad\xff"));
        for c in ['\u{1}', '\n', '\u{7f}', '\u{85}', '\u{202e}', '\u{2066}', '\u{200b}', '\u{feff}', '\u{200f}'] {
            assert!(is_unsupported_name(format!("a{c}b").as_bytes()), "{:x}", c as u32);
        }
    }
}
