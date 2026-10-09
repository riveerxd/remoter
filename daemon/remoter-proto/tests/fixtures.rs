//! Builds every contract fixture from the real code and compares it with the
//! committed file. `REMOTER_WRITE_FIXTURES=1 cargo test -p remoter-proto`
//! rewrites them. Kotlin reads the same files, so drift on either side fails a
//! test before the phone ever talks to the laptop.

use std::path::PathBuf;

use p256::ecdsa::signature::Signer;
use p256::ecdsa::{Signature, SigningKey};
use p256::pkcs8::EncodePublicKey;
use remoter_proto::api::*;
use remoter_proto::canonical::{SignInput, body_hash_hex, canonical};
use remoter_proto::{ErrorCode, b64, names, pair};
use serde_json::{Value, json};

const DEVICE: &str = "01K6B7Y3M4N5P6Q7R8S9T0V1W2";

/// A fixed scalar that exists only in these fixtures, so Kotlin can verify a
/// Rust signature over the exact canonical bytes. Never a real key.
const TEST_ONLY_SCALAR: [u8; 32] = [
    0x51, 0x9b, 0x42, 0x3d, 0x71, 0x5f, 0x8b, 0x58, 0x1f, 0x4f, 0xa8, 0xee, 0x59, 0xf4, 0x77, 0x1a, 0x5b, 0x44, 0xc8,
    0x13, 0x0b, 0x4e, 0x3e, 0xac, 0xca, 0x54, 0xa5, 0x6d, 0xda, 0x72, 0xb4, 0x64,
];

fn dir() -> PathBuf {
    PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("fixtures")
}

fn check(name: &str, value: Value) {
    let text = serde_json::to_string_pretty(&value).expect("serializes") + "\n";
    let path = dir().join(name);
    if std::env::var_os("REMOTER_WRITE_FIXTURES").is_some() {
        std::fs::create_dir_all(dir()).expect("fixtures dir");
        std::fs::write(&path, &text).expect("write fixture");
        return;
    }
    let on_disk = std::fs::read_to_string(&path)
        .unwrap_or_else(|_| panic!("{name} missing, run with REMOTER_WRITE_FIXTURES=1"));
    assert_eq!(on_disk, text, "{name} drifted from the code");
}

struct Case {
    name: &'static str,
    method: &'static str,
    /// Path segments after `/v1/` and query pairs, as the app starts with them.
    segments: &'static [&'static str],
    query: &'static [(&'static str, &'static str)],
    /// How OkHttp puts the above on the wire. The Kotlin contract test builds
    /// the `HttpUrl` from the parts and asserts it matches this.
    target: &'static str,
    body: &'static str,
    ts: i64,
    nonce: [u8; 16],
}

const CASES: &[Case] = &[
    Case {
        name: "mkdir",
        method: "POST",
        segments: &["fs", "mkdir"],
        query: &[],
        target: "/v1/fs/mkdir",
        body: r#"{"parent":"Projects","name":"new-thing","git_init":true}"#,
        ts: 1_790_611_106_000,
        nonce: [0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15],
    },
    Case {
        name: "spawn",
        method: "POST",
        segments: &["sessions"],
        query: &[],
        target: "/v1/sessions",
        body: r#"{"path":"Projects/remoter","name":"remoter","mode":"same-dir"}"#,
        ts: 1_790_611_107_123,
        nonce: [0xff; 16],
    },
    Case {
        name: "kill_a",
        method: "DELETE",
        segments: &["sessions", "rc-01k6b7y3m4n5p6q7r8s9t0v1w2"],
        query: &[],
        target: "/v1/sessions/rc-01k6b7y3m4n5p6q7r8s9t0v1w2",
        body: "",
        ts: 1_790_611_108_000,
        nonce: [7; 16],
    },
    Case {
        name: "kill_b_same_nonce",
        method: "DELETE",
        segments: &["sessions", "rc-01k6b7y3m4n5p6q7r8s9t0v1w3"],
        query: &[],
        target: "/v1/sessions/rc-01k6b7y3m4n5p6q7r8s9t0v1w3",
        body: "",
        ts: 1_790_611_108_000,
        nonce: [7; 16],
    },
    Case {
        name: "signal",
        method: "POST",
        segments: &["procs", "4242", "signal"],
        query: &[],
        target: "/v1/procs/4242/signal",
        body: r#"{"start":17006290,"signal":"term"}"#,
        ts: 1_790_611_108_500,
        nonce: [0x0b; 16],
    },
    Case {
        name: "view_token",
        method: "POST",
        segments: &["view-token"],
        query: &[],
        target: "/v1/view-token",
        body: "{}",
        ts: 1_790_611_109_000,
        nonce: [0x42; 16],
    },
    Case {
        name: "unpair",
        method: "DELETE",
        segments: &["devices", "self"],
        query: &[],
        target: "/v1/devices/self",
        body: "",
        ts: 1_790_611_110_000,
        nonce: [0x10; 16],
    },
    Case {
        name: "awkward_space_unicode_reserved",
        method: "POST",
        segments: &["fs", "mkdir"],
        query: &[("path", "Projects/x y/caf\u{e9}"), ("q", "a+b&c=d#e"), ("hidden", "true")],
        target: "/v1/fs/mkdir?path=Projects%2Fx%20y%2Fcaf%C3%A9&q=a%2Bb%26c%3Dd%23e&hidden=true",
        body: "{}",
        ts: 1_790_611_111_000,
        nonce: [0x20; 16],
    },
    Case {
        name: "awkward_path_segment",
        method: "POST",
        segments: &["fs", "we ird?#seg%"],
        query: &[],
        target: "/v1/fs/we%20ird%3F%23seg%25",
        body: "{}",
        ts: 1_790_611_112_000,
        nonce: [0x30; 16],
    },
];

#[test]
fn signing_fixture() {
    let key = SigningKey::from_bytes(&TEST_ONLY_SCALAR.into()).expect("valid scalar");
    let spki = key.verifying_key().to_public_key_der().expect("spki").into_vec();
    let cases: Vec<Value> = CASES
        .iter()
        .map(|c| {
            let nonce = b64::encode(&c.nonce);
            let input = SignInput {
                method: c.method,
                target: c.target,
                device: DEVICE,
                timestamp_ms: c.ts,
                nonce: &nonce,
                body: c.body.as_bytes(),
            };
            let canon = canonical(&input);
            let sig: Signature = key.sign(canon.as_bytes());
            json!({
                "name": c.name,
                "method": c.method,
                "segments": c.segments,
                "query": c.query.iter().map(|(k, v)| json!([k, v])).collect::<Vec<_>>(),
                "target": c.target,
                "device": DEVICE,
                "timestamp_ms": c.ts,
                "nonce": nonce,
                "body": c.body,
                "body_sha256": body_hash_hex(c.body.as_bytes()),
                "canonical": canon,
                "signature_der": b64::encode(sig.to_der().as_bytes()),
            })
        })
        .collect();
    check(
        "signing.json",
        json!({
            "note": "test_only_public_key belongs to a throwaway scalar used only here",
            "test_only_public_key_spki": b64::encode(&spki),
            "cases": cases,
        }),
    );
}

#[test]
fn names_fixture() {
    let folder = ["a", ".env", "v1.2-rc_3", "", ".", "..", "-rf", "a b", "a/b", "caf\u{e9}", "a;"];
    let session = ["remoter", "my proj", "  padded  ", "-x", "a;b", "a$b", "tab\tname"];
    let from_folder = ["remoter", "C#{x}", "--rf", "  ", "\u{1f680} launch", "a$HOME", "my folder "];
    let unsupported: [&[u8]; 6] =
        [b"normal", "caf\u{e9}".as_bytes(), b"bad\xff", "a\u{202e}b".as_bytes(), "a\u{200b}b".as_bytes(), b"a\nb"];
    check(
        "names.json",
        json!({
            "folder": folder.iter().map(|n| json!({"name": n, "valid": names::is_valid_folder_name(n)})).collect::<Vec<_>>(),
            "session": session.iter().map(|n| json!({"name": n, "valid": names::is_valid_session_name(n)})).collect::<Vec<_>>(),
            "session_from_folder": from_folder.iter().map(|n| json!({"folder": n, "name": names::session_name_from_folder(n)})).collect::<Vec<_>>(),
            "unsupported": unsupported.iter().map(|b| json!({"hex": b64::hex(b), "unsupported": names::is_unsupported_name(b)})).collect::<Vec<_>>(),
        }),
    );
}

#[test]
fn pair_fixture() {
    let secret = [0x5au8; 32];
    let fp = [0xa1u8; 32];
    let t = pair::Transcript { server_fp: &fp, tls_spki: b"\x30\x59tls", sig_spki: b"\x30\x59sig", device_name: "S25 Ultra" };
    check(
        "pair.json",
        json!({
            "secret": b64::encode(&secret),
            "server_fp": b64::encode(&fp),
            "tls_spki": b64::encode(t.tls_spki),
            "sig_spki": b64::encode(t.sig_spki),
            "device_name": t.device_name,
            "transcript_hex": b64::hex(&t.bytes()),
            "mac": b64::encode(&pair::mac(&secret, &t)),
            "code": pair::confirmation_code(&secret, &t),
            "link": pair::Link {
                host: std::net::Ipv4Addr::new(10, 66, 66, 3),
                port: 8443,
                pair_port: 8444,
                server_fp: fp,
                secret,
                challenge: [0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15],
                expires_unix: 1_790_611_406,
            }.to_uri(),
        }),
    );
}

fn summary(id: &str, state: SessionState) -> SessionSummary {
    SessionSummary {
        id: id.into(),
        name: "remoter".into(),
        path: "Projects/remoter".into(),
        device: Some(DEVICE.into()),
        started: 1_790_611_106_000,
        state,
        reason: None,
        exit_code: None,
        claude: (state == SessionState::Ready)
            .then(|| ClaudeLink::from_parts("session_01Hq7cXv2mTnR4bWkYe9pLsA", Some("env_01Kd3fPzQw8nVb2sLxRt6uYm")))
            .flatten(),
        worktree: None,
    }
}

#[test]
fn responses_fixture() {
    let entry = FsEntry {
        name: "remoter".into(),
        mtime: 1_790_611_000_000,
        is_git: true,
        has_claude_md: true,
        symlink: SymlinkKind::None,
        symlink_target: None,
        file_count: Some(3),
        session_count: 1,
        trusted: true,
        spawn_allowed: true,
        deny_reason: None,
        unsupported: false,
    };
    let denied = FsEntry {
        name: ".ssh".into(),
        is_git: false,
        has_claude_md: false,
        file_count: Some(0),
        session_count: 0,
        spawn_allowed: false,
        deny_reason: Some(DenyReason::Denied),
        ..entry.clone()
    };
    let link = FsEntry {
        name: "work".into(),
        symlink: SymlinkKind::Absolute,
        symlink_target: Some("Projects/remoter".into()),
        file_count: None,
        spawn_allowed: false,
        deny_reason: Some(DenyReason::SymlinkAbsolute),
        ..entry.clone()
    };
    let weird = FsEntry {
        name: "bad\u{fffd}".into(),
        unsupported: true,
        spawn_allowed: false,
        deny_reason: Some(DenyReason::Unsupported),
        ..entry.clone()
    };
    let mut stuck = summary("rc-01k6b7y3m4n5p6q7r8s9t0v1w3", SessionState::Stuck);
    stuck.reason = Some(StuckReason::Untrusted);
    let mut exited = summary("rc-01k6b7y3m4n5p6q7r8s9t0v1w4", SessionState::Exited);
    exited.exit_code = Some(1);
    let mut ready = summary("rc-01k6b7y3m4n5p6q7r8s9t0v1w2", SessionState::Ready);
    ready.worktree = Some("bright-otter-3f2a".into());
    let sessions = vec![ready, stuck, exited];

    let resources = Resources {
        cpu_pct: 23.4,
        cores: 16,
        mem_total: 33_324_118_016,
        mem_available: 19_843_563_520,
        swap_total: 17_179_865_088,
        swap_free: 17_179_865_088,
        disk_total: 999_142_281_216,
        disk_free: 412_418_985_984,
    };
    let claude = Proc {
        pid: 4242,
        ppid: 4240,
        start: 17_006_290,
        user: "river".into(),
        name: "claude".into(),
        cmd: "claude --remote-control=remoter --permission-mode bypassPermissions".into(),
        cpu_pct: 104.5,
        rss: 512_000_000,
        killable: true,
        session: Some(ProcSession { id: "rc-01k6b7y3m4n5p6q7r8s9t0v1w2".into(), name: "remoter".into() }),
    };
    let sshd = Proc {
        pid: 811,
        ppid: 1,
        start: 2_301,
        user: "root".into(),
        name: "sshd".into(),
        cmd: "sshd: /usr/bin/sshd -D".into(),
        cpu_pct: 0.0,
        rss: 9_000_000,
        killable: false,
        session: None,
    };

    check(
        "responses.json",
        json!({
            "health": Health { hostname: "r1v3r".into(), version: "0.1.0".into(), server_time: 1_790_611_106_000, locked: false, sessions: 1, on_ac: Some(false), battery_pct: Some(12), fresh_until: Some(1_790_697_506_000), tunnel: Some(Tunnel::Hub) },
            "health_direct": Health { hostname: "r1v3r".into(), version: "0.1.0".into(), server_time: 1_790_611_106_000, locked: false, sessions: 1, on_ac: Some(false), battery_pct: Some(12), fresh_until: Some(1_790_697_506_000), tunnel: Some(Tunnel::Direct) },
            "health_older": Health { hostname: "r1v3r".into(), version: "0.1.0".into(), server_time: 1_790_611_106_000, locked: false, sessions: 1, on_ac: Some(false), battery_pct: Some(12), fresh_until: Some(1_790_697_506_000), tunnel: None },
            "list": ListResponse { path: "Projects".into(), is_git: false, trusted: true, spawn_allowed: true, deny_reason: None, entries: vec![entry, denied, link, weird], truncated: false, partial: false },
            "search": SearchResponse { query: "rem".into(), hits: vec![SearchHit { path: "Projects/remoter".into(), name: "remoter".into(), is_git: true, depth: 2 }], capped: false },
            "recent": RecentResponse { entries: vec![RecentEntry { path: "Projects/remoter".into(), name: "remoter".into(), is_git: true, last_spawn: 1_790_611_106_000 }], typical_start_ms: Some(6100) },
            "mkdir_request": MkdirRequest { parent: "Projects".into(), name: "new-thing".into(), git_init: true },
            "mkdir": MkdirResponse { path: "Projects/new-thing".into() },
            "spawn_request": SpawnRequest { path: "Projects/remoter".into(), name: "remoter".into(), mode: SpawnMode::SameDir, trust: false, resume: None, handoff: None },
            "handoff_request": SpawnRequest { path: "Projects/remoter".into(), name: "fix the banner".into(), mode: SpawnMode::SameDir, trust: false, resume: None, handoff: Some("c82d8b5c-edd4-453e-8d59-4748ff325c03".into()) },
            "resume_request": SpawnRequest { path: "Projects/remoter".into(), name: "fix the banner".into(), mode: SpawnMode::SameDir, trust: false, resume: Some("c82d8b5c-edd4-453e-8d59-4748ff325c03".into()), handoff: None },
            "history": HistoryResponse {
                path: "Projects/remoter".into(),
                conversations: vec![
                    Conversation { id: "c82d8b5c-edd4-453e-8d59-4748ff325c03".into(), title: "fix the banner".into(), last_prompt: Some("the banner still overlaps the sheet, can you check".into()), started: 1_790_524_706_000, updated: 1_790_531_906_000, branch: Some("main".into()), open: false },
                    Conversation { id: "1170e57c-4a8e-4fb2-9437-21f0fdc2cef3".into(), title: "remoter".into(), last_prompt: None, started: 1_790_438_306_000, updated: 1_790_611_000_000, branch: None, open: true },
                ],
                truncated: false,
            },
            "spawn": SpawnResponse { id: "rc-01k6b7y3m4n5p6q7r8s9t0v1w2".into(), view_token: "dmlldy10b2tlbi1maXh0dXJlLW9ubHktMzItYnl0ZXM".into(), view_token_expires: 1_790_612_006_000 },
            "sessions": SessionsResponse { sessions: sessions.clone(), cap: 8 },
            "live_sessions": LiveSessions { sessions: sessions.clone() },
            "session_detail": SessionDetail { session: sessions[0].clone(), tail: vec!["·✔︎· Connected · remoter · main".into(), "    Capacity: 1/32".into()], tail_at: 1_790_611_110_000 },
            "view_token": ViewTokenResponse { token: "dmlldy10b2tlbi1maXh0dXJlLW9ubHktMzItYnl0ZXM".into(), expires: 1_790_612_006_000 },
            "attest_challenge": AttestChallenge { challenge: "AAECAwQFBgcICQoLDA0ODw".into(), expires: 1_790_611_406_000 },
            "attest_response": AttestResponse { fresh_until: 1_790_697_506_000 },
            "lock": LockResponse { locked: true },
            "resources": resources.clone(),
            "procs": ProcsResponse { resources, procs: vec![claude, sshd], truncated: false },
            "signal_request": SignalRequest { start: 17_006_290, signal: Signal::Term },
            "audit": AuditPage { entries: vec![AuditEntry { ts: 1_790_611_106_000, device: Some(DEVICE.into()), action: "spawn".into(), path: Some("Projects/remoter".into()), result: "ok".into(), request_id: "01K6B7Y3M4N5P6Q7R8S9T0V1X0".into() }], next_before: Some(1_790_611_106_000) },
            "pair": PairResponse { device_id: DEVICE.into(), hostname: "r1v3r".into() },
            "events": [
                { "event": "phase", "data": serde_json::from_str::<Value>(&Event::Phase { step: Phase::Accepted, at: 1 }.data_json()).expect("json") },
                { "event": "phase", "data": serde_json::from_str::<Value>(&Event::Phase { step: Phase::Handoff, at: 1 }.data_json()).expect("json") },
                { "event": "state", "data": serde_json::from_str::<Value>(&Event::State { state: SessionState::Stuck, reason: Some(StuckReason::HandoffFailed), exit_code: None }.data_json()).expect("json") },
                { "event": "state", "data": serde_json::from_str::<Value>(&Event::State { state: SessionState::Stuck, reason: Some(StuckReason::FolderChanged), exit_code: None }.data_json()).expect("json") },
                { "event": "tail", "data": serde_json::from_str::<Value>(&Event::Tail { lines: vec!["line".into()], at: 2 }.data_json()).expect("json") },
            ],
        }),
    );
}

#[test]
fn errors_fixture() {
    let bodies: Vec<Value> = ErrorCode::ALL
        .iter()
        .map(|&code| {
            let mut body = ErrorBody {
                code,
                message: format!("{} (fixture)", code.as_str()),
                request_id: "01K6B7Y3M4N5P6Q7R8S9T0V1X1".into(),
                retry_after_s: None,
                sessions: None,
                server_time: None,
            };
            match code {
                ErrorCode::RateLimited => body.retry_after_s = Some(17),
                ErrorCode::SessionCap | ErrorCode::FolderBusy => body.sessions = Some(vec![summary("rc-01k6b7y3m4n5p6q7r8s9t0v1w2", SessionState::Ready)]),
                ErrorCode::ClockSkew => body.server_time = Some(1_790_611_153_000),
                _ => {}
            }
            json!({ "status": code.http_status(), "body": body })
        })
        .collect();
    check("errors.json", json!(bodies));
}
