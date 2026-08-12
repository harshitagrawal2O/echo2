package com.fersaiyan.cyanbridge.plugins.cue

import android.content.Context
import com.fersaiyan.cyanbridge.shared.plugins.NativePluginIds
import com.fersaiyan.cyanbridge.ui.CommunityPluginPrefs

/**
 * The single seam between Cue and the rest of the app.
 *
 * Cue is a plugin, not a fork. `MainActivity` is 10,094 lines and every line of it Cue touches is a
 * merge conflict and a regression surface, so the whole integration is two delegations into this
 * object: one in the device-notify dispatch, one for connection state. Everything else lives under
 * `plugins/cue/`.
 */
object CuePlugin {

    @Volatile
    private var service: CueService? = null

    internal fun attach(service: CueService) {
        this.service = service
    }

    internal fun detach(service: CueService) {
        if (this.service === service) this.service = null
    }

    fun isEnabled(context: Context): Boolean =
        CuePreferences.isEnabled(context) &&
            CommunityPluginPrefs.isNativePluginEnabled(context, NativePluginIds.CUE)

    fun setEnabled(context: Context, enabled: Boolean) {
        CuePreferences.setEnabled(context, enabled)
        CommunityPluginPrefs.setNativePluginEnabled(context, NativePluginIds.CUE, enabled)
        if (enabled) CueService.start(context) else CueService.stop(context)
    }

    /**
     * Forwards one decoded device-notify frame. Returns true when Cue consumed it, so the caller
     * can return early instead of running its own handling for the same press.
     */
    fun onDeviceNotify(context: Context, code: Int, loadData: ByteArray): Boolean {
        if (!isEnabled(context)) return false
        return service?.router?.onDeviceNotify(code, loadData) ?: false
    }

    /**
     * Called when the BLE link drops.
     *
     * The user must hear this. Silence is indistinguishable from an empty room, and acting on
     * "nobody said anything" when the truth is "Cue stopped listening" is exactly the
     * desynchronisation the product exists to prevent.
     */
    fun onGlassesDisconnected(context: Context) {
        if (!isEnabled(context)) return
        service?.onGlassesDisconnected()
    }

    fun onGlassesConnected(context: Context) {
        if (!isEnabled(context)) return
        val active = service
        if (active != null) active.onGlassesConnected() else CueService.start(context)
    }

    /** Current session state, or null when nothing is running. Used by the settings screen. */
    fun status(): CueSessionStatus? = service?.status()

    fun session(): CueSession? = service?.session
}
