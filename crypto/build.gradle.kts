// aura-crypto: Aurora's post-quantum crypto core as a standalone, pure-JVM library.
//
// It has NO Android dependency: it talks to liboqs (post-quantum KEM/signatures) and
// Bouncy Castle (X25519/Ed25519/SHA3/ChaCha20-Poly1305) only, and persists through the
// PrekeyStore / RatchetStore interfaces it defines (the host app supplies the storage).
// That keeps the cryptography auditable and reusable in isolation. The native liboqs
// .so files live with the host app (Android jniLibs); this artifact is just the JVM code.
//
// Build + publish into the repo's local Maven repository with:
//     ./gradlew -p crypto publish
plugins {
    kotlin("jvm") version "1.9.23"
    `maven-publish`
}

group = "com.aura"
version = "0.1.0"

// Compile with the JVM that runs Gradle (JAVA_HOME → Android Studio's JBR 17), the
// same way the app module does. No toolchain auto-detection (the JBR isn't a
// discoverable system JDK).
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}
tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    kotlinOptions { jvmTarget = "17" }
}

// Surface println output from demo tests (CryptoStackDemo) in the console.
tasks.withType<Test>().configureEach {
    testLogging { showStandardStreams = true }
}

dependencies {
    // Exposed to consumers (api) so the app gets them transitively.
    api("org.openquantumsafe:liboqs-java:0.3.0")
    api("org.bouncycastle:bcprov-jdk15to18:1.78.1")
    api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.0")
    // org.json is part of the Android platform at runtime, so compile against it only.
    compileOnly("org.json:json:20240303")

    // ── Tests ────────────────────────────────────────────────────────────────
    // T1 (pure-JVM, no native liboqs): Hkdf, SymmetricCipher, RatchetManager, utils.
    // T2 (native liboqs required): HybridKem/HybridSigner/NodeIdentity/PrekeyManager —
    // those tests are tagged and skipped unless the native lib is on java.library.path.
    testImplementation(kotlin("test-junit"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.0")
    // Present at compile-time only for main; provide it on the test runtime classpath
    // so any class that touches org.json can load during tests.
    testImplementation("org.json:json:20240303")
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            artifactId = "aura-crypto"
            from(components["java"])
        }
    }
    repositories {
        maven {
            name = "RepoLocal"
            url  = uri(rootProject.projectDir.resolve("../libs/maven"))
        }
    }
}
