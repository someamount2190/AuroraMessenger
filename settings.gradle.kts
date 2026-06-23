rootProject.name = "aurora"

// Dependency resolution. `libs/maven` is an in-repo local Maven repository bundled
// with the project so the build is fully standalone (no external sibling folders):
//  - aura-crypto (com.aura) — Aurora's own crypto module, built from /crypto and
//    published into this repo (see crypto/README and crypto/build.gradle.kts).
// The crypto stack is pure-JVM now (Bouncy Castle + Google Tink); the old liboqs
// JNI wrapper has been fully removed. Remote repos are fallbacks for everything else.
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
    repositories {
        maven {
            name = "RepoLocal"
            url  = uri(rootProject.projectDir.resolve("libs/maven"))
        }
        google()
        mavenCentral()
        maven {
            name = "JitPack"
            url  = uri("https://jitpack.io")
        }
    }
}

pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

include(":app")
