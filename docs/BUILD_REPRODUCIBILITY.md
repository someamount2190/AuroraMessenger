# Build & Reproducibility

How a reviewer builds Aurora from source and verifies a release artifact.

## Toolchain (pinned)

| Tool | Version | Source |
|---|---|---|
| Android Gradle Plugin | 8.3.2 | `gradle/libs.versions.toml` |
| Kotlin | 1.9.23 | `libs.versions.toml` |
| KSP | 1.9.23-1.0.19 | `libs.versions.toml` |
| Gradle (wrapper) | per `gradle/wrapper/gradle-wrapper.properties` (8.7) | committed wrapper |
| JDK | 17 (Android Studio **JBR**) | `JAVA_HOME` |
| compileSdk / targetSdk / minSdk | 34 / 34 / 29 | `app/build.gradle.kts` |
| Java/Kotlin target | 17 | `compileOptions` / `kotlinOptions` |

## Standalone build
Everything the build needs is vendored under `libs/maven` (an in-repo Maven repository), so a
fresh clone builds with no external folders.

```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
.\gradlew.bat assembleDebug            # debug APK
.\gradlew.bat :app:assembleRelease     # signed release APK (needs keystore.properties)
```

If anything under `crypto/` changes, republish it before rebuilding the app:
```powershell
.\gradlew.bat -p crypto publish        # republishes com.aura:aura-crypto into libs/maven
```

## Release configuration
- `isMinifyEnabled = false` (pre-release): R8/shrinking deferred until keep-rules for the
  reflective stack (BouncyCastle, Google Tink, Room, Hilt) are validated. (The APK's
  weight is native `.so`, which R8 would not shrink.)
- `versionNameSuffix = "-pre"`.
- Signed via `keystore.properties` at the repo root (kept private). The signing config loads
  only if that file exists.

## Verifying a released APK
1. Build `:app:assembleRelease` at the **same tag** as the release.
2. Compare the **APK signing certificate** of your build vs the published release. Aurora is
   signed with **APK Signature Scheme v2/v3** (not v1 JAR signing), so use `apksigner`
   (`keytool -printcert -jarfile` will report "Not a signed jar file"):
   ```
   apksigner verify --print-certs aurora-messenger-prealpha.apk
   ```
3. The published SHA-256 of each release APK is on the GitHub release page.

> **Release signing certificate** — a genuine release APK must present this certificate:
> - Subject DN: `CN=Aurora Messenger, OU=Aurora, O=Aurora, L=Unknown, ST=Unknown, C=US`
> - **SHA-256:** `f049428dbcd77228441f7dcd783f26b4891348f2f4592771a5d837f17871f624`
> - SHA-1: `dcc3be170d4d004ba8ddf6f282c34a4471071e8a`
>
> _Captured from `v0.2.2-pre` via `apksigner verify --print-certs`._

## Reproducibility status
Aurora is **not yet bit-for-bit reproducible.** Known sources of non-determinism to address
before claiming reproducible builds:
- Build timestamps / `versionControlInfo` embedded by AGP.
- Zip entry ordering and compression in the APK.
- Native `.so` provenance (built upstream, vendored).

Planned: a documented, containerized build with `SOURCE_DATE_EPOCH` and a comparison procedure.
Until then, the signing-certificate check above is the integrity anchor for releases.
