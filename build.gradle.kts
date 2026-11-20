plugins {
    kotlin("jvm") version "2.1.21" apply false
    kotlin("android") version "2.1.21" apply false
    kotlin("multiplatform") version "2.1.21" apply false
    kotlin("plugin.serialization") version "2.1.21" apply false
    id("com.android.library") version "8.7.3" apply false
    id("com.android.application") version "8.7.3" apply false
    id("com.gradleup.shadow") version "8.3.6" apply false
}

/** Latest reachable git tag without the leading `v`; null when the repository has no tags. */
fun latestGitTag(): String? = runCatching {
    val process = ProcessBuilder("git", "describe", "--tags", "--abbrev=0")
        .directory(rootDir)
        .redirectErrorStream(true)
        .start()
    val tag = process.inputStream.bufferedReader().readText().trim()
    if (process.waitFor() == 0) tag.removePrefix("v").takeIf { it.isNotBlank() } else null
}.getOrNull()

/**
 * -PlauncherVersion=… > RELEASE_VERSION > latest git tag > default. Tag the release (and fetch tags)
 * before building so the artifact names match the GitHub release.
 */
val projectVersion = providers.gradleProperty("launcherVersion")
    .orElse(providers.environmentVariable("RELEASE_VERSION"))
    .getOrElse(latestGitTag() ?: "0.1.0")

allprojects {
    group = "com.hmhamster"
    version = projectVersion
}

// ---------------------------------------------------------------- release packaging

val releaseDist = layout.buildDirectory.dir("dist")
val isWindows = System.getProperty("os.name").lowercase().contains("win")

/** Desktop app-image (installDist) packaged as a zip. */
val desktopBundle = tasks.register<Zip>("desktopBundle") {
    group = "release"
    description = "Zips the desktop launcher distribution"
    dependsOn(":desktop:installDist")
    archiveFileName.set("boardgame-launcher-${version}-desktop.zip")
    destinationDirectory.set(releaseDist)
    from(project(":desktop").layout.buildDirectory.dir("install/desktop")) { into("launcher") }
}

/** Renames the unsigned release APK to a versioned artifact. */
val androidApk = tasks.register<Copy>("androidApk") {
    group = "release"
    description = "Copies the release APK to build/dist"
    dependsOn(":android-app:assembleRelease")
    from(project(":android-app").layout.buildDirectory.dir("outputs/apk/release")) {
        include("*.apk")
    }
    into(releaseDist)
    rename { "boardgame-launcher-${version}.apk" }
}

/** Single executable jar (all runtime dependencies, Main-Class set). */
val singleJar = tasks.register<Copy>("singleJar") {
    group = "release"
    description = "Copies the executable launcher jar to build/dist"
    dependsOn(":desktop:shadowJar")
    from(project(":desktop").layout.buildDirectory.dir("libs")) {
        include("boardgame-launcher-*.jar")
    }
    into(releaseDist)
}

/** Desktop release artifacts: the single executable jar and the installDist zip. */
val desktopRelease = tasks.register("desktopRelease") {
    group = "release"
    description = "Builds the desktop artifacts (single jar + desktop zip)"
    dependsOn(singleJar, desktopBundle)
    if (isWindows) {
        dependsOn(":desktop:desktopExeZip")
    }
}

/** Android release artifact: the unsigned APK (embeds the core/CAH plugins and fronts). */
val androidRelease = tasks.register("androidRelease") {
    group = "release"
    description = "Builds the unsigned release APK"
    dependsOn(androidApk)
}

tasks.register("release") {
    group = "release"
    description = "Builds all launcher release artifacts"
    dependsOn(desktopRelease, androidRelease)
}

/**
 * Publishes the launcher release artifacts with the GitHub CLI. Requires an authenticated `gh`, a
 * clean tree, an `origin` remote and a local tag `v<version>`; the task pushes the tag and creates
 * the release with generated notes.
 *
 * `-PghDraft=true` creates a draft (not picked up by the launcher's latest-release check).
 */
tasks.register("publishRelease") {
    group = "release"
    description = "Publishes the launcher jar, desktop/Windows zips and APK as a GitHub release (GitHub CLI)"
    dependsOn("release")

    val version = project.version.toString()
    val tag = "v$version"
    val draft = project.hasProperty("ghDraft")
    val assets = mutableListOf(
        layout.projectDirectory.file("build/dist/boardgame-launcher-$version.jar"),
        layout.projectDirectory.file("build/dist/boardgame-launcher-$version-desktop.zip"),
        layout.projectDirectory.file("build/dist/boardgame-launcher-$version.apk")
    )
    if (isWindows) {
        assets.add(layout.projectDirectory.file("build/dist/boardgame-launcher-$version-windows.zip"))
    }

    doLast {
        fun run(command: List<String>, failure: String): String {
            val process = try {
                ProcessBuilder(command)
                    .directory(projectDir)
                    .redirectErrorStream(true)
                    .start()
            } catch (error: java.io.IOException) {
                error("$failure\n${error.message}")
            }
            val output = process.inputStream.bufferedReader().readText().trim()
            if (process.waitFor() != 0) error("$failure\n$output")
            if (output.isNotEmpty()) logger.lifecycle(output)
            return output
        }

        run(
            listOf("gh", "--version"),
            "GitHub CLI (gh) is not installed or not on PATH. Install it and run `gh auth login`."
        )
        run(
            listOf("gh", "auth", "status"),
            "GitHub CLI is not authenticated. Run `gh auth login` or set GH_TOKEN."
        )
        val missing = assets.filterNot { it.asFile.isFile }
        require(missing.isEmpty()) {
            "Release assets missing: ${missing.joinToString { it.asFile.path }}. Run `gradle release` first."
        }
        val dirty = run(
            listOf("git", "status", "--porcelain", "--untracked-files=no"),
            "Failed to read the git working tree status."
        )
        require(dirty.isEmpty()) {
            "Working tree has uncommitted changes; commit them before publishing:\n$dirty"
        }
        run(
            listOf("git", "rev-parse", "-q", "--verify", "refs/tags/$tag"),
            "Missing local tag $tag. Tag the release commit first: git tag $tag"
        )
        val head = run(listOf("git", "rev-parse", "HEAD"), "Failed to read HEAD.")
        val tagged = run(listOf("git", "rev-list", "-n", "1", "refs/tags/$tag"), "Failed to read tag $tag.")
        require(head == tagged) {
            "HEAD ($head) is not the commit tagged $tag ($tagged); check out the release tag before publishing."
        }
        run(
            listOf("git", "push", "origin", "refs/tags/$tag"),
            "Failed to push tag $tag to origin (is the repository created and 'origin' configured?)."
        )

        val create = mutableListOf("gh", "release", "create", tag)
        assets.forEach { create += it.asFile.absolutePath }
        create += listOf(
            "--title", "boardgame-launcher $version",
            "--generate-notes",
            "--verify-tag"
        )
        if (draft) create += "--draft" else create += "--latest"
        run(create, "gh release create failed for $tag.")
        logger.lifecycle("Published GitHub release $tag (${if (draft) "draft" else "latest"}).")
    }
}
