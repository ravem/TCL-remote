# TCL Remote (Android)

An Android app for controlling a TCL Google TV / Android TV over the local
network. It uses the Android TV Remote protocol v2, the same protocol used by
the Google TV mobile app, so no ADB and no developer options are required on
the TV.

The UI is built with Jetpack Compose and Material 3, adopting Material You
dynamic color on Android 12 and newer.

## Features

- Local network discovery of the TV via mDNS / NSD (Android TV Remote v2
  service).
- Pairing with the TV through the 6-digit code shown on screen. The client
  certificate is stored locally and reused, so pairing is done once until the
  TV is reset.
- Directional pad (up, down, left, right, OK), back and home.
- Power on / off and mute.
- Volume up / down.
- Text input to the TV's focused search field (IME).
- Launching apps from a curated catalog of well-known Android TV apps, using
  package names or app links. Since the remote protocol cannot enumerate
  installed apps, the list is curated and cannot list apps that are not in it.
- Push-to-talk voice input through Google Assistant on the TV. The phone acts
  as the microphone: audio is captured at 8 kHz mono 16-bit PCM and streamed to
  the TV over the same connection.
- Extra features when the TV network debugging (ADB) is enabled:
  - Listing the apps actually installed on the TV and launching them.
  - Switching between HDMI / AV inputs and the Live TV tuner.
  - Opening the audio output settings and the quick settings panel.

## Requirements

- Android 8.0 (API 26) or newer.
- The phone and the TV must be on the same local network.
- On Android 17 (API 37) the app requests the local network permission and on
  all versions it requests the microphone permission for voice input.
- The extra ADB features require the TV network debugging to be enabled:
  Settings -> System -> About, tap the Android TV OS build 7 times, then
  Developer options -> USB debugging and Network debugging. The first scan
  asks for authorisation on the TV.

## Protocol notes

The protocol is implemented in Kotlin without external remote-control
libraries. It uses:

- mDNS / NSD to discover `_androidtvremote2._tcp` services.
- A self-signed client certificate (RSA 2048, generated with BouncyCastle)
  used to authenticate against the TV.
- TLS connections: pairing on port 6467 and the persistent remote connection
  on port 6466.
- Protobuf messages (`polo.proto` for pairing and `remotemessage.proto` for
  commands), framed with a varint length prefix.
- An ADB client over the network (`dev.mobile:dadb`) for the optional
  network-debugging features (installed apps, inputs, audio).

## Building

```bash
./gradlew :app:assembleDebug
```

The debug APK is produced at
`app/build/outputs/apk/debug/app-debug.apk`.

## Install on a device

With USB debugging enabled and the device connected:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## Project layout

```
app/src/main/java/it/paolostefani/tclremote/
  MainActivity.kt            App entry point and permission requests.
  TclRemoteViewModel.kt      State holder and orchestration.
  ui/                        Compose screens and theme.
  remote/
    TvAdb.kt                  Optional ADB client (network debugging) extras.
    CertStore.kt             Self-signed certificate generation and SSL.
    PairingConnection.kt     Pairing handshake (port 6467).
    RemoteConnection.kt      Persistent remote connection (port 6466).
    TvDiscovery.kt           mDNS / NSD discovery.
    Keys.kt                  Friendly names to keycodes.
    AppCatalog.kt            Curated app catalog.
```
