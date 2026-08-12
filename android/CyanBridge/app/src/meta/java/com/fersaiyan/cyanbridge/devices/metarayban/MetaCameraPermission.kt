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
 *
 * **All three members of [ActivityResultContract] are delegated, including
 * [ActivityResultContract.getSynchronousResult].** That third one is easy to miss because it has a
 * default implementation returning null, so omitting it compiles and behaves plausibly — see the
 * note on it below for why omitting it would nonetheless be a regression.
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

            /**
             * Delegated defensively, because not delegating it is silently wrong.
             *
             * Permission contracts commonly override this to short-circuit when permission is
             * already granted, returning the answer without launching an activity — Android's own
             * `ActivityResultContracts.RequestPermission` does exactly that. Inheriting the null
             * default instead would launch the permission activity on *every* capture, where the
             * pre-facade code went straight through. The user sees a prompt or a flash each time,
             * and nothing about it fails: it compiles, and it is invisible to every build that
             * does not have the DAT artifact on a real device.
             *
             * Whether `Wearables.RequestPermissionContract` overrides it is unknown here — the
             * artifact needs a `read:packages` grant nobody on this project has. The delegation is
             * safe either way: if the SDK does not override it the call returns null and behaviour
             * is unchanged, and if it does, the short-circuit is preserved.
             */
            override fun getSynchronousResult(
                context: Context,
                input: Unit,
            ): SynchronousResult<Boolean>? =
                delegate.getSynchronousResult(context, Permission.CAMERA)?.let { synchronous ->
                    SynchronousResult(
                        synchronous.value
                            .getOrDefault(PermissionStatus.Denied) == PermissionStatus.Granted,
                    )
                }
        }
}
