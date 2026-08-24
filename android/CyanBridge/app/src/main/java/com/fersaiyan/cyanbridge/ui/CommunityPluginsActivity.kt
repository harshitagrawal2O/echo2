package com.fersaiyan.cyanbridge.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import com.fersaiyan.cyanbridge.MainActivity
import com.fersaiyan.cyanbridge.ai.router.AiProviderPrefs
import com.fersaiyan.cyanbridge.devices.DeviceProfileStore
import com.fersaiyan.cyanbridge.shared.devices.DeviceClass
import com.fersaiyan.cyanbridge.devices.metarayban.MetaRaybanManager
import com.fersaiyan.cyanbridge.devices.metarayban.MetaCameraPermission
import com.fersaiyan.cyanbridge.shared.chat.ChatRole
import com.fersaiyan.cyanbridge.chat.ChatStore
import com.fersaiyan.cyanbridge.shared.navigation.AppDestination
import com.fersaiyan.cyanbridge.shared.plugins.CommunityPluginCardData
import com.fersaiyan.cyanbridge.shared.plugins.NativePluginCardData
import com.fersaiyan.cyanbridge.shared.plugins.PluginTimeWindow
import com.fersaiyan.cyanbridge.ui.appearance.AppearancePreferences
import com.fersaiyan.cyanbridge.ui.appearance.rememberAppearanceSettings
import com.fersaiyan.cyanbridge.shared.ui.plugins.CommunityPluginsScreen
import com.fersaiyan.cyanbridge.plugins.walkingaid.WalkingAidService
import com.fersaiyan.cyanbridge.plugins.walkingaid.WalkingAidSettingsActivity
import com.fersaiyan.cyanbridge.plugins.walkingaid.WalkingAidPreferences
import com.fersaiyan.cyanbridge.plugins.cue.CuePlugin
import com.fersaiyan.cyanbridge.plugins.zygopay.ZygoPayPlugin
import com.fersaiyan.cyanbridge.plugins.zygopay.ZygoPayPreferences
import com.fersaiyan.cyanbridge.plugins.cue.CuePreferences
import com.fersaiyan.cyanbridge.plugins.cue.CueSettingsActivity
import com.fersaiyan.cyanbridge.plugins.meetingsparknotes.MeetingSparkNotesService
import com.fersaiyan.cyanbridge.plugins.meetingsparknotes.MeetingSparkNotesSettingsActivity
import com.fersaiyan.cyanbridge.plugins.meetingsparknotes.MeetingSparkNotesPreferences
import com.fersaiyan.cyanbridge.plugins.livecaptionrelay.LiveCaptionRelayService
import com.fersaiyan.cyanbridge.plugins.livecaptionrelay.LiveCaptionRelaySettingsActivity
import com.fersaiyan.cyanbridge.plugins.livecaptionrelay.LiveCaptionRelayPreferences
import com.fersaiyan.cyanbridge.plugins.handsfreetranslator.HandsFreeTranslatorService
import com.fersaiyan.cyanbridge.plugins.handsfreetranslator.HandsFreeTranslatorSettingsActivity
import com.fersaiyan.cyanbridge.plugins.handsfreetranslator.HandsFreeTranslatorPreferences
import com.fersaiyan.cyanbridge.plugins.errandbrain.ErrandBrainService
import com.fersaiyan.cyanbridge.plugins.errandbrain.ErrandBrainSettingsActivity
import com.fersaiyan.cyanbridge.plugins.errandbrain.ErrandBrainPreferences
import com.fersaiyan.cyanbridge.plugins.autodiary.AutoDiaryService
import com.fersaiyan.cyanbridge.plugins.autodiary.AutoDiarySettingsActivity
import com.fersaiyan.cyanbridge.plugins.localagent.LocalAgentPlugin
import com.fersaiyan.cyanbridge.plugins.localagent.LocalAgentSettingsActivity
import com.fersaiyan.cyanbridge.plugins.autoaudio.AutoAudioSettingsActivity
import com.fersaiyan.cyanbridge.plugins.visualdiary.VisualDiaryPreferences
import com.fersaiyan.cyanbridge.plugins.visualdiary.VisualDiarySettingsActivity
import com.fersaiyan.cyanbridge.plugins.visualdiary.VisualDiaryService
import com.fersaiyan.cyanbridge.media.autocapture.AutoAudioCapturePrefs
import com.fersaiyan.cyanbridge.media.autocapture.AutoAudioCaptureService
import com.fersaiyan.cyanbridge.plugins.PluginVoicePermissions
import com.fersaiyan.cyanbridge.ui.ensureNotificationPermission
import com.fersaiyan.cyanbridge.ui.hasNotificationPermission
import com.fersaiyan.cyanbridge.shared.plugins.NativePluginIds
import com.fersaiyan.cyanbridge.ui.recordings.RecordingsListActivity
import com.fersaiyan.cyanbridge.ui.theme.CyanBridgeTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

class CommunityPluginsActivity : AppCompatActivity() {

    private var selectedWindow by mutableStateOf(PluginTimeWindow.ALL_TIME)
    private var isRefreshing by mutableStateOf(false)
    private var serverPluginsLoaded = false
    private var communityPlugins by mutableStateOf<List<CommunityPluginCardData>>(emptyList())

    private var nativePluginsState by mutableStateOf<List<NativePluginCardData>>(emptyList())
    private var pendingMetaCameraPlugin: String? = null

    private val metaWearablePermissionLauncher =
        registerForActivityResult(MetaCameraPermission.cameraContract()) { granted ->
            val pluginId = pendingMetaCameraPlugin
            pendingMetaCameraPlugin = null
            if (pluginId == null) return@registerForActivityResult
            if (granted) {
                applyNativePluginToggle(pluginId, enabled = true)
            } else {
                val manager = MetaRaybanManager.getInstance(this)
                val detail = manager.reportExternalError("pluginCameraPermission", "Meta camera permission was denied")
                Toast.makeText(this, detail, Toast.LENGTH_LONG).show()
            }
        }

    private fun nativePluginPool(): List<NativePluginCardData> {
        val selectedClass = DeviceProfileStore.selectedClass(this)
        val hasCamera = selectedClass in setOf(DeviceClass.HEY_CYAN, DeviceClass.META_RAYBAN, DeviceClass.UNKNOWN)
        val hasOnboardStorage = selectedClass == DeviceClass.HEY_CYAN || selectedClass == DeviceClass.UNKNOWN

        val autoAudioDescription = when (selectedClass) {
            DeviceClass.META_RAYBAN -> "Unavailable for Meta Ray-Ban: DAT does not expose HeyCyan onboard audio-file recording."
            DeviceClass.MEIZU_MYVU -> "Unavailable for Meizu MYVU: device has no onboard audio file storage."
            DeviceClass.GENERIC_AUDIO -> "Unavailable for Earbuds / Audio-only glasses: device has no onboard audio file storage."
            else -> "Record glasses audio in resilient 15-minute loops with optional speech extension and sync."
        }

        val cameraUnavailableReason = when (selectedClass) {
            DeviceClass.MEIZU_MYVU -> "Unavailable for Meizu MYVU: device has no camera."
            DeviceClass.GENERIC_AUDIO -> "Unavailable for Earbuds / Audio-only glasses: device has no camera."
            else -> null
        }

        return listOf(
            NativePluginCardData(
                id = NativePluginIds.LOCAL_AGENT,
                title = "Local Agent",
                description = "Private phone automation with accessibility controls, action approval, local planning, and shared diary memory.",
                badge = "Automation",
                enabled = LocalAgentPlugin.isEnabled(this),
                hasSettings = true,
            ),
            NativePluginCardData(
                id = NativePluginIds.WALKING_AID,
                title = "Walking Aid",
                description = cameraUnavailableReason ?: "Real-time scene description and obstacle warnings for blind navigation. Captures images from glasses at regular intervals and describes the environment.",
                badge = "Accessibility",
                enabled = hasCamera && CommunityPluginPrefs.isNativePluginEnabled(this, NativePluginIds.WALKING_AID),
                hasSettings = true,
                isAvailable = hasCamera,
            ),
            NativePluginCardData(
                id = NativePluginIds.CUE,
                title = "Cue",
                description = "Ambient situational awareness for blind and low vision users. Names who is speaking, flags the moment someone arrives or quietly walks away, and answers what you are looking at — mostly without words.",
                badge = "Accessibility",
                enabled = CuePreferences.isEnabled(this),
                hasSettings = true,
            ),
            NativePluginCardData(
                id = NativePluginIds.ZYGO_PAY,
                title = "UPI Pay",
                description = "Pay a shop by photographing its UPI code with the glasses. Reads out who is being paid and how much, then waits for a yes — your own Solana wallet approves the transfer, so no key or card ever reaches this app.",
                badge = "Accessibility",
                enabled = ZygoPayPreferences.isEnabled(this),
                hasSettings = false,
                // Needs both an endpoint and a camera. Shown as unavailable rather than hidden, so
                // "why can't I pay" has an answer on screen instead of nothing happening.
                isAvailable = hasCamera && ZygoPayPreferences.isConfigured(this),
            ),
            NativePluginCardData(
                id = NativePluginIds.MEETING_SPARK_NOTES,
                title = "Meeting Spark Notes",
                description = "Turns live voice capture and chats into concise meeting summaries with action items.",
                badge = "Productivity",
                enabled = CommunityPluginPrefs.isNativePluginEnabled(this, NativePluginIds.MEETING_SPARK_NOTES),
                hasSettings = true,
            ),
            NativePluginCardData(
                id = NativePluginIds.LIVE_CAPTION_RELAY,
                title = "Live Caption Relay",
                description = if (selectedClass == DeviceClass.MEIZU_MYVU) {
                    "Captions live speech and streams text directly to your Meizu MYVU heads-up display."
                } else {
                    "Captions live speech from your phone or Bluetooth glasses mic, with optional translation."
                },
                badge = "Accessibility",
                enabled = CommunityPluginPrefs.isNativePluginEnabled(this, NativePluginIds.LIVE_CAPTION_RELAY),
                hasSettings = true,
            ),
            NativePluginCardData(
                id = NativePluginIds.HANDS_FREE_TRANSLATOR,
                title = "Hands-Free Translator",
                description = if (selectedClass == DeviceClass.MEIZU_MYVU) {
                    "Continuously translates live speech and displays translated subtitles on your Meizu MYVU HUD."
                } else {
                    "Continuously translates live speech from your phone or Bluetooth glasses mic while enabled."
                },
                badge = "Language",
                enabled = CommunityPluginPrefs.isNativePluginEnabled(this, NativePluginIds.HANDS_FREE_TRANSLATOR),
                hasSettings = true,
            ),
            NativePluginCardData(
                id = NativePluginIds.ERRAND_BRAIN,
                title = "Errand Brain",
                description = "Turns live voice notes into checklist tasks. Say “remind me in…” to schedule a phone reminder.",
                badge = "Planner",
                enabled = CommunityPluginPrefs.isNativePluginEnabled(this, NativePluginIds.ERRAND_BRAIN),
                hasSettings = true,
            ),
            NativePluginCardData(
                id = NativePluginIds.AUTO_DIARY,
                title = "AutoDiary",
                description = "Collect screen context and turn it into private daily facts, bullets, and summaries.",
                badge = "Productivity",
                enabled = AutoDiaryService.isEnabled(this),
                hasSettings = true,
            ),
            NativePluginCardData(
                id = NativePluginIds.AUTO_AUDIO,
                title = "Auto Audio",
                description = autoAudioDescription,
                badge = "Media",
                enabled = hasOnboardStorage && AutoAudioCapturePrefs.isEnabled(this),
                hasSettings = true,
                isAvailable = hasOnboardStorage,
            ),
            NativePluginCardData(
                id = NativePluginIds.VISUAL_DIARY,
                title = "Visual Diary",
                description = cameraUnavailableReason ?: "Capture glasses scenes, describe them with Gemma, and append concise notes to daily memory.",
                badge = "Productivity",
                enabled = hasCamera && VisualDiaryPreferences.isEnabled(this),
                hasSettings = true,
                isAvailable = hasCamera,
            ),
        )
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        refreshNativePluginUi()

        val appearancePreferences = AppearancePreferences(this)
        setContent {
            val appearance by rememberAppearanceSettings(appearancePreferences)
            CyanBridgeTheme(appearance) {
                CommunityPluginsScreen(
                    plugins = communityPlugins,
                    selectedWindow = selectedWindow,
                    isRefreshing = isRefreshing,
                    nativePlugins = nativePluginsState,
                    onOpenNativePluginSettings = { pluginId ->
                        when (pluginId) {
                            NativePluginIds.LOCAL_AGENT -> startActivity(Intent(this, LocalAgentSettingsActivity::class.java))
                            NativePluginIds.WALKING_AID -> startActivity(Intent(this, WalkingAidSettingsActivity::class.java))
                            NativePluginIds.CUE -> startActivity(Intent(this, CueSettingsActivity::class.java))
                            NativePluginIds.MEETING_SPARK_NOTES -> startActivity(Intent(this, MeetingSparkNotesSettingsActivity::class.java))
                            NativePluginIds.LIVE_CAPTION_RELAY -> startActivity(Intent(this, LiveCaptionRelaySettingsActivity::class.java))
                            NativePluginIds.HANDS_FREE_TRANSLATOR -> startActivity(Intent(this, HandsFreeTranslatorSettingsActivity::class.java))
                            NativePluginIds.ERRAND_BRAIN -> startActivity(Intent(this, ErrandBrainSettingsActivity::class.java))
                            NativePluginIds.AUTO_DIARY -> startActivity(Intent(this, AutoDiarySettingsActivity::class.java))
                            NativePluginIds.AUTO_AUDIO -> startActivity(Intent(this, AutoAudioSettingsActivity::class.java))
                            NativePluginIds.VISUAL_DIARY -> startActivity(Intent(this, VisualDiarySettingsActivity::class.java))
                        }
                    },
                    onToggleNativePlugin = ::toggleNativePlugin,
                    onWindowSelected = { selectedWindow = it },
                    onRefresh = ::fetchPluginsFromServer,
                    onOpenCommunityPlugin = ::openCommunityPlugin,
                    onPublishPlugin = {
                        startActivity(Intent(this, PublishPluginActivity::class.java))
                    },
                    onDestinationSelected = ::navigateTo,
                )
            }
        }
        fetchPluginsFromServer()
    }

    override fun onResume() {
        super.onResume()
        refreshNativePluginUi()
    }

    private fun refreshNativePluginUi() {
        nativePluginsState = nativePluginPool()
    }

    private fun toggleNativePlugin(pluginId: String, enabled: Boolean) {
        if (enabled && pluginId == NativePluginIds.AUTO_AUDIO && DeviceProfileStore.isMetaSelected(this)) {
            Toast.makeText(this, "Auto Audio is unavailable for Meta Ray-Ban devices", Toast.LENGTH_LONG).show()
            return
        }
        if (pluginId == NativePluginIds.AUTO_DIARY) {
            applyNativePluginToggle(pluginId, enabled)
            return
        }
        if (enabled &&
            DeviceProfileStore.isMetaSelected(this) &&
            pluginId in setOf(NativePluginIds.WALKING_AID, NativePluginIds.VISUAL_DIARY)
        ) {
            val manager = MetaRaybanManager.getInstance(this)
            if (!manager.isInitialized.value) manager.initialize()
            lifecycleScope.launch {
                if (!manager.awaitCameraReady()) {
                    val detail = manager.lastError.value
                        ?: "Register and connect a Meta camera before enabling $pluginId"
                    android.util.Log.e(
                        "CommunityPluginsActivity",
                        "Unable to enable Meta plugin=$pluginId: $detail\n${manager.diagnosticsSnapshot()}",
                    )
                    Toast.makeText(
                        this@CommunityPluginsActivity,
                        "Meta camera unavailable: $detail",
                        Toast.LENGTH_LONG,
                    ).show()
                    return@launch
                }
                manager.checkCameraPermission(
                    onGranted = { applyNativePluginToggle(pluginId, enabled = true) },
                    onRequestNeeded = {
                        pendingMetaCameraPlugin = pluginId
                        metaWearablePermissionLauncher.launch(Unit)
                    },
                    onError = { error ->
                        android.util.Log.e(
                            "CommunityPluginsActivity",
                            "Meta camera permission error for plugin=$pluginId: $error\n${manager.diagnosticsSnapshot()}",
                        )
                        Toast.makeText(
                            this@CommunityPluginsActivity,
                            "Meta camera permission error: $error",
                            Toast.LENGTH_LONG,
                        ).show()
                    },
                )
            }
            return
        }
        if (enabled && pluginId in VOICE_PLUGIN_IDS && !PluginVoicePermissions.hasRequiredPermissions(this)) {
            PluginVoicePermissions.request(this) { granted ->
                if (granted) {
                    applyNativePluginToggle(pluginId, enabled = true)
                } else {
                    Toast.makeText(
                        this,
                        "Microphone and notification permissions are required for $pluginId",
                        Toast.LENGTH_LONG,
                    ).show()
                }
            }
            return
        }
        if (enabled && pluginId in NOTIFICATION_PLUGIN_IDS && !hasNotificationPermission(this)) {
            ensureNotificationPermission(this, pluginId) {
                applyNativePluginToggle(pluginId, enabled = true)
            }
            return
        }
        applyNativePluginToggle(pluginId, enabled)
    }

    private fun applyNativePluginToggle(pluginId: String, enabled: Boolean) {
        when (pluginId) {
            NativePluginIds.AUTO_DIARY -> {
                if (enabled) AutoDiaryService.enable(this) else AutoDiaryService.disable(this)
                refreshNativePluginUi()
                return
            }
            NativePluginIds.VISUAL_DIARY -> {
                if (enabled) VisualDiaryService.enable(this) else VisualDiaryService.disable(this)
                refreshNativePluginUi()
                return
            }
        }
        CommunityPluginPrefs.setNativePluginEnabled(this, pluginId, enabled)
        nativePluginsState = nativePluginsState.map {
            if (it.id == pluginId) it.copy(enabled = enabled) else it
        }
        when (pluginId) {
            NativePluginIds.LOCAL_AGENT -> {
                LocalAgentPlugin.setEnabled(this, enabled)
            }
            "walking_aid" -> {
                WalkingAidPreferences.setEnabled(this, enabled)
                if (enabled) WalkingAidService.start(this) else WalkingAidService.stop(this)
            }
            NativePluginIds.CUE -> {
                CuePlugin.setEnabled(this, enabled)
            }
            NativePluginIds.ZYGO_PAY -> {
                ZygoPayPlugin.setEnabled(this, enabled)
            }
            "meeting_spark_notes" -> {
                MeetingSparkNotesPreferences.setEnabled(this, enabled)
                if (enabled) MeetingSparkNotesService.start(this) else MeetingSparkNotesService.stop(this)
            }
            "live_caption_relay" -> {
                LiveCaptionRelayPreferences.setEnabled(this, enabled)
                if (enabled) LiveCaptionRelayService.start(this) else LiveCaptionRelayService.stop(this)
            }
            "hands_free_translator" -> {
                HandsFreeTranslatorPreferences.setEnabled(this, enabled)
                if (enabled) HandsFreeTranslatorService.start(this) else HandsFreeTranslatorService.stop(this)
            }
            "errand_brain" -> {
                ErrandBrainPreferences.setEnabled(this, enabled)
                if (enabled) ErrandBrainService.start(this) else ErrandBrainService.stop(this)
            }
            NativePluginIds.AUTO_AUDIO -> {
                AutoAudioCapturePrefs.setEnabled(this, enabled)
                if (enabled) AutoAudioCaptureService.start(this) else AutoAudioCaptureService.stop(this)
            }
        }
    }

    private fun fetchPluginsFromServer() {
        if (isRefreshing) return
        if (!serverPluginsLoaded) {
            Toast.makeText(this, "Fetching plugins from server...", Toast.LENGTH_SHORT).show()
        }
        isRefreshing = true

        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { downloadCommunityPlugins() }
            }

            isRefreshing = false
            result.onSuccess { plugins ->
                communityPlugins = plugins
                serverPluginsLoaded = true
                Toast.makeText(this@CommunityPluginsActivity, "Plugins refreshed from server!", Toast.LENGTH_SHORT).show()
            }.onFailure {
                Toast.makeText(
                    this@CommunityPluginsActivity,
                    "Server unavailable. No community plugins were loaded.",
                    Toast.LENGTH_LONG,
                ).show()
            }
        }
    }

    private fun openCommunityPlugin(plugin: CommunityPluginCardData) {
        val link = plugin.taskerNetLink ?: plugin.downloadUrl ?: return
        runCatching {
            startActivity(
                Intent(
                    Intent.ACTION_VIEW,
                    Uri.parse(link),
                ),
            )
        }.onFailure {
            Toast.makeText(this, "Could not open ${plugin.title}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun downloadCommunityPlugins(): List<CommunityPluginCardData> {
        val relayUrl = AiProviderPrefs.getRelayBaseUrl(this).trimEnd('/')
        val connection = java.net.URL("$relayUrl/plugins").openConnection()
            as java.net.HttpURLConnection
        try {
            connection.requestMethod = "GET"
            connection.connectTimeout = 5_000
            connection.readTimeout = 5_000
            if (connection.responseCode != java.net.HttpURLConnection.HTTP_OK) {
                error("HTTP ${connection.responseCode}")
            }
            val payload = connection.inputStream.bufferedReader().use { it.readText() }
            return parseCommunityPlugins(payload)
        } finally {
            connection.disconnect()
        }
    }

    private fun parseCommunityPlugins(payload: String): List<CommunityPluginCardData> {
        val trimmed = payload.trim()
        val array = if (trimmed.startsWith("[")) {
            JSONArray(trimmed)
        } else {
            val root = JSONObject(trimmed)
            root.optJSONArray("plugins") ?: root.optJSONArray("data") ?: JSONArray()
        }

        return buildList {
            for (index in 0 until array.length()) {
                val plugin = array.optJSONObject(index) ?: continue
                val title = plugin.readString("title", "name") ?: continue
                add(
                    CommunityPluginCardData(
                        id = plugin.readString("id", "slug") ?: title,
                        title = title,
                        author = plugin.readString("author", "publisher") ?: "Unknown",
                        description = plugin.readString("description") ?: "",
                        badge = plugin.readString("badge", "category") ?: "Other",
                        downloadsAll = plugin.readMetric("downloads", "all_time", "all", "allTime"),
                        downloadsMonthly = plugin.readMetric("downloads", "monthly", "month"),
                        downloadsWeekly = plugin.readMetric("downloads", "weekly", "week"),
                        votesAll = plugin.readMetric("votes", "all_time", "all", "allTime"),
                        votesMonthly = plugin.readMetric("votes", "monthly", "month"),
                        votesWeekly = plugin.readMetric("votes", "weekly", "week"),
                        trendAll = plugin.readMetric("trend", "all_time", "all", "allTime"),
                         trendMonthly = plugin.readMetric("trend", "monthly", "month"),
                         trendWeekly = plugin.readMetric("trend", "weekly", "week"),
                         taskerNetLink = plugin.readString("taskernet_link", "taskerNetLink", "taskernetLink"),
                         downloadUrl = plugin.readString("download_url", "downloadUrl"),
                     ),
                )
            }
        }
    }

    private fun JSONObject.readString(vararg keys: String): String? {
        return keys.firstNotNullOfOrNull { key ->
            optString(key).trim().takeIf { it.isNotBlank() }
        }
    }

    private fun JSONObject.readMetric(key: String, vararg names: String): Int {
        val nested = optJSONObject(key)
        if (nested != null) {
            nested.readInt(*names)?.let { return it }
        }
        val flatKeys = names.map { name -> "${key}_$name" } + names.map { name -> "${key}${name.replaceFirstChar { it.uppercase() }}" }
        return readInt(*flatKeys.toTypedArray()) ?: 0
    }

    private fun JSONObject.readInt(vararg keys: String): Int? {
        return keys.firstNotNullOfOrNull { key ->
            if (has(key)) optInt(key) else null
        }
    }

    private fun navigateTo(destination: AppDestination) {
        val target = when (destination) {
            AppDestination.GLASSES -> Intent(this, MainActivity::class.java)
            AppDestination.CHATS -> buildRecentChatIntent()
            AppDestination.MEDIA -> Intent(this, RecordingsListActivity::class.java)
            AppDestination.PLUGINS -> return
            AppDestination.SETTINGS -> Intent(this, SettingsActivity::class.java)
        }
        target.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        startActivity(target)
    }

    private fun buildRecentChatIntent(): Intent {
        val last = ChatStore.listNonEmptyThreads().firstOrNull()
        val lastUserAt = last?.let { thread ->
            ChatStore.listMessages(thread.id)
                .lastOrNull { it.role == ChatRole.USER }
                ?.createdAt
        } ?: 0L
        val openChatId = last?.id?.takeIf {
            lastUserAt > 0L && System.currentTimeMillis() - lastUserAt < 30 * 60 * 1_000
        }
        return Intent(this, ChatThreadActivity::class.java).apply {
            if (openChatId != null) {
                putExtra(ChatThreadActivity.EXTRA_CHAT_ID, openChatId)
            }
        }
    }

    private companion object {
        private val VOICE_PLUGIN_IDS = setOf(
            "meeting_spark_notes",
            "live_caption_relay",
            "hands_free_translator",
            "errand_brain",
            NativePluginIds.AUTO_AUDIO,
            NativePluginIds.CUE,
        )

        private val NOTIFICATION_PLUGIN_IDS = setOf(
            NativePluginIds.WALKING_AID,
            NativePluginIds.AUTO_DIARY,
            NativePluginIds.VISUAL_DIARY,
        )
    }
}
