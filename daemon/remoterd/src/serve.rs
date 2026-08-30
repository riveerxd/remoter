//! One task per connection, until it ends or its device gets unpaired.

use std::net::SocketAddr;
use std::sync::Arc;
use std::time::{Duration, Instant};

use axum::Extension;
use hyper_util::rt::{TokioExecutor, TokioIo};
use hyper_util::service::TowerToHyperService;
use rustls::ServerConfig;
use tokio::net::TcpStream;
use tokio::sync::mpsc;
use tokio_rustls::TlsAcceptor;

use crate::http::{Peer, router};
use crate::state::{App, lock};
use crate::tls::spki_sha256;

const HANDSHAKE_TIMEOUT: Duration = Duration::from_secs(10);

pub async fn serve(app: Arc<App>, tls: Arc<ServerConfig>, mut conns: mpsc::Receiver<(TcpStream, SocketAddr)>) {
    let acceptor = TlsAcceptor::from(tls);
    let router = router(app.clone());
    while let Some((tcp, addr)) = conns.recv().await {
        let (app, acceptor, router) = (app.clone(), acceptor.clone(), router.clone());
        tokio::spawn(async move { connection(app, acceptor, router, tcp, addr).await });
    }
}

/// Once a minute per source at most. Garbage from strangers never counts toward the lock.
fn log_handshake(app: &App, addr: SocketAddr, why: &str) {
    let mut seen = lock(&app.handshake_log);
    let now = Instant::now();
    if seen.get(&addr.ip()).is_none_or(|t| now.duration_since(*t) > Duration::from_secs(60)) {
        seen.insert(addr.ip(), now);
        tracing::info!(%addr, why, "handshake refused");
    }
    if seen.len() > 1024 {
        seen.retain(|_, t| now.duration_since(*t) < Duration::from_secs(60));
    }
}

async fn connection(app: Arc<App>, acceptor: TlsAcceptor, router: axum::Router, tcp: TcpStream, addr: SocketAddr) {
    let tls = match tokio::time::timeout(HANDSHAKE_TIMEOUT, acceptor.accept(tcp)).await {
        Ok(Ok(t)) => t,
        Ok(Err(e)) => return log_handshake(&app, addr, &e.to_string()),
        Err(_) => return log_handshake(&app, addr, "timed out"),
    };
    let hash = {
        let (_, conn) = tls.get_ref();
        conn.peer_certificates().and_then(|c| c.first()).and_then(|c| spki_sha256(c))
    };
    let Some(hash) = hash else { return log_handshake(&app, addr, "no client certificate") };
    let mut devices = app.devices.subscribe();
    let found = devices.borrow().by_tls_hash(&hash).map(|d| d.record.id.clone());
    let Some(device) = found else {
        return log_handshake(&app, addr, "device gone after handshake");
    };
    let svc = router.layer(Extension(Peer { device: device.clone(), tls_hash: hash }));
    let conn = hyper::server::conn::http2::Builder::new(TokioExecutor::new())
        .serve_connection(TokioIo::new(tls), TowerToHyperService::new(svc));
    tokio::select! {
        r = conn => {
            if let Err(e) = r {
                tracing::debug!(%device, %e, "connection ended");
            }
        }
        () = revoked(&mut devices, hash) => {
            tracing::info!(%device, "revoked, closing its connection");
        }
    }
}

async fn revoked(devices: &mut tokio::sync::watch::Receiver<Arc<remoter_auth::Devices>>, hash: [u8; 32]) {
    loop {
        if !devices.borrow_and_update().has_tls_hash(&hash) {
            return;
        }
        if devices.changed().await.is_err() {
            std::future::pending::<()>().await;
        }
    }
}
