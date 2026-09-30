package it.paolostefani.tclremote.remote

/**
 * A curated catalog of well-known Android TV apps. The remote protocol can only
 * launch apps by package name or URL (app link); it cannot enumerate installed
 * apps without ADB. This catalog covers the most common apps plus an "Add
 * custom" entry the user can complete with any package name.
 */
object AppCatalog {

    data class App(val id: String, val label: String, val color: Long)

    val apps: List<App> = listOf(
        App("com.netflix.ninja", "Netflix", 0xFFE50914),
        App("https://www.youtube.com", "YouTube", 0xFFFF0000),
        App("com.google.android.youtube.tvmusic", "YouTube Music", 0xFFFF0000),
        App("com.disney.disneyplus", "Disney+", 0xFF0E47BA),
        App("com.amazon.amazonvideo.livingroom", "Prime Video", 0xFF00A8E1),
        App("com.apple.atve.androidtv.appletv", "Apple TV", 0xFF555555),
        App("com.wbd.stream", "Max", 0xFF002BE7),
        App("com.hulu.livingroomplus", "Hulu", 0xFF1CE783),
        App("com.spotify.tv.android", "Spotify", 0xFF1DB954),
        App("com.plexapp.android", "Plex", 0xFFE5A00D),
        App("org.videolan.vlc", "VLC", 0xFFFF8800),
        App("org.xbmc.kodi", "Kodi", 0xFF17B2E7),
        App("tv.twitch.android.app", "Twitch", 0xFF9146FF),
        App("com.cbs.ott", "Paramount+", 0xFF0064FF),
        App("com.peacocktv.peacockandroid", "Peacock", 0xFF000000),
        App("com.crunchyroll.crunchyroid", "Crunchyroll", 0xFFF47521),
        App("com.tubitv", "Tubi", 0xFF7408FF),
        App("tv.pluto.android", "Pluto TV", 0xFF191919),
        App("org.jellyfin.androidtv", "Jellyfin", 0xFF00A4DC),
        App("com.stremio.one", "Stremio", 0xFF7B5BF5),
        App("tv.emby.embyatv", "Emby", 0xFF52B54B),
        App("com.bydeluxe.d3.android.program.starz", "Starz", 0xFF101820),
        App("bbc.iplayer.android", "BBC iPlayer", 0xFFFF4C98),
        App("com.valvesoftware.steamlink", "Steam Link", 0xFF1B2838),
        App("com.limelight", "Moonlight", 0xFF7452FF),
        App("com.nvidia.geforcenow", "GeForce NOW", 0xFF76B900),
        App("ar.tvplayer.tv", "TiviMate", 0xFF3D5AFE),
        App("com.nst.iptvsmarterstvbox", "IPTV Smarters", 0xFF3F51B5),
        App("com.teamsmart.videomanager.tv", "SmartTube", 0xFFCC0000),
        App("com.anghami", "Anghami", 0xFF8D00F5),
        App("com.mubi", "MUBI", 0xFF001489),
        App("com.viki.android", "Viki", 0xFF0C9BFF),
    )
}
