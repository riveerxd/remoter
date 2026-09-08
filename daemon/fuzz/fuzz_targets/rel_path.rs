#![no_main]

use libfuzzer_sys::fuzz_target;
use remoter_agent::guard::parse_rel;

fuzz_target!(|data: &[u8]| {
    let Ok(rel) = parse_rel(data) else { return };
    for c in rel.components() {
        assert!(!c.is_empty() && c != "." && c != "..", "bad component {c:?}");
        assert!(!c.contains('/') && !c.contains('\0'));
        assert!(!remoter_proto_names_unsupported(c.as_bytes()));
    }
    // Whatever parses must round trip to itself, or two spellings would name
    // one folder differently in logs, tags and the audit trail.
    let again = parse_rel(rel.as_string().as_bytes()).expect("a parsed path reparses");
    assert_eq!(again, rel);
});

fn remoter_proto_names_unsupported(b: &[u8]) -> bool {
    remoter_agent::guard::is_unsupported_component(b)
}
