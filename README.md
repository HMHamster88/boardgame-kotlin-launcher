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

- `host/` — `LauncherHost`: Ktor CIO server (`/ws`, `/server-info`, static fronts), file-backed
  document store, `URLClassLoader`/`ServiceLoader` plugin loading, desktop console.
- `desktop/` — console launcher (`DATA_DIR`, `PORT`, `CORE_JAR`, `FRONT_DIR`, `NO_CLI`).
- `spike/` — the phase-1 plugin/dex/Ktor spike.

Verified: end-to-end 31-step protocol scenario (lobby, join, start, full CAH round, ObjectSync
write-back, backup, reconnect, kick, bots) against the core + CAH modules.

Not implemented yet: `shared/` (KMP update/download/install), `androidApp/`, jpackage, CLI admin
commands.

## Run (development)

```bash
# core and game plugin jars
gradle -p ../core :back:jar
gradle -p ../cah :back:jar

# data dir with the game module
mkdir -p "$DATA_DIR/games-modules/cah/back"
cp ../cah/back/build/libs/back-0.1.0.jar "$DATA_DIR/games-modules/cah/back/cah-0.1.0.jar"

DATA_DIR=... CORE_JAR=../core/back/build/libs/back-0.1.0.jar gradle :desktop:run
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
