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
