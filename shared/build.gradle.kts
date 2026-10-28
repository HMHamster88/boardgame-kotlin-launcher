plugins {
    kotlin("multiplatform")
    kotlin("plugin.serialization")
    id("com.android.library")
}

kotlin {
    jvm()

    androidTarget {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
        }
    }

    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.1")
                implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.0")
            }
        }

        // Both targets are JVM, so java.* implementations live in one shared intermediate set.
        val jvmSharedMain by creating {
            dependsOn(commonMain)
        }
        val jvmMain by getting { dependsOn(jvmSharedMain) }
        val androidMain by getting { dependsOn(jvmSharedMain) }

        val commonTest by getting {
            dependencies { implementation(kotlin("test")) }
        }
        val jvmTest by getting {
            dependencies { implementation(kotlin("test")) }
        }
    }
}

android {
    namespace = "boardgame.launcher.shared"
    compileSdk = 35

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}
