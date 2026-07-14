// Source: https://github.com/rustls/rustls-platform-verifier (Apache-2.0)
// Required by matrix-rust-sdk 26.06.x for Android TLS certificate verification via JNI.
// The SDK's native .so calls org.rustls.platformverifier.CertificateVerifier.verifyCertificateChain()
// via JNI; without this class, all HTTPS requests fail with "failed to call native verifier".

package org.rustls.platformverifier

import android.annotation.SuppressLint
import android.content.Context
import android.net.http.X509TrustManagerExtensions
import android.os.Build
import android.util.Log
import java.io.ByteArrayInputStream
import java.io.File
import java.security.KeyStore
import java.security.KeyStoreException
import java.security.MessageDigest
import java.security.PublicKey
import java.security.cert.CertPathValidator
import java.security.cert.CertPathValidatorException
import java.security.cert.CertificateException
import java.security.cert.CertificateExpiredException
import java.security.cert.CertificateFactory
import java.security.cert.CertificateNotYetValidException
import java.security.cert.CertificateParsingException
import java.security.cert.PKIXBuilderParameters
import java.security.cert.PKIXRevocationChecker
import java.security.cert.X509Certificate
import java.util.Date
import java.util.EnumSet
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import javax.security.auth.x500.X500Principal

// If this is updated, update the Rust definition too.
private enum class StatusCode(val value: Int) {
    Ok(0),
    Unavailable(1),
    Expired(2),
    UnknownCert(3),
    Revoked(4),
    InvalidEncoding(5),
    InvalidExtension(6),
}

private class VerificationResult(
    status: StatusCode,
    @Suppress("unused") val message: String? = null
) {
    @Suppress("unused")
    private val code: Int = status.value
}

private const val TEST = false

@Suppress("unused")
@SuppressLint("LongLogTag")
internal object CertificateVerifier {
    private const val TAG = "rustls-platform-verifier-android"

    private fun createTrustManager(keystore: KeyStore?): X509TrustManagerExtensions? {
        val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        factory.init(keystore)

        val availableTrustManagers = try {
            factory.trustManagers
        } catch (e: RuntimeException) {
            Log.w(TAG, "exception thrown creating a TrustManager: $e")
            return null
        }

        for (manager in availableTrustManagers) {
            if (manager is X509TrustManager) {
                return X509TrustManagerExtensions(manager)
            }
        }

        Log.e(TAG, "failed to find a usable trust manager")
        return null
    }

    private fun makeLazyTrustManager(keystore: KeyStore?): Lazy<X509TrustManagerExtensions?> {
        keystore?.load(null)
        return lazy { createTrustManager(keystore) }
    }

    private val certFactory: CertificateFactory = CertificateFactory.getInstance("X.509")

    private var systemTrustAnchorCache = hashSetOf<Pair<X500Principal, PublicKey>>()

    @get:Synchronized
    private var systemCertificateDirectory: File? = System.getenv("ANDROID_ROOT")?.let { rootPath ->
        File("$rootPath/etc/security/cacerts")
    }

    @get:Synchronized
    private val systemKeystore: KeyStore? = try {
        KeyStore.getInstance("AndroidCAStore")
    } catch (_: KeyStoreException) {
        null
    }

    @get:Synchronized
    private val systemTrustManager: Lazy<X509TrustManagerExtensions?> =
        makeLazyTrustManager(systemKeystore)

    @JvmStatic
    private fun verifyCertificateChain(
        @Suppress("UNUSED_PARAMETER") context: Context,
        serverName: String,
        authMethod: String,
        allowedEkus: Array<String>,
        ocspResponse: ByteArray?,
        time: Long,
        certChain: Array<ByteArray>
    ): VerificationResult {
        val certificateChain = mutableListOf<X509Certificate>()
        certChain.forEach { certBytes ->
            val certificate = try {
                certFactory.generateCertificate(ByteArrayInputStream(certBytes))
            } catch (e: CertificateException) {
                return VerificationResult(StatusCode.InvalidEncoding)
            }
            certificateChain.add(certificate as X509Certificate)
        }

        val endEntity = certificateChain[0]

        try {
            endEntity.checkValidity(Date(time))
        } catch (e: CertificateExpiredException) {
            return VerificationResult(StatusCode.Expired)
        } catch (e: CertificateNotYetValidException) {
            return VerificationResult(StatusCode.Expired)
        }

        if (!verifyCertUsage(endEntity, allowedEkus)) {
            return VerificationResult(StatusCode.InvalidExtension)
        }

        val trustManager = systemTrustManager.value
            ?: return VerificationResult(StatusCode.Unavailable)
        val keystore = systemKeystore

        val validChain = try {
            trustManager.checkServerTrusted(certificateChain.toTypedArray(), authMethod, serverName)
        } catch (e: CertificateException) {
            return VerificationResult(StatusCode.UnknownCert, e.toString())
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            if (ocspResponse == null && !isKnownRoot(validChain.last())) {
                return VerificationResult(StatusCode.Ok)
            }

            val parameters = PKIXBuilderParameters(keystore, null)
            val validator = CertPathValidator.getInstance("PKIX")
            val revocationChecker = validator.revocationChecker as PKIXRevocationChecker

            revocationChecker.options = EnumSet.of(
                PKIXRevocationChecker.Option.SOFT_FAIL,
                PKIXRevocationChecker.Option.ONLY_END_ENTITY
            )

            ocspResponse?.let { providedResponse ->
                revocationChecker.ocspResponses = mapOf(endEntity to providedResponse)
            }

            parameters.certPathCheckers = listOf(revocationChecker)
            parameters.isRevocationEnabled = false

            try {
                validator.validate(certFactory.generateCertPath(validChain), parameters)
            } catch (e: CertPathValidatorException) {
                return VerificationResult(StatusCode.Revoked, e.toString())
            }
        } else {
            Log.w(TAG, "did not attempt to validate OCSP due to Android version")
        }

        return VerificationResult(StatusCode.Ok)
    }

    private fun verifyCertUsage(certificate: X509Certificate, allowedEkus: Array<String>): Boolean {
        val ekus = try {
            certificate.extendedKeyUsage
        } catch (_: CertificateParsingException) {
            return false
        } catch (_: NullPointerException) {
            Log.w(TAG, "exception handling certificate EKU")
            return false
        } ?: return true

        return ekus.any { allowedEkus.contains(it) }
    }

    private fun hashPrincipal(principal: X500Principal): String {
        val hexDigits = "0123456789abcdef".toCharArray()
        val digest = MessageDigest.getInstance("MD5").digest(principal.encoded)
        val hexChars = CharArray(8)

        for (i in 0..3) {
            val digestByte = digest[3 - i].toInt()
            hexChars[2 * i] = hexDigits[(digestByte shr 4) and 0xf]
            hexChars[2 * i + 1] = hexDigits[digestByte and 0xf]
        }

        return String(hexChars)
    }

    fun isKnownRoot(root: X509Certificate): Boolean {
        systemKeystore?.let { loadedSystemKeystore ->
            systemCertificateDirectory?.let { loadedSystemCertificateDirectory ->
                val key = Pair(root.subjectX500Principal, root.publicKey)
                if (systemTrustAnchorCache.contains(key)) {
                    return true
                }

                val hash = hashPrincipal(root.subjectX500Principal)
                var i = 0
                while (true) {
                    val alias = "$hash.$i"

                    if (!File(loadedSystemCertificateDirectory, alias).exists()) {
                        break
                    }

                    val anchor = loadedSystemKeystore.getCertificate("system:$alias")

                    if (anchor == null) {
                        continue
                    } else if (anchor !is X509Certificate) {
                        Log.e(TAG, "anchor is not a certificate, alias: $alias")
                        continue
                    } else {
                        if ((root.subjectX500Principal == anchor.subjectX500Principal) && (root.publicKey == anchor.publicKey)) {
                            systemTrustAnchorCache.add(key)
                            return true
                        }
                    }

                    i += 1
                }
            }
        }

        return false
    }

    @JvmStatic
    fun getSystemRootCAs(): List<X509Certificate> {
        val rootCAs = mutableListOf<X509Certificate>()
        val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        factory.init(systemKeystore)

        val availableTrustManagers = try {
            factory.trustManagers
        } catch (e: RuntimeException) {
            Log.w(TAG, "exception thrown creating a TrustManager: $e")
            return rootCAs
        }

        availableTrustManagers.forEach { trustManager ->
            if (trustManager is X509TrustManager) {
                rootCAs.addAll(trustManager.acceptedIssuers)
            }
        }

        return rootCAs
    }
}
