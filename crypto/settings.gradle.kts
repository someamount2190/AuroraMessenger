// Standalone build for the aura-crypto library. It is intentionally NOT part of the
// app's Gradle build (not include()d in the root settings) so it builds and publishes
// on its own. The app consumes the published artifact from ../libs/maven.
rootProject.name = "aura-crypto"

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
    repositories {
        // liboqs-java is vendored in the repo's local Maven dir.
        maven { url = uri(rootProject.projectDir.resolve("../libs/maven")) }
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
    }
}

pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}
