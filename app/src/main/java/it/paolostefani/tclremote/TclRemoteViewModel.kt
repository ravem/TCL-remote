package it.paolostefani.tclremote

import android.app.Application
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import it.paolostefani.tclremote.remote.AppCatalog
import it.paolostefani.tclremote.remote.CertStore
import it.paolostefani.tclremote.remote.Keys
import it.paolostefani.tclremote.remote.PairingConnection
import it.paolostefani.tclremote.remote.RemoteConnection
import it.paolostefani.tclremote.remote.TvAdb
import it.paolostefani.tclremote.remote.TvDiscovery
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.net.ssl.SSLException

class TclRemoteViewModel(app: Application) : AndroidViewModel(app) {

    enum class Phase { IDLE, DISCOVERING, NEEDS_PAIRING, PAIRING, CONNECTED, ERROR }

    enum class VoiceStatus { IDLE, STARTING, LISTENING, ERROR }

    data class UiState(
        val phase: Phase = Phase.IDLE,
        val message: String? = null,
        val devices: List<TvDiscovery.TvDevice> = emptyList(),
        val deviceName: String? = null,
        val model: String? = null,
        val isOn: Boolean? = null,
        val currentApp: String? = null,
        val volume: Int? = null,
        val volumeMax: Int? = null,
        val muted: Boolean? = null,
        val voiceActive: Boolean = false,
        val voiceStatus: VoiceStatus = VoiceStatus.IDLE,
        val voiceMessage: String? = null,
        val voiceError: String? = null,
        val pairingServerName: String? = null,
        val apps: List<AppCatalog.App> = AppCatalog.apps,
        val adbConnected: Boolean = false,
        val installedApps: List<TvAdb.InstalledApp> = emptyList(),
        val inputs: List<TvAdb.InputSpec> = emptyList(),
        val audioOutputs: List<TvAdb.AudioOutput> = emptyList(),
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private val certStore = CertStore(app)
    private val discovery = TvDiscovery(app)

    private var remote: RemoteConnection? = null
    private var pendingPairing: PairingConnection? = null
    private var currentHost: String? = null
    private var voiceThread: Thread? = null
    private var audioRecord: AudioRecord? = null
    @Volatile private var adb: TvAdb? = null

    // ---- discovery --------------------------------------------------------

    fun discover() {
        viewModelScope.launch {
            _state.value = _state.value.copy(phase = Phase.DISCOVERING, message = "Looking for TVs...")
            val devices = try {
                discovery.discover()
            } catch (e: Exception) {
                _state.value = _state.value.copy(
                    phase = Phase.ERROR,
                    message = "Discovery failed: ${e.message}"
                )
                return@launch
            }
            _state.value = _state.value.copy(
                phase = if (devices.isEmpty()) Phase.IDLE else Phase.IDLE,
                devices = devices,
                message = if (devices.isEmpty()) "No TVs found on the network." else null
            )
        }
    }

    fun connect(device: TvDiscovery.TvDevice) {
        currentHost = device.host
        _state.value = _state.value.copy(
            deviceName = device.name, message = "Connecting to ${device.name}..."
        )
        viewModelScope.launch(Dispatchers.IO) {
            connectRemote(device.host)
        }
    }

    private suspend fun connectRemote(host: String) {
        try {
            val sslContext = certStore.buildSslContext()
            val conn = RemoteConnection(sslContext, listener)
            conn.connect(host)
            remote?.close()
            remote = conn
            _state.value = _state.value.copy(
                phase = Phase.CONNECTED,
                message = null,
                currentApp = null
            )
            connectAdb(host)
        } catch (e: SSLException) {
            // The TV rejected our (unpaired) certificate -> start pairing.
            startPairing(host)
        } catch (e: Exception) {
            _state.value = _state.value.copy(
                phase = Phase.ERROR,
                message = "Cannot reach TV: ${e.message}"
            )
        }
    }

    private suspend fun startPairing(host: String) {
        try {
            val sslContext = certStore.buildSslContext()
            val cert = loadCertForPairing()
            val pairing = PairingConnection(sslContext, CertStore.CLIENT_NAME, cert)
            val serverName = pairing.begin(host)
            pendingPairing = pairing
            _state.value = _state.value.copy(
                phase = Phase.NEEDS_PAIRING,
                pairingServerName = serverName,
                message = "Enter the 6-digit code shown on the TV"
            )
        } catch (e: Exception) {
            _state.value = _state.value.copy(phase = Phase.ERROR, message = "Pairing failed: ${e.message}")
        }
    }

    fun submitPairingCode(code: String) {
        val pairing = pendingPairing
        if (pairing == null) return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                pairing.finish(code)
                pairing.close()
                pendingPairing = null
                val host = currentHost
                if (host != null) connectRemote(host)
            } catch (e: Exception) {
                _state.value = _state.value.copy(
                    phase = Phase.ERROR,
                    message = "Pairing rejected: ${e.message}"
                )
            }
        }
    }

    private fun loadCertForPairing(): java.security.cert.X509Certificate =
        certStore.readCertificate()

    // ---- commands ---------------------------------------------------------

    fun sendKey(name: String) {
        val conn = remote ?: return
        val code = try { Keys.resolve(name) } catch (e: Exception) { return }
        viewModelScope.launch(Dispatchers.IO) { conn.sendKey(code) }
    }

    fun launchApp(link: String) {
        remote?.let { conn -> viewModelScope.launch(Dispatchers.IO) { conn.launchApp(link) } }
    }

    fun sendText(text: String) {
        remote?.let { conn -> viewModelScope.launch(Dispatchers.IO) { conn.sendText(text) } }
    }

    fun setVolume(up: Boolean) {
        sendKey(if (up) "VOLUME_UP" else "VOLUME_DOWN")
        // Optimistically reflect the change; TV will confirm.
        _state.value = _state.value.copy(
            volume = ((_state.value.volume ?: 0) + if (up) 1 else -1).coerceIn(0, _state.value.volumeMax ?: 100)
        )
    }

    fun toggleMute() = sendKey("MUTE")

    fun power() = sendKey("POWER")

    fun disconnect() {
        remote?.close()
        remote = null
        adb?.close()
        adb = null
        _state.value = _state.value.copy(phase = Phase.IDLE, isOn = null, currentApp = null)
    }

    // ---- ADB (network debugging) extras -------------------------------------

    private fun connectAdb(host: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val a = TvAdb(getApplication(), host)
            if (a.connect()) {
                adb = a
                refreshAdb()
            } else {
                _state.value = _state.value.copy(
                    adbConnected = false,
                    message = "ADB non raggiungibile: abilita Opzioni sviluppatore -> Debug di rete sul TV."
                )
            }
        }
    }

    fun refreshAdb() {
        val a = adb ?: return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val apps = a.scanInstalled()
                val inputs = a.scanInputs()
                val audio = a.scanAudio()
                _state.value = _state.value.copy(
                    adbConnected = true,
                    installedApps = apps,
                    inputs = inputs,
                    audioOutputs = audio,
                    message = null
                )
            } catch (e: Exception) {
                _state.value = _state.value.copy(adbConnected = true, message = "ADB scan failed: ${e.message}")
            }
        }
    }

    fun launchInstalledApp(pkg: String) {
        adb?.let { a -> viewModelScope.launch(Dispatchers.IO) { a.launchApp(pkg) } }
    }

    fun switchInput(inputId: String) {
        adb?.let { a -> viewModelScope.launch(Dispatchers.IO) { a.switchInput(inputId) } }
    }

    fun openSoundSettings() {
        adb?.let { a -> viewModelScope.launch(Dispatchers.IO) { a.openSoundSettings() } }
    }

    fun openQuickSettings() {
        adb?.let { a -> viewModelScope.launch(Dispatchers.IO) { a.openQuickSettings() } }
    }

    fun adbStartActivity(component: String) {
        adb?.let { a -> viewModelScope.launch(Dispatchers.IO) { a.startActivity(component) } }
    }

    // ---- voice ------------------------------------------------------------

    /** Tear down the voice session and surface an error to the UI. */
    private fun failVoice(message: String) {
        if (_state.value.voiceActive) {
            _state.value = _state.value.copy(
                voiceActive = false,
                voiceStatus = VoiceStatus.ERROR,
                voiceMessage = null,
                voiceError = message
            )
            remote?.endVoice()
            voiceThread?.interrupt()
            voiceThread = null
        }
    }

    fun startVoice() {
        if (_state.value.voiceActive) return
        val conn = remote ?: return
        if (remote?.isConnected() != true) {
            failVoice("Not connected to the TV.")
            return
        }
        _state.value = _state.value.copy(
            voiceActive = true,
            voiceStatus = VoiceStatus.STARTING,
            voiceMessage = "Starting voice...",
            voiceError = null
        )
        conn.startVoice()
        // Capture mic audio in a background thread and stream it.
        voiceThread = Thread({
            var rec: AudioRecord? = null
            try {
                val bufferSize = AudioRecord.getMinBufferSize(
                    SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
                )
                if (bufferSize <= 0) {
                    failVoice("Microphone buffer allocation failed.")
                    return@Thread
                }
                val record = AudioRecord(
                    MediaRecorder.AudioSource.MIC, SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufferSize
                )
                rec = record
                audioRecord = record
                if (record.state != AudioRecord.STATE_INITIALIZED) {
                    failVoice("Microphone unavailable. Check the microphone permission.")
                    return@Thread
                }
                record.startRecording()
                _state.value = _state.value.copy(voiceStatus = VoiceStatus.LISTENING, voiceMessage = "Listening...")
                val buf = ByteArray(4000)
                while (_state.value.voiceActive && !Thread.currentThread().isInterrupted) {
                    val n = record.read(buf, 0, buf.size)
                    when {
                        n > 0 -> conn.sendVoiceChunk(buf.copyOf(n))
                        n == AudioRecord.ERROR_DEAD_OBJECT -> {
                            failVoice("Microphone was disconnected.")
                            break
                        }
                        n == AudioRecord.ERROR_INVALID_OPERATION || n == AudioRecord.ERROR_BAD_VALUE -> {
                            failVoice("Microphone stopped unexpectedly.")
                            break
                        }
                    }
                }
            } catch (e: SecurityException) {
                failVoice("Microphone permission denied.")
            } catch (e: Exception) {
                failVoice("Voice failed: ${e.message ?: e.javaClass.simpleName}")
            } finally {
                try {
                    rec?.stop()
                } catch (_: Exception) {}
                rec?.release()
                audioRecord = null
                rec = null
            }
        }, "voice-stream").apply { isDaemon = true; start() }
    }

    fun stopVoice() {
        if (!_state.value.voiceActive && _state.value.voiceStatus != VoiceStatus.LISTENING) return
        _state.value = _state.value.copy(
            voiceActive = false,
            voiceStatus = VoiceStatus.IDLE,
            voiceMessage = null,
            voiceError = null
        )
        remote?.endVoice()
        voiceThread?.interrupt()
        voiceThread = null
    }

    /** Reset the voice UI: called when the TV reports the session ended. */
    private fun endVoiceFromTv() {
        if (_state.value.voiceStatus == VoiceStatus.ERROR) return
        _state.value = _state.value.copy(
            voiceActive = false,
            voiceStatus = VoiceStatus.IDLE,
            voiceMessage = null
        )
        voiceThread?.interrupt()
        voiceThread = null
    }

    // ---- remote listener --------------------------------------------------

    private val listener = object : RemoteConnection.Listener {
        override fun onConnected(deviceInfo: RemoteConnection.DeviceInfo?) {
            _state.value = _state.value.copy(
                phase = Phase.CONNECTED,
                model = deviceInfo?.let { "${it.manufacturer} ${it.model}".trim() },
                message = null
            )
        }

        override fun onDisconnected(reason: String?) {
            if (_state.value.phase == Phase.CONNECTED) {
                _state.value = _state.value.copy(
                    phase = Phase.IDLE,
                    message = reason ?: "Disconnected from TV"
                )
            }
        }

        override fun onIsOnChanged(isOn: Boolean) {
            _state.value = _state.value.copy(isOn = isOn)
        }

        override fun onCurrentAppChanged(pkg: String?) {
            _state.value = _state.value.copy(currentApp = pkg)
        }

        override fun onVolumeChanged(level: Int, max: Int, muted: Boolean) {
            _state.value = _state.value.copy(volume = level, volumeMax = max, muted = muted)
        }

        override fun onVoiceSessionStarted(sessionId: Int) {
            // Acknowledge the session is live; keep streaming focus on the mic thread.
            if (_state.value.voiceStatus == VoiceStatus.STARTING) {
                _state.value = _state.value.copy(voiceStatus = VoiceStatus.LISTENING, voiceMessage = "Listening...")
            }
        }

        override fun onVoiceSessionEnded() {
            endVoiceFromTv()
        }
    }

    companion object {
        const val SAMPLE_RATE = 8000
    }

    override fun onCleared() {
        stopVoice()
        remote?.close()
        adb?.close()
        super.onCleared()
    }
}
