package com.fersaiyan.cyanbridge.plugins.cue

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.sqrt

/** Which microphone the capture actually ended up on. */
enum class CueMicRoute { GLASSES, PHONE, NONE }

/**
 * The live microphone stream everything real-time in Cue hangs off.
 *
 * The glasses pair as an ordinary Bluetooth headset, so the phone can hear the mic live and
 * transcribe in real time. On-glasses recording is a different path entirely — files pulled later
 * in a batch transfer that hijacks the phone's Wi-Fi — and Cue never touches it during a session.
 *
 * **Unverified against hardware.** The CY-01 uses dual ENC microphones, and environmental noise
 * cancellation on a Bluetooth headset is tuned to isolate the *wearer* and suppress everyone else.
 * That is correct for a phone call and precisely backwards for Cue, whose whole job is transcribing
 * the people the wearer is talking to. Until that is measured on a real unit, the route is a user
 * preference and not an assumption: [CuePreferences.preferGlassesMic] flips this to the phone mic,
 * which is why the fallback below is a first-class path rather than an error handler.
 */
class CueAudioCapture(
    context: Context,
    private val preferGlassesMic: Boolean = true,
    private val requestedSampleRate: Int = DEFAULT_SAMPLE_RATE,
) {

    /** One analysis frame: raw PCM for the transcriber, RMS for the gap detector. */
    fun interface FrameSink {
        fun onFrame(pcm: ByteArray, length: Int, rms: Double, atMs: Long)
    }

    private val appContext = context.applicationContext
    private val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val running = AtomicBoolean(false)

    private var worker: Thread? = null
    private var record: AudioRecord? = null
    private var usedCommunicationDevice = false

    @Volatile
    var route: CueMicRoute = CueMicRoute.NONE
        private set

    /** The rate the stream actually opened at, which SCO may have forced down to 8 kHz. */
    @Volatile
    var activeSampleRate: Int = requestedSampleRate
        private set

    fun start(sink: FrameSink): Boolean {
        if (!hasRecordPermission()) {
            Log.w(TAG, "RECORD_AUDIO not granted")
            return false
        }
        if (!running.compareAndSet(false, true)) return true

        val glasses = preferGlassesMic && routeToGlassesMic()
        route = if (glasses) CueMicRoute.GLASSES else CueMicRoute.PHONE

        val opened = openRecord(glasses)
        if (opened == null) {
            running.set(false)
            clearGlassesMicRoute()
            route = CueMicRoute.NONE
            return false
        }
        record = opened

        worker = Thread({ pump(opened, sink) }, "cue-audio").apply {
            priority = Thread.MAX_PRIORITY
            isDaemon = true
            start()
        }
        Log.i(TAG, "Capture started on $route at ${activeSampleRate}Hz")
        return true
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        worker?.interrupt()
        worker = null
        record?.let { active ->
            runCatching { active.stop() }
            runCatching { active.release() }
        }
        record = null
        clearGlassesMicRoute()
        route = CueMicRoute.NONE
    }

    private fun pump(record: AudioRecord, sink: FrameSink) {
        val samplesPerFrame = (activeSampleRate * FRAME_MS / 1000).coerceAtLeast(160)
        val samples = ShortArray(samplesPerFrame)
        val bytes = ByteArray(samplesPerFrame * 2)

        runCatching { record.startRecording() }.onFailure {
            Log.e(TAG, "startRecording failed", it)
            return
        }

        while (running.get() && !Thread.currentThread().isInterrupted) {
            val read = record.read(samples, 0, samplesPerFrame)
            if (read <= 0) {
                if (read == AudioRecord.ERROR_INVALID_OPERATION || read == AudioRecord.ERROR_BAD_VALUE) {
                    Log.w(TAG, "AudioRecord read error $read; stopping capture")
                    break
                }
                continue
            }

            var sumSquares = 0.0
            for (index in 0 until read) {
                val sample = samples[index]
                sumSquares += sample.toDouble() * sample.toDouble()
                // Signed 16-bit little-endian, which is what the transcriber expects on the wire.
                bytes[index * 2] = (sample.toInt() and 0xFF).toByte()
                bytes[index * 2 + 1] = ((sample.toInt() shr 8) and 0xFF).toByte()
            }
            val rms = sqrt(sumSquares / read)
            sink.onFrame(bytes, read * 2, rms, System.currentTimeMillis())
        }

        runCatching { record.stop() }
    }

    private fun openRecord(overBluetooth: Boolean): AudioRecord? {
        for (rate in candidateSampleRates()) {
            val minBuffer = AudioRecord.getMinBufferSize(
                rate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
            )
            if (minBuffer <= 0) continue

            val source = if (overBluetooth) {
                MediaRecorder.AudioSource.VOICE_COMMUNICATION
            } else {
                // The phone-mic path wants the rawest signal available: VOICE_RECOGNITION skips
                // the call-oriented processing that would suppress the people Cue must hear.
                MediaRecorder.AudioSource.VOICE_RECOGNITION
            }

            val candidate = runCatching {
                AudioRecord(
                    source,
                    rate,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    minBuffer * BUFFER_MULTIPLIER,
                )
            }.getOrNull() ?: continue

            if (candidate.state != AudioRecord.STATE_INITIALIZED) {
                runCatching { candidate.release() }
                continue
            }
            if (overBluetooth) {
                findBluetoothInputDevice()?.let { runCatching { candidate.setPreferredDevice(it) } }
            }
            activeSampleRate = rate
            return candidate
        }
        Log.e(TAG, "Could not open AudioRecord at any candidate sample rate")
        return null
    }

    private fun candidateSampleRates(): List<Int> =
        listOf(requestedSampleRate, 16_000, 8_000).distinct()

    // ── Bluetooth routing ──

    /**
     * Routes input to the glasses. Android 12+ has an explicit communication-device API; older
     * releases only have the legacy SCO dance, which is why both paths exist.
     */
    @Suppress("DEPRECATION")
    private fun routeToGlassesMic(): Boolean {
        if (!isBluetoothHeadsetLikelyConnected()) return false

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val device = runCatching {
                audioManager.availableCommunicationDevices.firstOrNull {
                    it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                        it.type == AudioDeviceInfo.TYPE_BLE_HEADSET
                }
            }.getOrNull()
            if (device != null) {
                val ok = runCatching {
                    audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
                    audioManager.setCommunicationDevice(device)
                }.getOrDefault(false)
                if (ok) {
                    usedCommunicationDevice = true
                    Thread.sleep(ROUTE_SETTLE_MS)
                    return true
                }
            }
        }

        return runCatching {
            audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
            audioManager.startBluetoothSco()
            audioManager.isBluetoothScoOn = true
            val deadline = System.currentTimeMillis() + SCO_CONNECT_TIMEOUT_MS
            var connected = false
            while (!connected && System.currentTimeMillis() < deadline) {
                if (audioManager.isBluetoothScoOn) {
                    connected = true
                } else {
                    Thread.sleep(150)
                }
            }
            connected
        }.getOrElse {
            Log.w(TAG, "Bluetooth SCO route unavailable: ${it.message}")
            false
        }
    }

    @Suppress("DEPRECATION")
    private fun clearGlassesMicRoute() {
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && usedCommunicationDevice) {
                audioManager.clearCommunicationDevice()
            }
            audioManager.isBluetoothScoOn = false
            audioManager.stopBluetoothSco()
            audioManager.mode = AudioManager.MODE_NORMAL
        }
        usedCommunicationDevice = false
    }

    @Suppress("DEPRECATION")
    private fun isBluetoothHeadsetLikelyConnected(): Boolean = runCatching {
        val inputs = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
        when {
            inputs.any { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO } -> true
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                inputs.any { it.type == AudioDeviceInfo.TYPE_BLE_HEADSET } -> true

            else -> audioManager.isBluetoothScoAvailableOffCall
        }
    }.getOrDefault(false)

    private fun findBluetoothInputDevice(): AudioDeviceInfo? {
        val inputs = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
        return inputs.firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }
            ?: if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                inputs.firstOrNull { it.type == AudioDeviceInfo.TYPE_BLE_HEADSET }
            } else {
                null
            }
    }

    private fun hasRecordPermission(): Boolean =
        ContextCompat.checkSelfPermission(appContext, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    companion object {
        private const val TAG = "CueAudio"
        const val DEFAULT_SAMPLE_RATE = 16_000
        private const val FRAME_MS = 20
        private const val BUFFER_MULTIPLIER = 4
        private const val ROUTE_SETTLE_MS = 250L
        private const val SCO_CONNECT_TIMEOUT_MS = 6_000L
    }
}
