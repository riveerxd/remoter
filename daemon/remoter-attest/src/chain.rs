//! Chain checks, ported from android/keyattestation's
//! KeyAttestationCertPathValidator, plus one rule of ours: an expired
//! certificate is only forgiven on a legacy factory chain under the root whose
//! serial number attribute is f92009e853b6b045.

use std::collections::HashSet;

use x509_parser::certificate::X509Certificate;
use x509_parser::oid_registry::{OID_PKCS1_SHA1WITHRSA, OID_SHA1_WITH_RSA};
use x509_parser::prelude::FromDer;

use crate::keydesc::{self, KeyDescription};
use crate::{Error, Provisioning};

pub const LEGACY_ROOT_SERIAL: &str = "f92009e853b6b045";
const OID_SERIAL_NUMBER: &str = "2.5.4.5";
const MAX_CHAIN: usize = 8;

/// Google's roots, from the JSON list of PEMs that
/// https://android.googleapis.com/attestation/root serves.
#[derive(Debug, Clone)]
pub struct Roots {
    ders: Vec<Vec<u8>>,
}

impl Roots {
    pub fn from_json(bytes: &[u8]) -> Result<Roots, Error> {
        let pems: Vec<String> = serde_json::from_slice(bytes).map_err(|e| Error::Input(format!("roots: {e}")))?;
        let mut ders = Vec::new();
        for pem in pems {
            let body: String = pem.lines().filter(|l| !l.starts_with("-----")).collect();
            let der = base64_std(&body).ok_or_else(|| Error::Input("roots: bad base64".into()))?;
            X509Certificate::from_der(&der).map_err(|_| Error::Input("roots: not a certificate".into()))?;
            ders.push(der);
        }
        if ders.is_empty() {
            return Err(Error::Input("roots: empty".into()));
        }
        Ok(Roots { ders })
    }

    /// Keeps only roots whose SPKI SHA-256 is in `pins`, error if none is. The
    /// list comes over the web PKI daily, and faking that fetch mustn't be
    /// enough to add an anchor.
    pub fn pinned(self, pins: &[[u8; 32]]) -> Result<Roots, Error> {
        use sha2::{Digest, Sha256};
        let ders: Vec<Vec<u8>> = self
            .ders
            .into_iter()
            .filter(|der| {
                X509Certificate::from_der(der).is_ok_and(|(_, c)| pins.contains(&<[u8; 32]>::from(Sha256::digest(c.public_key().raw))))
            })
            .collect();
        if ders.is_empty() {
            return Err(Error::Input("roots: none of them is a pinned Google root".into()));
        }
        Ok(Roots { ders })
    }

    pub fn from_ders(ders: Vec<Vec<u8>>) -> Roots {
        Roots { ders }
    }

    pub fn len(&self) -> usize {
        self.ders.len()
    }

    pub fn is_empty(&self) -> bool {
        self.ders.is_empty()
    }
}

/// Revoked serials, lowercase hex without leading zeros, from
/// https://android.googleapis.com/attestation/status.
#[derive(Debug, Clone, Default)]
pub struct Revoked(pub HashSet<String>);

impl Revoked {
    pub fn from_json(bytes: &[u8]) -> Result<Revoked, Error> {
        #[derive(serde::Deserialize)]
        struct Entry {
            status: String,
        }
        #[derive(serde::Deserialize)]
        struct File {
            entries: std::collections::HashMap<String, Entry>,
        }
        let f: File = serde_json::from_slice(bytes).map_err(|e| Error::Input(format!("status: {e}")))?;
        Ok(Revoked(
            f.entries
                .into_iter()
                // Anything not plainly fine counts as revoked: SUSPENDED is a
                // status too, and fail closed is the rule.
                .filter(|(_, e)| e.status != "VALID")
                .map(|(k, _)| k.trim_start_matches('0').to_ascii_lowercase())
                .collect(),
        ))
    }
}

fn base64_std(s: &str) -> Option<Vec<u8>> {
    const A: &[u8; 64] = b"ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
    let mut out = Vec::new();
    let mut buf = 0u32;
    let mut bits = 0;
    for c in s.bytes().filter(|c| !c.is_ascii_whitespace()) {
        if c == b'=' {
            break;
        }
        let v = A.iter().position(|&x| x == c)? as u32;
        buf = (buf << 6) | v;
        bits += 6;
        if bits >= 8 {
            bits -= 8;
            out.push((buf >> bits) as u8);
            buf &= (1 << bits) - 1;
        }
    }
    Some(out)
}

pub struct Verified {
    pub provisioning: Provisioning,
    /// Raw DER SPKI of the leaf: the attested key.
    pub leaf_spki: Vec<u8>,
    pub key_description: KeyDescription,
    /// What the chain's own names say, cross checked against the key
    /// description (the library's SecurityLevel constraint).
    pub chain_security_level: Option<i64>,
    pub legacy_root: bool,
}

fn serial_hex(c: &X509Certificate<'_>) -> String {
    format!("{:x}", c.tbs_certificate.serial)
}

fn attr<'a>(name: &'a x509_parser::x509::X509Name<'_>, oid: &str) -> Option<&'a str> {
    name.iter_attributes().find(|a| a.attr_type().to_id_string() == oid).and_then(|a| a.as_str().ok())
}

fn has_attestation(c: &X509Certificate<'_>) -> bool {
    c.extensions().iter().any(|e| e.oid.to_id_string() == keydesc::OID)
}

fn verify_sig(child: &X509Certificate<'_>, issuer_spki: &x509_parser::x509::SubjectPublicKeyInfo<'_>) -> Result<(), Error> {
    let alg = &child.signature_algorithm.algorithm;
    if *alg == OID_PKCS1_SHA1WITHRSA || *alg == OID_SHA1_WITH_RSA {
        return Err(Error::Chain("SHA-1 signature".into()));
    }
    child.verify_signature(Some(issuer_spki)).map_err(|e| Error::Chain(format!("signature: {e}")))
}

/// `chain` is leaf first, root last, as Android returns it.
pub fn verify(chain: &[Vec<u8>], roots: &Roots, revoked: &Revoked, now_unix: i64) -> Result<Verified, Error> {
    if chain.len() < 3 || chain.len() > MAX_CHAIN {
        return Err(Error::Chain(format!("{} certificates", chain.len())));
    }
    let certs: Vec<X509Certificate<'_>> = chain
        .iter()
        .map(|d| match X509Certificate::from_der(d) {
            Ok((&[], c)) => Ok(c),
            _ => Err(Error::Chain("not a DER certificate".into())),
        })
        .collect::<Result<_, _>>()?;
    let sent_root = certs.last().ok_or_else(|| Error::Chain("empty".into()))?;
    if sent_root.subject().as_raw() != sent_root.issuer().as_raw() {
        return Err(Error::Chain("last certificate isn't self issued".into()));
    }
    // Everything below the root, root side first, as the validator walks it.
    let path: Vec<&X509Certificate<'_>> = certs[..certs.len() - 1].iter().rev().collect();
    let top = path[0];

    let provisioning = if attr(top.subject(), OID_SERIAL_NUMBER).is_some() {
        Provisioning::Factory
    } else if top.subject().iter_common_name().next().and_then(|c| c.as_str().ok()) == Some("Droid CA2")
        && top.subject().iter_organization().next().and_then(|o| o.as_str().ok()) == Some("Google LLC")
    {
        Provisioning::Remote
    } else {
        Provisioning::Unknown
    };
    let expected = match provisioning {
        Provisioning::Remote => 4,
        Provisioning::Factory => 3,
        Provisioning::Unknown if certs.len() == 4 => 3,
        Provisioning::Unknown => 2,
    };
    if path.len() != expected {
        return Err(Error::Chain(format!("{provisioning:?} chain needs {expected} certificates below the root, has {}", path.len())));
    }

    // The pinned root that signed the top certificate. The root the phone
    // sent is only used to find it by name, never trusted itself.
    let mut anchor = None;
    for der in &roots.ders {
        let Ok((_, r)) = X509Certificate::from_der(der) else { continue };
        if r.subject().as_raw() == top.issuer().as_raw() && verify_sig(top, r.public_key()).is_ok() {
            anchor = Some(r);
            break;
        }
    }
    let anchor = anchor.ok_or_else(|| Error::Chain("doesn't chain to a pinned Google root".into()))?;
    let legacy_root = attr(anchor.subject(), OID_SERIAL_NUMBER) == Some(LEGACY_ROOT_SERIAL);

    for (i, c) in path.iter().enumerate() {
        if i > 0 {
            let parent = path[i - 1];
            if c.issuer().as_raw() != parent.subject().as_raw() {
                return Err(Error::Chain("issuer and subject names don't chain".into()));
            }
            verify_sig(c, parent.public_key())?;
        }
        if revoked.0.contains(&serial_hex(c)) {
            return Err(Error::Revoked(serial_hex(c)));
        }
        let is_leaf = i == path.len() - 1;
        // The leaf's dates are set on the phone, so like the library we
        // don't judge them.
        if !is_leaf {
            let v = c.validity();
            if now_unix < v.not_before.timestamp() {
                return Err(Error::Chain("certificate not yet valid".into()));
            }
            if now_unix > v.not_after.timestamp() && !(provisioning == Provisioning::Factory && legacy_root) {
                return Err(Error::Chain("certificate expired".into()));
            }
        }
        if has_attestation(c) != is_leaf {
            return Err(Error::Chain(if is_leaf {
                "leaf has no attestation extension".into()
            } else {
                "attestation extension above the leaf".into()
            }));
        }
    }

    let leaf = path[path.len() - 1];
    // Exactly one: with two, a verifier reading the first and one reading the
    // last would each approve a different key (RFC 5280 4.2 allows one).
    let mut descs = leaf.extensions().iter().filter(|e| e.oid.to_id_string() == keydesc::OID);
    let ext = descs.next().ok_or_else(|| Error::Chain("no key description".into()))?;
    if descs.next().is_some() {
        return Err(Error::Chain("more than one key description".into()));
    }
    let key_description = keydesc::parse(ext.value).map_err(|e| Error::KeyDescription(e.0.into()))?;

    let chain_security_level = match provisioning {
        Provisioning::Remote => match path[path.len() - 2].subject().iter_organization().next().and_then(|o| o.as_str().ok()) {
            Some("TEE") => Some(1),
            Some("StrongBox") => Some(2),
            _ => Some(0),
        },
        Provisioning::Factory => {
            let sb = [path[path.len() - 2], top].iter().any(|c| c.subject().to_string().to_lowercase().contains("strongbox"));
            sb.then_some(2)
        }
        Provisioning::Unknown => None,
    };

    Ok(Verified { provisioning, leaf_spki: leaf.public_key().raw.to_vec(), key_description, chain_security_level, legacy_root })
}
