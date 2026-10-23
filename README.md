# SeparateSound

Per-app audio output routing for rooted Android — send one app to your
Bluetooth speaker while everything else stays on the phone speaker, like
Samsung's "Separate App Sound", on any device.

SeparateSound is an [LSPosed](https://github.com/LSPosed/LSPosed) module
built on the modern [libxposed](https://github.com/libxposed) API (API 102).
In the settings app you pick, per app, where its audio should go:

- **System** — follow the normal Android routing (default)
- **Bluetooth** — force the app's audio to the connected Bluetooth A2DP device
- **Speaker** — force the app's audio to the phone's built-in speaker

## Requirements

- Android 8.1 (API 27) or newer
- A root solution with a working Xposed framework that supports the modern
  libxposed API (current LSPosed releases)
- No Google Play Services, no account, no network access

## Install & use

1. Install `SeparateSound-v1.0.apk` from the
   [Releases](../../releases) page.
2. Open **LSPosed Manager**, enable the **SeparateSound** module, and tick
   the apps you want to control in its scope. (YouTube Music and TikTok are
   pre-suggested in the module's scope list.)
3. Reboot, or at least force-stop the target apps once.
4. Open SeparateSound (from your launcher, or later from LSPosed Manager →
   Modules → SeparateSound), turn on **Enable Separate Sound**, and set a
   route for each app.
5. Force-stop a target app and reopen it — routes are applied when the app
   process starts.
6. Test: with a Bluetooth speaker connected, an app routed to *Bluetooth*
   plays there, while an app routed to *Speaker* keeps playing on the phone.

Tip: the settings contain a **Hide launcher icon** switch. After hiding, open
the settings again from LSPosed Manager (the module entry) — the settings
activity is declared for LSPosed, so hiding the icon never locks you out.

## How it works

The module runs inside each scoped app process. On package-ready it reads
the shared **remote preferences** group `routes` (written by the settings
app through LSPosed's `XposedService`):

- `enabled` (boolean) — the master switch
- one string key per package name — `"bluetooth"` or `"speaker"`
  (no key = follow the system)

If a route exists, the module hooks every `AudioTrack` and `MediaPlayer`
construction (including `AudioTrack.Builder.build()`) and calls
`setPreferredDevice()` with the currently connected device of the requested
type (Bluetooth A2DP or built-in speaker). Created players are kept in a
weak registry; an `AudioDeviceCallback` re-applies the route whenever
devices appear or disappear, so a speaker that connects after the app
started is picked up. If the requested device is absent, nothing is forced
and the system routing applies — the same natural fallback Samsung uses.

## Honest limitations

- Routing enforcement ultimately depends on the ROM's audio policy. The
  platform documents preferred-device routing as a request; if the system
  ignores it, no app can force it. Verified behaviour therefore comes from
  testing on your device/ROM.
- Apps that play audio through native/offloaded paths that never create a
  Java `AudioTrack`/`MediaPlayer` (some games) may not be captured by the
  hooks.
- Route changes apply to newly started app processes — force-stop and
  reopen a target app after changing its route or the module scope.

## Building

Standard Gradle Android project (AGP 9.4, Gradle 9.7, JDK 17,
compileSdk 37 as required by the libxposed service AAR):

```sh
./gradlew assembleRelease
```

Dependencies are only the libxposed artifacts from Maven Central
(`io.github.libxposed:api:102.0.0` compile-only,
`io.github.libxposed:service:102.0.0`). The UI uses plain Android framework
widgets — no AppCompat/Material — to keep the APK small.

Release builds are signed with `separatesound.keystore` in the repo root so
that every release shares one certificate and updates install in place.

## License

MIT — see [LICENSE](LICENSE).

---

## Bahasa Indonesia

SeparateSound adalah modul LSPosed (API modern libxposed) untuk memisahkan
keluaran suara per aplikasi, meniru fitur "Separate App Sound" Samsung:
misalnya YouTube Music keluar di speaker Bluetooth, sementara aplikasi lain
tetap di speaker HP.

Cara pakai singkat:

1. Pasang APK dari halaman Releases.
2. Aktifkan modul di LSPosed Manager dan centang scope aplikasi yang mau
   diatur, lalu reboot / paksa berhenti aplikasi target.
3. Buka SeparateSound, nyalakan saklar utama, lalu pilih rute tiap
   aplikasi: **Sistem**, **Bluetooth**, atau **Speaker**.
4. Paksa berhenti lalu buka lagi aplikasi target, dan uji.

Batasan jujur: penegakan rute bergantung pada kebijakan audio ROM — bila
sistem mengabaikan rute pilihan, aplikasi tidak dapat memaksanya; sebagian
game dengan jalur audio native/offload mungkin tidak tertangkap hook.
Pengaturan tetap bisa dibuka dari LSPosed Manager walaupun ikon launcher
disembunyikan lewat saklar di dalam aplikasi.
