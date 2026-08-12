package com.fersaiyan.cyanbridge.devices.metarayban

import android.content.Context
import android.content.Intent
import androidx.activity.result.contract.ActivityResultContract

/**
 * Non-Meta variant: there is no Meta camera, so permission is permanently refused.
 *
 * Refusing is the honest answer rather than an error. The callers already handle a denial — they
 * show "Meta camera permission was denied" and leave the plugin off — so a build without Ray-Ban
 * support degrades along a path the UI already understands, instead of crashing or pretending.
 *
 * The launch never starts an activity: [createIntent] is unreachable because the contract is only
 * reached through callers that check [MetaRaybanManager] availability first, and if it somehow is
 * launched, an empty intent resolves to nothing and [parseResult] answers false.
 *
 * Paired with the `src/meta` implementation. Exactly one is compiled, selected by `-PmetaSupport`.
 */
object MetaCameraPermission {

    fun cameraContract(): ActivityResultContract<Unit, Boolean> =
        object : ActivityResultContract<Unit, Boolean>() {

            override fun createIntent(context: Context, input: Unit): Intent = Intent()

            override fun parseResult(resultCode: Int, intent: Intent?): Boolean = false

            /**
             * Short-circuits before any activity launch: the answer is known, so the framework
             * gets the result synchronously and no empty intent is ever dispatched.
             */
            override fun getSynchronousResult(
                context: Context,
                input: Unit,
            ): SynchronousResult<Boolean> = SynchronousResult(false)
        }
}
