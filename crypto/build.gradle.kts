// aura-crypto: Aurora's post-quantum crypto core as a standalone, pure-JVM library.
//
// It has NO Android dependency and NO native code: it talks to BouncyCastle (X-Wing
// ML-KEM-768 + X25519, ML-DSA-65, Ed25519, SHA3, HKDF) and Google Tink (XChaCha20-
// Poly1305) only, and persists through the PrekeyStore / RatchetStore interfaces it
// defines (the host app supplies the storage). Pure-JVM means the whole stack —
// including the post-quantum primitives — is unit-testable on the CI tier.
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
    // Pure-JVM post-quantum crypto: BouncyCastle X-Wing (ML-KEM-768 + X25519) and
    // ML-DSA-65 — liboqs/JNI fully removed (see docs/CRYPTO_MIGRATION_PLAN.md).
    api("org.bouncycastle:bcprov-jdk18on:1.84")
    // XChaCha20-Poly1305 AEAD: BouncyCastle ships no XChaCha engine in any released
    // version, so the symmetric cipher uses Tink. Plain `tink` jar (not the tink-android
    // AAR) because this is a pure-JVM module; the app gets it transitively.
    api("com.google.crypto.tink:tink:1.18.0")
    api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.0")
    // org.json is part of the Android platform at runtime, so compile against it only.
    compileOnly("org.json:json:20240303")

    // ── Tests ────────────────────────────────────────────────────────────────
    // All pure-JVM now (no native liboqs tier): Hkdf, SymmetricCipher, KemDoubleRatchet,
    // HybridKem (X-Wing), HybridSigner (ML-DSA), NodeIdentity, PrekeyManager, utils.
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
