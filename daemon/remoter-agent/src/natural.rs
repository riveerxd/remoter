//! Natural order, so `v2` sorts before `v10`.

use std::cmp::Ordering;

pub fn cmp(a: &str, b: &str) -> Ordering {
    let (mut x, mut y) = (a.as_bytes(), b.as_bytes());
    loop {
        match (x.first(), y.first()) {
            (None, None) => return a.cmp(b),
            (None, Some(_)) => return Ordering::Less,
            (Some(_), None) => return Ordering::Greater,
            (Some(p), Some(q)) if p.is_ascii_digit() && q.is_ascii_digit() => {
                let (dx, rx) = split_digits(x);
                let (dy, ry) = split_digits(y);
                let (tx, ty) = (trim_zeros(dx), trim_zeros(dy));
                let o = tx.len().cmp(&ty.len()).then_with(|| tx.cmp(ty));
                if o != Ordering::Equal {
                    return o;
                }
                x = rx;
                y = ry;
            }
            (Some(p), Some(q)) => {
                let o = p.to_ascii_lowercase().cmp(&q.to_ascii_lowercase());
                if o != Ordering::Equal {
                    return o;
                }
                x = &x[1..];
                y = &y[1..];
            }
        }
    }
}

fn split_digits(s: &[u8]) -> (&[u8], &[u8]) {
    let n = s.iter().take_while(|b| b.is_ascii_digit()).count();
    s.split_at(n)
}

fn trim_zeros(s: &[u8]) -> &[u8] {
    let n = s.iter().take_while(|&&b| b == b'0').count();
    &s[n.min(s.len().saturating_sub(1))..]
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn sorts_naturally() {
        let mut v = vec!["v10", "v2", "V1", "a", "B", "v02", "file1b", "file1a", "10", "9"];
        v.sort_by(|a, b| cmp(a, b));
        assert_eq!(v, vec!["9", "10", "a", "B", "file1a", "file1b", "V1", "v02", "v2", "v10"]);
    }

    #[test]
    fn huge_numbers_do_not_overflow() {
        assert_eq!(cmp("x99999999999999999999999", "x100000000000000000000000"), Ordering::Less);
    }
}
