# Meta Ray-Ban MVP Audit and Implementation Plan

Status: Android DAT Phase 1 implementation started; iOS and shared camera-source phases remain.

Audit date: 2026-07-20

Reference repository: [OpenVision](https://github.com/rayl15/OpenVision)

Audited OpenVision commit: `1cfeeee8fa4e42228c45f32e6169c23c5b802102`

Official SDK references:

- [Meta Wearables DAT for Android](https://github.com/facebook/meta-wearables-dat-android)
- [Meta Wearables DAT for iOS](https://github.com/facebook/meta-wearables-dat-ios)
- [Meta Wearables Developer Center](https://wearables.developer.meta.com/)

## Executive Verdict

At the start of this work, the current Meta Ray-Ban implementation was not functional end-to-end.

- Android resolves the Meta DAT `0.8.0` dependencies. The manager now uses direct DAT APIs for registration state, device metadata, sessions, camera streams, photo capture, and display capability checks.
- Android physical validation is still pending; the manager must be tested against a registered wearable and Meta AI app.
- The iOS KMP host has no Meta DAT integration. It currently uses the generic HeyCyan CoreBluetooth adapter.
- Existing HeyCyan media, thumbnail, P2P, OTA, and live-preview paths cannot be reused for Meta glasses.
- Some voice plugins may work through Android Bluetooth audio, but they need audio-route coordination with DAT camera streaming.
- Display support must be limited to Meta Ray-Ban Display devices. Ordinary Ray-Ban Meta glasses do not expose the display capability.

This is a static source and SDK audit. Physical Android/iOS glasses testing is still required.

## Android Progress

The Android Phase 1 adapter is now implemented in `MetaRaybanManager.kt`:

- Direct DAT `0.8.0` imports replace reflection.
- Registration, registration errors, device availability, metadata, compatibility, and display capability are observed from DAT flows.
- One `DeviceSession` and one camera `Stream` are owned by the manager, with session/stream terminal cleanup and a serialized one-shot photo API for background plugins.
- DAT photos are persisted to `DCIM/CyanBridge`, and raw I420 frames are converted to Android `Bitmap` callbacks.
- Android and DAT camera permissions are requested through Activity Result APIs.
- Meta camera ownership is represented by the existing exclusive glasses-session coordinator.
- Visual Diary and Walking Aid use the one-shot DAT photo path for Meta; HeyCyan retains its existing thumbnail path.
- HeyCyan onboard audio-file capture is disabled for Meta devices.

This progress has compile/unit-test coverage only. It has not been validated against a physical Meta wearable.

## Findings

### Android DAT integration

The audit found explicit stubs in `MetaRaybanManager.kt`; those Android stubs have now been replaced with direct DAT calls. The remaining validation risks are hardware behavior, permission callbacks, and terminal lifecycle transitions.

The previous manager also used reflection for SDK calls. DAT `0.8.0` exposes the initialization and session APIs as Kotlin APIs and the internal JVM method names are mangled. Direct Kotlin imports are now used.

The current registration state conversion also looks for `NOT_REGISTERED` at `MetaRaybanManager.kt:178-184`, while DAT `0.8.0` uses states including `AVAILABLE`, `REGISTERED`, `REGISTERING`, `UNAVAILABLE`, and `UNREGISTERING`.

The correct DAT `0.8.0` lifecycle is:

```kotlin
Wearables.initialize(context)

val session = Wearables.createSession(AutoDeviceSelector()).getOrElse { error ->
    throw IllegalStateException(error.description)
}
session.start()

val stream = session.addStream(
    StreamConfiguration(videoQuality = VideoQuality.MEDIUM, frameRate = 24),
).getOrElse { error ->
    throw IllegalStateException(error.description)
}

stream.start().onFailure { error, _ ->
    throw IllegalStateException(error.description)
}
```

The manager must collect registration state, device availability, device compatibility, session state/errors, and stream state/errors. It must not infer lifecycle state from button presses.

### Android build and manifest configuration

Meta Ray-Ban support is an opt-in build variant, selected by `-PmetaSupport=true`. Without it the app builds and runs normally with Ray-Ban support absent; the target hardware is the HeyCyan CY-01, so a Meta SDK grant is not a precondition for compiling this repo.

The variant is chosen by the source, not by the machine. That distinction is the lesson from how this used to work: the variant was inferred from whether the builder happened to hold a GitHub token, so two people on the same commit produced different APKs — different `minSdk`, different feature set — and neither could tell which from reading the source. Worse, that design could not distinguish *"doesn't want Meta"* from *"wants Meta but is misconfigured"*, and collapsed both into the same silent outcome: the dependencies were skipped while the code still imported them unconditionally, producing roughly 200 unresolved references rather than one sentence explaining the problem.

Splitting those two cases is what the current design buys:

| Invocation | Result |
|---|---|
| default | Non-Meta variant. Builds clean, no token needed, `minSdk` 24. |
| `-PmetaSupport=true` with a usable token | Meta variant, `minSdk` 29. |
| `-PmetaSupport=true` without one | Hard error naming the missing grant — the request cannot be honoured, so it fails rather than degrading. |

The token now only decides whether the dependency can be *resolved*; it never decides which variant you get. `app/build.gradle` logs the selected variant at configure time so a token that silently fails to be picked up is visible immediately.

Implementation: `src/meta/java` and `src/nometa/java` each define `MetaRaybanManager` and `MetaCameraPermission` with identical public surfaces, and exactly one is added to the source set. All Wearables DAT imports live in `src/meta`; nothing under `src/main` references the SDK. `MetaCameraPermission` narrows the permission handshake to `Unit -> Boolean` so the two screens that request camera access do not pull in the SDK's `Permission` and `PermissionStatus` types.

**Maintenance obligation:** the two implementations must stay in step. A member added to one and not the other breaks whichever variant was not built, and the default build is the one most people run.

The DAT metadata exists in `app/src/main/AndroidManifest.xml:77-86`, including `APPLICATION_ID`, `CLIENT_TOKEN`, and `DAM_ENABLED`. However:

- The values are hardcoded instead of coming from build placeholders or environment-specific configuration.
- The application manifest now declares `android.permission.CAMERA`.
- The Activity now uses `Wearables.RequestPermissionContract()` for the DAT camera permission.
- The official sample requests Bluetooth, Bluetooth Connect, Camera, and Internet permissions before initializing DAT.

### Android UI and device discovery

The Meta UI is wired to the Android DAT manager in `MainActivity.kt`; registration, session, stream, photo, and display actions now reach the real adapter. Device binding still uses the separate Oudmon/HeyCyan scanner and is not the source of truth for DAT devices.

The device binding flow at `DeviceBindActivity.kt:105-163` uses the Oudmon/HeyCyan scanner and `BleOperateManager`. DAT devices should be represented through `Wearables.devices` and DAT device metadata instead of relying on a BLE advertised-name heuristic.

`DeviceClassifier.kt` is acceptable as a temporary UI hint, but it must not be the source of truth for Meta capabilities. The DAT device object provides registration, link, compatibility, and display-capability information.

### iOS integration

The KMP host at `ios/CyanBridgeKMPHost/CyanBridgeKMPHostApp.swift` only embeds the shared Compose controller. There is no `MWDATCore`, `MWDATCamera`, or `MWDATDisplay` package in the current iOS target.

The current iOS controller uses `IosBleManager` and sends HeyCyan command bytes through generic CoreBluetooth. Relevant code is in:

- `shared/src/iosMain/kotlin/com/fersaiyan/cyanbridge/shared/platform/MainViewController.kt:196-399`
- `shared/src/iosMain/kotlin/com/fersaiyan/cyanbridge/shared/ble/IosBleManager.kt:42-49`

This is not a valid Meta integration. The iOS controller currently sets HeyCyan controls visible for any connected device at `MainViewController.kt:233-245`, and unhandled Meta actions fall through to the generic error path at `:266-281`.

The current iOS plugin catalog marks AutoDiary, Auto Audio, and Visual Diary as unavailable in `shared/src/commonMain/kotlin/com/fersaiyan/cyanbridge/shared/ui/SharedDestinationScreen.kt:303-331`.

The iOS implementation should use the Swift DAT framework, not generic CoreBluetooth. Since MWDAT is a Swift package, the likely integration is a native Swift Meta controller in the iOS host with an Objective-C-compatible or callback bridge to shared Kotlin state. MWDAT types should not be placed directly in `commonMain`.

### DAT API version mismatch in OpenVision

OpenVision is useful as an architecture reference, but its `project.yml:102-108` pins `meta-wearables-dat-ios` to `0.4.0` and its `GlassesManager.swift` uses the older `StreamSession` API.

The official DAT changelogs document the breaking changes in `0.7.0` and `0.8.0`:

- `StreamSession` became `Stream`.
- `StreamSessionConfig` became `StreamConfiguration`.
- `StreamSessionState` became `StreamState`.
- Explicit `DeviceSession` ownership was introduced.
- Session and stream failures use typed errors/results.
- Display capability selection must use device capability metadata.

Our Android dependency resolution currently confirms DAT `0.8.0`. OpenVision concepts should be reused, but its old API code should not be copied directly.

### Display support

The Android dashboard now keeps Display controls hidden until DAT reports that the selected device is display-capable. The shared state carries this capability separately from the generic `META_RAYBAN` device class. The official pattern is:

```kotlin
AutoDeviceSelector(filter = { device -> device.isDisplayCapable() })
```

For a user-selected device, use `SpecificDeviceSelector` and inspect compatibility before offering Display actions. Display also requires the DAT App Model (`DAM_ENABLED=true`) and a separate Display capability session.

### HeyCyan-only paths exposed to other device classes

Several advanced controls are currently available based on debug/build state rather than device capability:

- Live preview: `MainActivity.kt:1909-1980`
- Wi-Fi ADB debug: `MainActivity.kt:1990-2027`
- OTA and other developer controls: `GlassesDashboardScreen.kt:656-734`

These paths depend on HeyCyan `LargeDataHandler`, BLE notify slots, Wi-Fi Direct, or the HeyCyan SoC HTTP server. They must be hidden or disabled for Meta profiles.

## Plugin Compatibility

| Feature | Meta Ray-Ban status | Required treatment |
| --- | --- | --- |
| AutoDiary | Android screen/accessibility capture only | Keep Android-only. It is not glasses-camera capture and cannot be duplicated with iOS Accessibility APIs. |
| Auto Audio | HeyCyan `LargeDataHandler` recording commands | Do not run for Meta. Define a separate live microphone capture mode if desired; do not promise onboard 15-minute media files. |
| Visual Diary | HeyCyan thumbnail protocol | Replace with DAT fresh video frames or DAT photo capture. |
| Walking Aid | HeyCyan thumbnail protocol at `WalkingAidService.kt:274-331` | Replace the thumbnail source with the shared Meta camera capability. |
| Meeting Spark Notes | Android SpeechRecognizer plus Bluetooth audio | Potentially usable with Meta HFP audio; coordinate with active DAT camera sessions. |
| Live Caption Relay | Android SpeechRecognizer plus Bluetooth audio | Potentially usable with Meta HFP audio; provide phone-microphone fallback. |
| Hands-Free Translator | Android SpeechRecognizer plus Bluetooth audio | Potentially usable with Meta HFP audio; provide phone-microphone fallback. |
| Errand Brain | Android SpeechRecognizer plus Bluetooth audio | Potentially usable with Meta HFP audio; provide phone-microphone fallback. |
| AI photo questions | No Meta source currently | Feed DAT photo/frame bytes into the existing image AI services. |
| Live vision | No Meta source currently | Share one DAT stream with the selected AI backend and throttle frames. |
| OTA | HeyCyan-specific | Hide for Meta. DAT firmware update navigation is a separate Meta flow. |
| Wi-Fi media sync | HeyCyan-specific `media.config` flow | Do not use for Meta DAT camera photos. |
| Wi-Fi ADB/live preview | HeyCyan-specific | Hide for Meta. |
| Display | Only Meta Ray-Ban Display hardware | Gate by DAT display capability, not by name. |

The current Android plugin catalog in `CommunityPluginsActivity.kt:67-131` still does not gate every plugin card by device class. The service layer now blocks HeyCyan onboard audio-file capture for Meta and routes Visual Diary and Walking Aid one-shot images through the shared Android DAT manager, but plugin-card gating and the remaining audio policy work are still pending.

The voice plugin routing implementation is in `plugins/PluginVoiceSupport.kt:103-262`. It uses Android communication-device/SCO routing. This is a reasonable fallback for Meta audio, but the code needs a shared rule that switches to the phone microphone while the DAT camera stream is active if the Bluetooth audio route becomes unavailable.

The existing Auto Audio implementation explicitly sends HeyCyan commands at `media/autocapture/AutoAudioCaptureService.kt:40-45` and `:301-306`. The Visual Diary implementation obtains HeyCyan thumbnails through `AutoLoopVisualNoteGenerator.kt:203-212`. Neither path can be adapted by changing only the device name.

## Meta Ray-Ban MVP Scope

The first MVP should support:

1. Meta AI registration and unregistration.
2. DAT device availability and connection status.
3. Camera permission requests.
4. A single shared DAT device session.
5. Start/stop camera streaming.
6. Fresh frame delivery to the dashboard and AI services.
7. Photo capture with typed errors and MediaStore/Photos persistence.
8. AI image questions from a captured Meta frame/photo.
9. Walking Aid and Visual Diary using the shared Meta camera source.
10. Correct device and capability gating for HeyCyan-only features.

The following should remain outside the first Meta MVP:

- HeyCyan onboard audio-file recording semantics.
- HeyCyan `media.config` and Wi-Fi Direct media sync.
- HeyCyan OTA and Wi-Fi ADB.
- iOS AutoDiary parity with Android AccessibilityService.
- Meta Ray-Ban Display content, unless display hardware is available for testing.

## Implementation Steps

### Phase 0: Build and credentials

1. Decide whether Meta support is mandatory in the production Android artifact or is a dedicated product flavor.
2. Keep the GitHub package token in `local.properties`/CI only; never place it in source.
3. Move Meta application ID and client token to build placeholders/configuration.
4. Add `CAMERA` to the Android manifest and request the required Android permissions before DAT initialization.
5. Keep Developer Mode configuration separate from production release-channel credentials.

### Phase 1: Android DAT adapter

1. Replace reflection in `MetaRaybanManager` with direct DAT `0.8.0` imports.
2. Initialize DAT exactly once after required Android permissions are granted.
3. Add `Wearables.RequestPermissionContract()` to the owning Activity.
4. Collect `Wearables.registrationState`, `registrationErrorStream`, and `devices`.
5. Collect device metadata, link state, compatibility, and display capability.
6. Create `DeviceSession` only after registration and an eligible device are available.
7. Attach one `Stream` capability after the session reaches `STARTED`.
8. Collect session state/errors and stream state/errors.
9. Recreate sessions after terminal `STOPPED`/`CLOSED` states; do not reuse terminal sessions.
10. Return typed errors to the UI instead of generic reflection exceptions.

### Phase 2: Shared camera capability

1. Define a platform-neutral camera source contract for registration state, camera state, frames, and photo capture.
2. Keep DAT-specific classes in Android/iOS platform adapters.
3. Add a Meta camera lease to the glasses resource coordinator.
4. Ensure plugins request frames/photos through the camera source instead of creating their own sessions.
5. Track frame timestamps and reject stale frames for visual AI.
6. Stop the camera promptly after one-shot photo/visual-diary capture unless a live session owns it.
7. Persist captured photos through the existing Android MediaStore and iOS Photos adapters.

### Phase 3: iOS DAT adapter

1. Add `MWDATCore` and `MWDATCamera` to the iOS host target through Swift Package Manager.
2. Add the DAT `MWDAT` Info.plist configuration, callback URL scheme, `fb-viewapp` query scheme, Bluetooth usage text, and required background modes.
3. Call `Wearables.configure()` once at app launch.
4. Route `.onOpenURL` to `Wearables.shared.handleUrl(_:)`.
5. Implement registration, device monitoring, permission, `DeviceSession`, and `Stream` handling in Swift.
6. Bridge state, frame bytes, photo bytes, and commands to the shared KMP UI through a native adapter boundary.
7. Do not pass MWDAT Swift types directly through `commonMain`.
8. Keep the generic HeyCyan `IosBleManager` isolated from Meta device flows.

### Phase 4: Plugin migration

1. Migrate Visual Diary from `getPictureThumbnails()` to the shared camera source.
2. Migrate Walking Aid from its HeyCyan thumbnail method to DAT frames/photos.
3. Add a fresh-frame timeout and a clear user-facing error when no frame arrives.
4. Keep Meeting Spark Notes, Live Caption Relay, Hands-Free Translator, and Errand Brain on the audio abstraction, with phone fallback.
5. Add an audio-route policy that prevents a plugin from repeatedly renegotiating HFP while DAT camera streaming is active.
6. Keep AutoDiary explicitly Android-only.
7. Do not expose Auto Audio as existing glasses-recording functionality on Meta until a supported public DAT audio/file API exists.

### Phase 5: Capability gating

1. Separate `META_RAYBAN_CAMERA` from `META_RAYBAN_DISPLAY` capabilities.
2. Hide HeyCyan battery/storage/media/P2P/OTA/live-preview/Wi-Fi ADB controls for Meta profiles.
3. Gate plugin cards and background services by actual device capability, not only by the selected display name.
4. Use DAT compatibility and update actions for firmware/DAT-glasses-app requirements.
5. Ensure an unregistered or disconnected Meta device cannot start a camera-dependent plugin.

### Phase 6: Testing

1. Add Android MockDeviceKit tests for registration, permission denied/granted, device availability, session pause/resume, stream errors, photo capture, and terminal cleanup.
2. Add iOS MockDeviceKit tests for the same cases.
3. Test display filtering with a normal Ray-Ban Meta mock and a display-capable mock.
4. Test stale-frame rejection and one-shot camera shutdown.
5. Test concurrent plugin requests and session lease rejection.
6. Test Android voice plugins with Meta HFP audio and with the phone-microphone fallback.
7. Validate on physical hardware with Meta AI installed, Developer Mode enabled, compatible firmware, and current DAT/Meta AI versions.

## Recommended Architecture

Use one platform-neutral capability boundary and separate transport implementations:

```text
Shared plugins and AI flows
        |
        v
WearableCameraSource / WearableAudioSource
        |
        +-- Android Meta DAT adapter
        +-- iOS Meta DAT adapter
        +-- Android HeyCyan adapter
        +-- iOS HeyCyan QCSDK/CoreBluetooth adapter
```

The Meta adapter owns one DAT `DeviceSession` and one camera `Stream`. Visual plugins subscribe to frames or request a one-shot photo. The HeyCyan adapter continues to own `LargeDataHandler`, BLE notify slots, Wi-Fi Direct, media.config, OTA, and live-preview behavior.

The two transports must not share raw command bytes or generic writable BLE-characteristic discovery.

## Relevant Current Files

### Meta Android integration

- `app/src/main/java/com/fersaiyan/cyanbridge/devices/metarayban/MetaRaybanManager.kt`
- `app/src/main/java/com/fersaiyan/cyanbridge/MainActivity.kt:430-650`
- `app/src/main/java/com/fersaiyan/cyanbridge/MainActivity.kt:3633-3797`
- `app/build.gradle:190-200`
- `app/src/main/AndroidManifest.xml:77-86`
- `app/src/main/java/com/fersaiyan/cyanbridge/devices/DeviceClassifier.kt`
- `app/src/main/java/com/fersaiyan/cyanbridge/ui/DeviceBindActivity.kt`

### Shared UI and gating

- `shared/src/commonMain/kotlin/com/fersaiyan/cyanbridge/shared/devices/GlassesManagerGating.kt`
- `shared/src/commonMain/kotlin/com/fersaiyan/cyanbridge/shared/glasses/GlassesDashboardPresentation.kt`
- `shared/src/commonMain/kotlin/com/fersaiyan/cyanbridge/shared/ui/glasses/GlassesDashboardScreen.kt`
- `shared/src/commonMain/kotlin/com/fersaiyan/cyanbridge/shared/ui/SharedDestinationScreen.kt`
- `shared/src/androidMain/kotlin/com/fersaiyan/cyanbridge/shared/ble/AndroidBleManager.kt`
- `shared/src/commonMain/kotlin/com/fersaiyan/cyanbridge/shared/glasses/GlassesSessionCoordinator.kt`

### iOS KMP and HeyCyan transport

- `ios/CyanBridgeKMPHost/CyanBridgeKMPHostApp.swift`
- `ios/CYANBRIDGE_KMP_IOS.md`
- `shared/src/iosMain/kotlin/com/fersaiyan/cyanbridge/shared/platform/MainViewController.kt`
- `shared/src/iosMain/kotlin/com/fersaiyan/cyanbridge/shared/ble/IosBleManager.kt`
- `shared/src/iosMain/kotlin/com/fersaiyan/cyanbridge/shared/media/IosMediaTransfer.kt`

### Plugin paths requiring migration or gating

- `app/src/main/java/com/fersaiyan/cyanbridge/plugins/PluginVoiceSupport.kt`
- `app/src/main/java/com/fersaiyan/cyanbridge/plugins/walkingaid/WalkingAidService.kt`
- `app/src/main/java/com/fersaiyan/cyanbridge/plugins/meetingsparknotes/MeetingSparkNotesService.kt`
- `app/src/main/java/com/fersaiyan/cyanbridge/plugins/livecaptionrelay/LiveCaptionRelayService.kt`
- `app/src/main/java/com/fersaiyan/cyanbridge/plugins/handsfreetranslator/HandsFreeTranslatorService.kt`
- `app/src/main/java/com/fersaiyan/cyanbridge/media/autocapture/AutoAudioCaptureService.kt`
- `app/src/main/java/com/fersaiyan/cyanbridge/media/autocapture/AutoLoopVisualNoteGenerator.kt`
- `app/src/main/java/com/fersaiyan/cyanbridge/plugins/autodiary/AutoDiaryService.kt`
- `app/src/main/java/com/fersaiyan/cyanbridge/ui/CommunityPluginsActivity.kt`

### External implementation references

- OpenVision `OpenVision/Managers/GlassesManager.swift`
- OpenVision `OpenVision/Views/VoiceAgent/VoiceAgentViewModel.swift`
- OpenVision `project.yml`
- Official DAT Android `samples/CameraAccess/.../StreamViewModel.kt`
- Official DAT Android `AGENTS.md` and `plugins/mwdat-android/skills/`
- Official DAT iOS `samples/CameraAccess/.../StreamSessionViewModel.swift`
- Official DAT iOS `AGENTS.md` and `plugins/mwdat-ios/skills/`

## Validation Checklist

- Android DAT `0.8.0` artifacts resolve in every Meta-enabled build.
- Android app initializes DAT only after required runtime permissions.
- Meta AI registration reaches `REGISTERED` and reports registration errors.
- A compatible device appears through `Wearables.devices`.
- Device compatibility/update requirements are visible and actionable.
- Session reaches `STARTED` before a stream is attached.
- Stream reaches `STREAMING` before frames/photos are used.
- Pause, disconnect, hinge-close, thermal, battery, and terminal states clean up correctly.
- Photo data reaches the gallery/media repository.
- Visual Diary and Walking Aid use DAT frame/photo sources on Meta.
- Voice plugins fall back to the phone microphone when HFP is unavailable.
- HeyCyan P2P/OTA/live-preview controls are absent for Meta profiles.
- Display controls appear only for display-capable Meta hardware.
- iOS host builds on a Mac and passes physical-device DAT tests.
