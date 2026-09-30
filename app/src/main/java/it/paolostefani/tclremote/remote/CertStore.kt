package it.paolostefani.tclremote.remote

import android.content.Context
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.BasicConstraints
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.asn1.x509.GeneralName
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.openssl.jcajce.JcaPEMWriter
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.File
import java.io.StringWriter
import java.math.BigInteger
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import java.security.KeyFactory
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import java.security.cert.CertificateFactory
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * Generates and stores the self-signed client certificate used to authenticate
 * against the TV during pairing and for every subsequent TLS connection.
 *
 * The certificate is the credential the TV trusts: like in the reference macOS
 * implementation it must be kept private and reused across app restarts so that
 * pairing does not need to be redone.
 */
class CertStore(private val context: Context) {

    init {
        if (java.security.Security.getProvider("BC") == null) {
            java.security.Security.addProvider(org.bouncycastle.jce.provider.BouncyCastleProvider())
        }
    }

    private val certFile: File
        get() = File(context.filesDir, "tclr_cert.pem")
    private val keyFile: File
        get() = File(context.filesDir, "tclr_key.pem")

    private val keyPassword: CharArray
        get() = "tclremote".toCharArray()

    fun hasCredentials(): Boolean = certFile.exists() && keyFile.exists()

    /** Return the current client certificate (used to compute the pairing secret). */
    fun readCertificate(): X509Certificate {
        val cf = CertificateFactory.getInstance("X.509")
        return cf.generateCertificate(certFile.inputStream()) as X509Certificate
    }

    /** Generate (if needed) and return the private key + certificate chain. */
    private fun ensureKeyPair(): Pair<KeyPair, X509Certificate> {
        if (certFile.exists() && keyFile.exists()) {
            return loadFromDisk()
        }
        return generateAndSave()
    }

    private fun generateAndSave(): Pair<KeyPair, X509Certificate> {
        val keyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()

        val now = Instant.now()
        val notBefore = java.util.Date.from(now.minus(1, ChronoUnit.DAYS))
        val notAfter = java.util.Date.from(now.plus(3650, ChronoUnit.DAYS))

        val name = X500Name("CN=${CLIENT_NAME}")
        val builder = JcaX509v3CertificateBuilder(
            name,
            BigInteger.valueOf(1000L),
            notBefore,
            notAfter,
            name,
            keyPair.public
        )
        builder.addExtension(Extension.basicConstraints, true, BasicConstraints(true))
        builder.addExtension(
            Extension.subjectAlternativeName, false,
            org.bouncycastle.asn1.x509.GeneralNames(
                GeneralName(GeneralName.dNSName, CLIENT_NAME)
            )
        )
        val holder = builder.build(
            JcaContentSignerBuilder("SHA256withRSA").build(keyPair.private)
        )
        val cert = JcaX509CertificateConverter().getCertificate(holder)

        writePem(certFile, cert)
        writePem(keyFile, keyPair.private)

        // Make the private key readable only by this app.
        keyFile.setReadable(false, false)
        certFile.setReadable(false, false)

        return keyPair to cert
    }

    private fun loadFromDisk(): Pair<KeyPair, X509Certificate> {
        val cf = CertificateFactory.getInstance("X.509")
        val cert = cf.generateCertificate(certFile.inputStream()) as X509Certificate
        val keyBytes = java.util.Base64.getMimeDecoder().decode(
            keyFile.readText().replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "").replace("\n", "")
        )
        val spec = PKCS8EncodedKeySpec(keyBytes)
        val key = KeyFactory.getInstance("RSA").generatePrivate(spec)
        // The public key is not directly derivable from a private-only PKCS8 load here;
        // rebuild a KeyPair carrying the cert's public key.
        return KeyPair(null, key) to cert
    }

    private fun writePem(file: File, obj: Any) {
        val sw = StringWriter()
        JcaPEMWriter(sw).use { it.writeObject(obj) }
        file.writeText(sw.toString())
    }

    /** Build an SSLContext that presents our client cert and trusts any server cert. */
    fun buildSslContext(): SSLContext {
        val (keyPair, cert) = ensureKeyPair()
        val ks = KeyStore.getInstance("PKCS12").apply { load(null, null) }
        // Rebuild a proper client key pair: the private key from disk or generation.
        val privateKey = keyPair.private
        ks.setKeyEntry("client", privateKey, keyPassword, arrayOf(cert))

        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        kmf.init(ks, keyPassword)

        val trustAll = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
        }

        val sslContext = SSLContext.getInstance("TLS")
        sslContext.init(kmf.keyManagers, arrayOf<TrustManager>(trustAll), SecureRandom())
        return sslContext
    }

    companion object {
        const val CLIENT_NAME = "TCL Remote (Android)"
    }
}
