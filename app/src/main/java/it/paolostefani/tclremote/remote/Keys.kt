package it.paolostefani.tclremote.remote

import it.paolostefani.tclremote.proto.RemoteKeyCode

/**
 * Maps friendly button names to Android TV Remote protocol keycodes.
 * Use RAW lookups for protocol names (e.g. "KEYCODE_POWER") too.
 */
object Keys {

    private val aliases: Map<String, RemoteKeyCode> = mapOf(
        "up" to RemoteKeyCode.KEYCODE_DPAD_UP,
        "down" to RemoteKeyCode.KEYCODE_DPAD_DOWN,
        "left" to RemoteKeyCode.KEYCODE_DPAD_LEFT,
        "right" to RemoteKeyCode.KEYCODE_DPAD_RIGHT,
        "ok" to RemoteKeyCode.KEYCODE_DPAD_CENTER,
        "select" to RemoteKeyCode.KEYCODE_DPAD_CENTER,
        "enter" to RemoteKeyCode.KEYCODE_DPAD_CENTER,
        "back" to RemoteKeyCode.KEYCODE_BACK,
        "home" to RemoteKeyCode.KEYCODE_HOME,
        "power" to RemoteKeyCode.KEYCODE_POWER,
        "play" to RemoteKeyCode.KEYCODE_MEDIA_PLAY_PAUSE,
        "pause" to RemoteKeyCode.KEYCODE_MEDIA_PLAY_PAUSE,
        "playpause" to RemoteKeyCode.KEYCODE_MEDIA_PLAY_PAUSE,
        "next" to RemoteKeyCode.KEYCODE_MEDIA_NEXT,
        "prev" to RemoteKeyCode.KEYCODE_MEDIA_PREVIOUS,
        "stop" to RemoteKeyCode.KEYCODE_MEDIA_STOP,
        "rewind" to RemoteKeyCode.KEYCODE_MEDIA_REWIND,
        "ff" to RemoteKeyCode.KEYCODE_MEDIA_FAST_FORWARD,
        "vol+" to RemoteKeyCode.KEYCODE_VOLUME_UP,
        "vol-" to RemoteKeyCode.KEYCODE_VOLUME_DOWN,
        "volup" to RemoteKeyCode.KEYCODE_VOLUME_UP,
        "voldown" to RemoteKeyCode.KEYCODE_VOLUME_DOWN,
        "mute" to RemoteKeyCode.KEYCODE_MUTE,
        "ch+" to RemoteKeyCode.KEYCODE_CHANNEL_UP,
        "ch-" to RemoteKeyCode.KEYCODE_CHANNEL_DOWN,
        "settings" to RemoteKeyCode.KEYCODE_SETTINGS,
        "guide" to RemoteKeyCode.KEYCODE_GUIDE,
        "info" to RemoteKeyCode.KEYCODE_INFO,
        "search" to RemoteKeyCode.KEYCODE_SEARCH,
    )

    fun resolve(name: String): Int = aliases[name.lowercase()]?.number
        ?: run {
            val n = if (name.startsWith("KEYCODE_")) name.uppercase() else "KEYCODE_${name.uppercase()}"
            try {
                RemoteKeyCode.valueOf(n).number
            } catch (e: IllegalArgumentException) {
                throw IllegalArgumentException("Unknown key: $name")
            }
        }
}
