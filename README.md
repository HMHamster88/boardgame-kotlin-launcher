# boardgame-kotlin-launcher

One project for every platform shell: the KMP launcher logic, the platform host (HTTP/WS, storage,
plugin loading), the desktop app and the Android app. There is no separate Android wrapper and no
Node.js runtime on the device.

## Layout

```
shared/      :shared        KMP (jvm + androidTarget): update check, download, verify, install
host/        :host          KMP (jvm + android): Ktor CIO server, document store, plugin loading
desktop/     :desktop       JVM app: CLI, process spawn, jpackage/jlink
androidApp/  :android-app   AGP app: WebView + foreground service
spike/                      Phase-1 spike (separate Gradle build, not part of the app)
```

## Status

Scaffold: `spike/` moved here (desktop + API 37 emulator checks are green). The app modules are not
created yet.

## Notes

- JDK 21 (Gradle 8.11 + AGP 8.7 reject JDK 25).
- `spike/` has its own `settings.gradle.kts` and must not be included by the app build.
- Per-repo releases: the launcher publishes the desktop app-image and the APK; the core plugin comes
  from `boardgame-kotlin-core` releases.
