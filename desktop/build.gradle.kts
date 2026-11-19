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
