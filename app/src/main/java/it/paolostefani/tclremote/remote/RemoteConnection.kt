package it.paolostefani.tclremote.remote

import it.paolostefani.tclremote.proto.RemoteAppLinkLaunchRequest
import it.paolostefani.tclremote.proto.RemoteConfigure
import it.paolostefani.tclremote.proto.RemoteDeviceInfo
import it.paolostefani.tclremote.proto.RemoteEditInfo
import it.paolostefani.tclremote.proto.RemoteImeBatchEdit
import it.paolostefani.tclremote.proto.RemoteImeObject
import it.paolostefani.tclremote.proto.RemoteKeyInject
import it.paolostefani.tclremote.proto.RemoteKeyCode
import it.paolostefani.tclremote.proto.RemoteMessage
import it.paolostefani.tclremote.proto.RemoteDirection
import it.paolostefani.tclremote.proto.RemotePingResponse
import it.paolostefani.tclremote.proto.RemoteSetActive
import it.paolostefani.tclremote.proto.RemoteSetVolumeLevel
import it.paolostefani.tclremote.proto.RemoteVoiceBegin
import it.paolostefani.tclremote.proto.RemoteVoiceEnd
import it.paolostefani.tclremote.proto.RemoteVoicePayload
import it.paolostefani.tclremote.proto.RemoteStart
import com.google.protobuf.ByteString
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLContext
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * The TV remote connection (Android TV Remote protocol v2, port 6466).
 *
 * A single persistent TLS socket carries all commands and all state updates.
 * Incoming messages are parsed on a dedicated reader thread; outgoing commands
 * are synchronised through the socket's output stream.
 */
class RemoteConnection(
    private val sslContext: SSLContext,
    private val listener: Listener
) {

    interface Listener {
        fun onConnected(deviceInfo: DeviceInfo?)
        fun onDisconnected(reason: String?)
        fun onIsOnChanged(isOn: Boolean)
        fun onCurrentAppChanged(pkg: String?)
        fun onVolumeChanged(level: Int, max: Int, muted: Boolean)
        fun onVoiceSessionStarted(sessionId: Int)
        fun onVoiceSessionEnded()
    }

    data class DeviceInfo(val manufacturer: String, val model: String, val version: String)

    companion object {
        const val DEFAULT_PORT = 6466
        const val FEATURE_PING = 1 shl 0
        const val FEATURE_KEY = 1 shl 1
        const val FEATURE_IME = 1 shl 2
        const val FEATURE_VOICE = 1 shl 3
        const val FEATURE_UNKNOWN1 = 1 shl 4
        const val FEATURE_POWER = 1 shl 5
        const val FEATURE_VOLUME = 1 shl 6
        const val FEATURE_APP_LINK = 1 shl 9

        val DEFAULT_FEATURES =
            FEATURE_PING or FEATURE_KEY or FEATURE_IME or FEATURE_VOICE or
                FEATURE_POWER or FEATURE_VOLUME or FEATURE_APP_LINK

        const val VOICE_CHUNK_SIZE = 20 * 1024
        const val VOICE_CHUNK_MIN = 8 * 1024
    }

    private var socket: SSLSocket? = null
    private var input: InputStream? = null
    private var output: OutputStream? = null
    private var reader: Thread? = null
    private val connected = AtomicBoolean(false)
    private val writeLock = Any()

    @Volatile private var imeCounter = 0
    @Volatile private var imeFieldCounter = 0
    @Volatile private var voiceSessionId: Int? = null

    fun isConnected(): Boolean = connected.get()

    /** Open the TLS connection and start the reader loop. Blocks until the handshake completes. */
    fun connect(host: String, port: Int = DEFAULT_PORT, timeoutMs: Int = 8000) {
        val s = sslContext.socketFactory.createSocket() as SSLSocket
        s.connect(InetSocketAddress(host, port), timeoutMs)
        s.soTimeout = timeoutMs
        s.keepAlive = true
        socket = s
        input = s.inputStream
        output = s.outputStream
        connected.set(true)

        reader = Thread({ readLoop() }, "tv-remote-reader").apply {
            isDaemon = true
            start()
        }
    }

    fun close() {
        connected.set(false)
        try { socket?.close() } catch (_: Exception) {}
    }

    private fun readLoop() {
        try {
            val inp = input ?: return
            while (connected.get()) {
                val msg = RemoteMessage.parseDelimitedFrom(inp) ?: break
                handle(msg)
            }
        } catch (e: Exception) {
            if (connected.get()) {
                listener.onDisconnected(e.message)
            }
        } finally {
            connected.set(false)
            try { socket?.close() } catch (_: Exception) {}
            listener.onDisconnected(null)
        }
    }

    private fun handle(msg: RemoteMessage) {
        when {
            msg.hasRemoteConfigure() -> handleConfigure(msg.remoteConfigure)
            msg.hasRemoteSetActive() -> send(RemoteMessage.newBuilder()
                .setRemoteSetActive(RemoteSetActive.newBuilder().setActive(DEFAULT_FEATURES)).build())
            msg.hasRemoteImeKeyInject() -> {
                val appPkg = msg.remoteImeKeyInject.appInfo.appPackage
                listener.onCurrentAppChanged(appPkg.ifBlank { null })
            }
            msg.hasRemoteImeBatchEdit() -> {
                imeCounter = msg.remoteImeBatchEdit.imeCounter
                imeFieldCounter = msg.remoteImeBatchEdit.fieldCounter
            }
            msg.hasRemoteSetVolumeLevel() -> {
                val v = msg.remoteSetVolumeLevel
                listener.onVolumeChanged(v.volumeLevel, v.volumeMax, v.volumeMuted)
            }
            msg.hasRemoteStart() -> {
                val s = msg.remoteStart
                listener.onIsOnChanged(s.started)
            }
            msg.hasRemotePingRequest() -> {
                send(RemoteMessage.newBuilder()
                    .setRemotePingResponse(RemotePingResponse.newBuilder().setVal1(msg.remotePingRequest.val1))
                    .build())
            }
            msg.hasRemoteVoiceBegin() -> {
                val begin = msg.remoteVoiceBegin
                voiceSessionId = begin.sessionId
                listener.onVoiceSessionStarted(begin.sessionId)
                // Acknowledge so the TV starts accepting audio.
                send(RemoteMessage.newBuilder()
                    .setRemoteVoiceBegin(RemoteVoiceBegin.newBuilder().setSessionId(begin.sessionId))
                    .build(), false)
            }
            msg.hasRemoteVoiceEnd() -> {
                voiceSessionId = null
                listener.onVoiceSessionEnded()
            }
        }
    }

    private fun handleConfigure(cfg: RemoteConfigure) {
        listener.onConnected(DeviceInfo(
            manufacturer = cfg.deviceInfo.vendor,
            model = cfg.deviceInfo.model,
            version = cfg.deviceInfo.appVersion
        ))
        val resp = RemoteMessage.newBuilder()
            .setRemoteConfigure(
                RemoteConfigure.newBuilder()
                    .setCode1(DEFAULT_FEATURES)
                    .setDeviceInfo(
                        RemoteDeviceInfo.newBuilder()
                            .setUnknown1(1)
                            .setUnknown2("1")
                            .setPackageName("atvremote")
                            .setAppVersion("1.0.0")
                    )
            )
            .build()
        send(resp, false)
    }

    // ---- commands ---------------------------------------------------------

    fun sendKey(keyCode: Int, direction: Int = RemoteDirection.SHORT_VALUE) {
        send(RemoteMessage.newBuilder()
            .setRemoteKeyInject(
                RemoteKeyInject.newBuilder()
                    .setKeyCodeValue(keyCode)
                    .setDirectionValue(direction)
            )
            .build())
    }

    fun launchApp(appLink: String) {
        send(RemoteMessage.newBuilder()
            .setRemoteAppLinkLaunchRequest(
                RemoteAppLinkLaunchRequest.newBuilder().setAppLink(appLink)
            )
            .build())
    }

    fun sendText(text: String) {
        if (text.isEmpty()) return
        val pos = text.length - 1
        val imeObject = RemoteImeObject.newBuilder().setStart(pos).setEnd(pos).setValue(text).build()
        val editInfo = RemoteEditInfo.newBuilder().setInsert(1).setTextFieldStatus(imeObject).build()
        val batch = RemoteImeBatchEdit.newBuilder()
            .setImeCounter(imeCounter)
            .setFieldCounter(imeFieldCounter)
            .addEditInfo(editInfo)
            .build()
        send(RemoteMessage.newBuilder().setRemoteImeBatchEdit(batch).build())
    }

    fun startVoice() {
        // Sending SEARCH triggers the TV to open a voice session (remote_voice_begin).
        sendKey(RemoteKeyCode.KEYCODE_SEARCH_VALUE)
    }

    fun sendVoiceChunk(chunk: ByteArray) {
        val sid = voiceSessionId ?: return
        var offset = 0
        while (offset < chunk.size) {
            val end = minOf(offset + VOICE_CHUNK_SIZE, chunk.size)
            var samples = chunk.copyOfRange(offset, end)
            if (samples.size < VOICE_CHUNK_MIN) {
                samples = samples.copyOf(VOICE_CHUNK_MIN)
            }
            send(RemoteMessage.newBuilder()
                .setRemoteVoicePayload(
                    RemoteVoicePayload.newBuilder()
                        .setSessionId(sid)
                        .setSamples(ByteString.copyFrom(samples))
                )
                .build(), false)
            offset = end
        }
    }

    fun endVoice() {
        val sid = voiceSessionId ?: return
        voiceSessionId = null
        send(RemoteMessage.newBuilder()
            .setRemoteVoiceEnd(RemoteVoiceEnd.newBuilder().setSessionId(sid))
            .build(), false)
        listener.onVoiceSessionEnded()
    }

    private fun send(msg: RemoteMessage, flush: Boolean = true) {
        if (!connected.get()) return
        val out = output ?: return
        synchronized(writeLock) {
            try {
                msg.writeDelimitedTo(out)
                if (flush) out.flush()
            } catch (e: IOException) {
                // Drop silently; reader thread will surface the disconnect.
            }
        }
    }
}
