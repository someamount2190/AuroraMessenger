import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}

// Release signing config is loaded from keystore.properties in the consolidated
// ../keys folder (a sibling of this repo, kept out of git). It and aurora-release.jks
// sign every app update — keep them private. A clone without ../keys just builds unsigned.
val keystorePropsFile = rootProject.file("../keys/keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) load(keystorePropsFile.inputStream())
}

android {
    namespace  = "com.aura"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.aura"
        minSdk        = 29
        targetSdk     = 34
        versionCode   = 8
        versionName   = "0.2.6"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (keystorePropsFile.exists()) {
            create("release") {
                storeFile = rootProject.file(keystoreProps["storeFile"] as String)
                storePassword = keystoreProps["storePassword"] as String
                keyAlias = keystoreProps["keyAlias"] as String
                keyPassword = keystoreProps["keyPassword"] as String
            }
        }
    }

    buildTypes {
        release {
            // Pre-release: signed, non-debuggable, but NOT code-shrunk. The PQ
            // crypto stack (BouncyCastle) and Room/Hilt are heavily reflective;
            // R8 is deferred until keep rules are validated.
            isMinifyEnabled = false
            versionNameSuffix = "-pre"
            if (keystorePropsFile.exists()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    buildFeatures { compose = true; buildConfig = true }
    composeOptions { kotlinCompilerExtensionVersion = "1.5.11" }

    testOptions {
        // Pure-JVM unit tests. returnDefaultValues keeps incidental android.jar stubs
        // from throwing during class-load; tests that need real Android behaviour use
        // Robolectric or live in androidTest (see docs/TEST_ARCHITECTURE.md).
        unitTests.isReturnDefaultValues = true
    }

    sourceSets["main"].kotlin.srcDir("src/main/kotlin")

    packaging {
        resources {
            excludes += setOf(
                "META-INF/LICENSE*",
                "META-INF/NOTICE*",
                "META-INF/DEPENDENCIES",
                "META-INF/versions/9/OSGI-INF/MANIFEST.MF"
            )
        }
    }
}

// Room schema export — captures the DB schema per version so migrations can be
// written and tested. Commit the generated app/schemas/*.json files.
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(libs.core.ktx)
    implementation(libs.sharetarget)   // direct-share contact shortcuts backport
    implementation(libs.coroutines.core)
    implementation(libs.coroutines.android)

    // Hilt DI
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    // Aurora's crypto core — a standalone module published to the in-repo local
    // Maven repo (see /crypto and libs/maven). It brings BouncyCastle + Tink in
    // transitively. Pure-JVM now — no native liboqs .so files.
    implementation("com.aura:aura-crypto:0.1.0")
    // Kept explicit too (belt-and-suspenders for the post-quantum + classical stack).
    implementation(libs.bcprov)
    implementation(libs.tink)   // XChaCha20-Poly1305 AEAD (also transitive via aura-crypto)
    implementation(libs.security.crypto)

    // Encrypted DB (Phase 2+)
    implementation(libs.sqlcipher)
    implementation(libs.sqlite.ktx)
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    // Compose
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.unit)
    implementation(libs.compose.foundation)
    implementation(libs.compose.animation)
    implementation(libs.compose.animation.core)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    implementation(libs.compose.activity)
    implementation(libs.compose.navigation)
    implementation(libs.compose.lifecycle)
    implementation(libs.compose.viewmodel)
    implementation(libs.compose.hilt.nav)
    debugImplementation(libs.compose.ui.tooling)
    implementation(libs.compose.ui.tooling.preview)

    // QR
    implementation(libs.zxing.core)
    implementation(libs.zxing.android.embedded)

    // Phase 0: in-app rendezvous server + HTTP client
    implementation(libs.nanohttpd)
    implementation(libs.okhttp)

    // Phase 7: WebRTC peer-to-peer video/audio
    implementation(libs.webrtc)

    // ── Unit tests (JVM) ─────────────────────────────────────────────────────
    testImplementation(libs.junit)
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit:1.9.23")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.0")
    // Real org.json shadows the throw-on-call android.jar stub so JSON logic is testable.
    testImplementation("org.json:json:20240303")
    // App's own crypto test fakes reuse the published crypto module's interfaces.
    testImplementation("com.aura:aura-crypto:0.1.0")
    // Robolectric — runs Android-context tests (Room DAOs, settings) off-device.
    testImplementation("org.robolectric:robolectric:4.12.2")
    testImplementation("androidx.test:core:1.5.0")
    testImplementation("app.cash.turbine:turbine:1.1.0")
    // mockk — for orchestration classes whose collaborators are concrete (network/Keystore-bound)
    // and can't be driven with real instances under Robolectric.
    testImplementation("io.mockk:mockk:1.13.13")
    // Room migration testing runs under Robolectric in the unit-test source set (CI-gated).
    testImplementation("androidx.room:room-testing:2.6.1")
    androidTestImplementation("androidx.room:room-testing:2.6.1")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test:runner:1.5.2")
    androidTestImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.0")
    androidTestImplementation(kotlin("test-junit"))
}
