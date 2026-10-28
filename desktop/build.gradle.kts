plugins {
    kotlin("jvm")
    application
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
    implementation("com.hmhamster:boardgame-api:0.1.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.1")
}

application {
    mainClass.set("boardgame.launcher.desktop.MainKt")
    applicationDefaultJvmArgs = listOf("-Dfile.encoding=UTF-8")
}
