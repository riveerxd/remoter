use std::io::{BufRead, Write};
use std::path::{Path, PathBuf};
use std::process::ExitCode;
use std::time::Duration;

use remoter_auth::devices::DeviceRecord;
use remoter_proto::admin::{AdminRequest, PairStarted, PairStatus};
use remoterctl::{admin_call, code_matches};

const USAGE: &str = "usage: remoterctl [--config PATH] <command>

  init [--force]          make the server key and certificate        (sudo)
  refresh                 fetch Google's attestation roots and status (sudo)
  pair --name NAME        pair a phone                               (sudo)
  devices                 list paired phones                          (sudo)
  revoke ID               remove a phone and cut it off               (sudo)
  lock on|off             lock or unlock the laptop                   (sudo)
  log [--verify] [-f]     show, check or follow the audit log         (sudo)
  status                  services and tunnel at a glance
  doctor                  check the whole setup, with fixes";

struct Ctx {
    config_path: PathBuf,
    rd: remoterd::config::Config,
    agent: remoter_agent::config::AgentConfig,
}

fn is_root() -> bool {
    // SAFETY: geteuid has no preconditions.
    unsafe { libc::geteuid() == 0 }
}

fn need_root() -> Result<(), String> {
    if is_root() { Ok(()) } else { Err("this needs sudo".into()) }
}

fn admin(c: &Ctx, req: &AdminRequest) -> Result<serde_json::Value, String> {
    admin_call(&c.rd.paths.admin_socket, req, Duration::from_secs(70))
}

fn main() -> ExitCode {
    let mut args: Vec<String> = std::env::args().skip(1).collect();
    let mut config_path = PathBuf::from("/etc/remoter/config.toml");
    if args.first().map(String::as_str) == Some("--config") {
        if args.len() < 2 {
            eprintln!("{USAGE}");
            return ExitCode::from(2);
        }
        config_path = PathBuf::from(args.remove(1));
        args.remove(0);
    }
    let ctx = match (remoterd::config::Config::load(&config_path), remoter_agent::config::AgentConfig::load(&config_path)) {
        (Ok(rd), Ok(agent)) => Ctx { config_path, rd, agent },
        (Err(e), _) | (_, Err(e)) => {
            eprintln!("remoterctl: {e}");
            return ExitCode::FAILURE;
        }
    };
    let r = match args.iter().map(String::as_str).collect::<Vec<_>>().as_slice() {
        ["init"] => init(&ctx, false),
        ["init", "--force"] => init(&ctx, true),
        ["refresh"] => refresh(&ctx),
        ["pair", "--name", name] => pair(&ctx, name),
        ["devices"] => devices(&ctx),
        ["revoke", id] => revoke(&ctx, id),
        ["lock", "on"] => need_root().and_then(|()| admin(&ctx, &AdminRequest::Lock {})).map(|_| println!("locked")),
        ["lock", "off"] => lock_off(&ctx),
        ["log", rest @ ..] => log(&ctx, rest.contains(&"--verify"), rest.contains(&"-f")),
        ["status"] => status(&ctx),
        ["doctor"] => doctor(&ctx),
        _ => {
            eprintln!("{USAGE}");
            return ExitCode::from(2);
        }
    };
    match r {
        Ok(()) => ExitCode::SUCCESS,
        Err(e) => {
            eprintln!("remoterctl: {e}");
            ExitCode::FAILURE
        }
    }
}

fn init(c: &Ctx, force: bool) -> Result<(), String> {
    need_root()?;
    let gid = remoterctl::gid_of("remoterd").ok_or("no group remoterd; run the installer first")?;
    let fp = remoterctl::init_server_key(&c.rd.paths.server_key, &c.rd.paths.server_cert, gid, c.rd.net.listen_addr, force)?;
    println!("server key written, fingerprint {}", fingerprint_groups(&fp));
    Ok(())
}

fn fingerprint_groups(fp: &[u8]) -> String {
    let hex: String = fp.iter().take(8).map(|b| format!("{b:02X}")).collect();
    hex.as_bytes().chunks(4).map(|c| String::from_utf8_lossy(c).into_owned()).collect::<Vec<_>>().join(" ")
}

fn refresh(c: &Ctx) -> Result<(), String> {
    need_root()?;
    let (roots, status) = remoterctl::fetch_attestation_data()?;
    let r = admin(c, &AdminRequest::SetAttestationData { roots, status })?;
    println!("attestation data updated, {} roots", r["roots"]);
    Ok(())
}

fn pair(c: &Ctx, name: &str) -> Result<(), String> {
    need_root()?;
    // Fails closed: no fresh roots and status, no pairing.
    refresh(c)?;
    let started: PairStarted = serde_json::from_value(admin(c, &AdminRequest::PairStart { name: name.into(), ttl_s: 300 })?).map_err(|e| e.to_string())?;
    println!("{}", remoterctl::pairing_qr(&started.link)?);
    println!("Scan this with remoter, or paste the link:\n\n{}\n\nOpen for 5 minutes, one attempt.", started.link);
    let candidate = loop {
        let s: PairStatus = serde_json::from_value(admin(c, &AdminRequest::PairWait { wait_ms: 5000 })?).map_err(|e| e.to_string())?;
        match s {
            PairStatus::Waiting {} => continue,
            PairStatus::Received { candidate } => break candidate,
            PairStatus::Expired {} => return Err("the pairing window expired; run sudo remoterctl pair again".into()),
            PairStatus::Failed { reason } => return Err(format!("the phone's attempt was refused: {reason}")),
            PairStatus::Done {} => return Err("already paired".into()),
        }
    };
    println!(
        "\nA phone answered:\n  model       {} {}\n  keys        sig {}, tls {}\n  boot        {}\n  boot key    {}\n",
        candidate.manufacturer.as_deref().unwrap_or("?"),
        candidate.model.as_deref().unwrap_or("?"),
        level(candidate.sig_level),
        level(candidate.tls_level),
        candidate.boot_state,
        candidate.boot_key_prefix.to_uppercase(),
    );
    print!("Type the 6 digits the phone shows: ");
    let _ = std::io::stdout().flush();
    let mut typed = String::new();
    // /dev/tty, not stdin: the code has to come from a person at the laptop.
    let tty = std::fs::File::open("/dev/tty").map_err(|e| format!("/dev/tty: {e}"))?;
    std::io::BufReader::new(tty).read_line(&mut typed).map_err(|e| e.to_string())?;
    if !code_matches(&typed, &candidate.code) {
        admin(c, &AdminRequest::PairReject {})?;
        return Err("the code didn't match; nothing was paired".into());
    }
    let id = ulid::Ulid::generate().to_string();
    let record = DeviceRecord {
        id: id.clone(),
        name: candidate.name.clone(),
        tls_spki_sha256: candidate.tls_spki_sha256.clone(),
        sig_pub: candidate.sig_pub.clone(),
        verified_boot_key: candidate.verified_boot_key.clone(),
        attestation: candidate.attestation.clone(),
        paired_at: remoter_proto::local::now_ms(),
    };
    remoterctl::finish_pairing(&c.rd.paths.devices, &mut |r| admin(c, r), record)?;
    println!("Paired {} as {id}.", candidate.name);
    Ok(())
}

fn level(l: i64) -> &'static str {
    match l {
        2 => "StrongBox",
        1 => "TEE",
        _ => "software",
    }
}

fn agent_ids(c: &Ctx) -> Result<(u32, u32), String> {
    remoterctl::user_ids(&c.rd.paths.agent_user).ok_or_else(|| format!("no user {}", c.rd.paths.agent_user))
}

fn agent_unpaired(c: &Ctx) -> PathBuf {
    c.agent.agent_state().join(remoter_agent::server::AGENT_UNPAIRED_FILE)
}

fn devices(c: &Ctx) -> Result<(), String> {
    need_root()?;
    let list = remoterctl::list_devices(&c.rd.paths.devices)?;
    let unpaired = |p: &Path| match remoter_auth::unpaired::read(p) {
        remoter_auth::unpaired::Unpaired::Some(s) => s,
        remoter_auth::unpaired::Unpaired::Unreadable => Default::default(),
    };
    let (theirs, ours) = (unpaired(&c.rd.unpaired_file()), unpaired(&agent_unpaired(c)));
    if list.is_empty() {
        println!("no paired phones");
    }
    for d in list {
        let note = if theirs.contains(&d.id) || ours.contains(&d.id) { "  unpaired from the phone, run revoke to tidy" } else { "" };
        println!("{}  {}  paired {}{note}", d.id, d.name, d.paired_at);
    }
    Ok(())
}

fn revoke(c: &Ctx, id: &str) -> Result<(), String> {
    need_root()?;
    if !remoterctl::remove_device(&c.rd.paths.devices, id)? {
        return Err(format!("no device {id}"));
    }
    remoterctl::forget_unpaired(&c.rd.unpaired_file(), id)?;
    // The agent's list is in a directory the user owns: edited as the user.
    let (uid, gid) = agent_ids(c)?;
    let ours = agent_unpaired(c);
    remoterctl::as_user(uid, gid, || remoterctl::forget_unpaired(&ours, id))?;
    // Reloading cuts the device's live connections and event streams.
    admin(c, &AdminRequest::Reload {})?;
    println!("revoked {id}");
    Ok(())
}

fn lock_off(c: &Ctx) -> Result<(), String> {
    need_root()?;
    admin(c, &AdminRequest::Unlock {})?;
    let (uid, gid) = agent_ids(c)?;
    let state = c.agent.agent_state();
    remoterctl::as_user(uid, gid, || remoter_agent::server::clear_agent_lock(&state).map_err(|e| format!("agent lock: {e}")))?;
    println!("unlocked");
    Ok(())
}

fn log(c: &Ctx, verify: bool, follow: bool) -> Result<(), String> {
    need_root()?;
    let file = c.rd.audit_file();
    if verify {
        let n = remoterd::audit::verify(&file)?;
        let head: serde_json::Value = admin(c, &AdminRequest::AuditHead {})?;
        let disk = remoterd::audit::disk_head(&file)?;
        if head["head"].as_str() != Some(disk.as_str()) {
            return Err(format!("{n} lines chain correctly, but the newest ones are missing: remoterd wrote more than the file holds"));
        }
        println!("audit log intact, {n} lines");
        return Ok(());
    }
    let mut seen = 0usize;
    loop {
        let text = std::fs::read_to_string(&file).unwrap_or_default();
        let lines: Vec<&str> = text.lines().collect();
        if lines.len() < seen {
            seen = 0;
        }
        for l in &lines[seen..] {
            println!("{l}");
        }
        seen = lines.len();
        if !follow {
            return Ok(());
        }
        std::thread::sleep(Duration::from_secs(1));
    }
}

fn status(c: &Ctx) -> Result<(), String> {
    let state = |args: &[&str]| {
        std::process::Command::new("systemctl").args(args).output().map(|o| String::from_utf8_lossy(&o.stdout).trim().to_owned()).unwrap_or_default()
    };
    println!("remoterd        {}", state(&["is-active", "remoterd.service"]));
    println!("remoter-agent   {}", state(&["--user", "is-active", "remoter-agent.service"]));
    println!("firewall        {}", state(&["is-active", "remoter-firewall.service"]));
    println!("locked          {}", c.rd.locked_flag().exists());
    let n = remoterctl::list_devices(&c.rd.paths.devices).map(|d| d.len().to_string()).unwrap_or_else(|e| e);
    println!("paired phones   {n}");
    Ok(())
}

fn doctor(c: &Ctx) -> Result<(), String> {
    // SAFETY: getuid has no preconditions.
    let me = unsafe { libc::getuid() };
    let me = if me == 0 { remoterd::uid_of(&c.rd.paths.agent_user).unwrap_or(0) } else { me };
    let checks = remoterctl::doctor::run(&remoterctl::doctor::Inputs {
        config: &c.config_path,
        agent: &c.agent,
        remoterd: &c.rd,
        remoterd_uid: remoterd::uid_of("remoterd"),
        remoterd_gid: remoterctl::gid_of("remoterd"),
        me,
    });
    let mut bad = 0;
    for ch in &checks {
        if ch.ok {
            println!("ok    {}", ch.name);
        } else {
            bad += 1;
            println!("FAIL  {}  ({})\n      fix: {}", ch.name, ch.detail, ch.fix);
        }
    }
    if bad == 0 { Ok(()) } else { Err(format!("{bad} of {} checks failed", checks.len())) }
}
