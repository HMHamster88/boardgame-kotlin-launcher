pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}

rootProject.name = "boardgame-kotlin-launcher"

includeBuild("../core") {
    dependencySubstitution {
        substitute(module("com.hmhamster:boardgame-api")).using(project(":api"))
    }
}

include(":host", ":desktop")
