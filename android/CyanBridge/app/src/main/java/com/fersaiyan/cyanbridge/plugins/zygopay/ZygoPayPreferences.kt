package com.fersaiyan.cyanbridge.plugins.zygopay

import android.content.Context
import com.fersaiyan.cyanbridge.BuildConfig

/**
 * Plugin settings, and the resolution order for the service endpoint.
 *
 * The build-time value from Gradle properties (matching how `OPENAI_API_KEY` is already handled in
 * this project, so nothing is committed) is the fallback; a stored override takes precedence when
 * present, set from `ZygoPaySettingsActivity`. That order matters for a demo specifically: whoever
 * built the APK is rarely whoever is about to run it, and a deployment URL that only a rebuild can
 * change is the wrong shape for the day something needs pointing at a different service.
 *
 * Neither value is a merchant secret — that lives only in the service. See `lib/http.js`.
 *
 * No wallet is stored. Every payment starts with the wallet app's own authorisation prompt, which
 * is slower and is the right default here: that prompt is the user's one chance to see which app is
 * asking and which account will pay, and skipping it to save a tap on a *payment* is the wrong
 * economy. There is no key material to store either way.
 */
object ZygoPayPreferences {

    private const val PREFS = "zygo_pay"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_SERVICE_URL = "service_url"
    private const val KEY_PROXY_TOKEN = "proxy_token"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    fun serviceUrl(context: Context): String =
        prefs(context).getString(KEY_SERVICE_URL, null)?.takeIf { it.isNotBlank() }
            ?: BuildConfig.ZYGO_SERVICE_URL

    fun proxyToken(context: Context): String =
        prefs(context).getString(KEY_PROXY_TOKEN, null)?.takeIf { it.isNotBlank() }
            ?: BuildConfig.ZYGO_PROXY_TOKEN

    /**
     * The stored override only, distinct from [serviceUrl]/[proxyToken] which fall back to the
     * build-time value. The settings screen needs this distinction: showing the resolved value in
     * an editable field would present the build default as though it were already a saved
     * override, and saving the form unchanged would then lock it in as one.
     */
    fun storedServiceUrl(context: Context): String? =
        prefs(context).getString(KEY_SERVICE_URL, null)?.takeIf { it.isNotBlank() }

    fun storedProxyToken(context: Context): String? =
        prefs(context).getString(KEY_PROXY_TOKEN, null)?.takeIf { it.isNotBlank() }

    /** Empty clears the override and falls back to the build-time value, not to an empty string. */
    fun setServiceUrl(context: Context, url: String) {
        prefs(context).edit().putString(KEY_SERVICE_URL, url.trim()).apply()
    }

    fun setProxyToken(context: Context, token: String) {
        prefs(context).edit().putString(KEY_PROXY_TOKEN, token.trim()).apply()
    }

    /** True when either value has a stored override rather than the build-time default. */
    fun hasOverride(context: Context): Boolean {
        val p = prefs(context)
        return !p.getString(KEY_SERVICE_URL, null).isNullOrBlank() ||
            !p.getString(KEY_PROXY_TOKEN, null).isNullOrBlank()
    }

    fun isConfigured(context: Context): Boolean =
        serviceUrl(context).isNotBlank() && proxyToken(context).isNotBlank()
}
