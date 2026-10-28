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

Implemented:

- `shared/` — **Kotlin Multiplatform** (`jvm` + `androidTarget`): GitHub release lookup, semver update
  checks, checksum verification, atomic install (temp dir → move), zip extraction with path-traversal
  protection. JVM/Android file ops live in one `jvmSharedMain` source set. Tests cover install,
  checksum mismatch, release parsing, version compare and traversal.
- `host/` — `LauncherHost`: Ktor CIO server (`/ws`, `/server-info`, static fronts), file-backed
  document store, injectable `PluginLoader` (`URLClassLoader` on desktop, `DexClassLoader` on
  Android), `HttpURLConnection`-based HTTP that works on both platforms.
- `desktop/` — console launcher (`DATA_DIR`, `PORT`, `CORE_JAR`, `FRONT_DIR`, `NO_CLI`).
- `android-app/` — foreground service + WebView; extracts dual-format plugin jars and fronts from
  APK assets, loads them with `DexClassLoader` (read-only mark), serves the SPA on `127.0.0.1:8080`.
  The build runs d8 on the core/CAH jars and packages `classes.dex` + resources + fronts.
- `spike/` — the phase-1 plugin/dex/Ktor spike.

Verified on API 37 emulator: core plugin + CAH module loaded from dex, SPA served inside the app,
game created, CAH remote loaded and played (question, hand, Submit).

Not implemented yet: wiring `:shared` into the desktop/Android update checks (they still take local
artifacts), APK/app-image signing, Play-independent update of the app shell, iOS/macOS targets.

## Run (development)

```bash
# core and game plugin jars (needed by both targets)
gradle -p ../core :back:jar
gradle -p ../cah :back:jar

# shared tests
gradle :shared:jvmTest

# desktop
mkdir -p "$DATA_DIR/games-modules/cah/back"
cp ../cah/back/build/libs/back-0.1.0.jar "$DATA_DIR/games-modules/cah/back/cah-0.1.0.jar"
DATA_DIR=... CORE_JAR=../core/back/build/libs/back-0.1.0.jar gradle :desktop:run

# android (copies fronts and d8-dexes the jars into APK assets)
gradle :android-app:assembleDebug
adb install -r android-app/build/outputs/apk/debug/android-app-debug.apk
adb shell am start -n boardgame.launcher.android/.MainActivity
```

The end-to-end check drives the captured 31-step protocol scenario (requires the workspace `tools/`):

```bash
NO_CLI=1 DATA_DIR=... CORE_JAR=... PORT=8124 java -cp "desktop/build/install/desktop/lib/*" boardgame.launcher.desktop.MainKt
node ../../tools/e2e-kotlin.mjs --port 8124
```

## Notes

- JDK 21 (Gradle 8.11 + AGP 8.7 reject JDK 25).
- `spike/` has its own `settings.gradle.kts` and must not be included by the app build.
- Per-repo releases: the launcher publishes the desktop app-image and the APK; the core plugin comes
  from `boardgame-kotlin-core` releases.
