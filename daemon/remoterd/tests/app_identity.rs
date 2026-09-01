//! Production and staging each pair only their own app build, using the real
//! config templates. The e2e build skips the fingerprint, so it must never pair
//! with production, and each identity field has to be refused on its own.

use rcgen::{KeyPair, PKCS_ECDSA_P256_SHA256};
use remoter_attest::testkit::{ChainOpts, Pki, Spec};
use remoter_attest::{Error, Revoked, Role, Roots, check};

const RELEASE_PKG: &str = "me.river.remoter";
const E2E_PKG: &str = "me.river.remoter.e2e";
const RELEASE_DIGEST: [u8; 32] = [0x5a; 32];
const E2E_DIGEST: [u8; 32] = [0xe2; 32];
const C: &[u8] = b"pairing-challenge-0001";
const NOW: i64 = 1_790_611_106;

fn policy(template: &str) -> remoter_attest::Policy {
    let t = std::fs::read_to_string(std::path::Path::new(env!("CARGO_MANIFEST_DIR")).join("../../infra/laptop").join(template)).expect("template");
    let text = t
        .replace("@HOME@", "/home/river")
        .replace("@USER@", "river")
        .replace("@UID@", "1000")
        .replace("@APP_CERT_SHA256@", &remoter_proto::b64::hex(&RELEASE_DIGEST))
        .replace("@E2E_CERT_SHA256@", &remoter_proto::b64::hex(&E2E_DIGEST));
    remoterd::config::Config::parse(&text).expect("config parses").attestation.policy().expect("policy")
}

fn attest(pki: &Pki, roots: &Roots, policy: &remoter_attest::Policy, package: &str, digest: [u8; 32]) -> Result<remoter_attest::Attested, Error> {
    let spec = Spec { packages: vec![(package.into(), 1)], signatures: vec![digest.to_vec()], ..Spec::sig(C) };
    let chain = pki.chain(&spec, &KeyPair::generate_for(&PKCS_ECDSA_P256_SHA256).expect("key"), &ChainOpts::default());
    check(&chain, C, Role::Sig, policy, roots, &Revoked::default(), NOW)
}

#[test]
fn each_config_pairs_only_its_own_build() {
    let pki = Pki::new("testroot0000");
    let roots = Roots::from_json(&pki.roots_json()).expect("roots");
    let production = policy("config.toml.tmpl");
    let staging = policy("config-staging.toml.tmpl");

    // controls, so the refusals below are down to identity alone
    assert!(attest(&pki, &roots, &production, RELEASE_PKG, RELEASE_DIGEST).is_ok(), "production pairs the release build");
    assert!(attest(&pki, &roots, &staging, E2E_PKG, E2E_DIGEST).is_ok(), "staging pairs the e2e build");

    for (what, pkg, digest) in [
        ("the e2e build", E2E_PKG, E2E_DIGEST),
        ("the e2e package alone", E2E_PKG, RELEASE_DIGEST),
        ("the e2e cert alone", RELEASE_PKG, E2E_DIGEST),
    ] {
        let r = attest(&pki, &roots, &production, pkg, digest);
        assert!(matches!(&r, Err(Error::Policy(_))), "production must refuse {what}: {r:?}");
    }
    for (what, pkg, digest) in [
        ("the release build", RELEASE_PKG, RELEASE_DIGEST),
        ("the release package alone", RELEASE_PKG, E2E_DIGEST),
        ("the release cert alone", E2E_PKG, RELEASE_DIGEST),
    ] {
        let r = attest(&pki, &roots, &staging, pkg, digest);
        assert!(matches!(&r, Err(Error::Policy(_))), "staging must refuse {what}: {r:?}");
    }
}
