#![no_main]

use libfuzzer_sys::fuzz_target;
use remoter_attest::{Revoked, Roots, chain, keydesc};

// keydesc on raw bytes, then the chain path on certs cut from the input. No
// roots, so nothing verifies: this is only about panics and overreads.
fuzz_target!(|data: &[u8]| {
    if let Ok(kd) = keydesc::parse(data) {
        // tag 0 (KeyMint's INVALID) must never make it through
        assert!(kd.hardware.tags.iter().all(|t| *t > 0));
    }
    let roots = Roots::from_ders(Vec::new());
    let certs: Vec<Vec<u8>> = data.split(|b| *b == 0xfe).map(|c| c.to_vec()).take(8).collect();
    let _ = chain::verify(&certs, &roots, &Revoked::default(), 1_790_000_000);
});
