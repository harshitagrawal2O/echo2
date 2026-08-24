package com.fersaiyan.cyanbridge.plugins.zygopay

import android.content.Context
import android.content.Intent
import com.fersaiyan.cyanbridge.shared.plugins.NativePluginIds
import com.fersaiyan.cyanbridge.ui.CommunityPluginPrefs

/**
 * The single seam between UPI payments and the rest of the app, following the same rule as
 * [com.fersaiyan.cyanbridge.plugins.cue.CuePlugin]: `MainActivity` is over ten thousand lines and
 * every line of it a plugin touches is a merge conflict and a regression surface.
 *
 * Here that matters twice over, because `MainActivity` is under active edit by someone else. So the
 * integration is one delegation for the hardware button, and nothing else.
 */
object ZygoPayPlugin {

    fun isEnabled(context: Context): Boolean =
        ZygoPayPreferences.isEnabled(context) &&
            CommunityPluginPrefs.isNativePluginEnabled(context, NativePluginIds.ZYGO_PAY)

    fun setEnabled(context: Context, enabled: Boolean) {
        ZygoPayPreferences.setEnabled(context, enabled)
        CommunityPluginPrefs.setNativePluginEnabled(context, NativePluginIds.ZYGO_PAY, enabled)
    }

    /**
     * Starts a payment from a still already captured by the glasses.
     *
     * A payment can only ever begin in an activity, never in the background. That is not an
     * implementation detail — the flow's confirmation step needs a person present to hear the
     * merchant and the amount and agree, and the wallet's approval screen is an activity result. A
     * background path to this would be a background path to spending money.
     *
     * The still is handed over as bytes rather than a file path so nothing that could be paid
     * against is left on disk after the flow ends.
     */
    fun startPaymentFromCapture(context: Context, jpeg: ByteArray) {
        if (!isEnabled(context)) return
        context.startActivity(
            Intent(context, ZygoPayActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(ZygoPayActivity.EXTRA_CAPTURE_JPEG, jpeg),
        )
    }

    /** Opens the payment screen with no capture; it will ask the glasses for one. */
    fun open(context: Context) {
        context.startActivity(
            Intent(context, ZygoPayActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    fun client(context: Context): ZygoClient = ZygoClient(
        baseUrl = ZygoPayPreferences.serviceUrl(context),
        proxyToken = ZygoPayPreferences.proxyToken(context),
    )
}
