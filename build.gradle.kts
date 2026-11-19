plugins {
    kotlin("jvm") version "2.1.21" apply false
    kotlin("android") version "2.1.21" apply false
    kotlin("multiplatform") version "2.1.21" apply false
    kotlin("plugin.serialization") version "2.1.21" apply false
    id("com.android.library") version "8.7.3" apply false
    id("com.android.application") version "8.7.3" apply false
    id("com.gradleup.shadow") version "8.3.6" apply false
}

/** Latest reachable git tag without the leading `v`; null when the repository has no tags. */
fun latestGitTag(): String? = runCatching {
    val process = ProcessBuilder("git", "describe", "--tags", "--abbrev=0")
        .directory(rootDir)
        .redirectErrorStream(true)
        .start()
    val tag = process.inputStream.bufferedReader().readText().trim()
    if (process.waitFor() == 0) tag.removePrefix("v").takeIf { it.isNotBlank() } else null
}.getOrNull()

/**
 * -PlauncherVersion=… > RELEASE_VERSION > latest git tag > default. Tag the release (and fetch tags)
 * before building so the artifact names match the GitHub release.
 */
val projectVersion = providers.gradleProperty("launcherVersion")
    .orElse(providers.environmentVariable("RELEASE_VERSION"))
    .getOrElse(latestGitTag() ?: "0.1.0")

allprojects {
    group = "com.hmhamster"
    version = projectVersion
}

// ---------------------------------------------------------------- release packaging

val releaseDist = layout.buildDirectory.dir("dist")

/** Desktop app-image (installDist) packaged as a zip. */
val desktopBundle = tasks.register<Zip>("desktopBundle") {
    group = "release"
    description = "Zips the desktop launcher distribution"
    dependsOn(":desktop:installDist")
    archiveFileName.set("boardgame-launcher-${version}-desktop.zip")
    destinationDirectory.set(releaseDist)
    from(project(":desktop").layout.buildDirectory.dir("install/desktop")) { into("launcher") }
}

/** Renames the unsigned release APK to a versioned artifact. */
val androidApk = tasks.register<Copy>("androidApk") {
    group = "release"
    description = "Copies the release APK to build/dist"
    dependsOn(":android-app:assembleRelease")
    from(project(":android-app").layout.buildDirectory.dir("outputs/apk/release")) {
        include("*.apk")
    }
    into(releaseDist)
    rename { "boardgame-launcher-${version}.apk" }
}

/** Single executable jar (all runtime dependencies, Main-Class set). */
val singleJar = tasks.register<Copy>("singleJar") {
    group = "release"
    description = "Copies the executable launcher jar to build/dist"
    dependsOn(":desktop:shadowJar")
    from(project(":desktop").layout.buildDirectory.dir("libs")) {
        include("boardgame-launcher-*.jar")
    }
    into(releaseDist)
}

/** Desktop release artifacts: the single executable jar and the installDist zip. */
val desktopRelease = tasks.register("desktopRelease") {
    group = "release"
    description = "Builds the desktop artifacts (single jar + desktop zip)"
    dependsOn(singleJar, desktopBundle)
    if (System.getProperty("os.name").lowercase().contains("win")) {
        dependsOn(":desktop:desktopExeZip")
    }
}

/** Android release artifact: the unsigned APK (embeds the core/CAH plugins and fronts). */
val androidRelease = tasks.register("androidRelease") {
    group = "release"
    description = "Builds the unsigned release APK"
    dependsOn(androidApk)
}

tasks.register("release") {
    group = "release"
    description = "Builds all launcher release artifacts"
    dependsOn(desktopRelease, androidRelease)
}
