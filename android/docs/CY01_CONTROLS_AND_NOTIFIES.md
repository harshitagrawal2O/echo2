# CY-01 controls and notify frames

Mapped on hardware (CY 01_994B, Nothing Phone 3a Pro) on 2026-08-19 by pressing each control and
watching `adb logcat -s DeviceNotify`. The vendor documentation for these codes is not in this
repository, so this file is the ground truth until something better exists.

Reproduce with:

```bash
adb logcat -c && adb logcat -v time -s DeviceNotify | grep --line-buffered "loadData="
```

## Frame shape

```
loadData = BC 73 <len> 00 <ck0> <ck1> <code> <payload...>
             0  1    2   3    4     5      6      7+
```

`[0..1]` is a fixed `BC 73` header, `[2]` a length, `[4..5]` appear to be a checksum, `[6]` the
notify code and `[7]` onwards the payload. Read payload bytes as unsigned (`x.toInt() and 0xFF`) —
they arrive as signed Kotlin bytes.

## Naming

Use these names in code, comments and commit messages, so a control is never described by guesswork
about what it is "for":

| Name | Physical control |
| --- | --- |
| **btn1** | The picture button |
| **btn2** | The other button |
| **strip** | Right-side touch surface |

`btn2` is a name for a *control*, not for a signal. Nothing in the app can prove a `0x03` came from
btn2 rather than from the wake word or from a sleeve brushing the strip, so no behaviour may depend
on the distinction.

## Physical controls

| Gesture | Notify | Behaviour |
| --- | --- | --- |
| btn1 single press, idle | `0x01` only | Saves a photo **on the glasses**. The phone is told the photo count changed and nothing else — no image is offered, so the app cannot and does not respond. Verified: a press raised `0x01` with the first counter incremented and **no `0x02`**. |
| btn1 single press, **while recording** | `0x01` | **Stops any recording** - video started by btn1 double press *or* audio started by btn2 long press. btn1 is the universal stop. Context-dependent: the photo counter does *not* move, so no picture is taken; only the relevant recording counter increments |
| btn1 double press | *starts a recording* | **Video, `.mp4`.** Progress reported via `0x0b` while it runs. Stopped by a single btn1 press |
| btn2 triple press | **nothing** | The firmware waits out a gesture window, fails to classify three taps, and sends no frame at all - not even the `0x02` from the second tap. Timing-dependent: a slightly loose triple lands in the double-tap window and fires `0x02` instead, so the gesture is unusable rather than merely unbound |
| btn1 long press | **none — powers the glasses off** | Never reaches the phone. Do not bind a long press here |
| btn2 single press | `0x03` | Microphone activation → conversation turn |
| btn2 double press | `0x02` | Photo offered to the phone. The app describes it immediately, then opens a follow-up dialogue |
| btn2 long press | *starts a recording* | **Audio, `.opus`.** Increments `0x01` counter 3 rather than counter 2 |
| strip swipe toward the lens | `0x12` | Volume up |
| strip swipe toward the ear | `0x12` | Volume down |
| strip double tap | `0x03` | **A third AI trigger** |
| strip single tap / press-and-hold | none observed | Unimplemented, or handled entirely in firmware |

`0x02` is therefore **unique to btn2 double press**, and is the one photo signal the app can act on.
An earlier draft of this file attributed `0x02` to btn1 single press; that was an assumption about
which button had been pressed during logging, and measurement disproved it.

### The three triggers are indistinguishable

The wake word, the AI button and a touch-strip double tap all raise the **same** frame, byte for
byte including the checksum:

```
loadData=-68,115,2,0,-64,-128,3,1
```

So the app cannot tell them apart, and cannot ignore one source while honouring another. This is why
accidental activations cannot be filtered out in software: the touch strip is a bare capacitive
surface on the arm of a pair of glasses, and an arm brushing it is identical to a deliberate call.
The available mitigations are therefore (a) make a false trigger cost nothing — see
`No question heard; ending the turn quietly` in `MainActivity` — and (b) an all-or-nothing switch,
`glasses/AssistantMutePrefs.kt`.

## Notify codes

| Code | Meaning | Payload |
| --- | --- | --- |
| `0x01` | Media inventory of files still **on the glasses** | Three counters: **1 = photos (`.jpg`)**, **2 = video (`.mp4`, btn1 double press)**, **3 = audio (`.opus`, btn2 long press)**. Confirmed twice per gesture, the second time from an all-zero baseline left by a sync, which removes any ambiguity. All three go to **zero** after a sync, because the app deletes each file once imported |
| `0x02` | Photo ready | `[8]` observed as 16, 14, 60, 73 — **not** a counter and not remaining capacity, despite an early guess in both directions. Probably a file id |
| `0x03` | Microphone activation | Always `[7]=1`; identical from all three trigger sources |
| `0x04` | OTA / firmware progress | |
| `0x05` | Battery | `[7]` = percent, `[8]` = charging |
| `0x08` | Wi-Fi IP for transfer | bytes `[7..10]` |
| `0x09` | P2P / Wi-Fi error | code 255 is documented noise, not a failure |
| `0x0a` | Acknowledgement | Follows every `0x03` by 50–80 ms; carries nothing, safe to ignore |
| `0x0b` | A glasses-started recording is running | `[7]` is a progress value of **unknown unit** — see below |
| `0x0c` | Handler exists but is empty ("to do") | |
| `0x0d` | Handled, unlabelled | |
| `0x0e` | Memory low | |
| `0x10` | Translation pause | |
| `0x12` | Volume / audio scene | Three channels as `(id, 0, max, current)`; the **trailing byte** is `2` while a voice session is open and `3` when idle |

### `0x0b`'s unit is unknown, and three guesses have failed

Measured across three separate recordings:

```
41 → 43 → 46          over 6 s     (faster than wall clock)
40 → 41 → 41 → 42     over 9 s     (slower than wall clock)
39 → 42 → 44 → 45 → 46 → 48 → 49   over 18 s   (+10, about 0.6/s, increments 3,2,1,1,2,1)
```

It has been read as elapsed seconds (contradicted by run 2), as "not time at all" (contradicted by
run 3 rising steadily), and as a temperature curve. **Do not guess again from log samples alone.**

Two pieces of evidence do survive, both from *predictions* rather than re-reading the same numbers:

- **Three separate recordings all began at 38-39, never near 0.** A fresh recording starting at 39
  rules out elapsed time.
- **Video climbs, audio does not.** A btn1 double-press (video) run went `39 -> 49` in 18 s, while a
  btn2 long-press (audio) run held `39 -> 40` over 6 s. Video encoding is far more work than audio
  encoding, so a load-dependent quantity - temperature being the obvious candidate - behaves exactly
  this way. Elapsed time would not care which codec was running.

Still not proven. The controlled test is a recording of known duration, comparing first and last
values against wall clock.

Only its **freshness** is trustworthy, which is enough to know a recording is running - that is all
`GlassesMediaPrefs.recordingProgressOrNull` relies on. Never speak this number to the wearer or
present it as a duration.

### `0x12`'s trailing byte is a useful signal

It flips to `2` about 400 ms after every microphone activation and back to `3` when the session ends.
That is the glasses stating when *they* believe the voice channel is open — worth knowing, because
the phone's `AudioManager.isBluetoothScoOn()` returns true against SCO links that carry no audio.

## Media types, confirmed by sync

A sync lists and fetches from `http://<glasses-ip>/files/<name>`, with names that are timestamps
(the glasses clock ran ~10 h behind the phone, so do not correlate them with phone-side logs
directly - match by count and type instead):

```
20260819155817905.mp4    type=video    6,142,122 bytes
20260819181416470.opus   type=audio        1,600 bytes
20260819181420008.jpg    type=photo      881,492 bytes
20260819213910849.mp4    type=video   12,116,570 bytes
```

`.opus` arrives as raw packets and is wrapped into Ogg on save
(`OPUS save: raw=1600 bytes, out=1768 bytes, mode=wrapped packets=40`).

## The photo transfer is the slowest thing in the pipeline

A btn2 double press to a usable frame, measured twice:

```
transferDurationMs=9922   960x540, 73595 bytes    (~7 KB/s over BLE)
```

Ten seconds of standing still before the wearer hears anything — longer than the model call and
longer than every latency this app has otherwise been tuned for.

**The same hardware moves data ~200x faster over Wi-Fi.** Measured during a sync on the same
glasses, same session:

```
BLE thumbnail :     ~7 KB/s     (73 KB frame, 9922 ms)
Wi-Fi sync    : ~1,430 KB/s     (6 MB video in ~4 s)
```

So the ten seconds is a transport choice, not a hardware limit, and the fast path is already
implemented. What makes it non-trivial to reuse for a single question is the exclusive `MEDIA_SYNC`
lease and the P2P association it needs first — worth measuring how long association alone costs
before assuming it cannot beat 10 s.

## Vendor SDK controls worth knowing

Both are real SDK entry points, not guessed opcodes:

- `LargeDataHandler.aiVoiceWake(boolean, boolean, callback)` — the app enables the onboard "Hey Cyan"
  detector with `(true, true)` on every connection (`configureHeyCyanWakeWordIfNeeded`). Passing
  `false` for the second argument asks the glasses to stop listening; the response's `isOpen` reports
  the resulting state.
- `LargeDataHandler.wearFunctionSupport(callback)` → `GlassesTouchSupportRsp`, a **read** exposing
  `getGlassesModel()`, `isTranslationSupport()`, `isWearCheckSupport()`, `isVolumeControl()`.
- `TouchControlReq` exists with `getReadInstance(boolean)` and `getWriteInstance(int, boolean, int)`.
  The write parameters are undocumented and **have not been tried** — a wrong guess could disable the
  touch strip entirely. Read before writing, and never send it to personal hardware on a hunch.
