//! android/keyattestation's own test vectors: every chain that ends at a
//! Google root verifies at its creation time, and the parsed key description
//! matches the library's JSON field for field.

use std::path::{Path, PathBuf};

use remoter_attest::{Revoked, Roots, chain, keydesc};
use serde_json::Value;

fn dir() -> PathBuf {
    Path::new(env!("CARGO_MANIFEST_DIR")).join("testdata/keyattestation")
}

fn google() -> Roots {
    Roots::from_json(&std::fs::read(Path::new(env!("CARGO_MANIFEST_DIR")).join("testdata/google-roots.json")).expect("roots")).expect("parse")
}

fn pem_chain(p: &Path) -> Vec<Vec<u8>> {
    let text = std::fs::read_to_string(p).expect("pem");
    text.split("-----END CERTIFICATE-----")
        .filter(|b| b.contains("BEGIN CERTIFICATE"))
        .map(|b| {
            let body: String = b.lines().filter(|l| !l.starts_with("-----") && !l.trim().is_empty()).collect();
            b64(&body)
        })
        .collect()
}

fn b64(s: &str) -> Vec<u8> {
    const A: &[u8; 64] = b"ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
    let mut out = Vec::new();
    let (mut buf, mut bits) = (0u32, 0);
    for c in s.bytes() {
        if c == b'=' {
            break;
        }
        let Some(v) = A.iter().position(|&x| x == c) else { continue };
        buf = (buf << 6) | v as u32;
        bits += 6;
        if bits >= 8 {
            bits -= 8;
            out.push((buf >> bits) as u8);
            buf &= (1 << bits) - 1;
        }
    }
    out
}

/// Some vector JSON files open with `//` comment lines.
fn read_json(p: &Path) -> Value {
    let text: String = std::fs::read_to_string(p).expect("json").lines().filter(|l| !l.trim_start().starts_with("//")).collect::<Vec<_>>().join("\n");
    serde_json::from_str(&text).unwrap_or_else(|e| panic!("{}: {e}", p.display()))
}

fn vectors() -> Vec<(PathBuf, PathBuf)> {
    let mut v = Vec::new();
    for model in std::fs::read_dir(dir()).expect("testdata").flatten() {
        let Ok(sdks) = std::fs::read_dir(model.path()) else { continue };
        for sdk in sdks.flatten().filter(|e| e.file_name().to_string_lossy().starts_with("sdk")) {
            for f in std::fs::read_dir(sdk.path()).expect("sdk").flatten() {
                let p = f.path();
                if p.extension().is_some_and(|e| e == "pem") {
                    v.push((p.clone(), p.with_extension("json")));
                }
            }
        }
    }
    v.sort();
    v
}

fn level(name: &str) -> i64 {
    match name {
        "SOFTWARE" => 0,
        "TRUSTED_ENVIRONMENT" => 1,
        "STRONG_BOX" => 2,
        other => panic!("unknown level {other}"),
    }
}

fn int(v: &Value) -> Option<i64> {
    v.as_str().and_then(|s| s.parse().ok())
}

#[test]
fn vectors_match_library() {
    let roots = google();
    let mut verified = 0;
    let mut not_google = Vec::new();
    for (pem, json) in vectors() {
        let expect = read_json(&json);
        let created = int(&expect["softwareEnforced"]["creationDateTime"])
            .or_else(|| int(&expect["hardwareEnforced"]["creationDateTime"]))
            .expect("creationDateTime")
            / 1000;
        let name = pem.strip_prefix(dir()).expect("rel").display().to_string();
        match chain::verify(&pem_chain(&pem), &roots, &Revoked::default(), created) {
            Ok(v) => {
                verified += 1;
                let kd = &v.key_description;
                assert_eq!(kd.attestation_security_level, level(expect["attestationSecurityLevel"].as_str().expect("l")), "{name}");
                assert_eq!(kd.keymint_security_level, level(expect["keyMintSecurityLevel"].as_str().expect("l")), "{name}");
                assert_eq!(kd.attestation_version, int(&expect["attestationVersion"]).expect("v"), "{name}");
                assert_eq!(b64(expect["attestationChallenge"].as_str().unwrap_or("")), kd.challenge, "{name}");
                let hw = &expect["hardwareEnforced"];
                assert_eq!(kd.hardware.algorithm, int(&hw["algorithms"]), "{name} algorithm");
                assert_eq!(kd.hardware.ec_curve, int(&hw["ecCurve"]), "{name} curve");
                assert_eq!(kd.hardware.os_patch_level, int(&hw["osPatchLevel"]), "{name} os patch");
                assert_eq!(kd.hardware.boot_patch_level, int(&hw["bootPatchLevel"]), "{name} boot patch");
                assert_eq!(kd.hardware.has(keydesc::tag::NO_AUTH_REQUIRED), hw["noAuthRequired"] == true, "{name} noAuthRequired");
                assert_eq!(kd.hardware.user_auth_type, int(&hw["userAuthType"]), "{name} userAuthType");
                let purposes: Option<std::collections::BTreeSet<i64>> = hw["purposes"].as_array().map(|a| a.iter().filter_map(int).collect());
                assert_eq!(kd.hardware.purposes, purposes, "{name} purposes");
                if let Some(rot) = hw.get("rootOfTrust").filter(|r| r.is_object()) {
                    let got = kd.hardware.root_of_trust.as_ref().expect("rot");
                    assert_eq!(got.device_locked, rot["deviceLocked"] == true, "{name}");
                    assert_eq!(got.verified_boot_key, b64(rot["verifiedBootKey"].as_str().unwrap_or("")), "{name}");
                    if let Some(h) = rot["verifiedBootHash"].as_str() {
                        assert_eq!(got.verified_boot_hash.as_deref(), Some(b64(h).as_slice()), "{name}");
                    }
                }
                if let Some(app) = expect["softwareEnforced"].get("attestationApplicationId").filter(|a| a.is_object()) {
                    let got = kd.software.app_id.as_ref().expect("app id");
                    let names: Vec<&str> = app["packages"].as_array().expect("p").iter().filter_map(|p| p["name"].as_str()).collect();
                    assert_eq!(got.packages.iter().map(|p| p.0.as_str()).collect::<Vec<_>>(), names, "{name}");
                    let sigs: Vec<Vec<u8>> = app["signatures"].as_array().expect("s").iter().filter_map(|s| s.as_str()).map(b64).collect();
                    assert_eq!(got.signatures, sigs, "{name}");
                }
            }
            Err(e) => not_google.push(format!("{name}: {e}")),
        }
    }
    println!("verified {verified} vectors; not accepted: {not_google:#?}");
    assert_eq!(verified, 21, "{not_google:#?}");
    // The only two refused end at Android's software attestation root,
    // which the library's own test accepts only by adding software roots.
    assert_eq!(
        not_google,
        vec![
            "marlin/sdk29/TEE_EC_NONE.pem: chain: doesn't chain to a pinned Google root".to_owned(),
            "marlin/sdk29/TEE_RSA_NONE.pem: chain: doesn't chain to a pinned Google root".to_owned(),
        ]
    );
}

#[test]
fn rkp_vector_expires() {
    let roots = google();
    // RKP chains carry short lived certificates: far in the future they
    // expire and must be refused.
    let rkp = dir().join("caiman/sdk36/SB_EC_RKP.pem");
    let r = chain::verify(&pem_chain(&rkp), &roots, &Revoked::default(), 4_102_444_800);
    assert!(matches!(&r, Err(remoter_attest::Error::Chain(m)) if m.contains("expired")), "{:?}", r.err());
}

#[test]
fn revoked_serials_are_refused() {
    let roots = google();
    let p = dir().join("caiman/sdk36/SB_EC_RKP.pem");
    let c = pem_chain(&p);
    let expect = read_json(&p.with_extension("json"));
    let t = int(&expect["softwareEnforced"]["creationDateTime"]).expect("t") / 1000;
    let (_, att) = x509_parser::parse_x509_certificate(&c[1]).expect("cert");
    let serial = format!("{:x}", att.tbs_certificate.serial);
    let status = format!(r#"{{"entries":{{"{serial}":{{"status":"REVOKED","reason":"KEY_COMPROMISE"}}}}}}"#);
    let r = chain::verify(&c, &roots, &Revoked::from_json(status.as_bytes()).expect("status"), t);
    assert_eq!(r.err(), Some(remoter_attest::Error::Revoked(serial)));
}

/// The library edited these leaves to plant a bad encoding, so like its own
/// ExtensionTest they're parsed straight from the leaf: their signatures no
/// longer verify, and chain checks would stop first.
#[test]
fn invalid_vectors_refused() {
    for (f, why) in [("tags_not_in_ascending_order.pem", "ascending"), ("malformed_rot_device_locked.pem", "boolean")] {
        let c = pem_chain(&dir().join("invalid").join(f));
        let (_, leaf) = x509_parser::parse_x509_certificate(&c[0]).expect("leaf");
        let ext = leaf.extensions().iter().find(|e| e.oid.to_id_string() == keydesc::OID).expect("extension");
        let r = keydesc::parse(ext.value);
        // The library only logs these; we refuse them.
        assert!(matches!(&r, Err(e) if e.0.contains(why)), "{f}: {:?}", r.err());
        assert!(chain::verify(&c, &google(), &Revoked::default(), 1_700_000_000).is_err(), "{f}: tampered leaf");
    }
}

#[test]
#[ignore = "needs the real S25 Ultra chains, dumped from the phone"]
fn real_s25_chains_pass_pairing() {
    let dir = Path::new(env!("CARGO_MANIFEST_DIR")).join("testdata/s25");
    let tls = pem_chain(&dir.join("tls.pem"));
    let sig = pem_chain(&dir.join("sig.pem"));
    assert!(!tls.is_empty() && !sig.is_empty(), "put tls.pem and sig.pem from the phone in testdata/s25");
    panic!("TODO: fill in the challenge, package and digest from the dump, then check_pair");
}

/// testdata/google-roots.json is what Google served on 2026-09-28. A typo in
/// either pin would quietly lock out every real phone.
#[test]
fn google_pins_match_roots() {
    let json = std::fs::read(std::path::Path::new(env!("CARGO_MANIFEST_DIR")).join("testdata/google-roots.json")).expect("fixture");
    let all = remoter_attest::Roots::from_json(&json).expect("parses");
    assert_eq!(all.len(), 2);
    for pin in remoter_attest::GOOGLE_ROOT_SPKI_SHA256 {
        let kept = remoter_attest::Roots::from_json(&json).expect("parses").pinned(&[pin]).expect("this pin matches one of Google's roots");
        assert_eq!(kept.len(), 1);
    }
    assert!(remoter_attest::Roots::from_json(&json).expect("parses").pinned(&[[0u8; 32]]).is_err(), "and an unknown pin matches nothing");
}
