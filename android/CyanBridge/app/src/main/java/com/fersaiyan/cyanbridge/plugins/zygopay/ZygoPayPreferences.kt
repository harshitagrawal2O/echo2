package com.fersaiyan.cyanbridge.plugins.zygopay

import android.content.Context
import com.fersaiyan.cyanbridge.BuildConfig

/**
 * Plugin settings, and the resolution order for the service endpoint.
 *
 * Both values come from Gradle properties, matching how `OPENAI_API_KEY` is already handled in this
 * project, so no endpoint or token is committed.
 *
 * The stored-override keys are read but never written: there is no settings screen yet, so changing
 * either one means a rebuild. The readers exist so that adding that screen is a one-file change,
 * and they are documented as unwired rather than left to look like working configuration.
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

    fun isConfigured(context: Context): Boolean =
        serviceUrl(context).isNotBlank() && proxyToken(context).isNotBlank()
}
