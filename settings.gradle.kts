pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "boardgame-kotlin-launcher"

includeBuild("../core") {
    dependencySubstitution {
        substitute(module("com.hmhamster:boardgame-api")).using(project(":api"))
    }
}

include(":shared", ":host", ":desktop", ":android-app")
