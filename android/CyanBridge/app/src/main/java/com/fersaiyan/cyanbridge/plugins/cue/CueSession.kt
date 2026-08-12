package com.fersaiyan.cyanbridge.plugins.cue

import android.content.Context
import android.util.Log
import com.fersaiyan.cyanbridge.plugins.cue.transcribe.CueTranscriber
import com.fersaiyan.cyanbridge.plugins.cue.transcribe.DeepgramTranscriber
import com.fersaiyan.cyanbridge.plugins.cue.transcribe.ScriptedTranscriber
import com.fersaiyan.cyanbridge.plugins.cue.transcribe.TranscriptSegment
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/** What the dev overlay and the settings screen read back out of a running session. */
data class CueSessionStatus(
    val running: Boolean,
    val micRoute: CueMicRoute,
    val sampleRate: Int,
    val transcriptionLive: Boolean,
    val rehearsal: Boolean,
    val rosterSize: Int,
    val wearerBound: Boolean,
    val stats: CueOutputStats,
    val gapHistogram: Map<String, Int>,
    val gapAvailabilityAtThreshold: Double,
)

/**
 * Wires the whole product together for one wearing of the glasses.
 *
 * Flow, in one paragraph: the microphone produces frames; frames feed the gap detector (which
 * decides when Cue is allowed to talk) and the transcriber (which produces diarized turns); turns
 * feed the context engine (which maintains the roster, the addressee, and the staleness clocks) and
 * passive roll call (which binds names); the engine emits events; the arbiter decides which of
 * those events are worth the user's attention; the dispatcher plays them in the cheapest tier that
 * carries the information, waiting for a gap where required.
 *
 * Everything expensive is off the critical path. Naming a speaker is a hash lookup against a cached
 * binding, never a network call, because the budget from turn start to name in ear is 800ms.
 */
class CueSession(
    context: Context,
    private val onStatusChanged: (CueSessionStatus) -> Unit = {},
) {

    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val stateLock = Mutex()
    private val running = AtomicBoolean(false)

    private val engine = CueContextEngine(
        CueEngineConfig(
            addresseeSilenceMs = CuePreferences.getAddresseeSilenceMs(appContext),
            presenceTimeoutMs = CuePreferences.getPresenceTimeoutMs(appContext),
        ),
    )
    private val arbiter = CueDivergenceArbiter()
    private val gapDetector = CueGapDetector()
    private val wearerBinder = CueWearerBinder()
    private val energy = CueEnergyTrack()
    private val energyLock = Any()

    private val earcons = CueEarconPlayer(CuePreferences.getEarconVolume(appContext))
    private val speaker = CueSpeaker(appContext)
    private val dispatcher = CueOutputDispatcher(
        gapDetector = gapDetector,
        earcons = earcons,
        speaker = speaker,
        configProvider = {
            CueDispatchConfig(requiredGapMs = CuePreferences.getRequiredGapMs(appContext))
        },
        onSpoken = engine::onSpoke,
    )

    private val claude = CueClaudeClient(
        apiKeyProvider = { CuePreferences.getAnthropicApiKey(appContext) },
    )
    private val photoCapture = CuePhotoCapture(appContext)

    private var capture: CueAudioCapture? = null
    private var transcriber: CueTranscriber? = null
    private var tickJob: Job? = null

    @Volatile
    private var transcriptionLive = false

    @Volatile
    private var rehearsal = false

    @Volatile
    private var yieldedToVendorAssistant = false

    @Volatile
    private var lastRollCallAtMs = 0L

    // ── lifecycle ──

    fun start(): Boolean {
        if (!running.compareAndSet(false, true)) return true
        val nowMs = System.currentTimeMillis()

        engine.start(nowMs)
        arbiter.reset()
        gapDetector.reset()
        wearerBinder.reset()
        dispatcher.reset()
        synchronized(energyLock) { energy.clear() }

        speaker.setRates(
            CuePreferences.getWhisperRate(appContext),
            CuePreferences.getBriefingRate(appContext),
        )

        rehearsal = CuePreferences.isRehearsalEnabled(appContext)
        startTicking()

        // Bringing up the SCO route blocks for as long as the headset takes to answer, which can
        // be several seconds. start() is reachable from the service's onStartCommand, so none of
        // this may touch the calling thread.
        scope.launch {
            startTranscriber()
            val micUp = startCapture()
            // One utterance, not two: a briefing flushes whatever is already speaking, so a
            // separate caveat and roster would cut each other off.
            //
            // The caveat leads. What Cue can actually do this session matters more than who is in
            // the room, because a roster read from a dead microphone is a confident answer to the
            // wrong question.
            val caveat = startupCaveat(micUp)
            val roster = if (micUp) CuePrompts.rosterFallback(engine.snapshot()) else null
            val opening = listOfNotNull(caveat, roster).joinToString(" ")
            if (opening.isNotBlank()) {
                dispatcher.briefing(opening, System.currentTimeMillis())
            }
            publishStatus()
        }
        publishStatus()
        return true
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        tickJob?.cancel()
        tickJob = null
        capture?.stop()
        capture = null
        transcriber?.stop()
        transcriber = null
        speaker.stop()
        dispatcher.clearPending()
        // Taking the glasses off clears the roster. That is the privacy posture expressed as a
        // physical act rather than a settings toggle.
        engine.clear()
        publishStatus()
    }

    fun release() {
        stop()
        speaker.release()
        earcons.release()
        scope.cancel()
    }

    val isRunning: Boolean get() = running.get()

    // ── audio ──

    private fun startCapture(): Boolean {
        val audio = CueAudioCapture(
            context = appContext,
            preferGlassesMic = CuePreferences.preferGlassesMic(appContext),
        )
        val started = audio.start { pcm, length, rms, atMs ->
            onAudioFrame(pcm, length, rms, atMs)
        }
        if (!started) {
            Log.w(TAG, "Microphone unavailable; Cue is deaf for this session")
            dispatcher.playEarcon(CueEarcon.FAILED, System.currentTimeMillis())
            return false
        }
        capture = audio
        // Follow the mic: while SCO holds the link, media-usage output may not reach the glasses.
        val voiceRoute = audio.route == CueMicRoute.GLASSES
        earcons.setVoiceRoute(voiceRoute)
        speaker.setVoiceRoute(voiceRoute)
        return true
    }

    /**
     * Says out loud, once, what Cue can and cannot do this session.
     *
     * This is the single most important thing in the product for a user who cannot look at a
     * screen. A Cue that has lost its microphone or its speech recognition behaves *identically*
     * to a Cue in a silent room — and a user who cannot tell those apart will assume nobody is
     * there and act on it. Every other degradation in this file is allowed to be quiet. This one
     * is not.
     */
    private fun startupCaveat(micUp: Boolean): String? = when {
        !micUp -> "Cue is not hearing anything. Check microphone permission."
        !transcriptionLive && !rehearsal ->
            "Cue is listening, but names are off. Add a speech recognition key."

        rehearsal -> "Rehearsal mode."
        else -> null
    }

    /**
     * Speech recognition went away mid-session.
     *
     * Fires once. The earcon says something broke and the sentence says what stopped working, so
     * the user knows the quiet room is Cue's fault rather than the room's.
     */
    private fun announceLostNames() {
        val wasLive = transcriptionLive
        transcriptionLive = false
        publishStatus()
        if (!wasLive || !running.get()) return
        dispatcher.playEarcon(CueEarcon.FAILED, System.currentTimeMillis())
        dispatcher.briefing("Names are off. Cue is still listening.", System.currentTimeMillis())
    }

    private fun onAudioFrame(pcm: ByteArray, length: Int, rms: Double, atMs: Long) {
        if (yieldedToVendorAssistant) return

        synchronized(energyLock) { energy.record(rms, atMs) }

        val silenceStarted = gapDetector.onFrame(rms, atMs)
        if (silenceStarted) {
            // A gap just opened. Anything queued becomes eligible right now rather than on the
            // next scheduler tick, which is where most of the whisper latency would otherwise go.
            dispatcher.tick(atMs)
        }
        if (!rehearsal) transcriber?.send(pcm, length)
    }

    // ── transcription ──

    private fun startTranscriber() {
        val listener = object : CueTranscriber.Listener {
            override fun onSegment(segment: TranscriptSegment) {
                scope.launch { onSegment(segment) }
            }

            override fun onError(message: String) {
                Log.w(TAG, "Transcription error: $message")
                announceLostNames()
            }

            override fun onClosed() {
                // Degrade rather than stop: without labels Cue keeps its earcons and its buttons.
                Log.i(TAG, "Transcription closed; continuing earcon-only")
                announceLostNames()
            }
        }

        val backend: CueTranscriber? = if (rehearsal) {
            val lines = ScriptedTranscriber.parse(CuePreferences.getRehearsalScript(appContext))
            if (lines.isEmpty()) null else ScriptedTranscriber(lines)
        } else {
            val key = CuePreferences.getTranscriptionApiKey(appContext)
            if (key.isBlank()) {
                null
            } else {
                DeepgramTranscriber(
                    apiKey = key,
                    sampleRate = CueAudioCapture.DEFAULT_SAMPLE_RATE,
                    languageTag = CuePreferences.getLanguage(appContext),
                )
            }
        }

        transcriptionLive = backend?.start(listener) == true
        transcriber = backend.takeIf { transcriptionLive }
        if (!transcriptionLive) {
            Log.w(TAG, "No transcription backend; speaker attribution is unavailable")
        }
    }

    private suspend fun onSegment(segment: TranscriptSegment) {
        // Interim segments get relabelled retroactively. Acting on them means whispering a name
        // that the diarizer is about to change its mind about.
        if (!segment.isFinal || segment.text.isBlank()) return

        val turn = Turn(
            speakerLabel = segment.speakerLabel,
            text = segment.text,
            startMs = segment.startMs,
            endMs = segment.endMs,
            energyRms = synchronized(energyLock) {
                energy.meanRmsBetween(segment.startMs, segment.endMs)
            },
        )

        stateLock.withLock {
            wearerBinder.observe(turn)?.let { label ->
                Log.i(TAG, "Bound wearer to $label")
                engine.bindWearer(label)
            }
            val events = engine.onTurn(turn)
            dispatch(events, segment.endMs)
            runLocalRollCall(segment.endMs)
        }
        maybeRunModelRollCall(segment.endMs)
        publishStatus()
    }

    // ── roll call ──

    /** Regex path. Instant, offline, and conservative enough to run on every turn. */
    private fun runLocalRollCall(nowMs: Long) {
        val context = engine.snapshot()
        val bindings = CueRollCall.detectLocally(context.turns, context.wearerLabel)
        for (binding in bindings) {
            engine.bindName(binding.speakerLabel, binding.name, binding.confidence, nowMs)
                ?.let { dispatch(listOf(it), nowMs) }
        }
    }

    /**
     * Model path. One call over the transcript window, rate limited, and only while someone is
     * still unnamed — there is nothing to gain from re-asking about a fully named roster.
     */
    private fun maybeRunModelRollCall(nowMs: Long) {
        if (nowMs - lastRollCallAtMs < ROLL_CALL_INTERVAL_MS) return
        val context = engine.snapshot()
        val unnamed = context.presentOthers().count { it.name.isNullOrBlank() }
        if (unnamed == 0 || context.turns.isEmpty()) return
        if (CuePreferences.getAnthropicApiKey(appContext).isBlank()) return
        lastRollCallAtMs = nowMs

        scope.launch {
            val result = claude.ask(
                CueClaudeRequest(
                    model = CueModelIds.HAIKU,
                    system = CuePrompts.rollCallSystemPrompt(),
                    userText = CueRollCall.buildPrompt(context.turns, context.wearerLabel),
                    maxTokens = 400,
                ),
            )
            val bindings = result.getOrNull()?.let(CueRollCall::parseResponse) ?: return@launch
            val atMs = System.currentTimeMillis()
            stateLock.withLock {
                for (binding in bindings) {
                    if (binding.speakerLabel == engine.snapshot().wearerLabel) continue
                    engine.bindName(binding.speakerLabel, binding.name, binding.confidence, atMs)
                        ?.let { dispatch(listOf(it), atMs) }
                }
            }
            publishStatus()
        }
    }

    // ── scheduler ──

    private fun startTicking() {
        tickJob = scope.launch {
            while (isActive && running.get()) {
                val nowMs = System.currentTimeMillis()
                if (!yieldedToVendorAssistant) {
                    stateLock.withLock { dispatch(engine.onTick(nowMs), nowMs) }
                    dispatcher.tick(nowMs)
                }
                (transcriber as? DeepgramTranscriber)?.keepAliveIfIdle(nowMs)
                delay(TICK_INTERVAL_MS)
            }
        }
    }

    private fun dispatch(events: List<CueEvent>, nowMs: Long) {
        for (event in events) {
            if (event is CueEvent.Ambient && !CuePreferences.isAmbientEnabled(appContext)) continue
            dispatcher.submit(arbiter.decide(event, engine.snapshot()), nowMs)
        }
    }

    // ── user-triggered actions ──

    /**
     * "Who's here".
     *
     * Composed entirely from state already in memory: no network call, no coroutine, no await. The
     * bar is a spoken answer starting within 1.5 seconds of the press, and it has to hold when the
     * venue Wi-Fi does not.
     */
    fun whosHere() {
        dispatcher.briefing(
            CuePrompts.rosterFallback(engine.snapshot()),
            System.currentTimeMillis(),
        )
    }

    /** Replays the last output verbatim from cache. Never regenerates. */
    fun repeatLast() {
        dispatcher.repeat(engine.lastOutput(), System.currentTimeMillis())
    }

    /** Cuts speech immediately. A user who has heard enough should not have to wait it out. */
    fun interruptSpeech() {
        speaker.stop()
        dispatcher.clearPending()
    }

    /** Whether Cue is mid-utterance, which is what makes the pause button mean "stop" not "brief me". */
    fun isSpeakingNow(): Boolean = speaker.isSpeaking

    /**
     * "What am I looking at". The photo already exists — the hardware button took it — so this only
     * pulls the thumbnail and asks about it with the conversation attached.
     */
    fun answerVisualQuestion(spokenQuestion: String? = null) {
        val nowMs = System.currentTimeMillis()
        scope.launch {
            dispatcher.playEarcon(CueEarcon.WORKING, nowMs)
            val captured = runCatching { photoCapture.receiveThumbnail() }.getOrElse { error ->
                // Busy is not failure: the command was understood and the device was in another
                // mode. Different earcon, and the request is dropped rather than queued.
                val earcon = if (error is GlassesBusyException) CueEarcon.BUSY else CueEarcon.FAILED
                dispatcher.playEarcon(earcon, System.currentTimeMillis())
                Log.w(TAG, "Photo capture failed", error)
                return@launch
            }
            answerAboutImage(captured.file, spokenQuestion)
        }
    }

    private suspend fun answerAboutImage(file: File, spokenQuestion: String?) {
        val nowMs = System.currentTimeMillis()
        engine.onPhoto(PhotoContext(file.absolutePath, nowMs))

        val base64 = CuePhotoCapture.encodeBase64(file)
        if (base64 == null) {
            dispatcher.playEarcon(CueEarcon.FAILED, nowMs)
            return
        }
        val context = engine.snapshot()
        val result = claude.ask(
            CueClaudeRequest(
                model = CueModelIds.SONNET,
                system = CuePrompts.visualSystemPrompt(),
                userText = CuePrompts.visualUserPrompt(context, nowMs, spokenQuestion),
                imageBase64 = base64,
                maxTokens = 300,
                // No thinking and modest effort: this is a fifteen-word answer, and the user is
                // standing in a conversation waiting for it.
                disableThinking = true,
                effort = "medium",
            ),
        )
        result.onSuccess { answer ->
            dispatcher.briefing(answer, System.currentTimeMillis())
        }.onFailure { error ->
            Log.w(TAG, "Visual question failed", error)
            dispatcher.playEarcon(CueEarcon.FAILED, System.currentTimeMillis())
        }
    }

    /**
     * Arms the exact wearer binding: the next voice heard is the user.
     *
     * Confirms out loud rather than by changing a label on screen. The user pressing this cannot
     * see the button they just pressed, and an armed state with no feedback is indistinguishable
     * from a press that did nothing.
     */
    fun bindWearerOnNextVoice() {
        wearerBinder.armExplicitBinding()
        dispatcher.briefing("Say a few words now.", System.currentTimeMillis())
    }

    fun onGlassesLost() {
        dispatch(listOf(CueEvent.GlassesLost(System.currentTimeMillis())), System.currentTimeMillis())
    }

    fun onGlassesBusy() {
        dispatcher.playEarcon(CueEarcon.BUSY, System.currentTimeMillis())
    }

    fun onAmbientEvent(category: String) {
        val nowMs = System.currentTimeMillis()
        scope.launch { dispatch(listOf(engine.onAmbient(category, nowMs)), nowMs) }
    }

    /**
     * Yields to the vendor's own assistant.
     *
     * A collision becomes a handoff: when the vendor assistant opens or speaks, Cue goes fully
     * silent and releases the microphone, then picks back up when it closes. Fighting over the mic
     * would produce a whole class of failure that costs nothing to avoid.
     */
    fun onVendorAssistantActive(active: Boolean) {
        if (yieldedToVendorAssistant == active) return
        yieldedToVendorAssistant = active
        if (active) {
            Log.i(TAG, "Vendor assistant active; releasing the microphone")
            speaker.stop()
            dispatcher.clearPending()
            capture?.stop()
            capture = null
        } else if (running.get()) {
            Log.i(TAG, "Vendor assistant closed; resuming")
            gapDetector.reset()
            // Re-acquiring the SCO route blocks, and this arrives on a BLE callback thread.
            scope.launch {
                startCapture()
                publishStatus()
            }
        }
        publishStatus()
    }

    fun status(): CueSessionStatus {
        val context = engine.snapshot()
        return CueSessionStatus(
            running = running.get(),
            micRoute = capture?.route ?: CueMicRoute.NONE,
            sampleRate = capture?.activeSampleRate ?: 0,
            transcriptionLive = transcriptionLive,
            rehearsal = rehearsal,
            rosterSize = context.presentOthers().size,
            wearerBound = context.wearerLabel != null,
            stats = dispatcher.stats(),
            gapHistogram = gapDetector.gapHistogram(),
            gapAvailabilityAtThreshold =
            gapDetector.gapAvailability(CuePreferences.getRequiredGapMs(appContext)),
        )
    }

    private fun publishStatus() {
        runCatching { onStatusChanged(status()) }
    }

    private companion object {
        const val TAG = "CueSession"
        const val TICK_INTERVAL_MS = 500L

        /** How often the model roll call may run while someone is still unnamed. */
        const val ROLL_CALL_INTERVAL_MS = 12_000L
    }
}
