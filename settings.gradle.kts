rootProject.name = "aurora"

// Dependency resolution: ShadowMesh's vendored local repo is listed first so
// artifacts that are hard to reach (liboqs-java is JitPack-only) resolve from
// disk. Remote repos are fallbacks for anything not vendored.
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
    repositories {
        val vendored = rootProject.projectDir.resolve("../shadowmesh_v22_fixed/libs/maven")
        if (vendored.exists() && vendored.list()?.isNotEmpty() == true) {
            maven {
                name = "VendoredLocal"
                url  = uri(vendored)
            }
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
        val vendored = rootProject.projectDir.resolve("../shadowmesh_v22_fixed/libs/maven")
        if (vendored.exists() && vendored.list()?.isNotEmpty() == true) {
            maven {
                name = "VendoredLocal"
                url  = uri(vendored)
            }
        }
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

include(":app")
