//! Self-signed certificate generation for the PNTP QUIC transport.
//!
//! The host generates a persistent self-signed certificate; clients pin its
//! SHA-256 (DER) fingerprint and verify it via a custom `ServerCertVerifier`
//! (spec Section 17.2/17.4).

use sha2::{Digest, Sha256};

pub type Certificate = rustls::pki_types::CertificateDer<'static>;
pub type PrivateKey = rustls::pki_types::PrivatePkcs8KeyDer<'static>;

pub struct HostIdentity {
    pub certificate: Certificate,
    pub private_key: PrivateKey,
    /// Lowercase hex SHA-256 of the DER-encoded certificate.
    pub fingerprint_hex: String,
}

fn fingerprint_hex(cert_der: &[u8]) -> String {
    let digest = Sha256::digest(cert_der);
    digest.iter().map(|b| format!("{:02x}", b)).collect()
}

/// Lowercase hex SHA-256 of a DER certificate — used by the client verifier.
pub fn fingerprint_of(cert: &Certificate) -> String {
    fingerprint_hex(cert.as_ref())
}

/// Generates a fresh self-signed identity. Callers should persist
/// (certificate, key) across restarts so clients can keep trusting them;
/// persistence lands with pairing storage in Milestone 9.
pub fn generate_self_signed(device_name: &str) -> Result<HostIdentity, String> {
    let sanitized: String = device_name
        .chars()
        .filter(|c| c.is_ascii_alphanumeric() || *c == '-' || *c == '.')
        .take(64)
        .collect();
    let subject = if sanitized.is_empty() { "peernet".to_string() } else { sanitized };

    let pair = rcgen::generate_simple_self_signed(vec![subject])
        .map_err(|e| format!("rcgen failed: {e}"))?;

    let cert_der = pair.cert.der().to_owned();
    let key_der = pair.key_pair.serialize_der();

    Ok(HostIdentity {
        fingerprint_hex: fingerprint_hex(cert_der.as_ref()),
        certificate: cert_der,
        private_key: PrivateKey::from(key_der),
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn generates_identity_with_stable_fingerprint() {
        let id1 = generate_self_signed("PeerNet-test").unwrap();
        let id2 = generate_self_signed("PeerNet-test").unwrap();

        assert_eq!(id1.fingerprint_hex.len(), 64);

        // Same DER must always produce the same fingerprint...
        let again = fingerprint_hex(id1.certificate.as_ref());
        assert_eq!(again, id1.fingerprint_hex);

        // ...but two random identities differ.
        assert_ne!(id1.fingerprint_hex, id2.fingerprint_hex);
    }

    #[test]
    fn sanitizes_hostile_subjects() {
        let id = generate_self_signed("bad name!!!<>@#$").unwrap();
        assert!(!id.fingerprint_hex.is_empty());
    }
}
