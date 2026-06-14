package com.aura.settings

import android.content.Context
import android.content.SharedPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Non-secret app settings. Secret material (identity keys) lives in
 * [com.aura.identity.IdentityManager], never here.
 */
@Singleton
class AuroraSettings @Inject constructor(
    @ApplicationContext context: Context
) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("aura_settings", Context.MODE_PRIVATE)

    private val _serverMode = MutableStateFlow(prefs.getBoolean(KEY_SERVER_MODE, false))
    /** Phase 0: when true this device runs the in-app rendezvous server. */
    val serverMode: StateFlow<Boolean> = _serverMode

    private val _serverAddress = MutableStateFlow(
        prefs.getString(KEY_SERVER_ADDRESS, DEFAULT_SERVER_ADDRESS) ?: DEFAULT_SERVER_ADDRESS
    )
    /** Rendezvous server base URL used when this device is a client. */
    val serverAddress: StateFlow<String> = _serverAddress

    private val _advertisedAddress = MutableStateFlow(prefs.getString(KEY_ADVERTISED, "") ?: "")
    /**
     * Optional "ip:port" override advertised in rendezvous check-ins instead of
     * the auto-detected address. Needed when peers can't reach the detected IP
     * directly — NAT'd deployments, and the two-emulator test setup where each
     * emulator reaches the other through adb forward/reverse port plumbing.
     */
    val advertisedAddress: StateFlow<String> = _advertisedAddress

    private val _disappearingTimer = MutableStateFlow(
        DisappearingTimer.fromKey(prefs.getString(KEY_DISAPPEARING, null))
    )
    val disappearingTimer: StateFlow<DisappearingTimer> = _disappearingTimer

    private val _themeMode = MutableStateFlow(ThemeMode.fromKey(prefs.getString(KEY_THEME, null)))
    /** Light / dark / follow-system appearance preference. */
    val themeMode: StateFlow<ThemeMode> = _themeMode

    private val _themePalette = MutableStateFlow(ThemePalette.fromKey(prefs.getString(KEY_PALETTE, null)))
    /** Colour palette: the current Aurora look, or the classic "Cherish" plum theme. */
    val themePalette: StateFlow<ThemePalette> = _themePalette

    private val _developerMode = MutableStateFlow(prefs.getBoolean(KEY_DEVELOPER, false))
    /** When true, the rendezvous/network developer tools are shown in Settings. */
    val developerMode: StateFlow<Boolean> = _developerMode

    private val _blockedNodes = MutableStateFlow(
        prefs.getStringSet(KEY_BLOCKED, emptySet())?.toSet() ?: emptySet()
    )
    /** Node-ids the user has blocked at pairing time (rejected / repeated wrong code). */
    val blockedNodes: StateFlow<Set<String>> = _blockedNodes

    fun isBlocked(nodeIdHex: String): Boolean = _blockedNodes.value.contains(nodeIdHex)

    fun blockNode(nodeIdHex: String) {
        val next = _blockedNodes.value + nodeIdHex
        prefs.edit().putStringSet(KEY_BLOCKED, next).apply()
        _blockedNodes.value = next
    }

    fun unblockNode(nodeIdHex: String) {
        val next = _blockedNodes.value - nodeIdHex
        prefs.edit().putStringSet(KEY_BLOCKED, next).apply()
        _blockedNodes.value = next
    }

    private val _shadowMeshEnabled = MutableStateFlow(prefs.getBoolean(KEY_SHADOWMESH, false))
    /**
     * Opt-in to the ShadowMesh relay network. When false (the default), this
     * device neither relays traffic for other users nor routes its own messages
     * through relays — it only connects directly. See Terms §9 / Privacy §6.
     */
    val shadowMeshEnabled: StateFlow<Boolean> = _shadowMeshEnabled

    private val _duressWipe = MutableStateFlow(prefs.getBoolean(KEY_DURESS_WIPE, false))
    /**
     * When true, the decoy PIN doesn't just show an empty app — it triggers a full
     * cryptographic wipe and closes. For users whose threat model is coercion rather
     * than casual snooping. Off by default (decoy-hide is the safer default).
     */
    val duressWipe: StateFlow<Boolean> = _duressWipe

    private val _onboardingDone = MutableStateFlow(prefs.getBoolean(KEY_ONBOARDING_DONE, false))
    /** Observable so the wake service can start the moment setup completes. */
    val onboardingDoneFlow: StateFlow<Boolean> = _onboardingDone

    var onboardingDone: Boolean
        get() = prefs.getBoolean(KEY_ONBOARDING_DONE, false)
        set(value) {
            prefs.edit().putBoolean(KEY_ONBOARDING_DONE, value).apply()
            _onboardingDone.value = value
        }

    /** Whether we've already prompted once for the Doze battery-optimization exemption. */
    var batteryPromptShown: Boolean
        get() = prefs.getBoolean(KEY_BATTERY_PROMPT, false)
        set(value) { prefs.edit().putBoolean(KEY_BATTERY_PROMPT, value).apply() }

    /**
     * Whether the brand splash has already been shown on this install. The logo
     * dwell plays only on the very first launch of a freshly initialized app;
     * every later launch hands straight off to the main screen. Cleared by
     * [clearAll] (and by reinstalling), so a fresh start shows it again.
     */
    var splashShown: Boolean
        get() = prefs.getBoolean(KEY_SPLASH_SHOWN, false)
        set(value) { prefs.edit().putBoolean(KEY_SPLASH_SHOWN, value).apply() }

    fun setServerMode(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_SERVER_MODE, enabled).apply()
        _serverMode.value = enabled
    }

    fun setServerAddress(address: String) {
        val trimmed = address.trim().trimEnd('/')
        prefs.edit().putString(KEY_SERVER_ADDRESS, trimmed).apply()
        _serverAddress.value = trimmed
    }

    fun setAdvertisedAddress(address: String) {
        val trimmed = address.trim()
        prefs.edit().putString(KEY_ADVERTISED, trimmed).apply()
        _advertisedAddress.value = trimmed
    }

    fun setDisappearingTimer(timer: DisappearingTimer) {
        prefs.edit().putString(KEY_DISAPPEARING, timer.key).apply()
        _disappearingTimer.value = timer
    }

    fun setThemeMode(mode: ThemeMode) {
        prefs.edit().putString(KEY_THEME, mode.key).apply()
        _themeMode.value = mode
    }

    fun setThemePalette(palette: ThemePalette) {
        prefs.edit().putString(KEY_PALETTE, palette.key).apply()
        _themePalette.value = palette
    }

    fun setDeveloperMode(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_DEVELOPER, enabled).apply()
        _developerMode.value = enabled
    }

    fun setShadowMeshEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_SHADOWMESH, enabled).apply()
        _shadowMeshEnabled.value = enabled
    }

    fun setDuressWipe(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_DURESS_WIPE, enabled).apply()
        _duressWipe.value = enabled
    }

    fun clearAll() {
        prefs.edit().clear().commit()
        _serverMode.value = false
        _serverAddress.value = DEFAULT_SERVER_ADDRESS
        _advertisedAddress.value = ""
        _disappearingTimer.value = DisappearingTimer.OFF
        _themeMode.value = ThemeMode.SYSTEM
        _themePalette.value = ThemePalette.AURORA
        _developerMode.value = false
        _shadowMeshEnabled.value = false
        _duressWipe.value = false
        _blockedNodes.value = emptySet()
    }

    companion object {
        // Production rendezvous server (DigitalOcean droplet, fronted by Nginx +
        // Let's Encrypt TLS, cert-pinned in RendezvousClient). Reachable from real
        // devices and emulators alike. Override in developer settings for local
        // testing (e.g. http://10.0.2.2:8080 to reach a server on the host).
        const val DEFAULT_SERVER_ADDRESS = "https://api.auroramessenger.com"

        private const val KEY_SERVER_MODE     = "server_mode"
        private const val KEY_ADVERTISED      = "advertised_address"
        private const val KEY_SERVER_ADDRESS  = "server_address"
        private const val KEY_ONBOARDING_DONE = "onboarding_done"
        private const val KEY_SPLASH_SHOWN     = "splash_shown"
        private const val KEY_BATTERY_PROMPT   = "battery_prompt_shown"
        private const val KEY_DURESS_WIPE      = "duress_wipe"
        private const val KEY_DISAPPEARING    = "disappearing_timer"
        private const val KEY_THEME           = "theme_mode"
        private const val KEY_PALETTE         = "theme_palette"
        private const val KEY_DEVELOPER       = "developer_mode"
        private const val KEY_SHADOWMESH      = "shadowmesh_enabled"
        private const val KEY_BLOCKED         = "blocked_nodes"
    }
}

/** Appearance preference. SYSTEM follows the OS light/dark setting. */
enum class ThemeMode(val key: String, val label: String) {
    SYSTEM("system", "System default"),
    LIGHT ("light",  "Light"),
    DARK  ("dark",   "Dark");

    companion object {
        fun fromKey(key: String?): ThemeMode = entries.firstOrNull { it.key == key } ?: SYSTEM
    }
}

/**
 * Colour palette. AURORA is the current neon-aurora look; CHERISH restores the
 * original deep-plum / soft-rose theme (kept as a nostalgic alternative).
 */
enum class ThemePalette(val key: String, val label: String) {
    AURORA ("aurora",  "Aurora"),
    CHERISH("cherish", "Cherish");

    companion object {
        fun fromKey(key: String?): ThemePalette = entries.firstOrNull { it.key == key } ?: AURORA
    }
}

/** Per-app default disappearing-message timer (Phase 6 wires the actual deletion). */
enum class DisappearingTimer(val key: String, val label: String) {
    OFF      ("off", "Off"),
    ONE_HOUR ("1h",  "1 hour"),
    ONE_DAY  ("24h", "24 hours"),
    ONE_WEEK ("7d",  "7 days");

    companion object {
        fun fromKey(key: String?): DisappearingTimer =
            entries.firstOrNull { it.key == key } ?: OFF
    }
}
