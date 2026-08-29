//! TLS 1.3 with pinned keys on both sides. No CA anywhere.

use std::sync::Arc;

use remoter_auth::Devices;
use rustls::DistinguishedName;
use rustls::client::danger::HandshakeSignatureValid;
use rustls::crypto::{CryptoProvider, aws_lc_rs as aws};
use rustls::pki_types::{CertificateDer, PrivateKeyDer, UnixTime};
use rustls::server::danger::{ClientCertVerified, ClientCertVerifier};
use rustls::{DigitallySignedStruct, Error, ServerConfig, SignatureScheme};
use sha2::{Digest, Sha256};
use tokio::sync::watch;

/// Enforced here because the phone's Conscrypt can't be told.
pub fn provider() -> CryptoProvider {
    let mut p = aws::default_provider();
    p.cipher_suites = vec![aws::cipher_suite::TLS13_AES_256_GCM_SHA384, aws::cipher_suite::TLS13_CHACHA20_POLY1305_SHA256];
    p.kx_groups = vec![aws::kx_group::X25519MLKEM768, aws::kx_group::X25519];
    p
}

pub fn spki_sha256(cert_der: &[u8]) -> Option<[u8; 32]> {
    let (_, cert) = x509_parser::parse_x509_certificate(cert_der).ok()?;
    Some(Sha256::digest(cert.tbs_certificate.subject_pki.raw).into())
}

#[derive(Debug)]
pub struct PinnedDevices {
    devices: watch::Receiver<Arc<Devices>>,
    algs: rustls::crypto::WebPkiSupportedAlgorithms,
}

impl PinnedDevices {
    pub fn new(devices: watch::Receiver<Arc<Devices>>) -> PinnedDevices {
        PinnedDevices { devices, algs: provider().signature_verification_algorithms }
    }
}

impl ClientCertVerifier for PinnedDevices {
    fn offer_client_auth(&self) -> bool {
        true
    }

    fn client_auth_mandatory(&self) -> bool {
        true
    }

    fn root_hint_subjects(&self) -> &[DistinguishedName] {
        &[]
    }

    /// Only the key matters. Chain and dates ignored on purpose, attestation is separate.
    fn verify_client_cert(&self, end_entity: &CertificateDer<'_>, _: &[CertificateDer<'_>], _: UnixTime) -> Result<ClientCertVerified, Error> {
        let h = spki_sha256(end_entity).ok_or(Error::InvalidCertificate(rustls::CertificateError::BadEncoding))?;
        if self.devices.borrow().has_tls_hash(&h) {
            Ok(ClientCertVerified::assertion())
        } else {
            Err(Error::InvalidCertificate(rustls::CertificateError::UnknownIssuer))
        }
    }

    fn verify_tls12_signature(&self, _: &[u8], _: &CertificateDer<'_>, _: &DigitallySignedStruct) -> Result<HandshakeSignatureValid, Error> {
        Err(Error::PeerIncompatible(rustls::PeerIncompatible::Tls12NotOffered))
    }

    /// This is the proof of possession, a copied cert fails here.
    fn verify_tls13_signature(&self, message: &[u8], cert: &CertificateDer<'_>, dss: &DigitallySignedStruct) -> Result<HandshakeSignatureValid, Error> {
        if dss.scheme != SignatureScheme::ECDSA_NISTP256_SHA256 {
            return Err(Error::PeerMisbehaved(rustls::PeerMisbehaved::SignedHandshakeWithUnadvertisedSigScheme));
        }
        rustls::crypto::verify_tls13_signature(message, cert, dss, &self.algs)
    }

    fn supported_verify_schemes(&self) -> Vec<SignatureScheme> {
        vec![SignatureScheme::ECDSA_NISTP256_SHA256]
    }
}

pub fn server_config(
    devices: watch::Receiver<Arc<Devices>>,
    chain: Vec<CertificateDer<'static>>,
    key: PrivateKeyDer<'static>,
) -> Result<ServerConfig, Error> {
    let mut cfg = ServerConfig::builder_with_provider(Arc::new(provider()))
        .with_protocol_versions(&[&rustls::version::TLS13])?
        .with_client_cert_verifier(Arc::new(PinnedDevices::new(devices)))
        .with_single_cert(chain, key)?;
    cfg.alpn_protocols = vec![b"h2".to_vec()];
    // no resumption, a revoked phone can't come back on an old ticket
    cfg.send_tls13_tickets = 0;
    cfg.session_storage = Arc::new(rustls::server::NoServerSessionStorage {});
    cfg.max_early_data_size = 0;
    Ok(cfg)
}

/// Cert chain and PKCS#8 key, as `remoterctl` writes them.
pub fn load_pem(cert_path: &std::path::Path, key_path: &std::path::Path) -> Result<(Vec<CertificateDer<'static>>, PrivateKeyDer<'static>), String> {
    use rustls::pki_types::pem::PemObject;
    let chain: Vec<CertificateDer<'static>> = CertificateDer::pem_file_iter(cert_path)
        .map_err(|e| format!("{}: {e}", cert_path.display()))?
        .collect::<Result<_, _>>()
        .map_err(|e| format!("{}: {e}", cert_path.display()))?;
    if chain.is_empty() {
        return Err(format!("{}: no certificate", cert_path.display()));
    }
    let key = PrivateKeyDer::from_pem_file(key_path).map_err(|e| format!("{}: {e}", key_path.display()))?;
    Ok((chain, key))
}

/// Same key and rules, no client cert since the phone has no paired key yet.
pub fn pairing_config(chain: Vec<CertificateDer<'static>>, key: PrivateKeyDer<'static>) -> Result<ServerConfig, Error> {
    let mut cfg = ServerConfig::builder_with_provider(Arc::new(provider()))
        .with_protocol_versions(&[&rustls::version::TLS13])?
        .with_no_client_auth()
        .with_single_cert(chain, key)?;
    cfg.alpn_protocols = vec![b"h2".to_vec()];
    cfg.send_tls13_tickets = 0;
    cfg.session_storage = Arc::new(rustls::server::NoServerSessionStorage {});
    cfg.max_early_data_size = 0;
    Ok(cfg)
}
