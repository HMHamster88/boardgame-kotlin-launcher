import java.util.Properties
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

plugins {
    id("com.android.application")
    kotlin("android")
}

val sdkDir: String = Properties().apply {
    rootProject.file("local.properties").inputStream().use { load(it) }
}.getProperty("sdk.dir")

val buildTools = "35.0.0"
val androidJar = file("$sdkDir/platforms/android-35/android.jar")
val d8Bat = file("$sdkDir/build-tools/$buildTools/d8.bat")

android {
    namespace = "boardgame.launcher.android"
    compileSdk = 35

    defaultConfig {
        applicationId = "boardgame.launcher.android"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    packaging {
        resources.excludes += setOf(
            "META-INF/INDEX.LIST",
            "META-INF/DEPENDENCIES",
            "META-INF/LICENSE*",
            "META-INF/NOTICE*"
        )
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
    }
}

dependencies {
    implementation(project(":host"))
    implementation(project(":shared"))
    implementation("com.hmhamster:boardgame-api:0.1.0")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.1")
}

val generatedAssets = layout.buildDirectory.dir("generated/assets")

/** Copies the built Vue SPA and the game front into the APK assets. */
val copyCoreFront = tasks.register<Copy>("copyCoreFront") {
    from("../../core/back/public") { into("front") }
    into(generatedAssets)
}

val copyCahFront = tasks.register<Copy>("copyCahFront") {
    from("../../cah/front/dist") { into("modules/cah/front") }
    into(generatedAssets)
}

/**
 * Builds a dual-format plugin jar for Android: every non-class entry of the source jar plus a
 * `classes.dex` produced by d8. The same archive still works on desktop (URLClassLoader ignores the
 * dex; DexClassLoader ignores the class files). Uses the newest jar in the module's `build/libs`, so
 * a module version bump needs no changes here.
 */
fun registerDexTask(name: String, sourceDirPath: String, outputName: String) =
    tasks.register(name) {
        val sourceDir = file(sourceDirPath)
        val outputDir = generatedAssets.get().dir("plugins").asFile
        val output = File(outputDir, outputName)
        inputs.dir(sourceDir)
        outputs.file(output)
        doLast {
            val source = sourceDir.listFiles { file -> file.isFile && file.extension == "jar" }
                ?.maxByOrNull { it.lastModified() }
                ?: error("Missing module jar in $sourceDir — build the module first (gradle -p ../../core :back:jar or -p ../../cah :back:jar)")
            outputDir.mkdirs()
            val dexDir = File(temporaryDir, "dex").apply { deleteRecursively(); mkdirs() }
            val command = listOf(
                "cmd", "/c", d8Bat.absolutePath,
                "--release", "--min-api", "26",
                "--lib", androidJar.absolutePath,
                "--output", dexDir.absolutePath,
                source.absolutePath
            )
            val process = ProcessBuilder(command).redirectErrorStream(true).start()
            val log = process.inputStream.bufferedReader().readText()
            check(process.waitFor() == 0) { "d8 failed for ${source.name}:\n$log" }
            val dex = File(dexDir, "classes.dex")
            check(dex.isFile) { "d8 produced no classes.dex for ${source.name}:\n$log" }

            ZipFile(source).use { zip ->
                ZipOutputStream(output.outputStream()).use { out ->
                    zip.entries().asSequence()
                        .filter { !it.isDirectory && !it.name.endsWith(".class") }
                        .forEach { entry ->
                            out.putNextEntry(ZipEntry(entry.name))
                            zip.getInputStream(entry).use { it.copyTo(out) }
                            out.closeEntry()
                        }
                    out.putNextEntry(ZipEntry("classes.dex"))
                    dex.inputStream().use { it.copyTo(out) }
                    out.closeEntry()
                }
            }
        }
    }

val dexCore = registerDexTask(
    name = "dexCore",
    sourceDirPath = "../../core/back/build/libs",
    outputName = "core.jar"
)

val dexCah = registerDexTask(
    name = "dexCah",
    sourceDirPath = "../../cah/back/build/libs",
    outputName = "cah.jar"
)

android.sourceSets.getByName("main").assets.srcDir(generatedAssets)

// Everything that reads assets (merge, lint, bundle) must wait for the generated asset tasks.
tasks.matching {
    it.name.contains("Assets") || it.name.contains("Lint") ||
        it.name.contains("Bundle") || it.name.contains("Package")
}.configureEach {
    dependsOn(copyCoreFront, copyCahFront, dexCore, dexCah)
}
