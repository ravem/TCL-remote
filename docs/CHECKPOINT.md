# Checkpoint — TCL Remote (Android)

Stato del progetto e implementazioni previste. Aggiornato al primo rilascio
funzionante, in attesa dei test sulla TV fisica.

## Stato attuale (implementato)

App Android per controllare una TV TCL Google TV / Android TV tramite il
protocollo Android TV Remote v2 (stesso protocollo dell'app Google TV). Nessun
ADB e nessuna opzione sviluppatore richiesta sulla TV per le funzioni base.

- UI: Jetpack Compose, Material 3, Material You (colore dinamico da Android 12).
- Discovery della TV via mDNS / NSD (`_androidtvremote2._tcp`).
- Pairing tramite codice a 6 cifre mostrato dal TV. Certificato client
  self-signed (RSA 2048, BouncyCastle) generato e riusato in locale.
- Connessione remota persistente (porta 6466) con gestione di stato
  (acceso/spento, app corrente, volume).
- Comandi: D-pad, OK, back, home, power, volume su/giu, mute.
- Input testo via IME nelle caselle di ricerca della TV.
- Voice push-to-talk (il telefono fa da microfono, 8 kHz mono PCM).
- Catalogo curato di app note (avvio via app link / package).
- Permessi gestiti: ACCESS_LOCAL_NETWORK (Android 17), RECORD_AUDIO.

Build: `./gradlew :app:assembleDebug` (APK in
`app/build/outputs/apk/debug/app-debug.apk`).

## Implementazioni previste (prossime)

Queste funzioni richiedono il debug di rete attivato sulla TV (porta 5555).
Per abilitarlo: Impostazioni -> Sistema -> Informazioni -> premere 7 volte su
"Build della versione Android TV OS", poi Opzioni sviluppatore -> abilita
"Debug USB" e "Debug di rete". Alla prima scansione accettare la richiesta di
autorizzazione sul TV.

1. Enumerazione automatica delle app installate
   - Query degli activity LEANBACK_LAUNCHER via `cmd package query-activities`.
   - Fallback a `pm list packages -3` su firmware piu vecchi.
   - Risoluzione dei nomi reali tramite la pagina Play Store; cache in locale.
   - Esclusione dei package di sistema (com.android.*, com.tcl.*, ecc.).

2. Cambio input HDMI / AV
   - Lettura della topologia input via `dumpsys tv_input`.
   - Avvio dell'input passthrough tramite intent VIEW con URI
     `content://android.media.tv/passthrough/...`.
   - Scorciatoia "Live TV" per il servizio tuner (com.tcl.tv).

3. Impostazioni audio e quick settings
   - Apertura della schermata Audio Output (Sound settings).
   - Apertura del dialogo/scheda di Output Audio di sistema.
   - Apertura della dashboard Quick Settings.

4. Migliorie voce (opzionali)
   - Indicatore di stato e gestione errori della sessione vocale.

## Note tecniche

- Protocollo implementato in Kotlin senza librerie remote di terze parti.
- Connessioni TLS: pairing su porta 6467, remota su porta 6466.
- Messaggi protobuf: `app/src/main/proto/polo.proto` (pairing) e
  `app/src/main/proto/remotemessage.proto` (comandi), framing con varint.
- Telefono e TV devono essere sulla stessa rete locale.
- La enumerazione app via ADB e esclusa dal primo rilascio (richiede debug).

## Struttura del progetto

```
app/src/main/java/it/paolostefani/tclremote/
  MainActivity.kt            Entry point e permessi.
  TclRemoteViewModel.kt      Stato e orchestrazione.
  ui/                        Schermate Compose e tema.
  remote/
    CertStore.kt             Certificato self-signed e SSL.
    PairingConnection.kt     Handshake di pairing (porta 6467).
    RemoteConnection.kt      Connessione remota persistente (porta 6466).
    TvDiscovery.kt           Discovery mDNS / NSD.
    Keys.kt                  Nomi amichevoli -> keycode.
    AppCatalog.kt            Catalogo app curato.
```
