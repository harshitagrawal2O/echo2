package com.fersaiyan.cyanbridge.plugins.cue

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.fersaiyan.cyanbridge.R
import com.fersaiyan.cyanbridge.plugins.PluginVoicePermissions
import com.fersaiyan.cyanbridge.shared.plugins.NativePluginIds
import com.fersaiyan.cyanbridge.ui.NativePluginShortcutPreference
import com.fersaiyan.cyanbridge.ui.installComposeHostWithLegacyAdapter
import com.fersaiyan.cyanbridge.ui.setThemedComposeContent
import kotlinx.coroutines.delay
import java.util.Locale

/**
 * Cue's settings screen.
 *
 * This app is operated by a blind person, so accessibility here is a correctness requirement rather
 * than a polish pass. Concretely: every control carries its own label, nothing communicates state
 * by colour alone, touch targets clear 48dp, the enable switch and the quick actions sit at the top
 * so the primary flows are within two swipes of launch, and the sound vocabulary is taught by
 * *playing* each earcon rather than by printing a list of descriptions nobody can hear.
 *
 * Most hackathon projects for blind users ship an app the target user cannot operate. The test for
 * this screen is running it with the screen off.
 */
class CueSettingsActivity : AppCompatActivity() {

    private lateinit var composeView: ComposeView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        composeView = installComposeHostWithLegacyAdapter(R.layout.activity_cue_settings)
        setThemedComposeContent(composeView) {
            CueSettingsScreen(
                onBack = ::finish,
                onEnable = { enabled ->
                    if (enabled) {
                        PluginVoicePermissions.ensure(this) { CuePlugin.setEnabled(this, true) }
                    } else {
                        CuePlugin.setEnabled(this, false)
                    }
                },
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CueSettingsScreen(
    onBack: () -> Unit,
    onEnable: (Boolean) -> Unit,
) {
    val context = LocalContext.current
    val scrollState = rememberScrollState()
    val earconPlayer = remember { CueEarconPlayer(CuePreferences.getEarconVolume(context)) }
    // The lesson's own player holds an AudioTrack; leaving the screen must give it back.
    DisposableEffect(earconPlayer) { onDispose { earconPlayer.release() } }

    var enabled by remember { mutableStateOf(CuePreferences.isEnabled(context)) }
    var preferGlasses by remember { mutableStateOf(CuePreferences.preferGlassesMic(context)) }
    var gapMs by remember { mutableIntStateOf(CuePreferences.getRequiredGapMs(context).toInt()) }
    var addresseeSeconds by remember {
        mutableIntStateOf((CuePreferences.getAddresseeSilenceMs(context) / 1000).toInt())
    }
    var presenceSeconds by remember {
        mutableIntStateOf((CuePreferences.getPresenceTimeoutMs(context) / 1000).toInt())
    }
    var whisperRate by remember { mutableFloatStateOf(CuePreferences.getWhisperRate(context)) }
    var briefingRate by remember { mutableFloatStateOf(CuePreferences.getBriefingRate(context)) }
    var earconVolume by remember { mutableFloatStateOf(CuePreferences.getEarconVolume(context)) }
    var ambientEnabled by remember { mutableStateOf(CuePreferences.isAmbientEnabled(context)) }
    var rehearsal by remember { mutableStateOf(CuePreferences.isRehearsalEnabled(context)) }
    var rehearsalScript by remember { mutableStateOf(CuePreferences.getRehearsalScript(context)) }
    var anthropicKey by remember { mutableStateOf(CuePreferences.getAnthropicApiKey(context)) }
    var transcriptionKey by remember { mutableStateOf(CuePreferences.getTranscriptionApiKey(context)) }
    var wearerArmed by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(
                            R.string.compose_plugin_settings_title,
                            stringResource(R.string.compose_plugin_name_cue),
                        ),
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.heightIn(min = 48.dp)) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.compose_back),
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(scrollState),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // ── Enable, first, so it is one swipe from launch ──
            CueSection(stringResource(R.string.compose_general))
            CueCard {
                SwitchRow(
                    label = stringResource(R.string.compose_cue_enabled),
                    checked = enabled,
                    onCheckedChange = {
                        enabled = it
                        onEnable(it)
                    },
                )
                Spacer(Modifier.height(6.dp))
                Hint(stringResource(R.string.compose_cue_enabled_hint))
            }

            // ── Quick actions, second, so both primary flows are within two swipes ──
            CueSection(stringResource(R.string.compose_cue_quick_actions))
            CueCard {
                ActionButton(
                    label = stringResource(R.string.compose_cue_whos_here),
                    hint = stringResource(R.string.compose_cue_whos_here_hint),
                ) { CueService.send(context, CueService.ACTION_WHOS_HERE) }
                Spacer(Modifier.height(10.dp))
                ActionButton(
                    label = stringResource(R.string.compose_cue_repeat_last),
                    hint = stringResource(R.string.compose_cue_repeat_last_hint),
                ) { CueService.send(context, CueService.ACTION_REPEAT) }
                Spacer(Modifier.height(10.dp))
                ActionButton(
                    label = if (wearerArmed) {
                        stringResource(R.string.compose_cue_bind_wearer_armed)
                    } else {
                        stringResource(R.string.compose_cue_bind_wearer)
                    },
                    hint = stringResource(R.string.compose_cue_bind_wearer_hint),
                ) {
                    wearerArmed = true
                    CueService.send(context, CueService.ACTION_BIND_WEARER)
                }
            }

            // ── The sound vocabulary, taught as audio ──
            CueSection(stringResource(R.string.compose_cue_earcon_lesson))
            CueCard {
                Hint(stringResource(R.string.compose_cue_earcon_lesson_hint))
                Spacer(Modifier.height(10.dp))
                for ((earcon, labelRes) in EARCON_LESSON) {
                    val label = stringResource(labelRes)
                    OutlinedButton(
                        onClick = { earconPlayer.play(earcon) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .semantics {
                                contentDescription =
                                    context.getString(R.string.compose_cue_play_sound, label)
                            },
                    ) { Text(label) }
                    Spacer(Modifier.height(8.dp))
                }
            }

            // ── Voice ──
            CueSection(stringResource(R.string.compose_cue_voice))
            CueCard {
                LabelledSlider(
                    label = stringResource(
                        R.string.compose_cue_whisper_rate,
                        String.format(Locale.US, "%.1f", whisperRate),
                    ),
                    value = whisperRate,
                    range = CueSpeaker.MIN_RATE..CueSpeaker.MAX_RATE,
                    steps = 24,
                ) {
                    whisperRate = it
                    CuePreferences.setWhisperRate(context, it)
                }
                LabelledSlider(
                    label = stringResource(
                        R.string.compose_cue_briefing_rate,
                        String.format(Locale.US, "%.1f", briefingRate),
                    ),
                    value = briefingRate,
                    range = CueSpeaker.MIN_RATE..CueSpeaker.MAX_RATE,
                    steps = 24,
                ) {
                    briefingRate = it
                    CuePreferences.setBriefingRate(context, it)
                }
                LabelledSlider(
                    label = stringResource(
                        R.string.compose_cue_earcon_volume,
                        (earconVolume * 100).toInt(),
                    ),
                    value = earconVolume,
                    range = 0.1f..1.0f,
                    steps = 8,
                ) {
                    earconVolume = it
                    CuePreferences.setEarconVolume(context, it)
                }
            }

            // ── Listening ──
            CueSection(stringResource(R.string.compose_cue_listening))
            CueCard {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    MicChip(
                        label = stringResource(R.string.compose_cue_mic_glasses),
                        selected = preferGlasses,
                    ) {
                        preferGlasses = true
                        CuePreferences.setPreferGlassesMic(context, true)
                    }
                    MicChip(
                        label = stringResource(R.string.compose_cue_mic_phone),
                        selected = !preferGlasses,
                    ) {
                        preferGlasses = false
                        CuePreferences.setPreferGlassesMic(context, false)
                    }
                }
                Spacer(Modifier.height(8.dp))
                Hint(stringResource(R.string.compose_cue_mic_hint))

                Spacer(Modifier.height(12.dp))
                LabelledSlider(
                    label = stringResource(R.string.compose_cue_gap_threshold, gapMs),
                    value = gapMs.toFloat(),
                    range = 100f..1_500f,
                    steps = 27,
                ) {
                    gapMs = it.toInt()
                    CuePreferences.setRequiredGapMs(context, gapMs)
                }
                // The measurement that says whether this threshold leaves Cue anything to say.
                CuePlugin.status()?.let { status ->
                    Hint(
                        stringResource(
                            R.string.compose_cue_gap_measured,
                            (status.gapAvailabilityAtThreshold * 100).toInt(),
                        ),
                    )
                }
                Hint(stringResource(R.string.compose_cue_gap_hint))
            }

            // ── Timing ──
            CueSection(stringResource(R.string.compose_cue_timing))
            CueCard {
                LabelledSlider(
                    label = stringResource(R.string.compose_cue_addressee_silence, addresseeSeconds),
                    value = addresseeSeconds.toFloat(),
                    range = 2f..15f,
                    steps = 12,
                ) {
                    addresseeSeconds = it.toInt()
                    CuePreferences.setAddresseeSilenceMs(context, addresseeSeconds * 1_000)
                }
                LabelledSlider(
                    label = stringResource(R.string.compose_cue_presence_timeout, presenceSeconds),
                    value = presenceSeconds.toFloat(),
                    range = 20f..300f,
                    steps = 27,
                ) {
                    presenceSeconds = it.toInt()
                    CuePreferences.setPresenceTimeoutMs(context, presenceSeconds * 1_000)
                }
                Spacer(Modifier.height(8.dp))
                SwitchRow(
                    label = stringResource(R.string.compose_cue_ambient_enabled),
                    checked = ambientEnabled,
                    onCheckedChange = {
                        ambientEnabled = it
                        CuePreferences.setAmbientEnabled(context, it)
                    },
                )
                Hint(stringResource(R.string.compose_cue_ambient_hint))
            }

            // ── Keys ──
            CueSection(stringResource(R.string.compose_cue_keys))
            CueCard {
                TextField(
                    value = anthropicKey,
                    onValueChange = {
                        anthropicKey = it
                        CuePreferences.setAnthropicApiKey(context, it)
                    },
                    label = { Text(stringResource(R.string.compose_cue_anthropic_key)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(10.dp))
                TextField(
                    value = transcriptionKey,
                    onValueChange = {
                        transcriptionKey = it
                        CuePreferences.setTranscriptionApiKey(context, it)
                    },
                    label = { Text(stringResource(R.string.compose_cue_transcription_key)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                Hint(stringResource(R.string.compose_cue_keys_hint))
            }

            // ── Rehearsal ──
            CueSection(stringResource(R.string.compose_cue_rehearsal))
            CueCard {
                SwitchRow(
                    label = stringResource(R.string.compose_cue_rehearsal),
                    checked = rehearsal,
                    onCheckedChange = {
                        rehearsal = it
                        CuePreferences.setRehearsalEnabled(context, it)
                    },
                )
                Hint(stringResource(R.string.compose_cue_rehearsal_hint))
                if (rehearsal) {
                    Spacer(Modifier.height(10.dp))
                    TextField(
                        value = rehearsalScript,
                        onValueChange = {
                            rehearsalScript = it
                            CuePreferences.setRehearsalScript(context, it)
                        },
                        label = { Text(stringResource(R.string.compose_cue_rehearsal_script)) },
                        minLines = 3,
                        maxLines = 8,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            // ── Status ──
            CueSection(stringResource(R.string.compose_cue_status))
            CueCard { CueStatusBlock() }

            CueSection(stringResource(R.string.compose_glasses_tab))
            NativePluginShortcutPreference(
                pluginId = NativePluginIds.CUE,
                pluginTitle = stringResource(R.string.compose_plugin_name_cue),
            )

            Spacer(Modifier.height(32.dp))
        }
    }
}

/**
 * The live status readout.
 *
 * Written as prose rather than as indicator dots because it is read aloud: "listening on the
 * glasses microphone, speech recognition is live, two people present" is a sentence, and a row of
 * coloured dots is nothing at all.
 */
@Composable
private fun CueStatusBlock() {
    // Polled rather than read once at composition. This block is the only way a user who cannot
    // see the glasses can find out whether Cue is actually listening, so a value frozen at the
    // moment the screen opened is worse than no value: it looks authoritative and is stale.
    var status by remember { mutableStateOf(CuePlugin.status()) }
    LaunchedEffect(Unit) {
        while (true) {
            status = CuePlugin.status()
            delay(1_000)
        }
    }

    val current = status
    if (current == null || !current.running) {
        Text(
            stringResource(R.string.compose_cue_status_stopped),
            style = MaterialTheme.typography.bodyMedium,
            // Announced when it changes, so a user parked on this text hears Cue start and stop
            // instead of having to swipe away and back to re-read it.
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
        return
    }
    val mic = stringResource(
        when (current.micRoute) {
            CueMicRoute.GLASSES -> R.string.compose_cue_status_mic_glasses
            CueMicRoute.PHONE -> R.string.compose_cue_status_mic_phone
            CueMicRoute.NONE -> R.string.compose_cue_status_mic_none
        },
    )
    val stt = stringResource(
        when {
            current.rehearsal -> R.string.compose_cue_status_stt_rehearsal
            current.transcriptionLive -> R.string.compose_cue_status_stt_live
            else -> R.string.compose_cue_status_stt_off
        },
    )
    Text(
        stringResource(R.string.compose_cue_status_running, mic, stt, current.rosterSize),
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
    )
    Spacer(Modifier.height(6.dp))
    Text(
        stringResource(
            R.string.compose_cue_status_counts,
            current.stats.whispersSpoken,
            current.stats.whispersDroppedStale,
            current.stats.interruptions,
        ),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/**
 * A microphone-source chip whose selected state is not carried by colour.
 *
 * The persona is blind *or low vision*, and the low vision half is reading this screen. Material's
 * default selected chip differs from an unselected one only in container colour, which is exactly
 * the state a user with reduced contrast sensitivity or colour vision deficiency cannot see. The
 * checkmark makes the selection legible without colour, and screen readers get it from the chip's
 * own selected semantics.
 */
@Composable
private fun MicChip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label) },
        leadingIcon = if (selected) {
            { Icon(Icons.Filled.Check, contentDescription = null) }
        } else {
            null
        },
        modifier = Modifier.heightIn(min = 48.dp),
    )
}

@Composable
private fun CueSection(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 4.dp),
    )
}

@Composable
private fun CueCard(content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(modifier = Modifier.padding(16.dp)) { content() }
    }
}

/**
 * Label and switch as a single toggleable node.
 *
 * The row owns the interaction and the switch is inert (`onCheckedChange = null`), which merges
 * both into one focus stop. Labelling the switch separately instead would make a screen reader read
 * the same words twice — once for the text, once for the control — on every setting in the screen.
 */
@Composable
private fun SwitchRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = null)
    }
}

/** The hint is folded into the button's own description so it is heard before the tap, not after. */
@Composable
private fun ActionButton(label: String, hint: String, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .semantics { contentDescription = "$label. $hint" },
    ) { Text(label) }
}

/**
 * A slider whose current value is spoken as part of its label.
 *
 * Compose announces slider position as a bare percentage, which is meaningless for a value like
 * "400 milliseconds", so the label carries the real number and the slider itself is silenced.
 */
@Composable
private fun LabelledSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
    onValueChange: (Float) -> Unit,
) {
    // The visible label is hidden from the screen reader because the slider already carries the
    // same words as its description. Leaving both would read every setting's name twice.
    Text(
        label,
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.clearAndSetSemantics { },
    )
    Slider(
        value = value.coerceIn(range.start, range.endInclusive),
        onValueChange = onValueChange,
        valueRange = range,
        steps = steps,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .semantics { contentDescription = label },
    )
    Spacer(Modifier.height(4.dp))
}

@Composable
private fun Hint(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** The vocabulary, in the order it is worth learning: presence first, then the rest. */
private val EARCON_LESSON = listOf(
    CueEarcon.ADDRESSEE_LEFT to R.string.compose_cue_earcon_addressee_left,
    CueEarcon.PERSON_ENTERED to R.string.compose_cue_earcon_person_entered,
    CueEarcon.PERSON_LEFT to R.string.compose_cue_earcon_person_left,
    CueEarcon.ADDRESSING_YOU to R.string.compose_cue_earcon_addressing_you,
    CueEarcon.AWAITING_YOU to R.string.compose_cue_earcon_awaiting_you,
    CueEarcon.AMBIENT to R.string.compose_cue_earcon_ambient,
    CueEarcon.WORKING to R.string.compose_cue_earcon_working,
    CueEarcon.FAILED to R.string.compose_cue_earcon_failed,
    CueEarcon.BUSY to R.string.compose_cue_earcon_busy,
    CueEarcon.GLASSES_LOST to R.string.compose_cue_earcon_glasses_lost,
)
