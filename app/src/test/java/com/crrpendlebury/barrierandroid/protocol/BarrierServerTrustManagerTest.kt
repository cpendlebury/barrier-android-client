package com.crrpendlebury.barrierandroid.protocol

import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.security.auth.x500.X500Principal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for [BarrierServerTrustManager]: SHA-256 fingerprint pinning of the
 * server leaf certificate, mirroring Barrier's ENCRYPTED_AUTHENTICATED model.
 */
class BarrierServerTrustManagerTest {

    /** A minimal X509Certificate whose [getEncoded] returns [bytes]. */
    private fun fakeCert(bytes: ByteArray): X509Certificate = object : X509Certificate() {
        override fun getEncoded(): ByteArray = bytes
        override fun checkValidity() = Unit
        override fun checkValidity(date: java.util.Date?) = Unit
        override fun getBasicConstraints(): Int = -1
        override fun getExtendedKeyUsage(): List<String>? = null
        override fun getIssuerAlternativeNames(): Collection<List<*>>? = null
        override fun getIssuerX500Principal(): X500Principal = X500Principal("CN=Barrier")
        override fun getKeyUsage(): BooleanArray? = null
        override fun getSerialNumber(): java.math.BigInteger = java.math.BigInteger.ZERO
        override fun getSigAlgName(): String = "SHA256withRSA"
        override fun getSigAlgOID(): String = ""
        override fun getSigAlgParams(): ByteArray? = null
        override fun getSignature(): ByteArray = ByteArray(0)
        override fun getSubjectAlternativeNames(): Collection<List<*>>? = null
        override fun getSubjectX500Principal(): X500Principal = X500Principal("CN=Barrier")
        override fun getTBSCertificate(): ByteArray = bytes
        override fun getVersion(): Int = 3
        override fun verify(key: java.security.PublicKey) = Unit
        override fun verify(key: java.security.PublicKey, sigProvider: String) = Unit
        override fun getNotAfter(): java.util.Date = java.util.Date()
        override fun getNotBefore(): java.util.Date = java.util.Date()
        override fun getIssuerDN(): X500Principal = X500Principal("CN=Barrier")
        override fun getSubjectDN(): X500Principal = X500Principal("CN=Barrier")
        override fun getIssuerUniqueID(): BooleanArray? = null
        override fun getSubjectUniqueID(): BooleanArray? = null
        override fun getPublicKey(): java.security.PublicKey? = null
        override fun toString(): String = "fake-cert"
        override fun hasUnsupportedCriticalExtension(): Boolean = false
        override fun getCriticalExtensionOIDs(): Set<String>? = null
        override fun getNonCriticalExtensionOIDs(): Set<String>? = null
        override fun getExtensionValue(oid: String): ByteArray? = null
    }

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    @Test
    fun normalizeStripsSeparatorsAndLowercases() {
        assertEquals("28fd0a98", BarrierServerTrustManager.normalize("28:FD:0A:98"))
        assertEquals("28fd0a98", BarrierServerTrustManager.normalize(" 28 FD 0A 98 "))
    }

    @Test
    fun formatRendersUppercaseColonSeparatedPairs() {
        assertEquals("28:FD:0A:98", BarrierServerTrustManager.format("28fd0a98"))
    }

    @Test
    fun trustOnFirstUseAcceptsAndRecordsObserved() {
        val bytes = "server-cert-der".toByteArray()
        val mgr = BarrierServerTrustManager(null)
        mgr.checkServerTrusted(arrayOf(fakeCert(bytes)), "RSA")
        assertEquals(sha256Hex(bytes), mgr.observedFingerprint)
    }

    @Test
    fun matchingPinAccepts() {
        val bytes = "server-cert-der".toByteArray()
        val pin = sha256Hex(bytes)
        val mgr = BarrierServerTrustManager(pin)
        mgr.checkServerTrusted(arrayOf(fakeCert(bytes)), "RSA")
        assertEquals(pin, mgr.observedFingerprint)
    }

    @Test
    fun mismatchedPinThrows() {
        val bytes = "server-cert-der".toByteArray()
        val mgr = BarrierServerTrustManager("00".repeat(32))
        var threw = false
        try {
            mgr.checkServerTrusted(arrayOf(fakeCert(bytes)), "RSA")
        } catch (e: CertificateException) {
            threw = true
            assertTrue(e.message?.contains("mismatch") == true)
        }
        assertTrue(threw)
    }

    @Test
    fun emptyChainThrows() {
        val mgr = BarrierServerTrustManager(null)
        var threw = false
        try {
            mgr.checkServerTrusted(emptyArray(), "RSA")
        } catch (e: CertificateException) {
            threw = true
        }
        assertTrue(threw)
    }

    @Test
    fun getAcceptedIssuersIsEmpty() {
        val mgr = BarrierServerTrustManager(null)
        assertEquals(0, mgr.getAcceptedIssuers().size)
    }
}