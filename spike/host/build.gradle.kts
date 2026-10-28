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
    implementation(project(":api"))
}

application {
    mainClass.set("spike.host.MainKt")
}

evaluationDependsOn(":plugin")
val pluginProject = project(":plugin")
val pluginZip = pluginProject.layout.buildDirectory.file("dist/spike-module.zip")

tasks.named<JavaExec>("run") {
    dependsOn(pluginProject.tasks.named("polyglot"))
    args(pluginZip.get().asFile.absolutePath)
    standardInput = System.`in`
}
