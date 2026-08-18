package com.fersaiyan.cyanbridge.devconfig

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.util.Log
import com.fersaiyan.cyanbridge.agent.LocalAgentPrefs
import com.fersaiyan.cyanbridge.localmodels.remote.RemoteOpenAiPrefs
import com.fersaiyan.cyanbridge.shared.glasses.GlassesAssistantMode
import com.fersaiyan.cyanbridge.shared.settings.AgentProviderType
import org.json.JSONObject
import java.io.File

/**
 * Debug-only provisioning for the settings that decide whether the AI button can answer at all.
 *
 * The remote API key lives in [android.security.crypto.EncryptedSharedPreferences], so it cannot be
 * seeded by writing a prefs file over adb, and on this ROM SELinux denies `runas_app` write access
 * to the app sandbox anyway. Driving the settings forms with `input text` is the other option and is
 * worse: a 164-character key that silently drops one character fails as an opaque 401.
 *
 * So the values arrive in a file that adb *can* write - the app-specific external files dir, which
 * needs no storage permission - and this hands them to the real setters.
 *
 * Deliberately a [ContentProvider] rather than a receiver or an activity:
 *  - the system instantiates providers during application startup, so provisioning needs no
 *    exported component and no broadcast - push the file, relaunch the app, and it applies
 *  - the credential therefore never travels as an intent extra, where it would surface in the
 *    process list and in ActivityManager's logcat output
 *
 * The file is deleted the moment it is applied, so a plaintext key does not linger on disk.
 *
 * This class lives in `src/debug`, so it is absent from release builds by construction rather than
 * by a runtime flag someone could get wrong.
 */
class DevConfigInitializer : ContentProvider() {

    override fun onCreate(): Boolean {
        val context = context ?: return true
        val dir = context.getExternalFilesDir(null)
        val file = File(dir, CONFIG_FILE)
        if (!file.isFile) return true

        val json = runCatching { JSONObject(file.readText()) }.getOrElse {
            Log.e(TAG, "$CONFIG_FILE is not valid JSON: ${it.message}")
            return true
        }

        val applied = mutableListOf<String>()

        json.optString("base_url").takeIf { it.isNotBlank() }?.let {
            RemoteOpenAiPrefs.setBaseUrl(context, it)
            applied += "base_url=$it"
        }
        json.optString("model").takeIf { it.isNotBlank() }?.let {
            RemoteOpenAiPrefs.setModel(context, it)
            applied += "model=$it"
        }
        json.optString("api_key").takeIf { it.isNotBlank() }?.let {
            RemoteOpenAiPrefs.setApiKey(context, it)
            applied += "api_key=${mask(it)}"
        }
        if (json.has("enabled")) {
            val enabled = json.optBoolean("enabled")
            RemoteOpenAiPrefs.setEnabled(context, enabled)
            applied += "enabled=$enabled"
        }

        // These two decide whether the glasses AI button answers in-app or tries to puppeteer the
        // phone's assistant. Both are persisted on first read, so changing their defaults does not
        // move a device that has already stored the old value - it has to be set explicitly.
        json.optString("assistant_mode").takeIf { it.isNotBlank() }?.let { raw ->
            runCatching { GlassesAssistantMode.valueOf(raw.trim().uppercase()) }
                .onSuccess {
                    LocalAgentPrefs.setGlassesAssistantMode(context, it)
                    applied += "assistant_mode=$it"
                }
                .onFailure { Log.e(TAG, "Unknown assistant_mode '$raw'") }
        }
        json.optString("agent_provider").takeIf { it.isNotBlank() }?.let { raw ->
            runCatching { AgentProviderType.valueOf(raw.trim().uppercase()) }
                .onSuccess {
                    LocalAgentPrefs.setProviderType(context, it)
                    applied += "agent_provider=$it"
                }
                .onFailure { Log.e(TAG, "Unknown agent_provider '$raw'") }
        }

        // Delete before reporting success: a config that was applied but left on disk is a
        // plaintext credential sitting where anything with storage access can read it.
        val deleted = file.delete()

        Log.i(TAG, "Applied ${applied.joinToString(", ")}")
        Log.i(
            TAG,
            "remoteConfigured=${RemoteOpenAiPrefs.isConfigured(context)} " +
                "remoteEnabled=${RemoteOpenAiPrefs.isEnabled(context)} " +
                "assistantMode=${LocalAgentPrefs.getGlassesAssistantMode(context)} " +
                "agentProvider=${LocalAgentPrefs.getProviderType(context)} " +
                "configFileDeleted=$deleted",
        )
        if (!deleted) {
            Log.e(TAG, "Could not delete $CONFIG_FILE - remove it manually, it still holds the key")
        }
        return true
    }

    /** Enough to confirm which key landed, never enough to use it. */
    private fun mask(key: String): String =
        if (key.length <= 12) "*".repeat(key.length) else "${key.take(8)}...${key.takeLast(4)}"

    // Not a real provider; it exists only for the startup callback above.
    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? = null

    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0

    companion object {
        private const val TAG = "DevConfig"
        const val CONFIG_FILE = "dev_config.json"
    }
}
