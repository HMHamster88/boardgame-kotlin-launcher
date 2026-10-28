plugins {
    kotlin("jvm") version "2.1.21" apply false
    kotlin("android") version "2.1.21" apply false
    kotlin("multiplatform") version "2.1.21" apply false
    kotlin("plugin.serialization") version "2.1.21" apply false
    id("com.android.library") version "8.7.3" apply false
    id("com.android.application") version "8.7.3" apply false
    id("com.gradleup.shadow") version "8.3.6" apply false
}

allprojects {
    group = "com.hmhamster"
    version = "0.1.0"
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

tasks.register("release") {
    group = "release"
    description = "Builds all launcher release artifacts"
    dependsOn(desktopBundle, androidApk, singleJar)
}
