package com.fersaiyan.cyanbridge.glasses

/**
 * The vendor SDK exposes singleton BLE response/listener slots and a singleton P2P controller.
 * Only one workflow may control those resources at a time.
 */
enum class GlassesSession(val label: String) {
    MEDIA_SYNC("media sync"),
    LIVE_PREVIEW("live preview"),
    OTA("firmware update"),
    WIFI_ADB_DEBUG("Wi-Fi ADB debug"),
    META_CAMERA("Meta camera"),
}

class GlassesSessionLease internal constructor(
    val session: GlassesSession,
    internal val id: Long,
)

class BackgroundGlassesCommandPermit internal constructor(
    internal val id: Long,
    /** Who asked for the slot. Used only for diagnostics when a later caller is denied. */
    val owner: String,
)

object GlassesSessionCoordinator {
    private var activeLease: GlassesSessionLease? = null
    private var nextSessionId = 0L
    private var nextBackgroundCommandId = 0L
    /**
     * Held permits, by id. A map rather than a set of ids because the only hard question this
     * class ever gets asked in anger is "who is holding the slot, and for how long" - and an
     * unlabelled id cannot answer it.
     */
    private val activeBackgroundCommands = mutableMapOf<Long, BackgroundCommandRecord>()

    private data class BackgroundCommandRecord(val owner: String, val acquiredAtMs: Long)

    @Synchronized
    fun tryAcquire(session: GlassesSession): Boolean {
        return tryAcquireLease(session) != null
    }

    @Synchronized
    fun tryAcquireLease(session: GlassesSession): GlassesSessionLease? {
        if (activeLease != null || activeBackgroundCommands.isNotEmpty()) return null
        return GlassesSessionLease(session, ++nextSessionId).also { activeLease = it }
    }

    @Synchronized
    fun release(session: GlassesSession): Boolean {
        val lease = activeLease ?: return false
        if (lease.session != session) return false
        activeLease = null
        return true
    }

    @Synchronized
    fun release(lease: GlassesSessionLease): Boolean {
        if (activeLease !== lease) return false
        activeLease = null
        return true
    }

    @Synchronized
    fun currentSession(): GlassesSession? = activeLease?.session

    @Synchronized
    fun isOwnedBy(session: GlassesSession): Boolean = activeLease?.session == session

    @Synchronized
    fun isActive(lease: GlassesSessionLease): Boolean = activeLease === lease

    @Synchronized
    fun canRunBackgroundCommand(): Boolean =
        activeLease == null && activeBackgroundCommands.isEmpty()

    /** Atomically reserves the shared SDK response slot for a short one-shot command. */
    @Synchronized
    fun tryAcquireBackgroundCommand(
        owner: String = "unlabelled",
    ): BackgroundGlassesCommandPermit? {
        if (activeLease != null || activeBackgroundCommands.isNotEmpty()) return null
        val permit = BackgroundGlassesCommandPermit(++nextBackgroundCommandId, owner)
        activeBackgroundCommands[permit.id] =
            BackgroundCommandRecord(owner, System.currentTimeMillis())
        return permit
    }

    /**
     * Names the current holders and how long they have held the slot.
     *
     * A permit that is never released blocks every later one-shot command for the life of the
     * process, and the failure surfaces far from its cause - so a denial has to be able to say
     * more than "something else owns it".
     */
    @Synchronized
    fun describeBackgroundCommandHolders(): String {
        if (activeBackgroundCommands.isEmpty()) return "no background command"
        val now = System.currentTimeMillis()
        return activeBackgroundCommands.values.joinToString { record ->
            "${record.owner} (held ${now - record.acquiredAtMs}ms)"
        }
    }

    @Synchronized
    fun releaseBackgroundCommand(permit: BackgroundGlassesCommandPermit) {
        activeBackgroundCommands -= permit.id
    }

    @Synchronized
    fun isBackgroundCommandActive(permit: BackgroundGlassesCommandPermit): Boolean =
        permit.id in activeBackgroundCommands

    /** A BLE reconnect discards any vendor callback slot that may have been left pending. */
    @Synchronized
    fun clearBackgroundCommands() {
        activeBackgroundCommands.clear()
    }

    /** A real BLE disconnect invalidates every pending vendor response and P2P workflow. */
    @Synchronized
    fun clearForDisconnectedDevice() {
        activeLease = null
        activeBackgroundCommands.clear()
    }
}
