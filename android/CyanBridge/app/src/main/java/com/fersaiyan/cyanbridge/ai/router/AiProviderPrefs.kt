package com.fersaiyan.cyanbridge.ai.router

import android.content.Context

enum class AiProviderType(val wire: String, val label: String) {
    MOCK("mock", "Mock (local demo)"),
    COMPANY_BACKEND("company_backend", "Company Backend (stub)"),
    CLI_RELAY("cli_relay", "CLI Relay (Codex/Gemini)"),
    LOCAL_MODELS("local_models", "Local Models (on-device)");

    companion object {
        fun fromWire(value: String?): AiProviderType =
            entries.firstOrNull { it.wire == value } ?: MOCK
    }
}

enum class CliRelayBackend(val wire: String, val label: String) {
    GEMINI("gemini", "Gemini CLI"),
    CODEX("codex", "Codex CLI");

    companion object {
        fun fromWire(value: String?): CliRelayBackend =
            entries.firstOrNull { it.wire == value } ?: GEMINI
    }
}

object AiProviderPrefs {
    private const val PREFS_NAME = "ai_provider_prefs"
    private const val KEY_PROVIDER = "provider"
    private const val KEY_RELAY_BASE_URL = "relay_base_url"
    private const val KEY_RELAY_BACKEND = "relay_backend"
    private const val LEGACY_PUBLIC_RELAY_URL = "https://carelens-wine.vercel.app"
    private const val DEFAULT_PUBLIC_RELAY_URL = "https://cyanbridge.vercel.app"

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * Local Models is the only provider that can actually serve a request.
     *
     * MOCK and COMPANY_BACKEND are stubs. CLI_RELAY points at the upstream relay, which shells out
     * to the `gemini`/`codex` CLIs from a Termux server - there are no CLI binaries and no OAuth
     * state on the deployed serverless host, so it cannot answer. It used to be the default, and
     * the only code that ever moved a user off it was the Pro activation flow. With monetisation
     * removed nothing sets this pref at all, so the default has to be the provider that works, and
     * a device still carrying the old stored value has to be migrated off it rather than stranded.
     */
    fun getProvider(context: Context): AiProviderType {
        val stored = AiProviderType.fromWire(
            prefs(context).getString(KEY_PROVIDER, AiProviderType.LOCAL_MODELS.wire),
        )
        return if (stored == AiProviderType.CLI_RELAY) AiProviderType.LOCAL_MODELS else stored
    }

    fun setProvider(context: Context, provider: AiProviderType) {
        prefs(context).edit().putString(KEY_PROVIDER, provider.wire).apply()
    }

    fun getRelayBaseUrl(context: Context): String =
        prefs(context).getString(KEY_RELAY_BASE_URL, DEFAULT_PUBLIC_RELAY_URL)
            ?.trim()
            .orEmpty()
            .let { current ->
                when {
                    current.isBlank() -> DEFAULT_PUBLIC_RELAY_URL
                    current == LEGACY_PUBLIC_RELAY_URL -> DEFAULT_PUBLIC_RELAY_URL
                    else -> current
                }
            }

    fun setRelayBaseUrl(context: Context, value: String) {
        prefs(context).edit().putString(KEY_RELAY_BASE_URL, value.trim()).apply()
    }

    fun getRelayBackend(context: Context): CliRelayBackend =
        CliRelayBackend.fromWire(prefs(context).getString(KEY_RELAY_BACKEND, CliRelayBackend.GEMINI.wire))

    fun setRelayBackend(context: Context, backend: CliRelayBackend) {
        prefs(context).edit().putString(KEY_RELAY_BACKEND, backend.wire).apply()
    }
}
