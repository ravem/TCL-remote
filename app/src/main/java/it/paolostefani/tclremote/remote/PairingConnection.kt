package it.paolostefani.tclremote.remote

import it.paolostefani.tclremote.proto.PoloProto
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.math.BigInteger
import java.net.InetSocketAddress
import java.security.MessageDigest
import java.security.cert.X509Certificate
import java.security.interfaces.RSAPublicKey
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLContext

/**
 * Implements the Android TV pairing handshake (port 6467, polo.proto).
 *
 * The flow: the client and the TV perform a protocol exchange over TLS. At the
 * end the TV shows a 6-digit hexadecimal code on screen; the user types it into
 * this app. The code is used to derive a shared secret authorising the client
 * certificate. After success the same TLS session (and certificate) is reused
 * for the remote connection on port 6466.
 */
class PairingConnection(
    private val sslContext: SSLContext,
    private val clientName: String,
    private val cert: X509Certificate
) {

    companion object {
        const val DEFAULT_PORT = 6467
        const val TIMEOUT_MS = 10_000
    }

    private var socket: SSLSocket? = null
    private var input: InputStream? = null
    private var output: OutputStream? = null

    /** Open TLS to the TV and run the initial handshake (up to configuration ack). */
    fun begin(host: String, port: Int = DEFAULT_PORT): String {
        val s = sslContext.socketFactory.createSocket() as SSLSocket
        s.connect(InetSocketAddress(host, port), TIMEOUT_MS)
        s.soTimeout = TIMEOUT_MS
        socket = s
        input = s.inputStream
        output = s.outputStream

        // 1. Pairing request
        send {
            this.pairingRequest = PoloProto.PairingRequest.newBuilder()
                .setServiceName("atvremote")
                .setClientName(clientName)
                .build()
        }
        val ack = read()
        check(ack.hasPairingRequestAck()) { "Expected pairing_request_ack" }
        val serverName = ack.pairingRequestAck.serverName

        // 2. Options
        send {
            val encoding = PoloProto.Options.Encoding.newBuilder()
                .setType(PoloProto.Options.Encoding.EncodingType.ENCODING_TYPE_HEXADECIMAL)
                .setSymbolLength(6)
                .build()
            this.options = PoloProto.Options.newBuilder()
                .setPreferredRole(PoloProto.Options.RoleType.ROLE_TYPE_INPUT)
                .addInputEncodings(encoding)
                .build()
        }
        val options = read()
        check(options.hasOptions()) { "Expected options" }

        // 3. Configuration
        send {
            this.configuration = PoloProto.Configuration.newBuilder()
                .setClientRole(PoloProto.Options.RoleType.ROLE_TYPE_INPUT)
                .setEncoding(
                    PoloProto.Options.Encoding.newBuilder()
                        .setType(PoloProto.Options.Encoding.EncodingType.ENCODING_TYPE_HEXADECIMAL)
                        .setSymbolLength(6)
                )
                .build()
        }
        val cfgAck = read()
        check(cfgAck.hasConfigurationAck()) { "Expected configuration_ack" }

        return serverName
    }

    /** Finish pairing by providing the 6-digit code shown on the TV. */
    fun finish(code: String) {
        val pin = code.trim()
        require(pin.length == 6) { "Pairing code must be exactly 6 characters" }

        val clientPub = cert.publicKey as RSAPublicKey
        val serverCert = socket!!.session.peerCertificates[0] as X509Certificate
        val serverPub = serverCert.publicKey as RSAPublicKey

        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(bigEndianBytes(clientPub.modulus))
        digest.update(bigEndianBytes(clientPub.publicExponent))
        digest.update(bigEndianBytes(serverPub.modulus))
        digest.update(bigEndianBytes(serverPub.publicExponent))
        // Only the trailing 4 hex characters of the code are hashed; the leading
        // 2 are verified against the first hash byte below (matches the reference
        // implementation).
        digest.update(hexToBytes(pin.substring(2)))

        val hash = digest.digest()

        // The first byte of the code must match the leading hash byte.
        val codeFirstByte = (pin.substring(0, 2).toInt(16) and 0xFF)
        check((hash[0].toInt() and 0xFF) == codeFirstByte) {
            "Pairing code did not match the certificate exchange"
        }

        send {
            this.secret = PoloProto.Secret.newBuilder().setSecret(com.google.protobuf.ByteString.copyFrom(hash)).build()
        }
        val secretAck = read()
        check(secretAck.hasSecretAck()) { "Expected secret_ack" }
    }

    fun close() {
        try { socket?.close() } catch (_: Exception) {}
    }

    private fun hexToBytes(hex: String): ByteArray {
        val result = ByteArray(hex.length / 2)
        for (i in result.indices) {
            result[i] = hex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
        return result
    }

    private fun bigEndianBytes(value: BigInteger): ByteArray {
        val bytes = value.toByteArray()
        // toByteArray may add a leading zero sign byte; strip it.
        return if (bytes.size > 1 && bytes[0] == 0.toByte()) bytes.copyOfRange(1, bytes.size) else bytes
    }

    private fun send(builder: PoloProto.OuterMessage.Builder.() -> Unit) {
        val m = PoloProto.OuterMessage.newBuilder()
        m.protocolVersion = 2
        m.status = PoloProto.OuterMessage.Status.STATUS_OK
        m.builder()
        val bytes = m.build().toByteArray()
        writeVarint(bytes.size)
        output!!.write(bytes)
        output!!.flush()
    }

    private fun read(): PoloProto.OuterMessage {
        val len = readVarint()
        val body = ByteArray(len)
        var off = 0
        while (off < len) {
            val n = input!!.read(body, off, len - off)
            if (n < 0) throw IOException("Connection closed during pairing")
            off += n
        }
        return PoloProto.OuterMessage.parseFrom(body)
    }

    private fun writeVarint(value: Int) {
        var v = value
        while (v >= 0x80) {
            output!!.write((v and 0x7F) or 0x80)
            v = v ushr 7
        }
        output!!.write(v)
    }

    private fun readVarint(): Int {
        var result = 0
        var shift = 0
        while (true) {
            val b = input!!.read()
            if (b < 0) throw IOException("Connection closed during pairing")
            result = result or ((b and 0x7F) shl shift)
            if (b and 0x80 == 0) break
            shift += 7
            if (shift > 35) throw IOException("Varint too long")
        }
        return result
    }
}
