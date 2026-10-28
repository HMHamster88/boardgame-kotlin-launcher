plugins {
    kotlin("jvm") version "2.1.21" apply false
    kotlin("android") version "2.1.21" apply false
    kotlin("multiplatform") version "2.1.21" apply false
    kotlin("plugin.serialization") version "2.1.21" apply false
    id("com.android.library") version "8.7.3" apply false
    id("com.android.application") version "8.7.3" apply false
}

allprojects {
    group = "com.hmhamster"
    version = "0.1.0"
}
