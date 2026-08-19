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

## Physical controls

| Control | Notify | Notes |
| --- | --- | --- |
| Picture button, single press | `0x02` | Photo ready; the app starts an image-question turn, source tag `hardware_image_button` |
| Picture button, double press | *starts a recording* | Reports progress via `0x0b`; media type unconfirmed |
| Picture button, long press | **none — powers the glasses off** | Do not bind a long-press gesture to this button |
| AI button, single press | `0x03` | Microphone activation |
| Touch strip, swipe toward the lens | `0x12` | Volume up |
| Touch strip, swipe toward the ear | `0x12` | Volume down |
| Touch strip, double tap | `0x03` | **A third AI trigger** |
| Second button, long press | *starts a recording* | A *different* `0x01` counter increments than for the double press, so the two gestures appear to produce different media types |
| Touch strip single tap / press-and-hold | none observed | Either unimplemented or handled entirely in firmware |

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
| `0x01` | Media inventory | Three counters; a *different* one increments after a double press than after a long press |
| `0x02` | Photo ready | `[8]` decreased 16 → 14 over two captures; possibly remaining capacity |
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

### `0x0b` is not a clock

It was read as elapsed seconds on three samples, then measured again and contradicted:

```
41 → 43 → 46   across 6 s of wall clock   (faster than real time)
40 → 41 → 41 → 42   across 9 s            (slower than real time)
```

Neither fits a timer. Only its **freshness** is trustworthy, which is enough to know a recording is
running — that is all `GlassesMediaPrefs.recordingProgressOrNull` relies on. Do not speak this
number to the wearer or convert it to a duration until it has been identified.

### `0x12`'s trailing byte is a useful signal

It flips to `2` about 400 ms after every microphone activation and back to `3` when the session ends.
That is the glasses stating when *they* believe the voice channel is open — worth knowing, because
the phone's `AudioManager.isBluetoothScoOn()` returns true against SCO links that carry no audio.

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
