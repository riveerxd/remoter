//! Every attestation policy rule broken on its own, with a stand in Google PKI
//! from the test kit. The baseline passes first, so each failure below is
//! caused by the one thing the case changes.

use rcgen::{KeyPair, PKCS_ECDSA_P256_SHA256};
use remoter_attest::testkit::{self, APP_DIGEST, ChainOpts, PACKAGE, Pki, Shape, Spec};
use remoter_attest::{Error, Policy, Revoked, Role, Roots, UnlockedList, Weakness, check, check_pair, check_reattest, weaknesses};

const C: &[u8] = b"pairing-challenge-0001";
/// 2026-09-28.
const NOW: i64 = 1_790_611_106;

fn policy() -> Policy {
    Policy { app_package: PACKAGE.into(), app_cert_sha256: APP_DIGEST, max_patch_age_months: 6, unlocked_device_required_in: UnlockedList::Hardware }
}

fn key() -> KeyPair {
    KeyPair::generate_for(&PKCS_ECDSA_P256_SHA256).expect("key")
}

struct T {
    pki: Pki,
    roots: Roots,
}

impl T {
    fn new(root_serial: &str) -> T {
        let pki = Pki::new(root_serial);
        let roots = Roots::from_json(&pki.roots_json()).expect("roots");
        T { pki, roots }
    }

    fn run(&self, spec: &Spec, role: Role, o: &ChainOpts) -> Result<remoter_attest::Attested, Error> {
        check(&self.pki.chain(spec, &key(), o), C, role, &policy(), &self.roots, &Revoked::default(), NOW)
    }
}

fn policy_fail(r: Result<remoter_attest::Attested, Error>, rule: &str) {
    match r {
        Err(Error::Policy(m)) => assert!(m.contains(rule), "failed on {m:?}, wanted {rule:?}"),
        other => panic!("wanted policy failure {rule:?}, got {other:?}"),
    }
}

#[test]
fn baseline_passes() {
    let t = T::new("testroot0000");
    for shape in [Shape::Remote, Shape::Factory] {
        let o = ChainOpts { shape, ..Default::default() };
        let a = t.run(&Spec::sig(C), Role::Sig, &o).expect("sig");
        assert_eq!((a.attestation_security_level, a.model.as_deref()), (2, Some("SM-S938B")));
        assert_eq!(a.remotely_provisioned, shape == Shape::Remote);
        t.run(&Spec::tls(C), Role::Tls, &o).expect("tls");
        t.run(&Spec::reattest(C), Role::Reattest, &o).expect("reattest");
    }
}

#[test]
fn every_rule_fails_on_its_own() {
    let t = T::new("testroot0000");
    let o = ChainOpts::default();
    let sig = Spec::sig(C);
    let cases: Vec<(&str, Spec, Role, &str)> = vec![
        ("wrong challenge", Spec { challenge: b"other".to_vec(), ..sig.clone() }, Role::Sig, "attestationChallenge"),
        ("sig in software", Spec { attestation_security_level: 0, keymint_security_level: 0, ..sig.clone() }, Role::Sig, "security level"),
        ("KeyMint in software", Spec { keymint_security_level: 0, ..Spec::tls(C) }, Role::Tls, "security level"),
        ("imported origin", Spec { origin: Some(2), ..sig.clone() }, Role::Sig, "origin"),
        ("origin missing", Spec { origin: None, ..sig.clone() }, Role::Sig, "origin"),
        ("purpose decrypt too", Spec { purposes: vec![1, 2], ..sig.clone() }, Role::Sig, "purpose"),
        ("RSA", Spec { algorithm: Some(1), ..sig.clone() }, Role::Sig, "algorithm"),
        ("P-384", Spec { ec_curve: Some(2), ..sig.clone() }, Role::Sig, "curve"),
        ("no auth required on sig", Spec { no_auth_required: true, ..sig.clone() }, Role::Sig, "NO_AUTH_REQUIRED"),
        ("auth type PIN", Spec { user_auth_type: Some(1), ..sig.clone() }, Role::Sig, "USER_AUTH_TYPE"),
        ("auth type PIN or fingerprint", Spec { user_auth_type: Some(3), ..sig.clone() }, Role::Sig, "USER_AUTH_TYPE"),
        ("auth timeout present", Spec { auth_timeout: Some(0), ..sig.clone() }, Role::Sig, "AUTH_TIMEOUT"),
        ("unlocked device not required", Spec { unlocked_in_hardware: false, ..sig.clone() }, Role::Sig, "UNLOCKED_DEVICE_REQUIRED"),
        ("only in the software list", Spec { unlocked_in_hardware: false, unlocked_in_software: true, ..sig.clone() }, Role::Sig, "UNLOCKED_DEVICE_REQUIRED"),
        ("no boot hash", Spec { boot_hash: None, ..sig.clone() }, Role::Sig, "verifiedBootHash"),
        ("stale OS patch", Spec { os_patch: Some(202512), ..sig.clone() }, Role::Sig, "osPatchLevel"),
        ("stale boot patch", Spec { boot_patch: Some(20251201), ..sig.clone() }, Role::Sig, "bootPatchLevel"),
        ("OS patch missing", Spec { os_patch: None, ..sig.clone() }, Role::Sig, "osPatchLevel"),
        ("wrong package", Spec { packages: vec![("com.evil.remoter".into(), 1)], ..sig.clone() }, Role::Sig, "package"),
        ("two packages", Spec { packages: vec![(PACKAGE.into(), 1), ("com.other".into(), 1)], ..sig.clone() }, Role::Sig, "package"),
        ("wrong digest", Spec { signatures: vec![vec![0x11; 32]], ..sig.clone() }, Role::Sig, "digest"),
        ("two digests", Spec { signatures: vec![APP_DIGEST.to_vec(), vec![0x11; 32]], ..sig.clone() }, Role::Sig, "digest"),
    ];
    for (why, spec, role, rule) in cases {
        let r = t.run(&spec, role, &o);
        assert!(matches!(r, Err(Error::Policy(_))), "{why}: {r:?}");
        policy_fail(r, rule);
    }
}

#[test]
fn weak_phones_pass_with_a_warning() {
    let t = T::new("testroot0000");
    let o = ChainOpts::default();
    let sig = Spec::sig(C);
    assert!(t.run(&sig, Role::Sig, &o).expect("baseline").weaknesses.is_empty());
    let cases: Vec<(Spec, Role, Vec<Weakness>)> = vec![
        (Spec { attestation_security_level: 1, keymint_security_level: 1, ..sig.clone() }, Role::Sig, vec![Weakness::NoStrongBox]),
        (Spec { keymint_security_level: 1, ..sig.clone() }, Role::Sig, vec![Weakness::NoStrongBox]),
        (Spec { device_locked: false, ..sig.clone() }, Role::Sig, vec![Weakness::BootloaderUnlocked]),
        (Spec { boot_state: 1, ..sig.clone() }, Role::Sig, vec![Weakness::BootNotVerified]),
        (Spec { device_locked: false, boot_state: 2, ..Spec::tls(C) }, Role::Tls, vec![Weakness::BootloaderUnlocked, Weakness::BootNotVerified]),
        // a TEE tls key is normal, not a weakness
        (Spec::tls(C), Role::Tls, vec![]),
    ];
    for (spec, role, want) in cases {
        assert_eq!(t.run(&spec, role, &o).expect("passes").weaknesses, want, "{role:?}");
    }
}

#[test]
fn pair_collects_weaknesses_once() {
    let t = T::new("testroot0000");
    let o = ChainOpts::default();
    let tls = t.pki.chain(&Spec { device_locked: false, ..Spec::tls(C) }, &key(), &o);
    let sig = t.pki.chain(&Spec { device_locked: false, attestation_security_level: 1, keymint_security_level: 1, ..Spec::sig(C) }, &key(), &o);
    let (a, b) = check_pair(&tls, &sig, C, &policy(), &t.roots, &Revoked::default(), NOW).expect("pair");
    assert_eq!(weaknesses(&a, &b), vec![Weakness::NoStrongBox, Weakness::BootloaderUnlocked]);
}

#[test]
fn six_month_patch_window_edges() {
    let t = T::new("testroot0000");
    let o = ChainOpts::default();
    // September 2026: March is 6 months back, February 7.
    assert!(t.run(&Spec { os_patch: Some(202603), boot_patch: Some(20260301), ..Spec::sig(C) }, Role::Sig, &o).is_ok());
    policy_fail(t.run(&Spec { os_patch: Some(202602), ..Spec::sig(C) }, Role::Sig, &o), "osPatchLevel");
    policy_fail(t.run(&Spec { os_patch: Some(202613), ..Spec::sig(C) }, Role::Sig, &o), "osPatchLevel");
}

#[test]
fn unlocked_list_follows_config() {
    let t = T::new("testroot0000");
    let sw_only = Spec { unlocked_in_hardware: false, unlocked_in_software: true, ..Spec::sig(C) };
    let chain = t.pki.chain(&sw_only, &key(), &ChainOpts::default());
    for (list, ok) in [(UnlockedList::Hardware, false), (UnlockedList::Software, true), (UnlockedList::Either, true)] {
        let p = Policy { unlocked_device_required_in: list, ..policy() };
        assert_eq!(check(&chain, C, Role::Sig, &p, &t.roots, &Revoked::default(), NOW).is_ok(), ok, "{list:?}");
    }
}

#[test]
fn chain_rules() {
    let t = T::new("testroot0000");
    let spec = Spec::sig(C);
    // Broken signature: a leaf from one chain on another chain's parents.
    let mut a = t.pki.chain(&spec, &key(), &ChainOpts::default());
    let b = t.pki.chain(&spec, &key(), &ChainOpts::default());
    a[0] = b[0].clone();
    let r = check(&a, C, Role::Sig, &policy(), &t.roots, &Revoked::default(), NOW);
    assert!(matches!(&r, Err(Error::Chain(m)) if m.contains("signature")), "{r:?}");

    // A byte flipped in the leaf's signed part.
    let mut flipped = t.pki.chain(&spec, &key(), &ChainOpts::default());
    let n = flipped[0].len() / 2;
    flipped[0][n] ^= 0x01;
    assert!(check(&flipped, C, Role::Sig, &policy(), &t.roots, &Revoked::default(), NOW).is_err());

    // Not our root.
    let other = T::new("testroot0000");
    let foreign = other.pki.chain(&spec, &key(), &ChainOpts::default());
    let r = check(&foreign, C, Role::Sig, &policy(), &t.roots, &Revoked::default(), NOW);
    assert!(matches!(&r, Err(Error::Chain(m)) if m.contains("pinned")), "{r:?}");

    // Expired RKP intermediate.
    let r = t.run(&spec, Role::Sig, &ChainOpts { expired_intermediate: true, ..Default::default() });
    assert!(matches!(&r, Err(Error::Chain(m)) if m.contains("expired")), "{r:?}");

    // Revoked serial.
    let c = t.pki.chain(&spec, &key(), &ChainOpts { intermediate_serial: 0xdead_beef, ..Default::default() });
    let status = br#"{"entries":{"deadbeef":{"status":"REVOKED","reason":"KEY_COMPROMISE"}}}"#;
    let r = check(&c, C, Role::Sig, &policy(), &t.roots, &Revoked::from_json(status).expect("status"), NOW);
    assert_eq!(r.err(), Some(Error::Revoked("deadbeef".into())));
    let suspended = br#"{"entries":{"deadbeef":{"status":"SUSPENDED"}}}"#;
    assert!(check(&c, C, Role::Sig, &policy(), &t.roots, &Revoked::from_json(suspended).expect("status"), NOW).is_err(), "anything not VALID counts");

    // An attestation extension above the leaf.
    let r = t.run(&spec, Role::Sig, &ChainOpts { extension_above_leaf: true, ..Default::default() });
    assert!(matches!(&r, Err(Error::Chain(m)) if m.contains("above the leaf")), "{r:?}");

    // Two key descriptions on the leaf. A verifier that reads the first and
    // one that reads the last would disagree about the key; RFC 5280 says an
    // extension appears once, so the chain is refused whichever comes first.
    for (first, second) in [(spec.clone(), Spec { user_auth_type: Some(1), ..spec.clone() }), (Spec { user_auth_type: Some(1), ..spec.clone() }, spec.clone())] {
        let c = t.pki.chain(&first, &key(), &ChainOpts { second_key_description: Some(second), ..Default::default() });
        let r = check(&c, C, Role::Sig, &policy(), &t.roots, &Revoked::default(), NOW);
        assert!(r.is_err(), "a duplicated key description is refused: {r:?}");
    }

    // The chain names TEE while the key description claims StrongBox.
    let mut lying = t.pki.chain(&Spec { attestation_security_level: 1, keymint_security_level: 1, ..spec.clone() }, &key(), &ChainOpts::default());
    let honest = t.pki.chain(&spec, &key(), &ChainOpts::default());
    lying[0] = honest[0].clone();
    assert!(check(&lying, C, Role::Sig, &policy(), &t.roots, &Revoked::default(), NOW).is_err());
}

#[test]
fn expiry_forgiven_on_legacy_factory_only() {
    let expired = |shape| ChainOpts { shape, expired_intermediate: true, ..Default::default() };
    let legacy = T::new(remoter_attest::chain::LEGACY_ROOT_SERIAL);
    assert!(legacy.run(&Spec::sig(C), Role::Sig, &ChainOpts { root_serial_attr: remoter_attest::chain::LEGACY_ROOT_SERIAL.into(), ..expired(Shape::Factory) }).is_ok(), "legacy factory");
    let r = legacy.run(&Spec::sig(C), Role::Sig, &expired(Shape::Remote));
    assert!(matches!(&r, Err(Error::Chain(m)) if m.contains("expired")), "RKP under the legacy root: {r:?}");
    let modern = T::new("testroot0000");
    let r = modern.run(&Spec::sig(C), Role::Sig, &expired(Shape::Factory));
    assert!(matches!(&r, Err(Error::Chain(m)) if m.contains("expired")), "factory under another root: {r:?}");
}

#[test]
fn tag_order_and_unknown_tags() {
    let t = T::new("testroot0000");
    let o = ChainOpts::default();
    // A tag we don't read, in order at the end, is fine.
    let unknown = Spec { extra_hw: vec![(724, testkit::octets(b"hash"))], ..Spec::sig(C) };
    assert!(t.run(&unknown, Role::Sig, &o).is_ok());
    // Anything out of order, or a tag twice, is refused.
    let dup = Spec { extra_hw: vec![(1, testkit::set(&[testkit::int(2)]))], ..Spec::sig(C) };
    let r = t.run(&dup, Role::Sig, &o);
    assert!(matches!(&r, Err(Error::KeyDescription(m)) if m.contains("ascending")), "{r:?}");
}

#[test]
fn pair_needs_one_phone() {
    let t = T::new("testroot0000");
    let o = ChainOpts::default();
    let tls = t.pki.chain(&Spec::tls(C), &key(), &o);
    let sig = t.pki.chain(&Spec::sig(C), &key(), &o);
    let (a, b) = check_pair(&tls, &sig, C, &policy(), &t.roots, &Revoked::default(), NOW).expect("pair");
    assert_eq!(a.verified_boot_key, b.verified_boot_key);
    let other_phone = t.pki.chain(&Spec { boot_key: vec![0x77; 32], ..Spec::sig(C) }, &key(), &o);
    policy_fail(check_pair(&tls, &other_phone, C, &policy(), &t.roots, &Revoked::default(), NOW).map(|p| p.0), "one device");
    let other_hash = t.pki.chain(&Spec { boot_hash: Some(vec![0x78; 32]), ..Spec::sig(C) }, &key(), &o);
    policy_fail(check_pair(&tls, &other_hash, C, &policy(), &t.roots, &Revoked::default(), NOW).map(|p| p.0), "one device");
    // a tls chain in the sig slot fails
    assert!(check_pair(&tls, &tls, C, &policy(), &t.roots, &Revoked::default(), NOW).is_err());
}

#[test]
fn reattestation_needs_the_same_phone() {
    let t = T::new("testroot0000");
    let c = t.pki.chain(&Spec::reattest(C), &key(), &ChainOpts::default());
    let again = |c: &[Vec<u8>], key: &[u8], paired: &[Weakness]| check_reattest(c, C, key, paired, &policy(), &t.roots, &Revoked::default(), NOW);
    assert!(again(&c, &testkit::BOOT_KEY, &[]).is_ok());
    policy_fail(again(&c, &[0x77; 32], &[]), "same verifiedBootKey");
    let unlocked = t.pki.chain(&Spec { device_locked: false, ..Spec::reattest(C) }, &key(), &ChainOpts::default());
    policy_fail(again(&unlocked, &testkit::BOOT_KEY, &[]), "device locked");
    policy_fail(again(&unlocked, &testkit::BOOT_KEY, &[Weakness::NoStrongBox]), "device locked");
    assert!(again(&unlocked, &testkit::BOOT_KEY, &[Weakness::BootloaderUnlocked]).is_ok());
    let rooted = t.pki.chain(&Spec { device_locked: false, boot_state: 2, ..Spec::reattest(C) }, &key(), &ChainOpts::default());
    policy_fail(again(&rooted, &testkit::BOOT_KEY, &[Weakness::BootloaderUnlocked]), "Verified");
    assert!(again(&rooted, &testkit::BOOT_KEY, &[Weakness::BootloaderUnlocked, Weakness::BootNotVerified]).is_ok());
}
