plugins {
    id("com.android.application")
    kotlin("android")
}

android {
    namespace = "spike.android"
    compileSdk = 35

    defaultConfig {
        applicationId = "spike.android"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
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
    implementation(project(":api"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.1")
    implementation("io.ktor:ktor-server-core:3.0.3")
    implementation("io.ktor:ktor-server-cio:3.0.3")
    implementation("io.ktor:ktor-server-websockets:3.0.3")
    implementation("org.slf4j:slf4j-simple:2.0.16")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    androidTestImplementation("androidx.test:core:1.6.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}

// The same zip the desktop host verifies, packaged into the app assets.
evaluationDependsOn(":plugin")
val pluginProject = project(":plugin")
val pluginZipTask = pluginProject.tasks.named<Zip>("polyglot")
val pluginAssetsDir = layout.buildDirectory.dir("generated/pluginAssets")

val copyPluginAsset = tasks.register<Copy>("copyPluginAsset") {
    dependsOn(pluginZipTask)
    from(pluginZipTask.flatMap { it.archiveFile })
    into(pluginAssetsDir)
}

android.sourceSets.getByName("main").assets.srcDir(pluginAssetsDir)

tasks.matching { it.name == "mergeDebugAssets" || it.name == "mergeReleaseAssets" }.configureEach {
    dependsOn(copyPluginAsset)
}
