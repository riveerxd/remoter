//! TCP listener bound to the tunnel device. `SO_BINDTODEVICE` because the
//! address alone doesn't stop other interfaces (weak host model). The binding
//! follows the ifindex and NetworkManager recreates `rmt0` on every reconnect,
//! so we rebind on netlink link changes.

use std::io;
use std::net::SocketAddr;
use std::os::fd::{AsRawFd, FromRawFd, OwnedFd};
use std::time::Duration;

use socket2::{Domain, Protocol, Socket, Type};
use tokio::sync::mpsc;

pub fn bind(device: &str, addr: SocketAddr) -> io::Result<std::net::TcpListener> {
    let s = Socket::new(Domain::for_address(addr), Type::STREAM, Some(Protocol::TCP))?;
    s.set_reuse_address(true)?;
    s.bind_device(Some(device.as_bytes()))?;
    s.bind(&addr.into())?;
    s.listen(64)?;
    s.set_nonblocking(true)?;
    Ok(s.into())
}

pub fn ifindex(device: &str) -> Option<u32> {
    let c = std::ffi::CString::new(device).ok()?;
    // SAFETY: c is NUL terminated for the call.
    let i = unsafe { libc::if_nametoindex(c.as_ptr()) };
    (i != 0).then_some(i)
}

/// One tick per link/address change. The messages aren't parsed, the listener
/// just looks the device up again. A missed one only costs the next poll.
pub fn watch_links() -> io::Result<mpsc::UnboundedReceiver<()>> {
    // SAFETY: plain socket calls; the fd is owned right after creation.
    let fd = unsafe { libc::socket(libc::AF_NETLINK, libc::SOCK_RAW | libc::SOCK_CLOEXEC, libc::NETLINK_ROUTE) };
    if fd < 0 {
        return Err(io::Error::last_os_error());
    }
    let fd = unsafe { OwnedFd::from_raw_fd(fd) };
    // SAFETY: sockaddr_nl is plain data.
    let mut sa: libc::sockaddr_nl = unsafe { std::mem::zeroed() };
    sa.nl_family = libc::AF_NETLINK as u16;
    sa.nl_groups = (libc::RTMGRP_LINK | libc::RTMGRP_IPV4_IFADDR) as u32;
    let r = unsafe {
        libc::bind(fd.as_raw_fd(), (&sa as *const libc::sockaddr_nl).cast(), size_of::<libc::sockaddr_nl>() as u32)
    };
    if r != 0 {
        return Err(io::Error::last_os_error());
    }
    let (tx, rx) = mpsc::unbounded_channel();
    std::thread::spawn(move || {
        let mut buf = vec![0u8; 16 * 1024];
        loop {
            // SAFETY: buf is writable for its length.
            let n = unsafe { libc::recv(fd.as_raw_fd(), buf.as_mut_ptr().cast(), buf.len(), 0) };
            if n < 0 && io::Error::last_os_error().kind() != io::ErrorKind::Interrupted {
                std::thread::sleep(Duration::from_millis(200));
            }
            if tx.send(()).is_err() {
                return;
            }
        }
    });
    Ok(rx)
}

pub async fn run(device: String, addr: SocketAddr, conns: mpsc::Sender<(tokio::net::TcpStream, SocketAddr)>) {
    let mut links = watch_links().ok();
    loop {
        let (listener, bound_to) = loop {
            if let Some(idx) = ifindex(&device)
                && let Ok(l) = bind(&device, addr)
                && let Ok(l) = tokio::net::TcpListener::from_std(l)
            {
                tracing::info!(%device, idx, %addr, "listening");
                break (l, idx);
            }
            wait_for_change(&mut links, Duration::from_secs(2)).await;
        };
        loop {
            tokio::select! {
                accepted = listener.accept() => {
                    if let Ok(pair) = accepted && conns.send(pair).await.is_err() {
                        return;
                    }
                }
                () = wait_for_change(&mut links, Duration::from_secs(5)) => {
                    if ifindex(&device) != Some(bound_to) {
                        tracing::info!(%device, "link changed, rebinding");
                        break;
                    }
                }
            }
        }
    }
}

async fn wait_for_change(links: &mut Option<mpsc::UnboundedReceiver<()>>, poll: Duration) {
    match links {
        Some(rx) => {
            let _ = tokio::time::timeout(poll, rx.recv()).await;
        }
        None => tokio::time::sleep(poll).await,
    }
}
