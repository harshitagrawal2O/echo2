# Next steps

Agreed after hardware testing on 2026-08-20. Ordered by value, not by effort.

## 1. Photo transfer latency — 10 s per question

A btn2 double-press takes **9.9 s** to get a 73 KB frame to the phone over BLE (~7 KB/s). Measured
on the same glasses in the same session, the Wi-Fi sync path moved a 6 MB video at **~1,430 KB/s** —
about **200x faster**. So the wait is a transport choice, not a hardware limit, and the fast path is
already written (`WIFI_TRANSFER_ARCHITECTURE.md`).

What is unknown is how long P2P association alone takes. If it is ~2 s, a photo question could go
from 10 s to 3 s. **Measure association cost before assuming this cannot be done.** The obstacles are
the exclusive `MEDIA_SYNC` lease and the association step, not bandwidth.

This affects every single interaction, which is why it is first.

## 2. First-run setup flow

Roughly 80% of the parts exist: `ui/WelcomeActivity.kt`, `ui/BatteryOptimizationGuideActivity.kt`,
`ui/OnboardingFeatureActivity.kt`, `ui/DeviceBindActivity.kt`, `ui/AutoPairManager.kt`,
`ui/PermissionUtil.kt`, and the key screen in `agent/LocalModelsConfigureActivity.kt`. What is missing
is a first-run *sequence* through them, and key entry a low-vision user can complete.

Already fixed: onboarding now requests `RECORD_AUDIO` (without it a fresh phone pairs and then
answers nothing), and `RemoteOpenAiPrefs.isConfigured` now requires the API key.

Still open:
- Sequence the existing screens behind the `onboarding_completed` flag.
- Key entry: the field is a plain `EditText`, so clipboard paste already works. A "Paste from
  clipboard" button would make that discoverable without sight. **Do not** deliver the key via a
  `cyanbridge://setup?key=...` deep link - that puts a live credential in logcat and intent history.
- `GlassesPresenceService.start()` fires only from `MainActivity.onBluetoothEvent`, so after a process
  kill and an `AutoPairManager` reconnect there is no foreground service and the microphone gets
  silenced. There is also no `BOOT_COMPLETED` receiver. **Measure what `START_STICKY` actually
  restores before writing the fix** - reasoning about this layer without measurement failed three
  times in one session.

## 3. Transcription quality

`libmoonshine.so` (16.9 MB) is **already bundled in the APK** along with `libvosk.so`, but neither is
wired to the glasses AI path, which uses Android's `SpeechRecognizer`. That recognizer returns
`ERROR_NO_MATCH` on clean audio and produced "no mind" for "never mind" and "questionnai asked".

Do not try to fix this by selecting a different `RecognitionService`: Android System Intelligence
(`com.google.android.as`) answers `ERROR_LANGUAGE_UNAVAILABLE` for `en-IN` and delivers zero audio.
The path is `AudioRecord` plus an on-device or hosted transcriber - and the on-device one is already
shipping in the binary.

Same change also unblocks speaker discrimination later, since `SpeechRecognizer` holds a global lock
and never yields raw audio.

## 4. Smaller items

- **Follow-up efficiency.** On the follow-up path every utterance re-uploads the image, so "thanks"
  costs a routing probe plus a full image call. If the probe answers without needing the image, speak
  the probe. Halves latency on conversational follow-ups.
- **Assistant-off switch** is built but never functionally tested. It needs one notification tap:
  expect the title to change to "Assistant off" and a trigger to be answered with "Assistant is off."
- **Web search.** `RemoteOpenAiClient` has no tools, no `tool_calls`, no web access at all, so
  anything needing live data cannot work and there is no signal to the wearer about which is which.
- **Phone screen reading / control.** Parked: it needs accessibility and overlay permissions that are
  tangled up with UPI apps refusing to run. `SYSTEM_ALERT_WINDOW` should become opt-in at the point of
  use rather than demanded at launch, which is likely the fix for both.
- **`MANAGE_EXTERNAL_STORAGE`** (`AndroidManifest.xml`) blocks Play distribution and is requested
  during onboarding. Irrelevant for sideloading; blocking if distribution is ever wanted.
