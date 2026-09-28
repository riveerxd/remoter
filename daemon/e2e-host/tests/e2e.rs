//! Each scenario pairs a software phone with the real daemons. The outer test
//! builds a world and reruns its `inner_` twin inside the lab namespaces.

use std::time::Duration;

use remoter_e2e_host::*;
use remoter_proto::admin::{AdminRequest, PairStarted, PairStatus};
use remoter_proto::pair;

macro_rules! scenario {
    ($outer:ident, $inner:ident, $body:expr) => {
        #[test]
        fn $outer() {
            let mut w = World::new(stringify!($outer));
            w.run_inner(stringify!($inner));
        }

        #[test]
        #[ignore = "run by its outer test inside the lab"]
        fn $inner() {
            if std::env::var_os("REMOTER_E2E_WORLD").is_none() {
                eprintln!("only runs inside the lab");
                return;
            }
            let lab = Lab::start();
            let f: fn(&Lab) = $body;
            f(&lab);
        }
    };
}

const S: Duration = Duration::from_secs(1);

fn spawn_body(path: &str, name: &str) -> Vec<u8> {
    serde_json::to_vec(&serde_json::json!({ "path": path, "name": name, "mode": "same-dir" })).expect("json")
}

fn mkdir_body(parent: &str, name: &str, git: bool) -> Vec<u8> {
    serde_json::to_vec(&serde_json::json!({ "parent": parent, "name": name, "git_init": git })).expect("json")
}

fn token(t: &str) -> Vec<(&'static str, String)> {
    vec![("remoter-view-token", t.to_owned())]
}

/// Waits until the session leaves Starting. Returns (id, view token, that state event, everything before it).
async fn spawn_and_settle(lab: &Lab, phone: &Phone, path: &str, wait: Duration) -> (String, String, SseEvent, Vec<SseEvent>) {
    let mut c = lab.client(phone).await;
    let r = c.signed(&phone.sign("POST", "/v1/sessions", &spawn_body(path, "e2e"))).await;
    assert_eq!(r.status, 202, "spawn {path}: {r:?}");
    let j = r.json();
    let id = j["id"].as_str().expect("id").to_owned();
    let tok = j["view_token"].as_str().expect("token").to_owned();
    let (status, mut sse) = c.stream(&format!("/v1/sessions/{id}/events"), &token(&tok)).await;
    assert_eq!(status, 200);
    let (last, seen) = sse.until(wait, |e| e.event == "state" && e.data["state"] != "starting").await;
    let last = last.unwrap_or_else(|| panic!("{path} never settled, saw {seen:?}"));
    (id, tok, last, seen)
}

scenario!(pairing_reads_and_mkdir, inner_pairing_reads_and_mkdir, |lab| {
    let phone = lab.pair("S25 Ultra");

    // wrong secret is refused and the listener closes, so a second POST can't connect
    let started: PairStarted = serde_json::from_value(lab.admin(&AdminRequest::PairStart { name: "S25 Ultra".into(), ttl_s: 60 }).expect("start")).expect("started");
    let link = pair::Link::parse(&started.link).expect("link");
    let keys = PhoneKeys::new();
    let bad = keys.pair_request(&lab.pki, &link, "S25 Ultra", &[0x13; 32]);
    let pp = (link.host, link.pair_port).into();
    let (status, _) = lab.block_on(post_pair(lab.phone_tcp(pp), link.clone(), bad.body.clone())).expect("first post");
    assert_eq!(status, 403, "a wrong MAC is refused");
    let st: PairStatus = serde_json::from_value(lab.admin(&AdminRequest::PairWait { wait_ms: 2000 }).expect("wait")).expect("status");
    assert!(matches!(st, PairStatus::Failed { .. }), "{st:?}");
    let good = keys.pair_request(&lab.pki, &link, "S25 Ultra", &link.secret);
    let second = lab.block_on(post_pair(lab.phone_tcp(pp), link.clone(), good.body));
    assert!(second.is_err(), "{second:?}");
    assert_eq!(remoterctl::list_devices(&lab.devices_file()).expect("devices").len(), 1, "nothing else was paired");

    lab.block_on(async {
        let mut c = lab.client(&phone).await;
        let h = c.get("/v1/health").await;
        assert!(h.headers.contains_key("remoter-request-id"));
        let h = h.json();
        assert_eq!(h["locked"], false);
        assert_eq!(h["battery_pct"], 12);
        assert!(h["fresh_until"].as_i64().is_some_and(|t| t > now()), "pairing counts as today's attestation");

        let l = c.get("/v1/fs/list?path=Projects&hidden=true").await.json();
        let entry = |n: &str| l["entries"].as_array().expect("entries").iter().find(|e| e["name"] == n).cloned().unwrap_or_else(|| panic!("{n} missing: {l}"));
        assert_eq!(entry("remoter")["is_git"], true);
        assert_eq!(entry("remoter")["spawn_allowed"], true);
        assert_eq!(entry(".ssh")["deny_reason"], "denied");
        let docs = c.get("/v1/fs/list?path=Documents").await.json();
        assert_eq!(docs["trusted"], false);
        assert_eq!(docs["deny_reason"], "untrusted");
        assert_eq!(c.get("/v1/fs/list?path=..%2F..").await.code(), "path_outside_home");

        let s = c.get("/v1/fs/search?q=rem&path=").await.json();
        assert!(s["hits"].as_array().expect("hits").iter().any(|h| h["path"] == "Projects/remoter"), "{s}");
        assert_eq!(c.get("/v1/fs/recent").await.json()["entries"], serde_json::json!([]));

        lab.wait_agent_window();
        let made = c.signed(&phone.sign("POST", "/v1/fs/mkdir", &mkdir_body("Projects", "made", true))).await;
        assert_eq!(made.status, 201, "{made:?}");
        assert_eq!(made.json()["path"], "Projects/made");
        assert!(lab.dir.join("home/Projects/made/.git").is_dir(), "git init ran in the new folder");
        assert_eq!(mode_of(&lab.dir.join("home/Projects/made")).0, uid(), "created as you, by the agent");
        assert_eq!(c.signed(&phone.sign("POST", "/v1/fs/mkdir", &mkdir_body("Projects", "made", false))).await.code(), "exists");
        assert_eq!(c.signed(&phone.sign("POST", "/v1/fs/mkdir", &mkdir_body("Projects", "-rf", false))).await.code(), "name_invalid");
        assert_eq!(c.signed(&phone.sign("POST", "/v1/sessions", &spawn_body("Documents/notes", "x"))).await.code(), "untrusted_folder");
        assert_eq!(c.signed(&phone.sign("POST", "/v1/sessions", &spawn_body("Projects/.ssh", "x"))).await.code(), "path_denied");

        let audit = c.get("/v1/audit").await.json();
        let actions: Vec<&str> = audit["entries"].as_array().expect("entries").iter().filter_map(|e| e["action"].as_str()).collect();
        assert!(actions.contains(&"pair") && actions.contains(&"mkdir"), "{actions:?}");
    });
});

fn seed_conversation(lab: &Lab, rel: &str, id: &str, title: &str) {
    let cwd = lab.dir.join("home").join(rel).display().to_string();
    // spelled out by hand on purpose, not borrowed from the agent
    let spelled: String = cwd.chars().map(|c| if c.is_ascii_alphanumeric() { c } else { '-' }).collect();
    let dir = lab.dir.join("home/.claude/projects").join(spelled);
    std::fs::create_dir_all(&dir).expect("projects");
    let lines = [
        serde_json::json!({"type":"user","cwd":cwd,"entrypoint":"cli","timestamp":"2026-10-06T18:40:00.000Z","gitBranch":"main","message":{"content":"the first ask"}}),
        serde_json::json!({"type":"custom-title","customTitle":title}),
    ];
    std::fs::write(dir.join(format!("{id}.jsonl")), lines.iter().map(|l| format!("{l}\n")).collect::<String>()).expect("transcript");
}

fn resume_body(path: &str, name: &str, id: &str) -> Vec<u8> {
    serde_json::to_vec(&serde_json::json!({ "path": path, "name": name, "mode": "same-dir", "resume": id })).expect("json")
}

scenario!(resume_a_past_conversation, inner_resume_a_past_conversation, |lab| {
    let phone = lab.pair("S25 Ultra");
    let conv = "c82d8b5c-edd4-453e-8d59-4748ff325c03";
    let elsewhere = "0b4910a1-dc2c-41b3-81c1-b8c9fd626592";
    seed_conversation(lab, "Projects/resume-me", conv, "fix the banner");
    seed_conversation(lab, "Projects/plain", elsewhere, "another folder");
    lab.wait_agent_window();
    lab.block_on(async {
        let mut c = lab.client(&phone).await;
        let target = "/v1/fs/history?path=Projects%2Fresume-me";
        // mTLS only, no token
        let h = c.get(target).await.json();
        assert_eq!(h["path"], "Projects/resume-me");
        let convs = h["conversations"].as_array().expect("conversations");
        assert_eq!(convs.len(), 1, "{h}");
        assert_eq!(convs[0]["id"], conv);
        assert_eq!(convs[0]["title"], "fix the banner");
        assert_eq!(convs[0]["branch"], "main");
        assert_eq!(convs[0]["open"], false);

        let r = c.signed(&phone.sign("POST", "/v1/sessions", &resume_body("Projects/resume-me", "x", elsewhere))).await;
        assert_eq!(r.code(), "not_found", "only in its own folder");

        let r = c.signed(&phone.sign("POST", "/v1/sessions", &resume_body("Projects/resume-me", "fix the banner", conv))).await;
        assert_eq!(r.status, 202, "{r:?}");
        let j = r.json();
        let id = j["id"].as_str().expect("id").to_owned();
        let tok = j["view_token"].as_str().expect("token").to_owned();
        let (status, mut sse) = c.stream(&format!("/v1/sessions/{id}/events"), &token(&tok)).await;
        assert_eq!(status, 200);
        let (last, seen) = sse.until(20 * S, |e| e.event == "state" && e.data["state"] != "starting").await;
        let last = last.unwrap_or_else(|| panic!("never settled: {seen:?}"));
        assert_eq!(last.data["state"], "ready", "a reattach reads as ready: {last:?}");

        let list = c.get("/v1/sessions").await.json();
        let s = list["sessions"].as_array().expect("sessions").iter().find(|s| s["id"] == id.as_str()).cloned().expect("listed");
        assert_eq!(s["claude"]["session_url"], "https://claude.ai/code/session_01Pm4tWq9zHc2vNe7gRb5kDy");
        assert_eq!(s["name"], "fix the banner");
        let detail = c.get_with(&format!("/v1/sessions/{id}"), &token(&tok)).await.json();
        let tail = detail["tail"].as_array().expect("tail").iter().filter_map(|l| l.as_str()).collect::<Vec<_>>().join("\n");
        assert!(tail.contains(&format!("--resume {conv}")), "claude got the uuid: {tail}");

        let h = c.get(target).await.json();
        assert_eq!(h["conversations"][0]["open"], true, "held by the running session");
        let again = c.signed(&phone.sign("POST", "/v1/sessions", &resume_body("Projects/resume-me", "again", conv))).await;
        assert_eq!(again.status, 409);
        assert_eq!(again.code(), "conversation_open");

        let end = c.signed(&phone.sign("DELETE", &format!("/v1/sessions/{id}"), b"")).await;
        assert_eq!(end.status, 202);
        let (gone, seen) = sse.until(15 * S, |e| e.event == "state" && e.data["state"] == "gone").await;
        assert!(gone.is_some(), "never gone: {seen:?}");
        let audit = c.get("/v1/audit").await.json();
        assert!(audit["entries"].as_array().expect("entries").iter().any(|e| e["action"] == "spawn" && e["path"] == "Projects/resume-me" && e["result"] == "ok"), "{audit}");
        let notes = std::fs::read_to_string(lab.dir.join("notify.log")).unwrap_or_default();
        assert!(notes.contains("resumed fix the banner in ~/Projects/resume-me"), "{notes}");
    });
});

scenario!(spawn_ready_link_tokens_and_kill, inner_spawn_ready_link_tokens_and_kill, |lab| {
    let phone = lab.pair("S25 Ultra");
    lab.wait_agent_window();
    lab.block_on(async {
        let (id, tok, last, seen) = spawn_and_settle(lab, &phone, "Projects/remoter", 20 * S).await;
        assert_eq!(last.data["state"], "ready", "{last:?}");
        let steps: Vec<String> = seen.iter().filter(|e| e.event == "phase").map(|e| e.data["step"].as_str().unwrap_or("").to_owned()).collect();
        assert_eq!(steps, ["accepted", "terminal", "claude", "remote_control"], "every phase, in order");

        let mut c = lab.client(&phone).await;
        let list = c.get("/v1/sessions").await.json();
        let s = &list["sessions"][0];
        assert_eq!(s["id"], id.as_str());
        assert_eq!(s["state"], "ready");
        assert_eq!(s["device"], phone.id.as_str());
        assert_eq!(s["claude"]["session_url"], "https://claude.ai/code/session_01Hq7cXv2mTnR4bWkYe9pLsA", "link on Ready");
        assert_eq!(s["claude"]["environment_url"], "https://claude.ai/code?environment=env_01Kd3fPzQw8nVb2sLxRt6uYm");
        assert!(list.to_string().find("Connected to fake").is_none(), "no terminal output without a view token");

        // mTLS is enough, a token from an older phone (real or not) changes nothing
        let detail = format!("/v1/sessions/{id}");
        let d = c.get(&detail).await;
        assert_eq!(d.status, 200, "{d:?}");
        assert!(d.json()["tail"].to_string().contains("Connected to fake remote control"), "{d:?}");
        assert_eq!(c.get_with(&detail, &token("bm90LWEtcmVhbC10b2tlbg")).await.status, 200, "a made up token is ignored");
        assert_eq!(c.get_with(&detail, &token(&tok)).await.status, 200);
        let vt = c.signed(&phone.sign("POST", "/v1/view-token", b"{}")).await;
        assert_eq!(vt.status, 200, "{vt:?}");

        let (_, mut tail) = c.stream(&format!("/v1/sessions/{id}/events"), &[]).await;
        let (t, _) = tail.until(10 * S, |e| e.event == "tail").await;
        assert!(t.expect("a tail event without any token").data["lines"].to_string().contains("Connected to fake"));

        let note = std::fs::read_to_string(lab.dir.join("notify.log")).unwrap_or_default();
        assert!(note.contains("Projects/remoter"), "{note:?}");
        let recent = c.get("/v1/fs/recent").await.json();
        assert_eq!(recent["entries"][0]["path"], "Projects/remoter");

        let (_, mut watch) = c.stream(&format!("/v1/sessions/{id}/events"), &[]).await;
        let _ = watch.until(3 * S, |e| e.event == "state").await;
        let k = c.signed(&phone.sign("DELETE", &detail, b"")).await;
        assert_eq!(k.status, 202, "{k:?}");
        let (ending, _) = watch.until(5 * S, |e| e.event == "state" && e.data["state"] == "ending").await;
        assert!(ending.is_some(), "ending is announced right away");
        assert!(watch.closes_within(10 * S).await, "stream should end");
        let after = c.get("/v1/sessions").await.json();
        assert!(!after.to_string().contains(&id) || after["sessions"][0]["state"] == "gone", "{after}");

        let audit = c.get("/v1/audit").await.json().to_string();
        assert!(audit.contains("\"spawn\"") && audit.contains("\"end\""), "{audit}");
    });
});

scenario!(stuck_and_exited, inner_stuck_and_exited, |lab| {
    let phone = lab.pair("S25 Ultra");
    lab.wait_agent_window();
    lab.block_on(async {
        let (id, tok, last, _) = spawn_and_settle(lab, &phone, "Projects/exit1", 20 * S).await;
        assert_eq!(last.data["state"], "exited", "{last:?}");
        assert_eq!(last.data["exit_code"], 1);
        let mut c = lab.client(&phone).await;
        let d = c.get_with(&format!("/v1/sessions/{id}"), &token(&tok)).await.json();
        assert!(d["tail"].to_string().contains("something broke"), "the last lines say why: {d}");

        let (_, _, last, _) = spawn_and_settle(lab, &phone, "Projects/untrusted-sim", 20 * S).await;
        assert_eq!(last.data["state"], "stuck", "{last:?}");
        assert_eq!(last.data["reason"], "untrusted");

        // no ready line, so Stuck after 20s
        let (_, _, last, _) = spawn_and_settle(lab, &phone, "Projects/hang", 40 * S).await;
        assert_eq!(last.data["state"], "stuck", "{last:?}");
        assert_eq!(last.data["reason"], "timeout");
    });
});

scenario!(session_cap, inner_session_cap, |lab| {
    let phone = lab.pair("S25 Ultra");
    lab.wait_agent_window();
    lab.block_on(async {
        let (a, ..) = spawn_and_settle(lab, &phone, "Projects/ready-a", 20 * S).await;
        let _ = spawn_and_settle(lab, &phone, "Projects/ready-b", 20 * S).await;
        let mut c = lab.client(&phone).await;
        let r = c.signed(&phone.sign("POST", "/v1/sessions", &spawn_body("Projects/ready-c", "c"))).await;
        assert_eq!(r.code(), "session_cap", "{r:?}");
        let listed = r.json()["sessions"].as_array().map_or(0, |v| v.len());
        assert_eq!(listed as u32, MAX_SESSIONS, "refusal lists what runs");
        assert_eq!(c.signed(&phone.sign("DELETE", &format!("/v1/sessions/{a}"), b"")).await.status, 202);
    });
});

scenario!(idempotent_retry_and_clock_skew, inner_idempotent_retry_and_clock_skew, |lab| {
    let phone = lab.pair("S25 Ultra");
    lab.wait_agent_window();
    lab.block_on(async {
        let s = phone.sign("POST", "/v1/sessions", &spawn_body("Projects/ready-a", "retry"));
        let first = lab.client(&phone).await.signed(&s).await;
        assert_eq!(first.status, 202, "{first:?}");
        // app resends the same bytes on a new connection
        let again = lab.client(&phone).await.signed(&s).await;
        assert_eq!((again.status, again.body.clone()), (first.status, first.body.clone()), "the original answer, not a second run");
        let list = lab.client(&phone).await.get("/v1/sessions").await.json();
        assert_eq!(list["sessions"].as_array().map(|v| v.len()), Some(1), "{list}");

        let mut c = lab.client(&phone).await;
        let body = mkdir_body("Projects", "skew", false);
        for off in [-(WINDOW_S * 1000 + 1500), WINDOW_S * 1000 + 1500] {
            let r = c.signed(&phone.sign_at("POST", "/v1/fs/mkdir", &body, now() + off, &random16())).await;
            assert_eq!(r.code(), "clock_skew", "{off} ms: {r:?}");
            let server_time = r.json()["server_time"].as_i64().expect("server_time");
            assert!((server_time - now()).abs() < 5000, "the error carries the laptop's clock");
        }
        assert!(!lab.dir.join("home/Projects/skew").exists());

        // past the window the same bytes are refused
        let ts: i64 = s.headers.iter().find(|(k, _)| *k == "remoter-timestamp").map(|(_, v)| v.parse().expect("ts")).expect("ts");
        let wait = ts + WINDOW_S * 1000 + 1000 - now();
        if wait > 0 {
            tokio::time::sleep(Duration::from_millis(wait as u64)).await;
        }
        assert_eq!(lab.client(&phone).await.signed(&s).await.code(), "clock_skew");
    });
});

scenario!(lock_is_sticky_until_lock_off, inner_lock_is_sticky_until_lock_off, |lab| {
    let phone = lab.pair("S25 Ultra");
    lab.wait_agent_window();
    lab.block_on(async {
        let mut c = lab.client(&phone).await;
        assert_eq!(c.signed(&phone.sign("POST", "/v1/fs/mkdir", &mkdir_body("Projects", "before", false))).await.status, 201);
        // no fingerprint needed to lock
        let l = c.send("POST", "/v1/lock", &[], vec![]).await.expect("lock");
        assert_eq!(l.status, 200, "{l:?}");
        assert_eq!(c.get("/v1/health").await.json()["locked"], true);
        assert_eq!(c.get("/v1/fs/list?path=Projects").await.status, 200, "reads still work");
        assert_eq!(c.signed(&phone.sign("POST", "/v1/fs/mkdir", &mkdir_body("Projects", "during", false))).await.code(), "locked");
        assert!(lab.dir.join("state/locked").exists());

        // clearing remoterd's side alone must leave the agent locked
        let sticky = lab.agent_state().join("locked");
        let deadline = tokio::time::Instant::now() + 6 * S;
        while !sticky.exists() && tokio::time::Instant::now() < deadline {
            tokio::time::sleep(Duration::from_millis(200)).await;
        }
        assert!(sticky.exists(), "agent didn't lock itself");
        lab.admin(&AdminRequest::Unlock {}).expect("unlock remoterd");
        assert_eq!(c.get("/v1/health").await.json()["locked"], false);
        let r = c.signed(&phone.sign("POST", "/v1/fs/mkdir", &mkdir_body("Projects", "half", false))).await;
        assert_eq!(r.code(), "locked", "the agent still refuses: {r:?}");
        assert!(!lab.dir.join("home/Projects/half").exists());

        // rest of `remoterctl lock off`
        remoter_agent_clear(lab);
        assert_eq!(c.signed(&phone.sign("POST", "/v1/fs/mkdir", &mkdir_body("Projects", "after", false))).await.status, 201);
    });
});

fn remoter_agent_clear(lab: &Lab) {
    let f = lab.agent_state().join("locked");
    match std::fs::remove_file(&f) {
        Ok(()) => {}
        Err(e) if e.kind() == std::io::ErrorKind::NotFound => {}
        Err(e) => panic!("{}: {e}", f.display()),
    }
}

scenario!(bad_signatures_lock_and_garbage_does_not, inner_bad_signatures_lock_and_garbage_does_not, |lab| {
    let phone = lab.pair("S25 Ultra");
    lab.wait_agent_window();
    // junk from strangers never locks
    for _ in 0..5 {
        if let Ok(mut t) = lab.phone_tcp(API) {
            use std::io::Write;
            let _ = t.write_all(b"\x16\x03\x01\x00\x05hello garbage");
        }
    }
    lab.block_on(async {
        let mut c = lab.client(&phone).await;
        assert_eq!(c.get("/v1/health").await.json()["locked"], false, "handshake garbage locks nothing");
        let thief = p256::ecdsa::SigningKey::from_bytes(&[0x42u8; 32].into()).expect("key");
        for i in 0..3 {
            let s = phone.sign_with(&thief, "POST", "/v1/fs/mkdir", &mkdir_body("Projects", &format!("x{i}"), false), now(), &random16());
            assert_eq!(c.signed(&s).await.code(), "sig_invalid");
        }
        assert_eq!(c.get("/v1/health").await.json()["locked"], true, "3 bad sigs lock");
        assert_eq!(c.signed(&phone.sign("POST", "/v1/fs/mkdir", &mkdir_body("Projects", "real", false))).await.code(), "locked");
        assert_eq!(c.get("/v1/fs/list?path=Projects").await.status, 200);
    });
});

scenario!(a_reused_nonce_locks, inner_a_reused_nonce_locks, |lab| {
    let phone = lab.pair("S25 Ultra");
    lab.wait_agent_window();
    lab.block_on(async {
        let mut c = lab.client(&phone).await;
        let nonce = random16();
        let a = phone.sign_at("POST", "/v1/fs/mkdir", &mkdir_body("Projects", "one", false), now(), &nonce);
        assert_eq!(c.signed(&a).await.status, 201);
        let b = phone.sign_at("POST", "/v1/fs/mkdir", &mkdir_body("Projects", "two", false), now(), &nonce);
        assert_eq!(c.signed(&b).await.code(), "nonce_reused");
        assert!(!lab.dir.join("home/Projects/two").exists());
        assert_eq!(c.get("/v1/health").await.json()["locked"], true, "one reused nonce is enough");
    });
});

scenario!(revoke_cuts_the_phone_off, inner_revoke_cuts_the_phone_off, |lab| {
    let phone = lab.pair("S25 Ultra");
    lab.wait_agent_window();
    lab.block_on(async {
        let (id, ..) = spawn_and_settle(lab, &phone, "Projects/ready-a", 20 * S).await;
        let mut c = lab.client(&phone).await;
        let (status, mut sse) = c.stream(&format!("/v1/sessions/{id}/events"), &[]).await;
        assert_eq!(status, 200);
        let _ = sse.until(3 * S, |e| e.event == "state").await;

        // `sudo remoterctl revoke <id>`.
        assert!(remoterctl::remove_device(&lab.devices_file(), &phone.id).expect("remove"));
        remoterctl::forget_unpaired(&lab.dir.join("state/unpaired.json"), &phone.id).expect("forget");
        remoterctl::forget_unpaired(&lab.agent_state().join("unpaired.json"), &phone.id).expect("forget");
        lab.admin(&AdminRequest::Reload {}).expect("reload");

        assert!(sse.closes_within(5 * S).await, "the live event stream is cut");
        assert!(c.send("GET", "/v1/health", &[], vec![]).await.is_err(), "the open connection is gone too");
        let refused = match lab.try_client(&phone).await {
            Err(_) => true,
            // TLS 1.3: a refused client cert shows up on the first read
            Ok(mut again) => again.send("GET", "/v1/health", &[], vec![]).await.is_err(),
        };
        assert!(refused, "and a new handshake is refused");
    });
});

scenario!(live_follows_spawn_and_kill, inner_live_follows_spawn_and_kill, |lab| {
    let phone = lab.pair("S25 Ultra");
    lab.wait_agent_window();
    lab.block_on(async {
        let mut c = lab.client(&phone).await;
        let (status, mut live) = c.stream("/v1/live", &[]).await;
        assert_eq!(status, 200);
        let mut first = Vec::new();
        for _ in 0..2 {
            first.push(live.next(3 * S).await.expect("a snapshot right after connect"));
        }
        let of = |name: &str| first.iter().find(|e| e.event == name).cloned().unwrap_or_else(|| panic!("no {name} in {first:?}"));
        assert!(first.iter().all(|e| e.id.is_none()), "no ids: {first:?}");
        assert_eq!(of("sessions").data, serde_json::json!({ "sessions": [] }));
        let mut health = c.get("/v1/health").await.json();
        let mut pushed = of("health").data;
        for h in [&mut health, &mut pushed] {
            h.as_object_mut().expect("object").remove("server_time");
        }
        assert_eq!(pushed, health, "the same body as GET /v1/health");
        assert_eq!(pushed["sessions"], 0);

        let t0 = std::time::Instant::now();
        let r = c.signed(&phone.sign("POST", "/v1/sessions", &spawn_body("Projects/ready-a", "live"))).await;
        assert_eq!(r.status, 202, "{r:?}");
        let id = r.json()["id"].as_str().expect("id").to_owned();
        let listed = |e: &SseEvent| e.event == "sessions" && e.data["sessions"].as_array().is_some_and(|v| v.iter().any(|s| s["id"] == id.as_str()));
        let (got, seen) = live.until(2 * S, listed).await;
        let got = got.unwrap_or_else(|| panic!("not listed within 2s, saw {seen:?}"));
        println!("spawn listed after {:?}", t0.elapsed());
        let ready = |e: &SseEvent| e.event == "sessions" && e.data["sessions"][0]["state"] == "ready";
        let counted = |e: &SseEvent| e.event == "health" && e.data["sessions"] == 1;
        let mut seen = Vec::new();
        // the fake is often ready before the first list goes out
        if !ready(&got) {
            let (r, more) = live.until(20 * S, ready).await;
            assert!(r.is_some(), "never ready in the list, saw {more:?}");
            seen = more;
        }
        if !seen.iter().any(counted) {
            let (got, seen) = live.until(4 * S, counted).await;
            assert!(got.is_some(), "health counts it too, saw {seen:?}");
        }

        let t0 = std::time::Instant::now();
        let k = c.signed(&phone.sign("DELETE", &format!("/v1/sessions/{id}"), b"")).await;
        assert_eq!(k.status, 202, "{k:?}");
        let (got, seen) = live.until(2 * S, |e| e.event == "sessions" && e.data["sessions"][0]["state"] == "ending").await;
        assert!(got.is_some(), "ending shows within 2 s, saw {seen:?}");
        let (got, seen) = live.until(15 * S, |e| e.event == "sessions" && e.data["sessions"] == serde_json::json!([])).await;
        assert!(got.is_some(), "still listed, saw {seen:?}");
        println!("kill unlisted after {:?}", t0.elapsed());
        assert!(!live.ended, "stream closed");
    });
});
