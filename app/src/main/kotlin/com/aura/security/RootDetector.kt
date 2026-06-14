package com.aura.security

import java.io.File

/**
 * Phase 8: best-effort root detection. A rooted device can have its Keystore
 * keys extracted, so the user is warned (not blocked). Defeatable by a
 * determined attacker — this is tamper-evidence, not tamper-proofing.
 */
object RootDetector {
    private val SU_PATHS = listOf(
        "/system/bin/su", "/system/xbin/su", "/sbin/su",
        "/system/app/Superuser.apk", "/system/xbin/daemonsu",
        "/data/local/bin/su", "/data/local/xbin/su"
    )
    private val ROOT_APPS = listOf(
        "com.topjohnwu.magisk", "eu.chainfire.supersu", "com.koushikdutta.superuser"
    )

    fun isLikelyRooted(): Boolean {
        if (SU_PATHS.any { File(it).exists() }) return true
        // test-keys build tag is a classic custom-ROM signal
        if (android.os.Build.TAGS?.contains("test-keys") == true) return true
        // writable system partition
        if (listOf("/system", "/system/bin").any { File(it).canWrite() }) return true
        return false
    }

    /** Package names of common root managers found installed (caller has PackageManager). */
    fun rootAppCandidates(): List<String> = ROOT_APPS
}
