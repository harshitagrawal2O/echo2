package com.fersaiyan.cyanbridge.devices.metarayban

import android.content.Context
import android.content.Intent
import androidx.activity.result.contract.ActivityResultContract
import com.meta.wearable.dat.core.Wearables
import com.meta.wearable.dat.core.types.Permission
import com.meta.wearable.dat.core.types.PermissionStatus

/**
 * Meta variant: the real camera-permission handshake.
 *
 * This is the only file outside [MetaRaybanManager] that touches the DAT SDK, and it exists so that
 * the two screens which ask for camera permission do not have to. They see `Unit -> Boolean`; the
 * `Permission` and `PermissionStatus` types stop here.
 *
 * Paired with the `src/nometa` implementation of the same object. Exactly one is compiled, selected
 * by `-PmetaSupport` in `app/build.gradle`.
 */
object MetaCameraPermission {

    fun cameraContract(): ActivityResultContract<Unit, Boolean> =
        object : ActivityResultContract<Unit, Boolean>() {

            private val delegate = Wearables.RequestPermissionContract()

            override fun createIntent(context: Context, input: Unit): Intent =
                delegate.createIntent(context, Permission.CAMERA)

            override fun parseResult(resultCode: Int, intent: Intent?): Boolean =
                delegate.parseResult(resultCode, intent)
                    .getOrDefault(PermissionStatus.Denied) == PermissionStatus.Granted
        }
}
