package com.crrpendlebury.barrierandroid.protocol

import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.X509TrustManager

/**
 * Trust manager that authenticates a Barrier server the way Barrier's own
 * client does: by the SHA-256 fingerprint of the server's leaf certificate,
 * compared against a previously accepted (pinned) fingerprint.
 *
 * Barrier's TLS model is `ConnectionSecurityLevel.ENCRYPTED_AUTHENTICATED`:
 * the handshake requests a peer certificate but does not validate the chain
 * (the verify callback is an ignore callback); authentication happens at
 * application level via `verify_cert_fingerprint` against a trusted-fingerprint
 * store. This manager mirrors that: no chain validation, pin by SHA-256 of
 * the leaf certificate's DER encoding (which is what OpenSSL's
 * `X509_digest(cert, EVP_sha256())` computes).
 *
 * - [expectedFingerprint] is the accepted pin, normalised (lowercase hex, no
 *   separators), or `null` for trust-on-first-use: the first observed
 *   fingerprint is accepted and recorded in [observedFingerprint] for the
 *   caller to persist.
 * - A non-null, mismatched pin throws [CertificateException].
 *
 * Hostname verification is deliberately not performed here; identity is
 * established by the fingerprint pin, matching Barrier's client behaviour for
 * self-signed LAN certificates.
 */
class BarrierServerTrustManager(
    private val expectedFingerprint: String?,
) : X509TrustManager {

    /**
     * SHA-256 fingerprint (lowercase hex, no separators) of the leaf certificate
     * actually presented by the server during the most recent handshake, or
     * `null` if no handshake has completed.
     */
    @Volatile
    var observedFingerprint: String? = null
        private set

    override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {
        // This client never acts as a TLS server, so a request for a client
        // certificate is unexpected. (Barrier's ENCRYPTED_AUTHENTICATED mode
        // does request a client cert; supporting that is a separate, larger
        // piece and is out of scope for this plan.)
        throw CertificateException("client certificate authentication not supported")
    }

    override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
        if (chain.isEmpty()) {
            throw CertificateException("empty server certificate chain")
        }
        val leaf = chain[0]
        val digest = MessageDigest.getInstance("SHA-256").digest(leaf.encoded)
        val observed = digest.joinToString("") { "%02x".format(it) }
        observedFingerprint = observed

        val expected = expectedFingerprint?.let { normalize(it) }
        if (expected == null) {
            // Trust on first use: accept and let the caller persist the pin.
            return
        }
        if (observed != expected) {
            throw CertificateException(
                "server fingerprint mismatch: expected $expected, got $observed",
            )
        }
    }

    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()

    companion object {
        /** Normalises a fingerprint string to lowercase hex with no separators. */
        fun normalize(fingerprint: String): String =
            fingerprint.replace(":", "").replace(" ", "").lowercase()

        /** Renders a fingerprint as Barrier's display form: uppercase, colon-separated pairs. */
        fun format(fingerprint: String): String =
            normalize(fingerprint).chunked(2).joinToString(":").uppercase()
    }
}