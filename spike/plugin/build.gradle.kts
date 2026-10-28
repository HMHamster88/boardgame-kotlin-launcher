import java.util.Properties

plugins {
    kotlin("jvm")
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
    // Provided by the host / core classloader at runtime; must never be bundled in the plugin jar.
    compileOnly(project(":api"))
}

// --- dual-format plugin artefact: <classes> + classes.dex + resources in one zip ---

val sdkDir: String = run {
    val localProperties = rootProject.file("local.properties")
    if (localProperties.exists()) {
        Properties().apply { localProperties.inputStream().use { load(it) } }.getProperty("sdk.dir")
    } else {
        System.getenv("ANDROID_HOME") ?: System.getenv("ANDROID_SDK_ROOT")
    } ?: error("sdk.dir is missing: create local.properties or set ANDROID_HOME")
}

val buildToolsVersion = "35.0.0"
val androidJar = file("$sdkDir/platforms/android-35/android.jar")
val isWindows = System.getProperty("os.name").lowercase().contains("win")
val d8 = file("$sdkDir/build-tools/$buildToolsVersion/" + if (isWindows) "d8.bat" else "d8")

val jarFile = tasks.named<Jar>("jar").flatMap { it.archiveFile }
val dexDir = layout.buildDirectory.dir("dex")

val dex = tasks.register<Exec>("dex") {
    group = "build"
    description = "Converts the plugin jar to classes.dex with d8"
    dependsOn(tasks.named("jar"))
    inputs.file(jarFile)
    inputs.file(androidJar)
    outputs.dir(dexDir)
    doFirst {
        dexDir.get().asFile.mkdirs()
    }
    val args = listOf(
        "--release",
        "--min-api", "26",
        "--lib", androidJar.absolutePath,
        "--output", dexDir.get().asFile.absolutePath,
        jarFile.get().asFile.absolutePath
    )
    if (isWindows) {
        commandLine(listOf("cmd", "/c", d8.absolutePath) + args)
    } else {
        commandLine(listOf(d8.absolutePath) + args)
    }
}

val polyglot = tasks.register<Zip>("polyglot") {
    group = "build"
    description = "Single plugin zip: .class entries (desktop) + classes.dex (Android)"
    dependsOn(dex)
    archiveFileName.set("spike-module.zip")
    destinationDirectory.set(layout.buildDirectory.dir("dist"))
    from(zipTree(jarFile)) {
        exclude("META-INF/MANIFEST.MF")
    }
    from(dexDir)
}
