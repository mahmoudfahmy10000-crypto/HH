package com.bluewave.remote

import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.X509TrustManager

/**
 * Explicit first-use approval followed by exact certificate pinning for local Samsung TVs. This
 * does not grant public-CA trust and never accepts an unknown certificate automatically.
 */
internal class TvTrustManager(
    private val expectedFingerprint: () -> String?,
    private val untrustedCertificate: (String) -> Unit,
) : X509TrustManager {
    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()

    override fun checkClientTrusted(chain: Array<out X509Certificate>, authType: String) {
        throw CertificateException("Not a client")
    }

    override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String) {
        if (chain.isEmpty()) throw CertificateException("Missing TV certificate")
        val fingerprint = fingerprint(chain[0])
        if (expectedFingerprint() != fingerprint) {
            untrustedCertificate(fingerprint)
            throw CertificateException("TV certificate requires approval")
        }
    }

    companion object {
        fun fingerprint(certificate: X509Certificate): String =
            MessageDigest.getInstance("SHA-256").digest(certificate.encoded).joinToString(":") {
                "%02X".format(it)
            }
    }
}
