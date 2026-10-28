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
  protection, and `CoreResolver` (installed-version scan + optional update). JVM/Android file ops and
  HTTP live in one `jvmSharedMain` source set. Tests cover install, checksum mismatch, release
  parsing, version compare, traversal and resolver behaviour.
- `host/` — `LauncherHost`: Ktor CIO server (`/ws`, `/server-info`, static fronts), file-backed
  document store, injectable `PluginLoader` (`URLClassLoader` on desktop, `DexClassLoader` on
  Android), shared `HttpURLConnection` client, and access to the running core for the CLI.
- `desktop/` — console launcher: resolves the core via `CoreResolver` (or `CORE_JAR`), optional
  `CHECK_FOR_UPDATES=true`, and a CLI (`game-modules list|add`, `users list`, `users roles add|remove`).
- `android-app/` — foreground service + WebView; extracts the core into
  `<dataDir>/core/<version>/back/core.jar`, game modules and fronts from APK assets, and loads plugins
  with `DexClassLoader` (read-only mark). Serves the SPA on `127.0.0.1:8080`; WebView is padded by
  system-bar insets (Android 15 edge-to-edge).
- `spike/` — the phase-1 plugin/dex/Ktor spike.

Verified on API 37 emulator: core plugin + CAH module loaded from dex, SPA served inside the app,
game created, CAH remote loaded and played (question, hand, Submit); header visible after the inset
fix. Desktop e2e: the 31-step protocol scenario and the CLI commands.

Not implemented yet: app-image/APK signing, updating the core while the app is running, game-module
updates on Android (bundled copy is read-only), iOS/macOS targets.

## Run (development)

```bash
# core and game plugin jars (needed by both targets)
gradle -p ../core :back:jar
gradle -p ../cah :back:jar

# shared tests
gradle :shared:jvmTest

# desktop (uses CORE_JAR, or CHECK_FOR_UPDATES=true to fetch boardgame-kotlin-core)
mkdir -p "$DATA_DIR/games-modules/cah/back"
cp ../cah/back/build/libs/back-0.1.0.jar "$DATA_DIR/games-modules/cah/back/cah-0.1.0.jar"
DATA_DIR=... CORE_JAR=../core/back/build/libs/back-0.1.0.jar gradle :desktop:run

# android (copies fronts and d8-dexes the jars into APK assets)
gradle :android-app:assembleDebug
adb install -r android-app/build/outputs/apk/debug/android-app-debug.apk
adb shell am start -n boardgame.launcher.android/.MainActivity
```

Desktop env: `DATA_DIR`, `PORT`, `CORE_JAR`, `CORE_HOMEPAGE`, `CHECK_FOR_UPDATES`, `FRONT_DIR`, `NO_CLI`.
CLI: `game-modules list|add <homepage>`, `users list`, `users roles add|remove <userId> <role>`, `help`, `exit`.

## Release packaging

```bash
gradle release
# -> build/dist/boardgame-launcher-<v>.jar           single executable jar (all deps, merged services)
# -> build/dist/boardgame-launcher-<v>-desktop.zip   installDist (needs a JRE on the target)
# -> build/dist/boardgame-launcher-<v>.apk           unsigned release APK
```

The single jar is the quickest way to run a release (Java 11+):

```bash
java -jar boardgame-launcher-0.1.0.jar
```

It contains the launcher, host, shared KMP (jvm target), API and all runtime dependencies; only the
core/game plugin jars are external (resolved from `<DATA_DIR>/core/<v>/` or downloaded).

The Android release APK embeds the dual-format core/CAH jars and both fronts, d8-dexed by the
`android-app` build. APK signing and `jpackage` app-images are still future work.

Consuming a release: unzip the core asset into `<DATA_DIR>/core/<version>/` and a game asset into
`<DATA_DIR>/games-modules/<name>/`, then start without `CORE_JAR` — `CoreResolver` picks it up.

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
