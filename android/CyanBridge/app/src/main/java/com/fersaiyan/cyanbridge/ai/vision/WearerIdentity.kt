package com.fersaiyan.cyanbridge.ai.vision

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.ContactsContract
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * Who is wearing the glasses, taken from the phone rather than sensed.
 *
 * The wearer is the one person this product can never recognise: the camera points away from them,
 * so face matching identifies everyone except them. Their voice is bound once - see
 * `CueWearerBinder` - purely so it can be *excluded* from the roster of people around them.
 *
 * Their name, though, does not need sensing at all. It is already on the device: Android keeps a
 * "Me" contact for the owner, which is exactly this question already answered. Reading it costs one
 * query and makes the assistant able to say "you're holding a bottle, Amogh" instead of addressing a
 * stranger it is strapped to.
 *
 * Fails quiet and nameless. A missing profile, a denied permission or an empty display name all mean
 * "no name available", and the prompt simply omits it - a wrong name spoken confidently into
 * someone's ear is worse than no name at all.
 */
object WearerIdentity {

    private const val TAG = "WearerIdentity"

    @Volatile
    private var cached: String? = null

    /** The wearer's display name from the device owner profile, or blank if unavailable. */
    fun displayName(context: Context): String {
        cached?.let { return it }
        val resolved = readProfileName(context)
        cached = resolved
        Log.i(TAG, "Wearer name resolved: " + if (resolved.isBlank()) "<none>" else resolved)
        return resolved
    }

    /** Re-reads on the next call, for when the permission is granted after first use. */
    fun invalidate() {
        cached = null
    }

    private fun readProfileName(context: Context): String {
        if (
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            Log.i(TAG, "READ_CONTACTS not granted; wearer stays nameless")
            return ""
        }
        return runCatching {
            context.contentResolver.query(
                ContactsContract.Profile.CONTENT_URI,
                arrayOf(ContactsContract.Profile.DISPLAY_NAME_PRIMARY),
                null,
                null,
                null,
            )?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0)?.trim().orEmpty() else ""
            }.orEmpty()
        }.getOrElse {
            Log.w(TAG, "Could not read the owner profile", it)
            ""
        }
    }
}
