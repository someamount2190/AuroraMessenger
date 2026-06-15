import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}

// Release signing config is loaded from keystore.properties at the repo root
// (keep that file and keystore/*.jks private — they sign every app update).
val keystorePropsFile = rootProject.file("keystore.properties")
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
        versionCode   = 1
        versionName   = "0.1.0"
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
            // crypto stack (liboqs JNI + BouncyCastle) and Room/Hilt are heavily
            // reflective/native; R8 is deferred until keep rules are validated.
            // (R8 wouldn't shrink the APK much anyway — its weight is native .so.)
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

    buildFeatures { compose = true }
    composeOptions { kotlinCompilerExtensionVersion = "1.5.11" }

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
    // Maven repo (see /crypto and libs/maven). It brings liboqs + Bouncy Castle in
    // transitively; the native liboqs .so files live in this app module's jniLibs.
    implementation("com.aura:aura-crypto:0.1.0")
    // Kept explicit too (belt-and-suspenders for the native JNI + classical stack).
    implementation(libs.liboqs)
    implementation(libs.bcprov)
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

    testImplementation(libs.junit)
}
