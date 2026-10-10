//! remoterctl's logic, apart from the terminal, so it can be tested without
//! root. `pair`, `devices`, `revoke`, `lock`, `log`, `refresh` and `init` need
//! sudo; `status` and `doctor` don't.

pub mod doctor;

use std::io::{Read, Write};
use std::os::unix::fs::PermissionsExt;
use std::path::Path;
use std::time::Duration;

use remoter_auth::devices::{DeviceFile, DeviceRecord};
use remoter_proto::admin::{AdminReply, AdminRequest};

pub const ROOTS_URL: &str = "https://android.googleapis.com/attestation/root";
pub const STATUS_URL: &str = "https://android.googleapis.com/attestation/status";

/// The default render draws the dark modules as blocks, which comes out inverted on
/// a dark terminal, and plenty of phone scanners refuse that. Drawing the light
/// modules (and the quiet zone) as blocks gives a proper light square.
pub fn pairing_qr(link: &str) -> Result<String, String> {
    use qrcode::render::unicode::Dense1x2;
    let qr = qrcode::QrCode::new(link.as_bytes()).map_err(|e| e.to_string())?;
    Ok(qr.render::<Dense1x2>().dark_color(Dense1x2::Light).light_color(Dense1x2::Dark).quiet_zone(true).build())
}

/// Spaces are allowed, as the phone groups the digits; anything else fails.
pub fn code_matches(typed: &str, expected: &str) -> bool {
    let digits: String = typed.chars().filter(|c| !c.is_whitespace()).collect();
    if digits.len() != 6 || !digits.bytes().all(|b| b.is_ascii_digit()) || expected.len() != 6 {
        return false;
    }
    // Compared in full whatever the first difference, so the time taken
    // says nothing about how close a guess was.
    digits.bytes().zip(expected.bytes()).fold(0u8, |acc, (a, b)| acc | (a ^ b)) == 0
}

pub fn admin_call(sock: &Path, req: &AdminRequest, timeout: Duration) -> Result<serde_json::Value, String> {
    let mut s = std::os::unix::net::UnixStream::connect(sock).map_err(|e| format!("{}: {e} (is remoterd running?)", sock.display()))?;
    s.set_read_timeout(Some(timeout)).map_err(|e| e.to_string())?;
    s.write_all(&serde_json::to_vec(req).map_err(|e| e.to_string())?).map_err(|e| e.to_string())?;
    s.shutdown(std::net::Shutdown::Write).map_err(|e| e.to_string())?;
    let mut out = Vec::new();
    s.read_to_end(&mut out).map_err(|e| e.to_string())?;
    match serde_json::from_slice::<AdminReply>(&out) {
        Ok(AdminReply::Ok(v)) => Ok(v),
        Ok(AdminReply::Err(e)) => Err(e),
        Err(_) => Err("remoterd refused the admin request (run as root)".into()),
    }
}

fn read_devices(path: &Path) -> Result<DeviceFile, String> {
    match std::fs::read(path) {
        Ok(b) => serde_json::from_slice(&b).map_err(|e| format!("{}: {e}", path.display())),
        Err(e) if e.kind() == std::io::ErrorKind::NotFound => Ok(DeviceFile::default()),
        Err(e) => Err(format!("{}: {e}", path.display())),
    }
}

/// Root owned and 0644: both daemons read it, only root writes it.
fn write_devices(path: &Path, f: &DeviceFile) -> Result<(), String> {
    let json = serde_json::to_vec_pretty(f).map_err(|e| e.to_string())?;
    // Parsed back through the daemons' own loader before it replaces the
    // file, so a bad record can never lock both daemons out of every device.
    remoter_auth::Devices::parse(&json)?;
    remoter_proto::local::write_atomic(path, &json).map_err(|e| format!("{}: {e}", path.display()))?;
    std::fs::set_permissions(path, std::fs::Permissions::from_mode(0o644)).map_err(|e| e.to_string())
}

/// None for a clean phone.
pub fn not_secure(w: &[remoter_proto::admin::Weakness]) -> Option<String> {
    (!w.is_empty()).then(|| format!("not secure: {}", w.iter().map(|w| w.says()).collect::<Vec<_>>().join(", ")))
}

pub fn list_devices(path: &Path) -> Result<Vec<DeviceRecord>, String> {
    Ok(read_devices(path)?.devices)
}

pub fn add_device(path: &Path, r: DeviceRecord) -> Result<(), String> {
    let mut f = read_devices(path)?;
    if f.devices.iter().any(|d| d.id == r.id || d.tls_spki_sha256 == r.tls_spki_sha256) {
        return Err("that device or key is already paired".into());
    }
    f.devices.push(r);
    write_devices(path, &f)
}

pub fn remove_device(path: &Path, id: &str) -> Result<bool, String> {
    let mut f = read_devices(path)?;
    let before = f.devices.len();
    f.devices.retain(|d| d.id != id);
    let removed = f.devices.len() != before;
    if removed {
        write_devices(path, &f)?;
    }
    Ok(removed)
}

/// End of `remoterctl pair`, after the typed code matched. If remoterd can't
/// confirm to the phone, the record comes back out: a phone that never got its
/// device id must not stay in the device file.
pub fn finish_pairing(
    devices: &Path,
    admin: &mut dyn FnMut(&AdminRequest) -> Result<serde_json::Value, String>,
    record: DeviceRecord,
) -> Result<(), String> {
    let status = admin(&AdminRequest::PairWait { wait_ms: 0 })?;
    if status.get("state").and_then(|s| s.as_str()) != Some("received") {
        return Err("the pairing window closed before the code was typed; nothing was paired".into());
    }
    let id = record.id.clone();
    add_device(devices, record)?;
    if let Err(e) = admin(&AdminRequest::PairConfirm { device_id: id.clone() }) {
        return match remove_device(devices, &id) {
            Ok(_) => Err(format!("{e}; nothing was paired")),
            Err(r) => Err(format!("{e}; and taking {id} back out of the device file failed: {r}, run sudo remoterctl revoke {id}")),
        };
    }
    Ok(())
}

/// The agent's list sits in a dir your uid owns and this runs as root, so
/// nothing follows a link: `O_NOFOLLOW` read, plain files only, mode set on the
/// new fd before the rename. main.rs also drops to the agent's user for that one.
pub fn forget_unpaired(path: &Path, id: &str) -> Result<(), String> {
    use std::os::unix::fs::{MetadataExt, OpenOptionsExt};
    let mut f = match std::fs::OpenOptions::new().read(true).custom_flags(libc::O_NOFOLLOW).open(path) {
        Ok(f) => f,
        Err(e) if e.kind() == std::io::ErrorKind::NotFound => return Ok(()),
        Err(e) => return Err(format!("{}: {e}", path.display())),
    };
    let meta = f.metadata().map_err(|e| e.to_string())?;
    if !meta.file_type().is_file() {
        return Err(format!("{} isn't a plain file, leaving it alone", path.display()));
    }
    let mut bytes = Vec::new();
    f.read_to_end(&mut bytes).map_err(|e| e.to_string())?;
    let Ok(list) = serde_json::from_slice::<remoter_auth::unpaired::UnpairedFile>(&bytes) else {
        return Ok(());
    };
    if !list.devices.iter().any(|d| d == id) {
        return Ok(());
    }
    let mut v: Vec<String> = list.devices.into_iter().filter(|d| d != id).collect();
    v.sort();
    v.dedup();
    let json = serde_json::to_vec(&remoter_auth::unpaired::UnpairedFile { devices: v }).map_err(|e| e.to_string())?;
    let tmp = path.with_extension("tmp");
    let _ = std::fs::remove_file(&tmp);
    // create_new is O_EXCL, which never follows a link at the last step.
    let mut out = std::fs::OpenOptions::new().write(true).create_new(true).mode(0o600).open(&tmp).map_err(|e| format!("{}: {e}", tmp.display()))?;
    out.write_all(&json).map_err(|e| e.to_string())?;
    out.set_permissions(std::fs::Permissions::from_mode(meta.mode() & 0o777)).map_err(|e| e.to_string())?;
    out.sync_all().map_err(|e| e.to_string())?;
    std::fs::rename(&tmp, path).map_err(|e| e.to_string())
}

/// Runs `f` in a child dropped to `uid`/`gid`, for anything root does in a dir
/// the user owns. Without root (tests) it just runs `f`.
pub fn as_user(uid: u32, gid: u32, f: impl FnOnce() -> Result<(), String>) -> Result<(), String> {
    // SAFETY: geteuid has no preconditions.
    if unsafe { libc::geteuid() } != 0 {
        return f();
    }
    // SAFETY: remoterctl is single threaded here (its runtimes are built and
    // dropped per call), so the child may run ordinary code.
    match unsafe { libc::fork() } {
        -1 => Err(format!("fork: {}", std::io::Error::last_os_error())),
        0 => {
            // SAFETY: plain credential syscalls; the order drops groups and
            // gid while still root, then the uid, and checks it stuck.
            let dropped = unsafe {
                libc::setgroups(0, std::ptr::null()) == 0
                    && libc::setresgid(gid, gid, gid) == 0
                    && libc::setresuid(uid, uid, uid) == 0
                    && libc::geteuid() == uid
                    && libc::getegid() == gid
            };
            let code = if !dropped {
                eprintln!("remoterctl: couldn't drop to uid {uid}");
                2
            } else {
                match f() {
                    Ok(()) => 0,
                    Err(e) => {
                        eprintln!("remoterctl: {e}");
                        1
                    }
                }
            };
            // SAFETY: ends the child without running the parent's exit hooks.
            unsafe { libc::_exit(code) }
        }
        pid => {
            let mut status = 0;
            // SAFETY: waits for the child forked just above.
            if unsafe { libc::waitpid(pid, &mut status, 0) } != pid {
                return Err(format!("waitpid: {}", std::io::Error::last_os_error()));
            }
            if libc::WIFEXITED(status) && libc::WEXITSTATUS(status) == 0 { Ok(()) } else { Err("the step run as the agent's user failed".into()) }
        }
    }
}

/// uid and primary gid of a user.
pub fn user_ids(name: &str) -> Option<(u32, u32)> {
    let c = std::ffi::CString::new(name).ok()?;
    let mut buf = vec![0u8; 16 * 1024];
    // SAFETY: pw and buf outlive the call; out is set by getpwnam_r.
    let mut pw: libc::passwd = unsafe { std::mem::zeroed() };
    let mut out: *mut libc::passwd = std::ptr::null_mut();
    let r = unsafe { libc::getpwnam_r(c.as_ptr(), &mut pw, buf.as_mut_ptr().cast(), buf.len(), &mut out) };
    (r == 0 && !out.is_null()).then_some((pw.pw_uid, pw.pw_gid))
}

pub fn gid_of(group: &str) -> Option<u32> {
    let c = std::ffi::CString::new(group).ok()?;
    let mut buf = vec![0u8; 16 * 1024];
    // SAFETY: grp and buf outlive the call; out is set by getgrnam_r.
    let mut grp: libc::group = unsafe { std::mem::zeroed() };
    let mut out: *mut libc::group = std::ptr::null_mut();
    let r = unsafe { libc::getgrnam_r(c.as_ptr(), &mut grp, buf.as_mut_ptr().cast(), buf.len(), &mut out) };
    (r == 0 && !out.is_null()).then_some(grp.gr_gid)
}

/// The server key and a self signed certificate for it. The key is
/// root:remoterd 0640, so remoterd reads it and your uid can't.
pub fn init_server_key(key: &Path, cert: &Path, gid: u32, ip: std::net::Ipv4Addr, force: bool) -> Result<[u8; 32], String> {
    if !force && (key.exists() || cert.exists()) {
        return Err(format!("{} already exists; --force replaces it and every phone must pair again", key.display()));
    }
    let kp = rcgen::KeyPair::generate_for(&rcgen::PKCS_ECDSA_P256_SHA256).map_err(|e| e.to_string())?;
    let mut params = rcgen::CertificateParams::new(Vec::<String>::new()).map_err(|e| e.to_string())?;
    params.distinguished_name.push(rcgen::DnType::CommonName, "remoterd");
    params.subject_alt_names = vec![rcgen::SanType::IpAddress(ip.into())];
    params.not_before = rcgen::date_time_ymd(2026, 1, 1);
    params.not_after = rcgen::date_time_ymd(2046, 1, 1);
    let c = params.self_signed(&kp).map_err(|e| e.to_string())?;
    let pem = |label: &str, der: &[u8]| {
        let b64 = std_b64(der);
        let body: Vec<String> = b64.as_bytes().chunks(64).map(|l| String::from_utf8_lossy(l).into_owned()).collect();
        format!("-----BEGIN {label}-----\n{}\n-----END {label}-----\n", body.join("\n"))
    };
    let tmp_key = key.with_extension("tmp");
    let _ = std::fs::remove_file(&tmp_key);
    {
        use std::os::unix::fs::OpenOptionsExt;
        let mut f = std::fs::OpenOptions::new().write(true).create_new(true).mode(0o600).open(&tmp_key).map_err(|e| e.to_string())?;
        f.write_all(pem("PRIVATE KEY", &kp.serialize_der()).as_bytes()).map_err(|e| e.to_string())?;
        f.sync_all().map_err(|e| e.to_string())?;
    }
    let ck = std::ffi::CString::new(tmp_key.as_os_str().as_encoded_bytes()).map_err(|e| e.to_string())?;
    // SAFETY: plain chown on a path we just created.
    if unsafe { libc::chown(ck.as_ptr(), 0, gid) } != 0 {
        let _ = std::fs::remove_file(&tmp_key);
        return Err(format!("chown: {}", std::io::Error::last_os_error()));
    }
    std::fs::set_permissions(&tmp_key, std::fs::Permissions::from_mode(0o640)).map_err(|e| e.to_string())?;
    std::fs::rename(&tmp_key, key).map_err(|e| e.to_string())?;
    remoter_proto::local::write_atomic(cert, pem("CERTIFICATE", c.der()).as_bytes()).map_err(|e| e.to_string())?;
    std::fs::set_permissions(cert, std::fs::Permissions::from_mode(0o644)).map_err(|e| e.to_string())?;
    remoterd::tls::spki_sha256(c.der()).ok_or_else(|| "certificate doesn't parse".into())
}

fn std_b64(b: &[u8]) -> String {
    const A: &[u8; 64] = b"ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
    let mut s = String::new();
    for c in b.chunks(3) {
        let n = (c[0] as u32) << 16 | (*c.get(1).unwrap_or(&0) as u32) << 8 | *c.get(2).unwrap_or(&0) as u32;
        for i in 0..4 {
            s.push(if i <= c.len() { A[((n >> (18 - 6 * i)) & 63) as usize] as char } else { '=' });
        }
    }
    s
}

/// Any failure is an error: nothing pairs or re-attests on partial data.
pub fn fetch_attestation_data() -> Result<(String, String), String> {
    let rt = tokio::runtime::Builder::new_current_thread().enable_all().build().map_err(|e| e.to_string())?;
    rt.block_on(async {
        let https = hyper_rustls::HttpsConnectorBuilder::new()
            .with_webpki_roots()
            .https_only()
            .enable_http1()
            .build();
        let client: hyper_util::client::legacy::Client<_, axum::body::Body> =
            hyper_util::client::legacy::Client::builder(hyper_util::rt::TokioExecutor::new()).build(https);
        let get = |url: &'static str| {
            let client = client.clone();
            async move {
                let req = hyper::Request::get(url).body(axum::body::Body::empty()).map_err(|e| e.to_string())?;
                let res = tokio::time::timeout(Duration::from_secs(20), client.request(req)).await.map_err(|_| format!("{url}: timed out"))?.map_err(|e| format!("{url}: {e}"))?;
                if !res.status().is_success() {
                    return Err(format!("{url}: HTTP {}", res.status()));
                }
                let body = axum::body::to_bytes(axum::body::Body::new(res.into_body()), 32 << 20).await.map_err(|e| format!("{url}: {e}"))?;
                String::from_utf8(body.to_vec()).map_err(|_| format!("{url}: not UTF-8"))
            }
        };
        let roots = get(ROOTS_URL).await?;
        let status = get(STATUS_URL).await?;
        // checked here too, so a bad download fails before remoterd sees it
        check_download(&roots, &status)?;
        Ok((roots, status))
    })
}

fn check_download(roots: &str, status: &str) -> Result<(), String> {
    let v: serde_json::Value = serde_json::from_str(roots).map_err(|e| format!("roots: {e}"))?;
    if v.as_array().is_none_or(|a| a.is_empty()) {
        return Err("roots: not a non-empty list".into());
    }
    remoter_attest::Roots::from_json(roots.as_bytes())
        .and_then(|r| r.pinned(&remoter_attest::GOOGLE_ROOT_SPKI_SHA256))
        .map_err(|e| format!("{e}; refusing this download"))?;
    let s: serde_json::Value = serde_json::from_str(status).map_err(|e| format!("status: {e}"))?;
    if !s.get("entries").is_some_and(|e| e.is_object()) {
        return Err("status: no entries".into());
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    use remoter_proto::admin::PairStatus;

    #[test]
    fn pairing_qr_draws_the_quiet_zone() {
        let qr = pairing_qr("remoter://pair?x=1").expect("qr");
        let top = qr.lines().next().expect("a line");
        // The quiet zone is light, so on a dark terminal it must be drawn, not left blank.
        assert!(top.chars().all(|c| c == '\u{2588}'), "top row was {top:?}");
    }

    #[test]
    fn not_secure_lines() {
        use remoter_proto::admin::Weakness;
        assert_eq!(not_secure(&[]), None);
        assert_eq!(not_secure(&[Weakness::BootloaderUnlocked, Weakness::BootNotVerified]).as_deref(), Some("not secure: bootloader unlocked, boot not verified"));
    }

    fn record(id: &str) -> DeviceRecord {
        // A record add_device accepts, so every case below really writes.
        remoter_auth::testkit::Phone::new(id, 1, b"tls".to_vec()).record()
    }

    fn devices_file(tag: &str) -> std::path::PathBuf {
        let d = std::env::temp_dir().join(format!("remoterctl-finish-{tag}-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&d);
        std::fs::create_dir_all(&d).expect("mk");
        let f = d.join("devices.json");
        std::fs::write(&f, b"{\"devices\":[]}").expect("w");
        f
    }

    #[test]
    fn failed_confirm_leaves_nothing() {
        const ID: &str = "01K6B7Y3M4N5P6Q7R8S9T0V1W2";
        // The window closed while the code was being typed.
        let f = devices_file("late");
        let mut expired = |r: &AdminRequest| match r {
            AdminRequest::PairWait { .. } => Ok(serde_json::to_value(PairStatus::Expired {}).expect("j")),
            _ => Err("nothing to confirm".into()),
        };
        assert!(finish_pairing(&f, &mut expired, record(ID)).is_err());
        assert!(list_devices(&f).expect("read").is_empty(), "an expired window pairs nothing");

        // Still open, but the confirm itself fails (the phone gave up).
        let f = devices_file("gaveup");
        let received = serde_json::json!({ "state": "received", "candidate": null });
        let mut gave_up = |r: &AdminRequest| match r {
            AdminRequest::PairWait { .. } => Ok(received.clone()),
            _ => Err("the phone gave up waiting".into()),
        };
        assert!(finish_pairing(&f, &mut gave_up, record(ID)).is_err());
        assert!(list_devices(&f).expect("read").is_empty(), "the record is taken back out");

        // The normal case still pairs.
        let f = devices_file("ok");
        let mut ok = |r: &AdminRequest| match r {
            AdminRequest::PairWait { .. } => Ok(received.clone()),
            _ => Ok(serde_json::json!({})),
        };
        finish_pairing(&f, &mut ok, record(ID)).expect("pairs");
        assert_eq!(list_devices(&f).expect("read").len(), 1);
    }

    #[test]
    fn typed_codes() {
        assert!(code_matches("481207", "481207"));
        assert!(code_matches("481 207", "481207"), "grouped as the phone shows it");
        assert!(code_matches(" 481207\n", "481207"));
        for bad in ["481208", "48120", "4812070", "y", "", "48120a", "481-207"] {
            assert!(!code_matches(bad, "481207"), "{bad:?}");
        }
    }

    #[test]
    fn device_edits_checked_before_write() {
        let dir = std::env::temp_dir().join(format!("remoterctl-dev-{}", std::process::id()));
        std::fs::create_dir_all(&dir).expect("mk");
        let f = dir.join("devices.json");
        let phone = remoter_auth::testkit::Phone::new("01K6B7Y3M4N5P6Q7R8S9T0V1W2", 1, b"tls".to_vec());
        add_device(&f, phone.record()).expect("add");
        assert_eq!(list_devices(&f).expect("list").len(), 1);
        assert!(add_device(&f, phone.record()).is_err(), "no duplicates");
        let mut broken = phone.record();
        broken.id = "01K6B7Y3M4N5P6Q7R8S9T0V1W3".into();
        broken.tls_spki_sha256 = "x".into();
        broken.sig_pub = "not a key".into();
        assert!(add_device(&f, broken).is_err(), "a record the daemons can't load is never written");
        assert_eq!(list_devices(&f).expect("list").len(), 1);
        assert_eq!(std::fs::metadata(&f).expect("m").permissions().mode() & 0o777, 0o644);
        assert!(remove_device(&f, &phone.id).expect("rm"));
        assert!(!remove_device(&f, &phone.id).expect("rm"));
        let u = dir.join("unpaired.json");
        std::fs::write(&u, format!(r#"{{"devices":["{}","01K6B7Y3M4N5P6Q7R8S9T0V1W9"]}}"#, phone.id)).expect("w");
        forget_unpaired(&u, &phone.id).expect("forget");
        assert!(!std::fs::read_to_string(&u).expect("r").contains(&phone.id));
        let _ = std::fs::remove_dir_all(&dir);
    }

    #[test]
    fn server_key_is_group_readable_only() {
        let dir = std::env::temp_dir().join(format!("remoterctl-key-{}", std::process::id()));
        std::fs::create_dir_all(&dir).expect("mk");
        // SAFETY: getgid has no preconditions. As a normal user the key can
        // only go to our own group; root uses remoterd's.
        let gid = unsafe { libc::getgid() };
        let r = init_server_key(&dir.join("server.key"), &dir.join("server.crt"), gid, std::net::Ipv4Addr::new(10, 66, 66, 3), false);
        // chown to uid 0 fails without root; that's the only expected error.
        match r {
            Ok(_) => {
                assert_eq!(std::fs::metadata(dir.join("server.key")).expect("m").permissions().mode() & 0o777, 0o640);
                assert!(remoterd::tls::load_pem(&dir.join("server.crt"), &dir.join("server.key")).is_ok());
            }
            Err(e) => assert!(e.starts_with("chown"), "{e}"),
        }
        assert!(init_server_key(&dir.join("server.key"), &dir.join("server.crt"), gid, std::net::Ipv4Addr::LOCALHOST, false).is_err() || !dir.join("server.key").exists());
        let _ = std::fs::remove_dir_all(&dir);
    }
}
