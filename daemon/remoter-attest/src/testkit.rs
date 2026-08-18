//! A stand in for Google's attestation PKI, and a builder for attestation
//! chains with any key description, so every policy rule can be broken one
//! at a time. Only for tests, behind the `testkit` feature.

use rcgen::{
    BasicConstraints, CertificateParams, CertifiedIssuer, CustomExtension, DistinguishedName, DnType, IsCa, KeyPair,
    PKCS_ECDSA_P256_SHA256, SerialNumber, date_time_ymd,
};

use crate::keydesc::tag;

// A minimal DER writer, only for building test vectors.

fn len(n: usize) -> Vec<u8> {
    if n < 0x80 {
        return vec![n as u8];
    }
    let b: Vec<u8> = n.to_be_bytes().into_iter().skip_while(|x| *x == 0).collect();
    let mut out = vec![0x80 | b.len() as u8];
    out.extend(b);
    out
}

pub fn tlv(t: u8, content: &[u8]) -> Vec<u8> {
    let mut out = vec![t];
    out.extend(len(content.len()));
    out.extend_from_slice(content);
    out
}

pub fn int(v: i64) -> Vec<u8> {
    let bytes = v.to_be_bytes();
    let mut i = 0;
    while i < 7 && ((bytes[i] == 0 && bytes[i + 1] & 0x80 == 0) || (bytes[i] == 0xff && bytes[i + 1] & 0x80 != 0)) {
        i += 1;
    }
    tlv(0x02, &bytes[i..])
}

pub fn enumerated(v: i64) -> Vec<u8> {
    let mut e = int(v);
    e[0] = 0x0a;
    e
}

pub fn octets(b: &[u8]) -> Vec<u8> {
    tlv(0x04, b)
}

pub fn seq(items: &[Vec<u8>]) -> Vec<u8> {
    tlv(0x30, &items.concat())
}

pub fn set(items: &[Vec<u8>]) -> Vec<u8> {
    tlv(0x31, &items.concat())
}

pub fn null() -> Vec<u8> {
    vec![0x05, 0x00]
}

pub fn boolean(b: bool) -> Vec<u8> {
    vec![0x01, 0x01, if b { 0xff } else { 0x00 }]
}

/// `[CONTEXT tag] EXPLICIT inner`, with the high tag number form above 30.
pub fn explicit(t: u32, inner: &[u8]) -> Vec<u8> {
    let mut out = Vec::new();
    if t < 31 {
        out.push(0xa0 | t as u8);
    } else {
        out.push(0xbf);
        let mut groups = Vec::new();
        let mut v = t;
        loop {
            groups.push((v & 0x7f) as u8);
            v >>= 7;
            if v == 0 {
                break;
            }
        }
        for (i, g) in groups.iter().rev().enumerate() {
            out.push(if i + 1 < groups.len() { g | 0x80 } else { *g });
        }
    }
    out.extend(len(inner.len()));
    out.extend_from_slice(inner);
    out
}

/// Everything a key description can say that the policy looks at. The
/// default is a key that passes as the `sig` key.
#[derive(Debug, Clone)]
pub struct Spec {
    pub challenge: Vec<u8>,
    pub attestation_security_level: i64,
    pub keymint_security_level: i64,
    pub purposes: Vec<i64>,
    pub algorithm: Option<i64>,
    pub ec_curve: Option<i64>,
    pub origin: Option<i64>,
    pub no_auth_required: bool,
    pub user_auth_type: Option<i64>,
    pub auth_timeout: Option<i64>,
    pub unlocked_in_hardware: bool,
    pub unlocked_in_software: bool,
    pub device_locked: bool,
    pub boot_state: i64,
    pub boot_key: Vec<u8>,
    pub boot_hash: Option<Vec<u8>>,
    pub os_patch: Option<i64>,
    pub boot_patch: Option<i64>,
    pub packages: Vec<(String, i64)>,
    pub signatures: Vec<Vec<u8>>,
    /// Extra hardware list entries, written after the sorted ones as given,
    /// to test ordering and unknown tags.
    pub extra_hw: Vec<(u32, Vec<u8>)>,
}

pub const PACKAGE: &str = "me.river.remoter";
pub const APP_DIGEST: [u8; 32] = [0x5a; 32];
pub const BOOT_KEY: [u8; 32] = [0xa1; 32];
pub const BOOT_HASH: [u8; 32] = [0xb2; 32];

impl Spec {
    pub fn sig(challenge: &[u8]) -> Spec {
        Spec {
            challenge: challenge.to_vec(),
            attestation_security_level: 2,
            keymint_security_level: 2,
            purposes: vec![2],
            algorithm: Some(3),
            ec_curve: Some(1),
            origin: Some(0),
            no_auth_required: false,
            user_auth_type: Some(2),
            auth_timeout: None,
            unlocked_in_hardware: true,
            unlocked_in_software: false,
            device_locked: true,
            boot_state: 0,
            boot_key: BOOT_KEY.to_vec(),
            boot_hash: Some(BOOT_HASH.to_vec()),
            os_patch: Some(202608),
            boot_patch: Some(20260805),
            packages: vec![(PACKAGE.into(), 1)],
            signatures: vec![APP_DIGEST.to_vec()],
            extra_hw: Vec::new(),
        }
    }

    /// The TLS key: TEE, no user authentication.
    pub fn tls(challenge: &[u8]) -> Spec {
        Spec { attestation_security_level: 1, keymint_security_level: 1, no_auth_required: true, user_auth_type: None, ..Spec::sig(challenge) }
    }

    /// The daily throwaway key.
    pub fn reattest(challenge: &[u8]) -> Spec {
        Spec { unlocked_in_hardware: false, ..Spec::tls(challenge) }
    }

    pub fn encode(&self) -> Vec<u8> {
        let mut hw: Vec<(u32, Vec<u8>)> = Vec::new();
        if !self.purposes.is_empty() {
            hw.push((tag::PURPOSE, set(&self.purposes.iter().map(|p| int(*p)).collect::<Vec<_>>())));
        }
        if let Some(a) = self.algorithm {
            hw.push((tag::ALGORITHM, int(a)));
        }
        hw.push((tag::KEY_SIZE, int(256)));
        hw.push((tag::DIGEST, set(&[int(4)])));
        if let Some(c) = self.ec_curve {
            hw.push((tag::EC_CURVE, int(c)));
        }
        if self.no_auth_required {
            hw.push((tag::NO_AUTH_REQUIRED, null()));
        }
        if let Some(u) = self.user_auth_type {
            hw.push((tag::USER_AUTH_TYPE, int(u)));
        }
        if let Some(t) = self.auth_timeout {
            hw.push((tag::AUTH_TIMEOUT, int(t)));
        }
        if self.unlocked_in_hardware {
            hw.push((tag::UNLOCKED_DEVICE_REQUIRED, null()));
        }
        if let Some(o) = self.origin {
            hw.push((tag::ORIGIN, int(o)));
        }
        let mut rot = vec![octets(&self.boot_key), boolean(self.device_locked), enumerated(self.boot_state)];
        if let Some(h) = &self.boot_hash {
            rot.push(octets(h));
        }
        hw.push((tag::ROOT_OF_TRUST, seq(&rot)));
        hw.push((tag::OS_VERSION, int(160000)));
        if let Some(p) = self.os_patch {
            hw.push((tag::OS_PATCH_LEVEL, int(p)));
        }
        hw.push((tag::ATTESTATION_ID_MANUFACTURER, octets(b"samsung")));
        hw.push((tag::ATTESTATION_ID_MODEL, octets(b"SM-S938B")));
        if let Some(p) = self.boot_patch {
            hw.push((tag::BOOT_PATCH_LEVEL, int(p)));
        }
        hw.sort_by_key(|(t, _)| *t);
        hw.extend(self.extra_hw.iter().cloned());

        let mut sw: Vec<(u32, Vec<u8>)> = vec![(tag::CREATION_DATE_TIME, int(1_790_000_000_000))];
        if self.unlocked_in_software {
            sw.push((tag::UNLOCKED_DEVICE_REQUIRED, null()));
        }
        let app = seq(&[
            set(&self.packages.iter().map(|(n, v)| seq(&[octets(n.as_bytes()), int(*v)])).collect::<Vec<_>>()),
            set(&self.signatures.iter().map(|s| octets(s)).collect::<Vec<_>>()),
        ]);
        sw.push((tag::ATTESTATION_APPLICATION_ID, octets(&app)));
        sw.sort_by_key(|(t, _)| *t);

        let list = |items: &[(u32, Vec<u8>)]| seq(&items.iter().map(|(t, v)| explicit(*t, v)).collect::<Vec<_>>());
        seq(&[
            int(300),
            enumerated(self.attestation_security_level),
            int(300),
            enumerated(self.keymint_security_level),
            octets(&self.challenge),
            octets(b""),
            list(&sw),
            list(&hw),
        ])
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Shape {
    /// root, "Droid CA2", RKP server, attestation, leaf.
    Remote,
    /// root, factory intermediate (subject serialNumber), attestation, leaf.
    Factory,
}

#[derive(Debug, Clone)]
pub struct ChainOpts {
    pub shape: Shape,
    /// Serial number the fake root has as a subject attribute. Google's
    /// legacy root is `f92009e853b6b045`.
    pub root_serial_attr: String,
    pub expired_intermediate: bool,
    pub intermediate_serial: u64,
    /// Put an attestation extension on the attestation certificate too.
    pub extension_above_leaf: bool,
    /// A second key description on the leaf, after the real one.
    pub second_key_description: Option<Spec>,
}

impl Default for ChainOpts {
    fn default() -> Self {
        ChainOpts {
            shape: Shape::Remote,
            root_serial_attr: "testroot0000".into(),
            expired_intermediate: false,
            intermediate_serial: 0x1234,
            extension_above_leaf: false,
            second_key_description: None,
        }
    }
}

pub struct Pki {
    pub root_key: KeyPair,
    pub root_params: CertificateParams,
    pub root_der: Vec<u8>,
}

fn ca(dn: DistinguishedName, serial: u64) -> CertificateParams {
    let mut p = CertificateParams::new(Vec::<String>::new()).expect("params");
    p.distinguished_name = dn;
    p.is_ca = IsCa::Ca(BasicConstraints::Unconstrained);
    p.serial_number = Some(SerialNumber::from(serial));
    p.not_before = date_time_ymd(2020, 1, 1);
    p.not_after = date_time_ymd(2040, 1, 1);
    p
}

impl Pki {
    pub fn new(root_serial_attr: &str) -> Pki {
        let root_key = KeyPair::generate_for(&PKCS_ECDSA_P256_SHA256).expect("key");
        let mut dn = DistinguishedName::new();
        dn.push(DnType::CustomDnType(vec![2, 5, 4, 5]), root_serial_attr);
        let root_params = ca(dn, 1);
        let root_der = root_params.self_signed(&root_key).expect("root").der().to_vec();
        Pki { root_key, root_params, root_der }
    }

    /// The stand-in root's pin, for tests to pass where production passes
    /// `GOOGLE_ROOT_SPKI_SHA256`.
    pub fn root_pin(&self) -> [u8; 32] {
        use sha2::{Digest, Sha256};
        Sha256::digest(rcgen::PublicKeyData::subject_public_key_info(&self.root_key)).into()
    }

    /// The pinned roots, as Google serves them.
    pub fn roots_json(&self) -> Vec<u8> {
        let b64 = base64_std(&self.root_der);
        serde_json::to_vec(&vec![format!("-----BEGIN CERTIFICATE-----\n{b64}\n-----END CERTIFICATE-----\n")]).expect("json")
    }

    /// A chain for `leaf_key` with `spec`, leaf first, root last.
    pub fn chain(&self, spec: &Spec, leaf_key: &KeyPair, o: &ChainOpts) -> Vec<Vec<u8>> {
        let root = CertifiedIssuer::self_signed(self.root_params.clone(), &self.root_key).expect("root");
        let mut out = Vec::new();

        let mut top_dn = DistinguishedName::new();
        match o.shape {
            Shape::Remote => {
                top_dn.push(DnType::CommonName, "Droid CA2");
                top_dn.push(DnType::OrganizationName, "Google LLC");
            }
            Shape::Factory => {
                top_dn.push(DnType::CustomDnType(vec![2, 5, 4, 5]), "c6047571d8f0d17c");
                top_dn.push(DnType::CommonName, "factory intermediate");
            }
        }
        let mut top_params = ca(top_dn, o.intermediate_serial);
        if o.expired_intermediate {
            top_params.not_before = date_time_ymd(2015, 1, 1);
            top_params.not_after = date_time_ymd(2020, 1, 1);
        }
        let top_key = KeyPair::generate_for(&PKCS_ECDSA_P256_SHA256).expect("key");
        let top = CertifiedIssuer::signed_by(top_params, &top_key, &root).expect("top");

        let (att_issuer_holder, server_der);
        let server_key = KeyPair::generate_for(&PKCS_ECDSA_P256_SHA256).expect("key");
        let att_parent: &CertifiedIssuer<'_, &KeyPair> = match o.shape {
            Shape::Remote => {
                let mut dn = DistinguishedName::new();
                dn.push(DnType::CommonName, "rkp server");
                dn.push(DnType::OrganizationName, "Google LLC");
                att_issuer_holder = CertifiedIssuer::signed_by(ca(dn, 0x2000), &server_key, &top).expect("server");
                server_der = Some(att_issuer_holder.der().to_vec());
                &att_issuer_holder
            }
            Shape::Factory => {
                server_der = None;
                &top
            }
        };

        let mut att_dn = DistinguishedName::new();
        att_dn.push(DnType::CommonName, "attestation");
        att_dn.push(DnType::OrganizationName, if spec.attestation_security_level == 2 { "StrongBox" } else { "TEE" });
        if o.shape == Shape::Factory && spec.attestation_security_level == 2 {
            att_dn.push(DnType::OrganizationalUnitName, "StrongBox");
        }
        let mut att_params = ca(att_dn, 0x3000);
        if o.extension_above_leaf {
            att_params.custom_extensions = vec![CustomExtension::from_oid_content(&[1, 3, 6, 1, 4, 1, 11129, 2, 1, 17], spec.encode())];
        }
        let att_key = KeyPair::generate_for(&PKCS_ECDSA_P256_SHA256).expect("key");
        let att = CertifiedIssuer::signed_by(att_params, &att_key, att_parent).expect("attestation");

        let mut leaf_dn = DistinguishedName::new();
        leaf_dn.push(DnType::CommonName, "Android Keystore Key");
        let mut leaf_params = CertificateParams::new(Vec::<String>::new()).expect("params");
        leaf_params.distinguished_name = leaf_dn;
        leaf_params.serial_number = Some(SerialNumber::from(1u64));
        leaf_params.custom_extensions = vec![CustomExtension::from_oid_content(&[1, 3, 6, 1, 4, 1, 11129, 2, 1, 17], spec.encode())];
        if let Some(second) = &o.second_key_description {
            leaf_params.custom_extensions.push(CustomExtension::from_oid_content(&[1, 3, 6, 1, 4, 1, 11129, 2, 1, 17], second.encode()));
        }
        let leaf = leaf_params.signed_by(leaf_key, &att).expect("leaf");

        out.push(leaf.der().to_vec());
        out.push(att.der().to_vec());
        if let Some(s) = server_der {
            out.push(s);
        }
        out.push(top.der().to_vec());
        out.push(self.root_der.clone());
        out
    }
}

fn base64_std(b: &[u8]) -> String {
    const A: &[u8; 64] = b"ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
    let mut s = String::new();
    for c in b.chunks(3) {
        let n = (c[0] as u32) << 16 | (*c.get(1).unwrap_or(&0) as u32) << 8 | *c.get(2).unwrap_or(&0) as u32;
        for i in 0..4 {
            if i <= c.len() {
                s.push(A[((n >> (18 - 6 * i)) & 63) as usize] as char);
            } else {
                s.push('=');
            }
        }
    }
    s
}
