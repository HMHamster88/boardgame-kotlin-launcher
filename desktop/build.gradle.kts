plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
    application
    id("com.gradleup.shadow")
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
}

dependencies {
    implementation(project(":host"))
    implementation(project(":shared"))
    implementation("com.hmhamster:boardgame-api:0.1.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.1")
}

application {
    mainClass.set("boardgame.launcher.desktop.MainKt")
    applicationDefaultJvmArgs = listOf("-Dfile.encoding=UTF-8")
}

tasks.named<JavaExec>("run") {
    // Resolve relative DEV / CORE_JAR / DATA_DIR paths from the launcher repository root.
    workingDir = rootProject.projectDir
}

/** Development launcher: hot reload with a local data dir, no environment setup required. */
tasks.register<JavaExec>("devRun") {
    group = "application"
    description = "Runs the launcher in development mode (DEV=true, DATA_DIR=build/dev-data)"
    // Run the fat jar: it embeds the API, so rebuilding core/api while the launcher runs is not
    // blocked by a locked jar on Windows.
    val shadowJar = tasks.named<Jar>("shadowJar")
    dependsOn(shadowJar)
    mainClass.set("boardgame.launcher.desktop.MainKt")
    classpath = files(shadowJar.flatMap { it.archiveFile })
    workingDir = rootProject.projectDir
    environment("DEV", "true")
    environment("DATA_DIR", System.getenv("DATA_DIR") ?: "build/dev-data")
    val coreJar = rootProject.file("../core/back/build/libs")
        .listFiles { file -> file.isFile && file.extension == "jar" }
        ?.maxByOrNull { it.lastModified() }
    environment("CORE_JAR", System.getenv("CORE_JAR") ?: (coreJar?.absolutePath ?: "../core/back/build/libs/back.jar"))
    environment("PORT", System.getenv("PORT") ?: "8000")
    // IDEA cannot feed the Gradle daemon's stdin; pass -PlauncherCli to use :desktop:run from a terminal.
    environment("NO_CLI", if (project.hasProperty("launcherCli")) "0" else "1")
}

/**
 * Single executable jar: launcher + host + shared + API + runtime dependencies, service files merged.
 * Run with `java -jar boardgame-launcher-<version>.jar`.
 */
tasks.shadowJar {
    archiveBaseName.set("boardgame-launcher")
    archiveClassifier.set("")
    archiveVersion.set(project.version.toString())
    mergeServiceFiles()
    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA")
    manifest {
        attributes["Main-Class"] = "boardgame.launcher.desktop.MainKt"
    }
}

// ---------------------------------------------------------------- Windows app-image (jpackage)

val isWindows = System.getProperty("os.name").lowercase().contains("win")

/** Only the fat jar goes into jpackage's input dir (no thin jars/dependencies). */
val jpackageInput = layout.buildDirectory.dir("jpackage/input")
val jlinkRuntime = layout.buildDirectory.dir("jpackage/runtime")

val stageLauncherJar = tasks.register<Sync>("stageLauncherJar") {
    from(tasks.named<Jar>("shadowJar").flatMap { it.archiveFile })
    into(jpackageInput)
}

/** Trimmed JRE: what the launcher needs (NIO/Ktor, serialization, TLS, zip, charsets). */
val linkLauncherRuntime = tasks.register<Exec>("linkLauncherRuntime") {
    group = "release"
    description = "Creates the trimmed JRE embedded in the Windows launcher"
    onlyIf { isWindows }
    val jlink = File(System.getProperty("java.home"), "bin/jlink.exe")
    doFirst { jlinkRuntime.get().asFile.deleteRecursively() }
    commandLine(
        jlink.absolutePath,
        "--add-modules",
        "java.base,java.logging,java.naming,jdk.crypto.ec,jdk.unsupported,jdk.zipfs,jdk.charsets",
        "--output", jlinkRuntime.get().asFile.absolutePath,
        "--strip-debug",
        "--no-header-files",
        "--no-man-pages",
        "--compress=zip-6"
    )
}

/** Windows app-image: build/dist/Boardgames/Boardgames.exe with a bundled JRE. */
val desktopExe = tasks.register<Exec>("desktopExe") {
    group = "release"
    description = "Builds Boardgames.exe (jpackage app-image with a bundled JRE, Windows only)"
    onlyIf { isWindows }
    dependsOn(stageLauncherJar, linkLauncherRuntime)

    val jpackage = File(System.getProperty("java.home"), "bin/jpackage.exe")
    val dest = rootProject.layout.buildDirectory.dir("appimage").get().asFile
    val appVersion = project.version.toString().substringBefore('-')
        .takeIf { Regex("\\d+(\\.\\d+){1,3}").matches(it) } ?: "1.0.0"
    // The staged launcher jar is not a declared dependency input by default; without this a rebuilt
    // jar left the previously packaged Boardgames.exe in place.
    inputs.dir(jpackageInput)
    outputs.dir(File(dest, "Boardgames"))
    doFirst { File(dest, "Boardgames").deleteRecursively() }

    commandLine(
        jpackage.absolutePath,
        "--type", "app-image",
        "--name", "Boardgames",
        "--app-version", appVersion,
        "--input", jpackageInput.get().asFile.absolutePath,
        "--main-jar", "boardgame-launcher-${project.version}.jar",
        "--main-class", "boardgame.launcher.desktop.MainKt",
        "--runtime-image", jlinkRuntime.get().asFile.absolutePath,
        "--dest", dest.absolutePath,
        "--win-console",
        "--java-options", "-Dfile.encoding=UTF-8"
    )
}

val desktopExeZip = tasks.register<Zip>("desktopExeZip") {
    group = "release"
    description = "Zips the Windows app-image (Boardgames.exe + bundled JRE)"
    onlyIf { isWindows }
    dependsOn(desktopExe)
    archiveFileName.set("boardgame-launcher-${project.version}-windows.zip")
    destinationDirectory.set(rootProject.layout.buildDirectory.dir("dist"))
    from(rootProject.layout.buildDirectory.dir("appimage/Boardgames")) { into("Boardgames") }
}
