package com.bluewave.remote

import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import org.junit.Assert.*
import org.junit.Test

class SecurityTest {
    private fun certificate() =
        javaClass.getResourceAsStream("/tv-test.cer")!!.use {
            CertificateFactory.getInstance("X.509").generateCertificate(it) as X509Certificate
        }

    @Test
    fun privateLanAddressesOnly() {
        listOf("10.0.0.2", "172.16.0.1", "172.31.255.254", "192.168.1.20", "169.254.1.2").forEach {
            assertTrue(it, Protocol.localIp(it))
        }
        listOf(
                "8.8.8.8",
                "172.15.1.2",
                "172.32.0.1",
                "127.0.0.1",
                "0.0.0.0",
                "192.168.001.1",
                "١٩٢.١٦٨.١.١",
                "192.168.1.2/evil",
                "224.0.0.1",
                "example.com",
            )
            .forEach { assertFalse(it, Protocol.localIp(it)) }
    }

    @Test
    fun knownCertificatePinIsAccepted() {
        val cert = certificate()
        val trust =
            TvTrustManager({ TvTrustManager.fingerprint(cert) }, { fail("Unexpected new pin") })
        trust.checkServerTrusted(arrayOf(cert), "RSA")
    }

    @Test
    fun unknownAndChangedPinsAreRejectedUntilExplicitApproval() {
        val cert = certificate()
        for (expected in listOf(null, "different-pin")) {
            var offered: String? = null
            val trust = TvTrustManager({ expected }, { offered = it })
            try {
                trust.checkServerTrusted(arrayOf(cert), "RSA")
                fail("Unknown certificate accepted")
            } catch (_: CertificateException) {
                assertEquals(TvTrustManager.fingerprint(cert), offered)
            }
        }
    }

    @Test(expected = CertificateException::class)
    fun emptyCertificateIsRejected() {
        TvTrustManager({ null }, {}).checkServerTrusted(emptyArray(), "RSA")
    }

    @Test(expected = CertificateException::class)
    fun serverTrustCannotBeUsedForClientTrust() {
        TvTrustManager({ null }, {}).checkClientTrusted(arrayOf(certificate()), "RSA")
    }
}
