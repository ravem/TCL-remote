package it.paolostefani.tclremote.remote

import android.content.Context
import dadb.AdbKeyPair
import dadb.Dadb
import java.io.File

/**
 * Talks to the TV's ADB daemon over the network (network debugging, port 5555).
 *
 * Unlike the remote protocol, ADB can enumerate installed apps and access input
 * / audio / quick-settings features. It requires network debugging to be enabled
 * on the TV (Settings -> System -> Developer options -> Network debugging).
 *
 * The ADB key pair is generated locally and reused across app restarts so the
 * TV recognises this app the second time around and no longer prompts.
 */
class TvAdb(private val context: Context, private val host: String) {

    private var dadb: Dadb? = null

    private val privateKeyFile: File
        get() = File(context.filesDir, "adbkey")
    private val publicKeyFile: File
        get() = File(context.filesDir, "adbkey.pub")

    val isConnected: Boolean
        get() = dadb != null

    fun connect(timeoutMs: Int = 8000): Boolean {
        // Try a quick reachability ping first so we fail fast and clearly.
        val reachable = try {
            java.net.Socket().use { it.connect(java.net.InetSocketAddress(host, 5555), timeoutMs); true }
        } catch (_: Exception) {
            false
        }
        if (!reachable) return false
        return try {
            if (!privateKeyFile.exists()) {
                AdbKeyPair.generate(privateKeyFile, publicKeyFile)
            }
            val keyPair = AdbKeyPair.read(privateKeyFile, publicKeyFile)
            val d = Dadb.create(host, 5555, keyPair, connectTimeout = timeoutMs, socketTimeout = timeoutMs)
            // A cheap command verifies the connection and auth handshake.
            val res = d.shell("echo ok")
            dadb = d
            res.exitCode == 0
        } catch (_: Exception) {
            try { dadb?.close() } catch (_: Exception) {}
            dadb = null
            false
        }
    }

    fun close() {
        try { dadb?.close() } catch (_: Exception) {}
        dadb = null
    }

    private fun shell(cmd: String): String? = try {
        dadb?.shell(cmd)?.output
    } catch (_: Exception) {
        null
    }

    // ---- installed apps ----------------------------------------------------

    data class InstalledApp(val name: String, val pkg: String)

    fun scanInstalled(): List<InstalledApp> {
        var pkgs: Set<String> = emptySet()
        val out = shell(
            "cmd package query-activities --brief -a android.intent.action.MAIN -c android.intent.category.LEANBACK_LAUNCHER"
        )
        if (out != null) {
            pkgs = Regex("^\\s*([\\w.]+)/", RegexOption.MULTILINE)
                .findAll(out).map { it.groupValues[1] }.toSet()
        }
        if (pkgs.isEmpty()) {
            val legacy = shell("pm list packages -3")
            if (legacy != null) {
                pkgs = legacy.lineSequence()
                    .mapNotNull { it.takeIf { l -> l.contains(":") }?.substringAfter(":") }
                    .map { it.trim() }.filter { it.isNotEmpty() }.toSet()
            }
        }
        val noise = listOf(
            "com.android.", "com.google.android.tv.frameworkpackagestubs",
            "com.google.android.leanbacklauncher", "com.google.android.tungsten",
            "com.google.android.backdrop", "com.tcl."
        )
        return pkgs
            .filter { p -> noise.none { p.startsWith(it) } }
            .map { p -> InstalledApp(prettyName(p), p) }
            .sortedBy { it.name.lowercase() }
    }

    private fun prettyName(pkg: String): String {
        AppCatalog.apps.firstOrNull { it.id == pkg }?.let { return it.label }
        val generic = setOf("tv", "app", "android", "androidtv", "mobile", "play", "client")
        val parts = pkg.split(".")
        for (seg in parts.reversed()) {
            if (seg.lowercase() !in generic) return seg.replaceFirstChar { it.uppercase() }
        }
        return parts.last().replaceFirstChar { it.uppercase() }
    }

    // ---- input / HDMI ------------------------------------------------------

    data class InputSpec(val label: String, val type: String, val value: String)

    fun scanInputs(): List<InputSpec> {
        val dump = shell("dumpsys tv_input") ?: return emptyList()
        val airplay = shell("pm list packages | grep -i airplay") ?: ""
        val hasLiveTv = shell("pm list packages com.tcl.tv")?.contains("package:com.tcl.tv") == true

        val ports = Regex("TvInputHardwareInfo \\{id=(\\d+), type=9[^}]*hdmi_port=(\\d+)")
            .findAll(dump).associate { it.groupValues[1].toInt() to it.groupValues[2].toInt() }

        val specs = mutableListOf(InputSpec("Home", "key", "HOME"))
        if (hasLiveTv) specs.add(InputSpec("Live TV", "adb_app", "com.tcl.tv"))

        val seen = mutableSetOf<String>()
        val hdmi = mutableListOf<Pair<Int, InputSpec>>()
        Regex("(com\\.([\\w.]+)/\\.(\\w+)Service/HW(\\d+))")
            .findAll(dump).forEach { m ->
                val inputId = m.groupValues[1]
                if (!seen.add(inputId) || inputId.contains("TunerInputService")) return@forEach
                val port = ports[m.groupValues[4].toInt()]
                if (port != null) {
                    hdmi.add(port to InputSpec("HDMI $port", "adb_input", inputId))
                } else {
                    specs.add(InputSpec("AV", "adb_input", inputId))
                }
            }
        hdmi.sortedBy { it.first }.forEach { specs.add(it.second) }

        if (airplay.contains("realtek")) {
            specs.add(InputSpec("AirPlay", "adb_activity",
                "${airplay.substringAfter(":").trim()}/com.realtek.media.airplay2.SetupWizardActivity"))
        }
        return specs
    }

    fun switchInput(inputId: String) {
        val uri = "content://android.media.tv/passthrough/" + java.net.URLEncoder.encode(inputId, "UTF-8")
        shell("am start -a android.intent.action.VIEW -d \"$uri\"")
    }

    fun startActivity(component: String) = shell("am start -n $component")

    fun openQuickSettings() = shell("input keyevent KEYCODE_NOTIFICATION")

    fun openSoundSettings() {
        shell("am broadcast -a com.android.systemui.action.LAUNCH_MEDIA_OUTPUT_DIALOG --es package_name com.android.tv.settings")
        shell("am start -n com.android.tv.settings/com.tcl.settings.SoundActivity || am start -n com.android.tv.settings/.device.displaysound.DisplaySoundActivity")
    }

    fun launchApp(pkg: String) {
        shell("monkey -p $pkg -c android.intent.category.LEANBACK_LAUNCHER 1 || monkey -p $pkg -c android.intent.category.LAUNCHER 1")
    }

    // ---- audio -------------------------------------------------------------

    data class AudioOutput(val label: String, val kind: String, val active: Boolean, val mac: String? = null)

    fun scanAudio(): List<AudioOutput> {
        val devices = shell("dumpsys audio 2>/dev/null | grep -m1 ' Devices: '") ?: ""
        val earc = (shell("settings get global hdmi_earc_connected") ?: "").trim()
        val bonded = shell("dumpsys bluetooth_manager | grep -A20 'Bonded devices'") ?: ""

        val current = devices.substringAfter("Devices:", "").trim().lowercase()
        val kindActive = when {
            "a2dp" in current -> "bt"
            "hdmi" in current || "hmdi" in current -> "hdmi"
            "spdif" in current -> "spdif"
            else -> "speaker"
        }
        val outputs = mutableListOf(
            AudioOutput("TV Speakers", "speaker", "speaker" == kindActive),
            AudioOutput("HDMI ARC${if (earc == "1") " (eARC)" else ""}", "hdmi", "hdmi" == kindActive),
            AudioOutput("Optical (S/PDIF)", "spdif", "spdif" == kindActive),
        )
        Regex("^\\s*([0-9A-F:]{17})\\s+\\[BR/EDR\\]\\s+(.+?)\\s*$", RegexOption.MULTILINE)
            .findAll(bonded).forEach { m ->
                outputs.add(AudioOutput(m.groupValues[2], "bt", false, m.groupValues[1]))
            }
        return outputs
    }
}
