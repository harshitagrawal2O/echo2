# Cue — build and hardware test

Everything needed to build Cue on another machine and run the spikes the design depends on.

## Build

Requirements: **JDK 17 or 21** (not 25 — Gradle 8.13 rejects it), Android SDK **platform 35**,
**build-tools 35**, **NDK 27.0.12077973** and **CMake 3.22.1** (the vendored `:moonshine-voice`
module builds native sources), and roughly **4 GB of free commit** — Gradle plus the Kotlin daemon
are two JVMs.

```bash
cd android/CyanBridge
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

On a memory-constrained machine, keep it to a single JVM:

```bash
./gradlew :app:assembleDebug -Pkotlin.compiler.execution.strategy=in-process
```

Run the unit tests — 7 classes, 66 assertions covering the roster state machine, the speak/stay
silent rule, the gap detector, passive roll call, and the zero-interruption guarantee:

```bash
./gradlew :app:testDebugUnitTest --tests "com.fersaiyan.cyanbridge.plugins.cue.*"
```

### Windows: a space in the checkout path breaks the native build

`:moonshine-voice` compiles native sources, and the CMake/NDK toolchain fails with `[CXX1406]` when
any component of the path contains a space. A checkout under `C:\Users\you\Deep Station\...` will
not build no matter how the toolchain is configured.

The workaround is a directory junction from a space-free path, which needs no admin rights, costs no
disk, and leaves the checkout where it is:

```powershell
New-Item -ItemType Junction -Path C:\echo -Target "C:\path with spaces\Alternative-HeyCyan-App-and-SDK"
cd C:\echo\android\CyanBridge
```

**It works only because Gradle does not canonicalize the junction**, and that is worth stating
because the workaround silently stops helping if it ever changes. Measured on Gradle 8.13:

```
shell cwd:   C:\echo\android\CyanBridge
rootDir:     C:\echo\android\CyanBridge
projectDir:  C:\echo\android\CyanBridge\app
buildDir:    C:\echo\android\CyanBridge\app\build
```

The space-free path is what reaches the NDK. If Gradle resolved through to the real location, every
path would carry the space again and `[CXX1406]` would come back unchanged.

Measured by Amogh Shastry on the setup that has the junction. Not reproduced on a checkout whose
path has no spaces, which is the only kind either maintainer currently has.

## Turning it on

Plugins tab → **Cue** → enable (grants microphone and notification permission) → settings.

Two keys are needed and are stored only on the phone. Typing them by screen reader is miserable, so
push them over adb instead:

```bash
adb shell am start -n com.fersaiyan.cyanbridge/.plugins.cue.CueSettingsActivity
```

...or write them straight into preferences on a debuggable build:

```bash
adb shell "run-as com.fersaiyan.cyanbridge sh -c 'cat > /data/data/com.fersaiyan.cyanbridge/shared_prefs/cue_prefs.xml'" <<'XML'
<?xml version='1.0' encoding='utf-8' standalone='yes' ?>
<map>
    <boolean name="enabled" value="true" />
    <string name="anthropic_api_key">sk-ant-…</string>
    <string name="transcription_api_key">…</string>
</map>
XML
```

Force-stop the app afterwards so the preferences are re-read.

## The spikes, in the order they should be run

Each one can invalidate work downstream of it, so run them in this order and stop if one fails.

### Spike C — do the button events exist? (2 minutes, no conversation needed)

Cheapest and most load-bearing: it decides whether P0-5 and P0-7 have any input at all.

```bash
adb logcat -c
adb logcat -s DeviceNotify CueGlasses CueSession
```

With the glasses connected, press each control and watch for the notify code:

| Action | Expect | If nothing appears |
|---|---|---|
| Pause button | `loadData[6]=12` (`0x0c`) | "Who's here" has no trigger — fall back to the phone volume key |
| Volume up or down | `loadData[6]=18` (`0x12`) | Repeat-last has no trigger — same fallback |
| Put the glasses on | `CueGlasses: Wear state changed: worn=true` | Wear check unsupported on this unit; the session binds to BLE connect instead |
| Touchpad play/pause, next, prev | `MusicCommandRsp` action 1/3/2 | The second input channel does not exist; temple controls are out |

`CueGlasses: Capabilities: wearCheck=… volumeControl=… model=…` is logged once at connect and
answers the wear-check question directly.

### Spike A — the one that can kill P0

**Frame it as: record three people in conversation through the glasses, play it back, and ask
whether the two people who are _not_ wearing the glasses are intelligible.** Not "is HFP too
narrowband".

The CY-01 has dual ENC microphones. Environmental noise cancellation on a Bluetooth headset is
tuned to isolate the *wearer* and suppress everyone else — correct for a phone call, and precisely
backwards for Cue, whose entire P0 is transcribing the people the wearer is talking to.

```bash
adb logcat -s CueAudio CueSession CueTranscribe
```

`CueAudio: Capture started on GLASSES at 16000Hz` confirms the route. Then have three people talk
and watch whether `speaker_1` and `speaker_2` ever appear with sensible text.

- **Pass** → the glasses mic stays primary.
- **Fail** → flip *Listening → Phone microphone* in settings and re-run. The phone mic becomes the
  primary path, and the "mic placement is the hardware fit" claim comes out of the deck. Say so on
  stage; it is a better story than a demo that does not work.

While the SCO route is held, also listen to the earcons through the glasses speaker. Every tone is
under 1.2 kHz specifically so it survives an 8 kHz narrowband codec, but that is theory until
someone hears it. The one that matters most: **is the doubled departure earcon distinguishable from
the single "person left"?** If not, the hero moment does not land.

### Spike B — is there anywhere to speak?

Natural turn transitions in a three-person conversation are frequently under 200ms and often
overlapping. The 400ms whisper threshold is a placeholder, and if long gaps are rare, Cue says
almost nothing — which would satisfy the zero-interruptions metric trivially, by silence.

Record five minutes of real three-person conversation with Cue running, then read the measurement
off the settings screen: *Listening → "Measured: N percent of pauses this session were long
enough."* The same numbers are in `CueSessionStatus.gapHistogram`.

- Comfortably above zero → keep 400ms.
- Near zero → lower the threshold, or switch to naming at end-of-turn rather than turn-start, and
  say which you did.

### Spike D — is the thumbnail legible?

Press the hardware AI button while holding a printed chart.

```bash
adb logcat -s CuePhoto CueClaude CueSession
```

The bar is not "he is holding a laptop". It is "the chart on his screen shows Q3 revenue down about
12 percent". If the thumbnail cannot carry that, raise *Thumbnail quality* in settings; if it still
cannot, P0-6 rescopes to people and gross objects, which is still demoable.

## Reading a live session

```bash
adb logcat -s CueSession CueGlasses CueAudio CueTranscribe CuePhoto CueClaude CueEarcon CueSpeaker
```

The foreground notification doubles as the dev overlay and is the fastest health check:
`Listening · glasses mic · live · 2 present`. The two things that silently make Cue useless are the
mic route and whether transcription is live, and neither is visible from outside — a session with a
dead transcriber looks exactly like a quiet room. Cue also says both out loud at session start when
either is degraded.

## Rehearsal mode

Diarization degrades badly with overlapping speech and in noisy rooms, and demo venues are noisy
rooms. Settings → *Rehearsal mode* replays a recorded conversation instead of listening; everything
downstream — roster, arbiter, gap detector, speaker — runs exactly as it does live.

```json
{"segments": [
  {"at_ms": 0,     "speaker": "speaker_1", "text": "Hi, I'm Sarah.",        "duration_ms": 1400},
  {"at_ms": 2200,  "speaker": "speaker_2", "text": "I'm Priya.",            "duration_ms": 1100},
  {"at_ms": 4000,  "speaker": "speaker_0", "text": "Good to meet you both.","duration_ms": 1500},
  {"at_ms": 6000,  "speaker": "speaker_1", "text": "So what are we covering today?", "duration_ms": 1800}
]}
```

Rehearse both paths and pick on the day.

## Known-unverified

- Nothing in this project has been run on a CY-01. The three spike-dependent values —
  microphone route, whisper gap threshold, thumbnail quality — are settings rather than constants
  for exactly that reason.
- `MusicCommandRsp` notify id `29` was read out of the vendor SDK's own bean factory rather than
  from documentation. Spike C confirms or refutes it.
- Cue stops when the BLE link drops rather than falling back to the phone mic. Deliberate: a phone
  in a pocket produces poor attribution and would start speaking aloud from the phone. The
  lost-glasses earcon fires so the user knows. There is no setting to override this yet.
- P1 is not built: no ambient event classification, no spatial placement, no reaction readout, no
  re-recognition.
