//! The `/v1` API against a fake agent.

mod common;

use std::sync::atomic::Ordering;
use std::time::Duration;

use common::*;
use remoter_proto::ipc::AgentRequest;

#[tokio::test]
async fn request_ids_and_error_bodies() {
    let h = start().await;
    let mut c = h.client(&h.a).await;
    for (method, target) in [("GET", "/v1/health"), ("GET", "/v1/nope"), ("PUT", "/v1/health"), ("GET", "/v1/attest/challenge"), ("GET", "/v1/sessions/rc-x")] {
        let r = c.send(method, target, &[], vec![]).await.expect("req");
        let rid = r.headers.get("remoter-request-id").and_then(|v| v.to_str().ok()).unwrap_or_default().to_owned();
        assert_eq!(rid.len(), 26, "{method} {target}");
        if r.status.as_u16() >= 400 {
            let j = r.json();
            assert_eq!(j["request_id"], rid, "{method} {target}");
            assert!(j["code"].is_string() && j["message"].is_string());
        }
    }
    assert_eq!(c.get("/v1/nope").await.code(), "not_found");
    assert_eq!(c.get("/v1/sessions/rc-x").await.status, 200, "a read, mTLS is enough");
}

#[tokio::test]
async fn bodies_stop_at_16_kib() {
    let h = start().await;
    let mut c = h.client(&h.a).await;
    let big = vec![b' '; 16 * 1024 + 1];
    let sig = h.a.phone.sign("POST", "/v1/fs/mkdir", &big, now(), &[1; 16]);
    let r = c.signed("POST", "/v1/fs/mkdir", &sig, &big).await;
    assert_eq!(r.code(), "bad_request");
    assert_eq!(h.agent.mutations.load(Ordering::SeqCst), 0);
    assert!(!h.app.is_locked());
}

#[tokio::test]
async fn reads_are_limited_to_60_a_minute() {
    let h = start().await;
    let mut c = h.client(&h.a).await;
    for i in 0..60 {
        assert_eq!(c.get("/v1/sessions").await.status, 200, "{i}");
    }
    let r = c.get("/v1/sessions").await;
    assert_eq!(r.code(), "rate_limited");
    assert!(r.json()["retry_after_s"].as_u64().is_some());
    let mut other = h.client(&h.b).await;
    assert_eq!(other.get("/v1/sessions").await.status, 200, "per device");
}

#[tokio::test]
async fn list_query_reaches_the_agent_as_raw_bytes() {
    let h = start().await;
    let mut c = h.client(&h.a).await;
    let r = c.get("/v1/fs/list?path=Projects%2Fcaf%C3%A9%20x%2Bb%FF&hidden=true").await;
    let j = r.json();
    let raw = remoter_proto::b64::decode(j["echo_path"].as_str().expect("path")).expect("b64");
    assert_eq!(raw, b"Projects/caf\xc3\xa9 x+b\xff".to_vec(), "decoded once, + kept, non UTF-8 kept");
    assert_eq!(j["hidden"], true);
    assert_eq!(c.get("/v1/fs/search?q=%FF").await.code(), "bad_request", "a query must be text");
}

#[tokio::test]
async fn health_reports_power_from_sysfs() {
    let h = start().await;
    let mut c = h.client(&h.a).await;
    let j = c.get("/v1/health").await.json();
    assert_eq!((j["on_ac"].clone(), j["battery_pct"].clone(), j["hostname"].clone()), (false.into(), 12.into(), "r1v3r".into()));
}

fn events_in(chunks: &str) -> Vec<(Option<String>, String)> {
    chunks
        .split("\n\n")
        .filter(|b| b.contains("event:"))
        .map(|b| {
            let id = b.lines().find_map(|l| l.strip_prefix("id: ").or(l.strip_prefix("id:"))).map(String::from);
            let ev = b.lines().find_map(|l| l.strip_prefix("event: ").or(l.strip_prefix("event:"))).unwrap_or("").to_owned();
            (id, ev)
        })
        .collect()
}

async fn collect(rx: &mut tokio::sync::mpsc::UnboundedReceiver<Result<String, String>>, for_: Duration) -> String {
    let mut all = String::new();
    let deadline = tokio::time::Instant::now() + for_;
    while let Ok(Some(Ok(s))) = tokio::time::timeout_at(deadline, rx.recv()).await {
        all.push_str(&s);
    }
    all
}

#[tokio::test]
async fn sse_carries_ids_and_resumes_from_last_event_id() {
    let h = start().await;
    let mut c = h.client(&h.a).await;
    let (status, mut rx) = c.stream("/v1/sessions/rc-x/events", &[]).await;
    assert_eq!(status, 200);
    // tail events carry no id, only session events do
    let numbered = |chunks: &str| events_in(chunks).into_iter().filter(|(_, name)| name != "tail").collect::<Vec<_>>();
    let got = numbered(&collect(&mut rx, Duration::from_millis(800)).await);
    assert_eq!(got, vec![(Some("100".into()), "phase".into()), (Some("101".into()), "state".into())]);
    let (_, mut rx2) = c.stream("/v1/sessions/rc-x/events", &[("last-event-id", "100".into())]).await;
    let got = numbered(&collect(&mut rx2, Duration::from_millis(800)).await);
    assert_eq!(got, vec![(Some("101".into()), "state".into())], "picks up after the last one seen");
    let (s, _) = c.stream("/v1/sessions/rc-missing/events", &[]).await;
    assert_eq!(s, 404);
}

#[tokio::test]
async fn one_tail_poll_serves_every_subscriber() {
    let h = start().await;
    let mut c = h.client(&h.a).await;
    let (_, mut one) = c.stream("/v1/sessions/rc-x/events", &[]).await;
    // older phones send a token, maybe expired or made up. ignored.
    let (_, mut stale) = c.stream("/v1/sessions/rc-x/events", &[("remoter-view-token", "guess".into())]).await;
    let mut c2 = h.client(&h.a).await;
    let (_, mut two) = c2.stream("/v1/sessions/rc-x/events", &[]).await;
    let window = Duration::from_millis(3500);
    let (a, b, s) = tokio::join!(collect(&mut one, window), collect(&mut two, window), collect(&mut stale, window));
    assert!(a.contains("event: tail") && b.contains("event: tail") && s.contains("event: tail"), "every mTLS reader gets the tail");
    // one read per subscriber on join, the rest is the shared poller
    let polls = h.agent.tails.load(Ordering::SeqCst) - 3;
    assert!((5..=9).contains(&polls), "{polls} polls");
    // the fake's tick changes every second read, joins included. sending every
    // poll would blow well past this bound.
    let ticks = a.matches("event: tail").count();
    assert!(ticks <= (polls + 3).div_ceil(2) + 1, "{ticks} tails for {polls} polls");
}

// the tail's own sender used to keep the stream open after the session was gone
#[tokio::test]
async fn the_stream_closes_once_the_session_is_gone() {
    let h = start().await;
    let mut c = h.client(&h.a).await;
    let (_, mut rx) = c.stream("/v1/sessions/rc-gone/events", &[]).await;
    let deadline = tokio::time::Instant::now() + Duration::from_secs(5);
    loop {
        match tokio::time::timeout_at(deadline, rx.recv()).await {
            Ok(None) | Ok(Some(Err(_))) => break,
            Ok(Some(Ok(_))) => {}
            Err(_) => panic!("the stream outlived its session"),
        }
    }
}

// the poller only sends changes, so a quiet session used to show a blank terminal
#[tokio::test]
async fn a_new_subscriber_gets_the_screen_at_once() {
    let h = start().await;
    let mut c = h.client(&h.a).await;
    let (_, mut first) = c.stream("/v1/sessions/rc-x/events", &[]).await;
    let _ = collect(&mut first, Duration::from_millis(1200)).await;
    let mut c2 = h.client(&h.a).await;
    let (_, mut late) = c2.stream("/v1/sessions/rc-x/events", &[]).await;
    let got = collect(&mut late, Duration::from_millis(250)).await;
    assert!(got.contains("event: tail"), "{got}");
}

#[tokio::test]
async fn session_detail_needs_only_mtls() {
    let h = start().await;
    let mut c = h.client(&h.a).await;
    assert_eq!(c.get("/v1/sessions/rc-x").await.status, 200);
    let stale = c.send("GET", "/v1/sessions/rc-x", &[("remoter-view-token", "nope".into())], vec![]).await.expect("r");
    assert_eq!(stale.status, 200, "stale token");
    let sent: Vec<_> = h.agent.seen.lock().expect("l").iter().filter(|r| matches!(r, AgentRequest::Session { .. })).cloned().collect();
    assert_eq!(sent, vec![AgentRequest::Session { id: "rc-x".into() }; 2], "only the id reaches the agent");
}

#[tokio::test]
async fn history_needs_only_mtls() {
    let h = start().await;
    let mut c = h.client(&h.a).await;
    let stale = c.send("GET", "/v1/fs/history?path=Projects%2Fremoter", &[("remoter-view-token", "nope".into())], vec![]).await.expect("r");
    assert_eq!(stale.status, 200, "stale token");
    let ok = c.get("/v1/fs/history?path=Projects%2Fremoter").await;
    assert_eq!(ok.status, 200);
    let j = ok.json();
    assert_eq!(remoter_proto::b64::decode(j["echo_path"].as_str().unwrap_or_default()).as_deref(), Some(&b"Projects/remoter"[..]));
    assert_eq!(c.send("POST", "/v1/fs/history", &[], vec![]).await.expect("r").code(), "not_found", "a read only");
}

#[tokio::test]
async fn audit_lists_this_devices_actions() {
    let h = start().await;
    let mut c = h.client(&h.a).await;
    let b = br#"{"path":"Projects/remoter","name":"x","mode":"same-dir"}"#;
    let sig = h.a.phone.sign("POST", "/v1/sessions", b, now(), &[2; 16]);
    assert_eq!(c.signed("POST", "/v1/sessions", &sig, b).await.status, 202);
    let j = c.get("/v1/audit").await.json();
    let e = &j["entries"][0];
    // the path the agent acted on, not the one asked for
    assert_eq!((e["action"].as_str(), e["path"].as_str(), e["result"].as_str()), (Some("spawn"), Some("Projects/canonical"), Some("ok")));
    let mut other = h.client(&h.b).await;
    assert_eq!(other.get("/v1/audit").await.json()["entries"].as_array().map(|a| a.len()), Some(0), "only your own actions");
    assert_eq!(remoterd::audit::verify(&h.dir.join("state/audit.jsonl")), Ok(1));
}

#[tokio::test]
async fn unpair_cuts_the_device_off() {
    let h = start().await;
    let mut c = h.client(&h.a).await;
    let sig = h.a.phone.sign("DELETE", "/v1/devices/self", b"", now(), &[3; 16]);
    assert_eq!(c.signed("DELETE", "/v1/devices/self", &sig, b"").await.status, 202, "the agent's answer, passed through");
    // the agent verifies and records the unpair itself
    let forwarded = h.agent.seen.lock().expect("l").iter().any(|r| {
        matches!(r, AgentRequest::Mutate { method, target, .. } if method == "DELETE" && target == "/v1/devices/self")
    });
    assert!(forwarded, "unpair reached the agent");
    let f = h.dir.join("state/unpaired.json");
    assert!(std::fs::read_to_string(&f).expect("written").contains(A));
    use std::os::unix::fs::PermissionsExt;
    assert_eq!(std::fs::metadata(&f).expect("m").permissions().mode() & 0o777, 0o644, "the agent reads it");
    tokio::time::sleep(Duration::from_millis(800)).await;
    assert!(c.send("GET", "/v1/health", &[], vec![]).await.is_err(), "the live connection is gone");
    assert!(Client::connect(h.addr, h.config_for(&h.a)).await.is_err() || {
        let mut again = Client::connect(h.addr, h.config_for(&h.a)).await.expect("c");
        again.send("GET", "/v1/health", &[], vec![]).await.is_err()
    });
    let mut b = h.client(&h.b).await;
    assert_eq!(b.get("/v1/health").await.status, 200);
}

#[test]
fn remoterd_refuses_to_start_on_loose_trust_files() {
    let dir = std::path::PathBuf::from(env!("CARGO_TARGET_TMPDIR")).join(format!("loose-{}", std::process::id()));
    let _ = std::fs::remove_dir_all(&dir);
    std::fs::create_dir_all(&dir).expect("mk");
    let cfg = dir.join("config.toml");
    std::fs::write(
        &cfg,
        format!(
            "[net]\nlisten_device=\"lo\"\nlisten_addr=\"127.0.0.1\"\nport=1\npair_port=2\nphone_addr=\"127.0.0.2\"\nclock_window_s=30\n[agent]\nsocket=\"{d}/a.sock\"\n[remoterd]\nagent_user=\"root\"\ndevices=\"{d}/devices.json\"\nserver_key=\"{d}/server.key\"\nserver_cert=\"{d}/server.crt\"\nstate_dir=\"{d}\"\n[attestation]\napp_package=\"me.river.remoter\"\napp_cert_sha256=\"{z}\"\nmax_patch_age_months=6\nfresh_hours=24\n",
            d = dir.display(),
            z = "5a".repeat(32)
        ),
    )
    .expect("w");
    let out = std::process::Command::new(env!("CARGO_BIN_EXE_remoterd")).arg("--config").arg(&cfg).env_remove("REMOTER_E2E_TRUSTED_OWNER").output().expect("run");
    assert!(!out.status.success());
    let err = String::from_utf8_lossy(&out.stderr);
    assert!(err.contains("refusing to start") && err.contains("owned by uid"), "{err}");
    let _ = std::fs::remove_dir_all(&dir);
}

#[tokio::test]
async fn sse_pings_every_15_seconds() {
    let h = start().await;
    let mut c = h.client(&h.a).await;
    // quiet screen, or tail events would keep the stream busy
    let (_, mut rx) = c.stream("/v1/sessions/rc-quiet/events", &[]).await;
    let t0 = std::time::Instant::now();
    let got = collect(&mut rx, Duration::from_millis(16_500)).await;
    let ping = got.lines().find(|l| l.starts_with(':')).map(String::from);
    println!("ping line: {ping:?} after {:?}", t0.elapsed());
    assert!(ping.as_deref().is_some_and(|l| l.trim_start_matches(':').trim() == "ping"), "{got:?}");
}

/// (name, data) per event, plus whether any had an id.
fn live_events(chunks: &str) -> (Vec<(String, serde_json::Value)>, bool) {
    let blocks: Vec<&str> = chunks.split("\n\n").filter(|b| b.contains("event:")).collect();
    let any_id = blocks.iter().any(|b| b.lines().any(|l| l.starts_with("id:")));
    let evs = blocks
        .iter()
        .map(|b| {
            let field = |k: &str| b.lines().find_map(|l| l.strip_prefix(k)).map(|v| v.trim_start().to_owned()).unwrap_or_default();
            (field("event:"), serde_json::from_str(&field("data:")).unwrap_or(serde_json::Value::Null))
        })
        .collect();
    (evs, any_id)
}

fn named<'a>(evs: &'a [(String, serde_json::Value)], name: &str) -> Vec<&'a serde_json::Value> {
    evs.iter().filter(|(n, _)| n == name).map(|(_, d)| d).collect()
}

#[tokio::test]
async fn live_sends_a_snapshot_then_only_what_changed() {
    let h = start().await;
    h.agent.set_live(vec![summary_json("rc-a", "ready")]);
    let mut c = h.client(&h.a).await;
    let (status, mut rx) = c.stream("/v1/live", &[]).await;
    assert_eq!(status, 200);
    let (evs, any_id) = live_events(&collect(&mut rx, Duration::from_millis(1200)).await);
    assert!(!any_id, "snapshots carry no id");
    let sessions = named(&evs, "sessions");
    assert_eq!(sessions.len(), 1, "{evs:?}");
    assert_eq!(*sessions[0], serde_json::json!({ "sessions": [summary_json("rc-a", "ready")] }), "the sessions list without the cap");
    let health = named(&evs, "health");
    assert_eq!(health.len(), 1, "{evs:?}");
    assert_eq!((health[0]["battery_pct"].clone(), health[0]["hostname"].clone(), health[0]["sessions"].clone()), (12.into(), "r1v3r".into(), 1.into()));
    assert!(health[0]["server_time"].as_i64().is_some() && health[0]["fresh_until"].as_i64().is_some(), "the /v1/health body: {}", health[0]);

    // new seq, same list (phases do this): nothing to send, health neither
    h.agent.set_live(vec![summary_json("rc-a", "ready")]);
    let (evs, _) = live_events(&collect(&mut rx, Duration::from_millis(2200)).await);
    assert!(evs.is_empty(), "{evs:?}");

    let t0 = std::time::Instant::now();
    h.agent.set_live(vec![summary_json("rc-a", "ready"), summary_json("rc-b", "starting")]);
    let (evs, _) = live_events(&collect(&mut rx, Duration::from_millis(600)).await);
    let sessions = named(&evs, "sessions");
    assert_eq!(sessions.len(), 1, "{evs:?}");
    assert_eq!(sessions[0]["sessions"][1]["id"], "rc-b");
    println!("sessions change reached the phone within {:?}", t0.elapsed());

    std::fs::write(h.dir.join("power/BAT0/capacity"), "13\n").expect("w");
    let (evs, _) = live_events(&collect(&mut rx, Duration::from_millis(2500)).await);
    let health = named(&evs, "health");
    assert_eq!(health.len(), 1, "{evs:?}");
    assert_eq!(health[0]["battery_pct"], 13);
    assert!(named(&evs, "sessions").is_empty());
}

#[tokio::test]
async fn live_rides_out_an_agent_restart() {
    let h = start().await;
    let mut c = h.client(&h.a).await;
    let (_, mut rx) = c.stream("/v1/live", &[]).await;
    let (evs, _) = live_events(&collect(&mut rx, Duration::from_millis(800)).await);
    assert_eq!(named(&evs, "sessions").first().map(|d| d["sessions"].clone()), Some(serde_json::json!([])));

    h.agent.down.store(true, Ordering::SeqCst);
    h.agent.set_live(vec![summary_json("rc-a", "ready")]);
    let (evs, _) = live_events(&collect(&mut rx, Duration::from_millis(1500)).await);
    assert!(named(&evs, "sessions").is_empty(), "{evs:?}");
    assert_eq!(named(&evs, "health").last().map(|d| d["sessions"].clone()), Some(0.into()), "{evs:?}");

    h.agent.down.store(false, Ordering::SeqCst);
    let (evs, _) = live_events(&collect(&mut rx, Duration::from_millis(2500)).await);
    let sessions = named(&evs, "sessions");
    assert_eq!(sessions.len(), 1, "{evs:?}");
    assert_eq!(sessions[0]["sessions"][0]["id"], "rc-a");
}

#[tokio::test]
async fn live_pings_every_5_seconds() {
    let h = start().await;
    let mut c = h.client(&h.a).await;
    let (_, mut rx) = c.stream("/v1/live", &[]).await;
    let got = collect(&mut rx, Duration::from_millis(5800)).await;
    let pings = got.lines().filter(|l| l.trim_start_matches(':').trim() == "ping" && l.starts_with(':')).count();
    assert_eq!(pings, 1, "{got:?}");
}

#[tokio::test]
async fn live_stops_asking_once_the_phone_is_gone() {
    let h = start().await;
    let mut c = h.client(&h.a).await;
    let (_, mut rx) = c.stream("/v1/live", &[]).await;
    let _ = collect(&mut rx, Duration::from_millis(500)).await;
    drop(rx);
    drop(c);
    // our client only notices the drop on the next chunk, a ping at most 5s away
    tokio::time::sleep(Duration::from_millis(6500)).await;
    let status_calls = || h.agent.seen.lock().expect("l").iter().filter(|r| matches!(r, AgentRequest::Status {})).count();
    let (status_before, lives_before) = (status_calls(), h.agent.lives.load(Ordering::SeqCst));
    h.agent.set_live(vec![summary_json("rc-a", "ready")]);
    tokio::time::sleep(Duration::from_millis(3000)).await;
    assert_eq!(status_calls(), status_before, "no health checks for a phone that left");
    assert_eq!(h.agent.lives.load(Ordering::SeqCst), lives_before, "no more long polls either");
}

#[tokio::test]
async fn live_shares_the_read_budget() {
    let h = start().await;
    let mut c = h.client(&h.a).await;
    for i in 0..60 {
        assert_eq!(c.get("/v1/health").await.status, 200, "{i}");
    }
    assert_eq!(c.get("/v1/live").await.code(), "rate_limited", "costs a read");
}
