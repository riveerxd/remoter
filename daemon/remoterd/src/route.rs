//! Which tunnel the phone comes in on. NetworkManager adds a route per peer
//! AllowedIPs, so rmt0 has one to the hub's address only when the hub is its
//! peer. Read once per health answer. Only the phone's picture uses it, no
//! check here depends on it.

use std::io;
use std::net::Ipv4Addr;
use std::os::fd::{AsRawFd, FromRawFd, OwnedFd};
use std::time::Duration;

use remoter_proto::api::Tunnel;

pub const HUB_ADDR: Ipv4Addr = Ipv4Addr::new(10, 66, 66, 1);

const NLMSG_HDR: usize = 16;
const RTMSG: usize = 12;
const RTA_DST: u16 = 1;
const RTA_OIF: u16 = 4;
const RTA_TABLE: u16 = 15;
const RT_TABLE_MAIN: u32 = 254;

/// `None` when there's no such device or the routes can't be read.
pub fn tunnel(device: &str) -> Option<Tunnel> {
    let oif = crate::listener::ifindex(device)?;
    tunnel_in(&dump().ok()?, oif)
}

pub fn tunnel_in(dump: &[u8], oif: u32) -> Option<Tunnel> {
    let routes = parse(dump)?;
    let hub = routes.iter().any(|r| r.table == RT_TABLE_MAIN && r.oif == Some(oif) && r.covers(HUB_ADDR));
    Some(if hub { Tunnel::Hub } else { Tunnel::Direct })
}

#[derive(Debug, PartialEq)]
struct Route {
    dst: Ipv4Addr,
    len: u8,
    oif: Option<u32>,
    table: u32,
}

impl Route {
    fn covers(&self, a: Ipv4Addr) -> bool {
        // a default route on rmt0 would mean everything goes there, which says nothing about a hub
        if self.len == 0 || self.len > 32 {
            return false;
        }
        let mask = u32::MAX << (32 - u32::from(self.len));
        u32::from(self.dst) & mask == u32::from(a) & mask
    }
}

fn u16_at(b: &[u8], i: usize) -> Option<u16> {
    Some(u16::from_ne_bytes(b.get(i..i + 2)?.try_into().ok()?))
}

fn u32_at(b: &[u8], i: usize) -> Option<u32> {
    Some(u32::from_ne_bytes(b.get(i..i + 4)?.try_into().ok()?))
}

/// IPv4 routes out of a dump. Anything malformed spoils the whole answer.
fn parse(mut b: &[u8]) -> Option<Vec<Route>> {
    let mut out = Vec::new();
    while !b.is_empty() {
        let len = u32_at(b, 0)? as usize;
        let kind = u16_at(b, 4)?;
        if len < NLMSG_HDR || len > b.len() {
            return None;
        }
        let msg = &b[..len];
        match kind as i32 {
            libc::NLMSG_DONE => break,
            libc::NLMSG_ERROR => return None,
            k if k == libc::RTM_NEWROUTE as i32 => {
                let rt = msg.get(NLMSG_HDR..NLMSG_HDR + RTMSG)?;
                if i32::from(rt[0]) == libc::AF_INET {
                    let mut r = Route { dst: Ipv4Addr::UNSPECIFIED, len: rt[1], oif: None, table: u32::from(rt[4]) };
                    let mut a = NLMSG_HDR + RTMSG;
                    while a + 4 <= len {
                        let alen = u16_at(msg, a)? as usize;
                        let atype = u16_at(msg, a + 2)?;
                        if alen < 4 || a + alen > len {
                            return None;
                        }
                        let v = &msg[a + 4..a + alen];
                        match atype {
                            RTA_DST => r.dst = Ipv4Addr::from(<[u8; 4]>::try_from(v).ok()?),
                            RTA_OIF => r.oif = Some(u32_at(v, 0)?),
                            RTA_TABLE => r.table = u32_at(v, 0)?,
                            _ => {}
                        }
                        a += (alen + 3) & !3;
                    }
                    out.push(r);
                }
            }
            _ => {}
        }
        b = &b[((len + 3) & !3).min(b.len())..];
    }
    Some(out)
}

fn dump() -> io::Result<Vec<u8>> {
    // SAFETY: plain socket call; the fd is owned right after.
    let fd = unsafe { libc::socket(libc::AF_NETLINK, libc::SOCK_RAW | libc::SOCK_CLOEXEC, libc::NETLINK_ROUTE) };
    if fd < 0 {
        return Err(io::Error::last_os_error());
    }
    let fd = unsafe { OwnedFd::from_raw_fd(fd) };
    let tv = libc::timeval { tv_sec: 0, tv_usec: 300_000 };
    // SAFETY: tv outlives the call.
    unsafe { libc::setsockopt(fd.as_raw_fd(), libc::SOL_SOCKET, libc::SO_RCVTIMEO, (&tv as *const libc::timeval).cast(), size_of::<libc::timeval>() as u32) };

    let mut req = Vec::with_capacity(NLMSG_HDR + RTMSG);
    req.extend_from_slice(&((NLMSG_HDR + RTMSG) as u32).to_ne_bytes());
    req.extend_from_slice(&libc::RTM_GETROUTE.to_ne_bytes());
    req.extend_from_slice(&((libc::NLM_F_REQUEST | libc::NLM_F_DUMP) as u16).to_ne_bytes());
    req.extend_from_slice(&1u32.to_ne_bytes());
    req.extend_from_slice(&0u32.to_ne_bytes());
    req.push(libc::AF_INET as u8);
    req.extend_from_slice(&[0; RTMSG - 1]);
    // SAFETY: req is readable for its length.
    if unsafe { libc::send(fd.as_raw_fd(), req.as_ptr().cast(), req.len(), 0) } < 0 {
        return Err(io::Error::last_os_error());
    }

    let mut all = Vec::new();
    let mut buf = vec![0u8; 32 * 1024];
    let deadline = std::time::Instant::now() + Duration::from_secs(1);
    loop {
        // SAFETY: buf is writable for its length.
        let n = unsafe { libc::recv(fd.as_raw_fd(), buf.as_mut_ptr().cast(), buf.len(), 0) };
        if n < 0 {
            return Err(io::Error::last_os_error());
        }
        let got = &buf[..n as usize];
        all.extend_from_slice(got);
        if ends_dump(got) || std::time::Instant::now() > deadline {
            return Ok(all);
        }
    }
}

fn ends_dump(mut b: &[u8]) -> bool {
    while let (Some(len), Some(kind)) = (u32_at(b, 0), u16_at(b, 4)) {
        if kind as i32 == libc::NLMSG_DONE || kind as i32 == libc::NLMSG_ERROR {
            return true;
        }
        let step = (len as usize + 3) & !3;
        if step == 0 || step > b.len() {
            return false;
        }
        b = &b[step..];
    }
    false
}

#[cfg(test)]
mod tests {
    use super::*;

    fn hex(s: &str) -> Vec<u8> {
        (0..s.len()).step_by(2).map(|i| u8::from_str_radix(&s[i..i + 2], 16).expect("hex")).collect()
    }

    // from this laptop in hub mode, rmt0 is ifindex 4
    const DEFAULT_VIA_ENO1: &str = "440000001800020001000000aab40a0002000000fe1000010000000008000f00fe000000080006006400000008000700c0a8004408000500c0a800010800040002000000";
    const TO_HUB: &str = "3c0000001800020001000000aab40a0002200000fe04fd010000000008000f00fe000000080001000a42420108000600320000000800040004000000";
    const TO_PHONE: &str = "3c0000001800020001000000aab40a0002200000fe04fd010000000008000f00fe000000080001000a42420208000600320000000800040004000000";
    const DONE: &str = "140000000300020001000000aab40a0000000000";

    fn dump_of(parts: &[&str]) -> Vec<u8> {
        parts.iter().flat_map(|p| hex(p)).collect()
    }

    #[test]
    fn hub_route_means_hub() {
        assert_eq!(tunnel_in(&dump_of(&[DEFAULT_VIA_ENO1, TO_HUB, TO_PHONE, DONE]), 4), Some(Tunnel::Hub));
    }

    #[test]
    fn only_the_phone_means_direct() {
        assert_eq!(tunnel_in(&dump_of(&[DEFAULT_VIA_ENO1, TO_PHONE, DONE]), 4), Some(Tunnel::Direct));
    }

    #[test]
    fn hub_route_elsewhere_is_still_direct() {
        assert_eq!(tunnel_in(&dump_of(&[TO_HUB, TO_PHONE, DONE]), 7), Some(Tunnel::Direct));
    }

    #[test]
    fn default_route_on_rmt0_isnt_a_hub() {
        // the eno1 default with its oif swapped for rmt0's
        let mut d = hex(DEFAULT_VIA_ENO1);
        let n = d.len();
        d[n - 4] = 4;
        assert_eq!(tunnel_in(&[d, hex(DONE)].concat(), 4), Some(Tunnel::Direct));
    }

    #[test]
    fn broken_dumps_say_nothing() {
        let good = dump_of(&[TO_HUB, DONE]);
        for cut in 1..good.len() - hex(DONE).len() {
            let mut b = good[..cut].to_vec();
            b.extend_from_slice(&hex(DONE));
            let _ = tunnel_in(&b, 4);
        }
        assert_eq!(tunnel_in(&[0xff; 64], 4), None);
        let mut bad_attr = hex(TO_HUB);
        bad_attr[28] = 0xff;
        assert_eq!(tunnel_in(&bad_attr, 4), None);
        let err = "240000000200000001000000aab40a00fdffffff1c0000001a00010301000000aab40a00";
        assert_eq!(tunnel_in(&hex(err), 4), None);
    }

    #[test]
    fn reads_this_machine() {
        // whatever the laptop has, reading it must not fail or hang
        let t0 = std::time::Instant::now();
        let d = dump().expect("dump");
        assert!(ends_dump(&d) && parse(&d).is_some());
        assert!(t0.elapsed() < Duration::from_secs(2));
    }
}
