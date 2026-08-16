//! A strict DER reader for the attestation extension. Strict on purpose: one
//! value has one encoding, so nothing in a key description can be read two
//! ways by two readers. Definite lengths only, minimal lengths, minimal
//! integers, booleans as 00 or FF, tag numbers in their shortest form.

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Class {
    Universal,
    Application,
    Context,
    Private,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Tlv<'a> {
    pub class: Class,
    pub constructed: bool,
    pub tag: u32,
    pub value: &'a [u8],
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct DerError(pub &'static str);

pub type Result<T> = std::result::Result<T, DerError>;

fn err<T>(m: &'static str) -> Result<T> {
    Err(DerError(m))
}

pub const INTEGER: u32 = 2;
pub const BOOLEAN: u32 = 1;
pub const OCTET_STRING: u32 = 4;
pub const NULL: u32 = 5;
pub const ENUMERATED: u32 = 10;
pub const SEQUENCE: u32 = 16;
pub const SET: u32 = 17;

/// Reads one TLV and returns it with what follows.
pub fn read(input: &[u8]) -> Result<(Tlv<'_>, &[u8])> {
    let (&first, mut rest) = input.split_first().ok_or(DerError("empty"))?;
    let class = match first >> 6 {
        0 => Class::Universal,
        1 => Class::Application,
        2 => Class::Context,
        _ => Class::Private,
    };
    let constructed = first & 0x20 != 0;
    let mut tag = (first & 0x1f) as u32;
    if tag == 0x1f {
        tag = 0;
        let mut n = 0;
        loop {
            let (&b, r) = rest.split_first().ok_or(DerError("truncated tag"))?;
            rest = r;
            if n == 0 && b == 0x80 {
                return err("tag with a leading zero");
            }
            n += 1;
            if n > 4 {
                return err("tag too long");
            }
            tag = (tag << 7) | (b & 0x7f) as u32;
            if b & 0x80 == 0 {
                break;
            }
        }
        if tag < 0x1f {
            return err("tag not in its shortest form");
        }
    }
    let (&l0, r) = rest.split_first().ok_or(DerError("truncated length"))?;
    rest = r;
    let len = if l0 < 0x80 {
        l0 as usize
    } else if l0 == 0x80 {
        return err("indefinite length");
    } else {
        let n = (l0 & 0x7f) as usize;
        if n > 4 || rest.len() < n {
            return err("bad length");
        }
        let (bytes, r) = rest.split_at(n);
        rest = r;
        if bytes[0] == 0 {
            return err("length with a leading zero");
        }
        let len = bytes.iter().fold(0usize, |a, b| (a << 8) | *b as usize);
        if len < 0x80 {
            return err("long form length for a short value");
        }
        len
    };
    if rest.len() < len {
        return err("value runs past the end");
    }
    let (value, rest) = rest.split_at(len);
    Ok((Tlv { class, constructed, tag, value }, rest))
}

/// Exactly one TLV and nothing after it.
pub fn read_all(input: &[u8]) -> Result<Tlv<'_>> {
    let (t, rest) = read(input)?;
    if !rest.is_empty() {
        return err("trailing bytes");
    }
    Ok(t)
}

pub fn children(value: &[u8]) -> Result<Vec<Tlv<'_>>> {
    let mut out = Vec::new();
    let mut rest = value;
    while !rest.is_empty() {
        let (t, r) = read(rest)?;
        out.push(t);
        rest = r;
    }
    Ok(out)
}

impl<'a> Tlv<'a> {
    fn universal(&self, tag: u32, constructed: bool) -> Result<()> {
        if self.class != Class::Universal || self.tag != tag || self.constructed != constructed {
            return err("unexpected type");
        }
        Ok(())
    }

    pub fn sequence(&self) -> Result<Vec<Tlv<'a>>> {
        self.universal(SEQUENCE, true)?;
        children(self.value)
    }

    pub fn set(&self) -> Result<Vec<Tlv<'a>>> {
        self.universal(SET, true)?;
        children(self.value)
    }

    pub fn octets(&self) -> Result<&'a [u8]> {
        self.universal(OCTET_STRING, false)?;
        Ok(self.value)
    }

    pub fn null(&self) -> Result<()> {
        self.universal(NULL, false)?;
        if !self.value.is_empty() {
            return err("NULL with content");
        }
        Ok(())
    }

    pub fn boolean(&self) -> Result<bool> {
        self.universal(BOOLEAN, false)?;
        match self.value {
            [0x00] => Ok(false),
            [0xff] => Ok(true),
            _ => err("boolean not 00 or FF"),
        }
    }

    fn signed(&self) -> Result<i64> {
        let v = self.value;
        if v.is_empty() || v.len() > 8 {
            return err("integer size");
        }
        if v.len() > 1 && ((v[0] == 0x00 && v[1] & 0x80 == 0) || (v[0] == 0xff && v[1] & 0x80 != 0)) {
            return err("integer not minimal");
        }
        let neg = v[0] & 0x80 != 0;
        Ok(v.iter().fold(if neg { -1i64 } else { 0 }, |a, b| (a << 8) | *b as i64))
    }

    pub fn integer(&self) -> Result<i64> {
        self.universal(INTEGER, false)?;
        self.signed()
    }

    pub fn enumerated(&self) -> Result<i64> {
        self.universal(ENUMERATED, false)?;
        self.signed()
    }

    /// The single value inside an EXPLICIT context tag.
    pub fn explicit(&self) -> Result<Tlv<'a>> {
        if self.class != Class::Context || !self.constructed {
            return err("not an explicit context tag");
        }
        read_all(self.value)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn reads_high_tag_numbers() {
        // [CONTEXT 709] { OCTET STRING "a" }
        let t = read_all(&[0xbf, 0x85, 0x45, 0x03, 0x04, 0x01, b'a']).expect("tlv");
        assert_eq!((t.class, t.tag, t.constructed), (Class::Context, 709, true));
        assert_eq!(t.explicit().and_then(|i| i.octets().map(|o| o.to_vec())), Ok(b"a".to_vec()));
    }

    #[test]
    fn refuses_non_der_forms() {
        for bad in [
            &[0x02, 0x80][..],                      // indefinite length
            &[0x02, 0x81, 0x01, 0x00],              // long form for a short length
            &[0x02, 0x82, 0x00, 0x80],              // length with a leading zero
            &[0x02, 0x02, 0x00, 0x01],              // integer with a leading zero
            &[0x02, 0x02, 0xff, 0x80],              // integer with a leading FF
            &[0x9f, 0x05, 0x00],                    // tag 5 in the long form
            &[0xbf, 0x80, 0x01, 0x00],              // tag with a leading zero
            &[0x02, 0x05, 0x01],                    // runs past the end
            &[0x02, 0x01, 0x01, 0x00],              // trailing byte
        ] {
            let r = read_all(bad).and_then(|t| if t.tag == INTEGER { t.integer().map(|_| t) } else { Ok(t) });
            assert!(r.is_err(), "{bad:02x?}");
        }
        assert!(read_all(&[0x01, 0x01, 0x01]).expect("tlv").boolean().is_err(), "boolean 01");
        assert_eq!(read_all(&[0x01, 0x01, 0xff]).expect("tlv").boolean(), Ok(true));
    }

    #[test]
    fn integers() {
        assert_eq!(read_all(&[0x02, 0x01, 0x7f]).expect("t").integer(), Ok(127));
        assert_eq!(read_all(&[0x02, 0x02, 0x00, 0x80]).expect("t").integer(), Ok(128));
        assert_eq!(read_all(&[0x02, 0x01, 0xff]).expect("t").integer(), Ok(-1));
        assert_eq!(read_all(&[0x02, 0x03, 0x03, 0x16, 0x8e]).expect("t").integer(), Ok(202382));
    }
}
