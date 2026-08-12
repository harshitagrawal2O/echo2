# Cue

**Ambient situational awareness for blind and low vision users, on HeyCyan CY-01 glasses.**

PRD v3, 2026-08-11. Status: hackathon build spec.

Supersedes v2. Grounded against `GLASSES_CAPABILITY_BRIEF.md`, `CLAUDE.md`, the
vendor Android SDK guide, and a direct read of the `FerSaiyan/Alternative-HeyCyan-App-and-SDK`
source at HEAD. Where public listings disagree with those, those win. Where v2
disagrees with the source, the source wins, and §12 records what changed.

---

## 0. Assumptions

You did not pick these. Each is cheap to change, but changing it changes the build plan.

| Assumption | Chosen | Change it if |
|---|---|---|
| Platform | **Android**, Java 17+, Kotlin | Not negotiable in practice. iOS is a CI-validated simulator host only and `QCSDK.framework` has never run on hardware. |
| Hardware | **CY-01** (unit on hand: `CY 01_994B`) | Locked. |
| Codebase | **New plugin** under `plugins/cue/` in `android/CyanBridge` | Changed from v2. See §7.1. |
| Build window | **24 to 36 hours** | Under 24: ship P0-1 through P0-4 and nothing else. |
| Hero demo | **Silent departure detection** | Changed from v2. See §13. |
| AI provider | **Claude direct** (Sonnet for context, Haiku for the fast path) | The fork routes through `cyanbridge.vercel.app` to OpenRouter with the upstream author's subscription billing attached. `ai/router/AiProviderPrefs.kt:33`. Rip it out at Hour 1. |
| Capture path | **Undecided until Spike A** | This is the one genuinely open question and it gates everything. See §11. |

---

## 1. Problem

A sighted person in a room is **continuously synchronized** with it. Not
informed — synchronized. They receive a free, unconscious, high-frequency stream
that keeps an internal model of the room accurate at all times: who is present,
who just moved, who is speaking, who is waiting, who is about to speak, whether
the person they are addressing is still there.

A blind person receives a fraction of it, at low frequency, and mostly by asking.

The consequence is not "missing information." It is **desynchronization**: acting
confidently on a model of the room that stopped being true some seconds ago.

- Continuing to talk to someone who quietly walked away. Universally reported by
  blind users, and described as humiliating rather than merely inconvenient.
- Not knowing who is in a meeting, so you cannot address anyone by name — which
  reads to the room as disengagement.
- Missing the raised hand, the nod, the confused frown, the person waiting for
  you to finish.
- Missing every deictic reference. "This one." "Over there." "That number right
  here." All of it is noise.
- Greeting someone as a stranger who has met you three times.
- Not knowing whether the sound just now was the door, a dropped bag, or someone
  approaching you.

Note what these have in common. None of them is solved by *more description*.
Every one is solved by **the model being right at the moment you act on it.**

This is not a perception problem. It is a **synchronization** problem. The
existing assistive market solves perception on request: Seeing AI, Envision,
OrCam, and Be My AI are all fundamentally "describe the world to me when I ask."
Describing the world is a snapshot. Synchronization is a subscription.

## 2. The insight, and the rule that falls out of it

**Description is a query. Awareness is a stream. Cue is a stream that mostly says nothing.**

There is a real tension in this product and it should be named before anyone
writes code. The goal is to give the user context about their surroundings. The
naive implementation of that goal — narrate the surroundings — makes the product
useless, because a tool that speaks a sentence into your ear while a human is
talking to you has made your life worse. A conversation gives you neither the
time nor the silence.

So the product is defined less by what it knows than by **what it refuses to
say.** But "say less" is not a design; it is a mood. The design needs a rule.

Here it is:

> **Speak when the user's model of the room has diverged from the room, weighted
> by what it will cost them to act on the stale version. Stay silent otherwise.**

This gives you a decision procedure instead of a vibe. For every candidate
utterance, three questions:

1. **Divergence.** Does the user's model currently differ from reality?
2. **Consequence.** If they act on the stale model in the next thirty seconds,
   how badly does it go?
3. **Cost.** What does saying it cost right now — is a human speaking?

Worked examples, and note that they do not all resolve the same way:

| Event | Divergence | Consequence | Verdict |
|---|---|---|---|
| The person you are addressing silently left | Total | Severe and public | **Say it immediately.** Highest-value event in the product. |
| A new voice joined the conversation | High | Moderate — you cannot address them | Earcon now, name in the next gap |
| Someone across the room stood up | Low | None | **Silence.** This is what a narrating product gets wrong. |
| Speaker changed to someone already in the roster | Moderate | Low but constant | Whisper the name, gap only |
| Someone is waiting for you to respond | High | Moderate and immediate | Earcon, no words |
| The lights changed | Zero relevance | None | **Silence.** |

The third and sixth rows are the product. Anything that describes the room
without correcting a divergence that matters is noise, and noise is what gets
assistive wearables abandoned.

> Cue is a low bandwidth, high frequency channel of social telemetry, delivered
> in the gaps of a conversation, mostly without words.

**The competitive fact to confront up front.** This hardware already ships an AI
photo button: press it, ask a question about what you see, hear the answer.
`GLASSES_CAPABILITY_BRIEF.md` lists it as Confirmed and working, and the source
confirms it — the `0x02` notify is fully wired in `MainActivity.kt`. So **scene
description is the baseline this device already offers.** If your demo is "point
at thing, hear description," you have rebuilt a button that exists. Everything in
this section is what separates Cue from its own firmware. Say it to judges before
they find it themselves.

## 3. Users

**Primary persona.** Blind or low vision adult, screen reader fluent,
professionally active, attends meetings and social events. Already at expert
speed with TTS — they listen at 300 to 500 words per minute, far faster than you
will design for. Owns assistive tools and has abandoned most of them because they
were slow, conspicuous, or wrong.

Note carefully: **your user is a TTS power user and you are not.** Do not tune
voice speed to what sounds comfortable to a sighted judge. Make it configurable
and default it fast.

**Jobs to be done.**

1. When I enter a room, tell me who is here so I can greet them by name.
2. While someone is talking, tell me who it is without making me ask.
3. Tell me the moment the situation changes: someone arrived, someone left,
   someone is waiting on me.
4. Tell me when something happened near me that I would have seen — not
   everything, the things that matter.
5. When someone references something visual, let me ask what it is and get an
   answer fast.
6. Do all of this without announcing to the room that I am using a device.

That last one is a requirement, not a preference. Conspicuousness is the top
reason assistive wearables get abandoned. The glasses look like sunglasses. Keep
it that way.

**The user you must actually talk to.** v2 budgeted zero hours with a blind
person across a 36-hour build. That is the single largest credibility gap in the
project and it is cheap to close: one 30-minute conversation with one blind
screen-reader user, before Hour 0, will reorder your P0 list. It is also the
strongest single thing you can say to a judge. §12 now budgets it.

## 4. Non-goals

Say these out loud to the team, because each will try to sneak back in at hour 20.

- **Not a navigation aid.** No obstacle detection. Different safety bar,
  different liability, and a cane already wins. (Note: `plugins/walkingaid/`
  in this repo *is* that product. It is a sibling, not Cue.)
- **Not an OCR tool.** Reading documents is solved and is a demo cliche.
- **Not always-narrating.** If it describes the room continuously, it has failed.
  See §2. This is the non-goal most at risk from the project's own objective.
- **Not a meeting recorder.** The fork ships meeting capture and summarization.
  Ignore it. After-the-fact summaries are a different product.
- **Not face recognition of strangers.** See §10.
- **No media sync during a session.** See §7.5. Architectural commitment, not a
  preference.
- **Nothing shown on the glasses.** There is no display. Every output is spoken
  or sounded. Any feature phrased as "show the user X" is invalid by construction.

---

## 5. Interaction model

Get this right and mediocre perception still feels magical. Get it wrong and
perfect perception is unusable.

### 5.1 Four output tiers

Ranked by interruption cost. Cue always uses the cheapest tier that carries the
information. All output is spoken or sounded, because there is no display.

**Tier 0: Earcons (non-speech audio).** Under 200ms, no words, no language
processing. Carries the high frequency events.

| Event | Earcon | Rationale |
|---|---|---|
| New person entered | Two ascending notes | "Something got added" |
| Person left | Two descending notes | Mirror of the above |
| **The person you were addressing left** | **Two descending notes, doubled** | Distinct because the consequence is distinct. This is the §2 top row. |
| Someone is addressing you directly | Single soft chime | Directional metaphor |
| Someone is waiting for you to respond | Slow double pulse | Ambiguity is the point: "your turn" |
| Ambient event near you | Single low tick | See Tier 0.5 |
| Cue is working on your request | Rising tick | Covers the photo path latency |
| Cue failed or is unsure | Low muted thud | Never say "I'm sorry, I didn't catch that" |
| Glasses busy, command rejected | Short flat buzz | Distinct from failure. See §7.5. |
| Cue lost the glasses | Three descending notes | The user must know the system has gone blind. Silence is indistinguishable from an empty room, and that ambiguity is dangerous. |

Earcons are learned in about ten minutes and cost near zero attention afterward.
This is how screen readers already work and your user is fluent in the paradigm.
Lean on it hard. **Teach the vocabulary in onboarding as audio, not as a list.**

**A constraint v2 missed:** if the SCO microphone route holds the link for the
whole session, output may also be forced to 8 kHz mono. Every earcon must then
survive a ~3.4 kHz ceiling and narrowband voice coding. Design the set *through
the actual glasses speaker*, not on laptop monitors. Confirm in Spike A.

**Tier 0.5: Ambient events.** New in v3, and the main thing §2's objective adds
over v2. Non-speech sounds that a sighted person would have registered without
thinking: a door, a knock, applause, laughter, a phone ringing, footsteps
approaching. On-device audio classification, no cloud, no transcript.

These get an earcon only, never words, and they are **rate-limited hard** — at
most one per fifteen seconds, and suppressed entirely while a human is speaking.
Ambient awareness is the feature most likely to turn Cue into the narrating
product §4 forbids. Treat the rate limit as a correctness requirement.

**Tier 1: Whispers.** One to four words, spoken at 1.5x, only in a detected
speech gap. "Sarah." "Priya, on your right." "He left." A whisper is never a
sentence. If it needs a verb, it belongs in Tier 2.

**Tier 2: Briefings.** A full spoken response, only ever user-initiated, or on
session start.

### 5.2 The gap detector is the most important component you will build

Nothing in Tier 1 ever plays while a human is speaking. Ever. Build the voice
activity detector first and be conservative: require 400ms of silence before a
whisper, and abort mid-whisper if speech resumes.

If you build only one thing well, build this. A system that speaks over people is
worse than no system, and judges will feel it instantly even if they cannot
articulate why.

**Measure gap availability before you tune it.** Natural turn transitions in a
three-person conversation are frequently under 200ms and often overlapping. If
400ms gaps are rare in real speech, a strict threshold produces a system that
says almost nothing — and §14's zero-interruption metric would then be
trivially satisfied by silence. Record five minutes of real three-person
conversation at Hour 0 and histogram the gaps. If gaps are scarce, the honest
fallback is naming at end-of-turn rather than turn-start, and you say so.

### 5.3 Never say a name you are not sure of

New in v3, and it is a correctness rule, not a polish item.

Streaming diarizers revise speaker labels retroactively. Cue can whisper "Priya"
and be wrong. Your user cannot glance up to check, and will act on it — by name,
out loud, in front of people.

**Rule.** Every name-bearing utterance carries a confidence. Below threshold,
degrade rather than guess:

| Confidence | Output |
|---|---|
| High | Name |
| Medium | "Someone new" |
| Low | Earcon only, no words |

A wrong name is worse than no name. This is the same principle as §5.2 applied to
content instead of timing.

**Related: exclude the wearer.** The user talks constantly, and a headset mic
array is tuned to favour them. Nothing in v2 removed the wearer from the roster.
Bind the wearer's diarization label at session start and suppress it everywhere.

### 5.4 Input: the events the glasses already send

The brief is explicit that **buttons are fixed in firmware and cannot be
remapped**. But the glasses already report events to the phone, and CyanBridge
receives several and does nothing with them. You are not remapping firmware. You
are interpreting events that already arrive and are currently thrown away.

**Verified against source.** The dispatch is
`MainActivity.MyDeviceNotifyListener.parseData()`, switching on
`response.loadData[6]`:

| Code | Meaning | State in repo | Cue uses it for |
|---|---|---|---|
| `0x02` | AI photo ready / thumbnail | Wired, full image-question flow | P0-6 |
| `0x03` | AI / mic button | Wired to phone assistant hijack | Leave to the vendor path |
| `0x05` | Battery + charging | Wired | Low-battery earcon, dev overlay |
| `0x0c` | **Pause event** | **Empty — literally `//to do`** | **P0-5 "who's here" + interrupt** |
| `0x0e` | Memory low | Empty block | P1-3 |
| `0x12` | **Volume change** | Fully decoded, then discarded to a Toast | **P0-7 repeat last** |

`0x12` already parses music, call, and system min/max/current plus the current
mode. Detect a press as a delta against a value you cache on the first `0x12`
after session start.

**A second input channel v2 did not know about.** Touchpad play/pause/next/prev
arrive separately as `MusicCommandRsp` through `MusicCommandListener`, which the
vendor SDK converts into `dispatchMediaKeyEvent` and `adjustStreamVolume`.
CyanBridge references this class zero times. Intercepting it before the vendor
handler gives Cue **a second set of silent, tactile, eyes-free triggers on the
temple, with no phone in hand and no wake word.**

**Interrupt-on-pause is worth calling out separately.** A blind user who has
heard enough of a briefing has no way to stop it other than waiting. Wiring pause
to cut speech immediately is small work and it is the difference between a tool
that respects the user's time and one that lectures them.

**Fallbacks, in order.** Phone hardware volume key (zero latency, fully reliable,
good stage insurance). Then screen gestures, which require taking the phone out,
but build a basic version because the app must work when the glasses are dead.

**Do not use a wake word.** "Hey, Cyan" invokes the vendor assistant, and AI
conversation is a device mode that will collide with everything Cue does. New in
v3: you can now *detect* it rather than merely avoid it — see §7.4.

### 5.5 Session lifecycle

Bind sessions to connection state and wear detection so there is no start button
at all. Put the glasses on, Cue starts and briefs the room. Take them off, the
session ends and the roster clears — which is also §10's privacy story expressed
as a physical act rather than a settings toggle.

The vendor SDK exposes `GlassesWearRsp.isOpen()`, so this is real rather than
aspirational. **But** `GlassesTouchSupportRsp.isWearCheckSupport()` is a
capability flag, and the AAR is shared with a smartwatch line, so class existence
proves nothing about CY-01. Query capabilities at connect, and fall back to BLE
connect/disconnect if wear check is unsupported.

Zero-UI session control on a device for blind users is correct, and it demos
well: the audience watches someone put on sunglasses and hear the room.

---

## 6. Scope

Every feature states its internet dependency and its device-busy behaviour.

### P0, the demo (must ship)

**P0-1. Live speaker attribution.** Cue names who is speaking, in the gaps,
unprompted.
- Path: live mic (§7.2), streaming STT with diarization, on the phone.
- Internet: **required.** Degrades to earcon-only if offline.
- Device busy: unaffected. The mic route is a headset profile, not a glasses mode.
- Effort: **Medium.**
- Accept: with three enrolled speakers, correct name whispered within 800ms of
  turn start, at least 85 percent of turns, **and zero incorrect names above the
  confidence threshold.** The second half of that is not optional.

**P0-2. Passive roll call enrollment.** The social ritual of introducing yourself
is the enrollment flow. No setup screen, no training step, **no trigger at all.**
- Cue watches the transcript for self-introductions and binds diarization labels
  to names as they appear. Someone says "hi, I'm Sarah" and Sarah is in the roster.
- Internet: required (rides on P0-1).
- Effort: **Low.** One Haiku call over a transcript window.
- Accept: three people enrolled in under 30 seconds of natural conversation, zero
  taps and zero commands.

Making this passive rather than a command is a real upgrade: enrollment then
happens in conversations the user did not plan for, which is most of them.

**P0-3. Presence change alerts.** New voice detected, or a known voice silent
past threshold, fires the Tier 0 earcon plus optional Tier 1 name.
- Internet: required.
- Effort: **Low** once P0-1 exists.
- Accept: new speaker triggers the enter earcon within 2s of first utterance.

**P0-4. Addressee-departure detection.** *New in v3, and the hero.*
- Cue tracks who the user was last speaking *to* — the speaker they most recently
  exchanged turns with. If that person goes silent past threshold while the user
  is still talking, fire the doubled departure earcon immediately, at Tier 0,
  **without waiting for a gap.**
- This is the one case where interrupting is correct, because the user is already
  speaking into a void and every additional second is worse.
- Internet: required (rides on P0-1).
- Effort: **Low** once P0-3 exists. It is a state machine over the roster.
- Accept: from the addressee's last utterance to the earcon, under 8 seconds.
- This is §2's top row, it is the most-reported indignity in §1, and no shipping
  product does it.

**P0-5. "Who's here." Pause button.** Tier 2 briefing on demand, no voice, no phone.
- Internet: not required. Answers from the in-memory roster.
- Effort: **Low.** Wire the empty `0x0c` handler.
- Accept: spoken answer begins within 1.5s of the press.

**P0-6. "What am I looking at." AI / photo button.** The one visual feature.
- Path: the existing AI photo flow, `0x02` notify plus thumbnail over BLE. **Not
  a media sync.** See §7.5.
- Cue's change: inject the last 30 seconds of transcript plus the roster into the
  prompt.
- Internet: **required.**
- Device busy: photo is a device mode. If the glasses are busy, play the busy
  earcon and do not queue.
- Effort: **Low.** The plumbing exists; you are changing a prompt and a payload.
- Accept: "He is holding a laptop" is a failure. "The chart on his screen shows Q3
  revenue down about 12 percent" is a pass.

**P0-7. Repeat last. Volume button.** Replays the last whisper or briefing from cache.
- Internet: not required.
- Effort: **Low.** The `0x12` handler already decodes everything.
- Accept: replays instantly, never regenerates, works after any output tier.

Repeat-last is not a nice to have. Your user is in a live conversation with their
attention on a human, they will miss whispers constantly, and audio has no
scrollback. Every screen reader has this command for exactly this reason. Blind
testers will rank it above anything in P1.

### P1 (if time survives)

- **P1-1. Ambient event earcons.** Tier 0.5. On-device audio classification for
  door, knock, applause, laughter, phone, approaching footsteps. Runs on LiteRT,
  which is already a dependency (`plugins/walkingaid/vision/LiteRtVisionBackend.kt`).
  No cloud, no transcript, no internet. Hard rate limit per §5.1.
- **P1-2. Reaction readout.** After a long user turn, one earcon summarising room
  response. High wow, high risk, needs a photo so it inherits every constraint in §7.5.
- **P1-3. Spatial placement.** "On your left." Needs a photo plus face position
  mapping. See §7.6.
- **P1-4. Storage-full warning.** The glasses already report it at `0x0e` and the
  handler is an empty block. Trivial, and it prevents a silent failure mid-session.
- **P1-5. Re-recognition of consented contacts.** Strictly opt in, strictly local.
  See §10.

### P2 (roadmap slide only, do not build)

Braille display output. Calendar and CRM prefetch so Cue knows who is expected.
Group dynamics over time. Translation, which the hardware already does.

---

## 7. Architecture

### 7.1 Where the code goes

**Changed from v2.** v2 assumed a fork of `MainActivity.kt` and listed its 10,094
lines as the project's highest risk. That is avoidable. CyanBridge has a native
plugin system, and `plugins/walkingaid/` is a working accessibility plugin for
blind users that already consumes glasses photo notifications, drives TTS, runs a
foreground service, and ships a settings screen.

Cue is a sibling directory. Register it the way WalkingAid registers: an id in
`shared/plugins/NativePluginIds`, a card in `ui/CommunityPluginsActivity.kt`, and
enable/disable through `CommunityPluginPrefs.setNativePluginEnabled`.

**You make exactly two edits to `MainActivity.kt`:** one delegation line in
`MyDeviceNotifyListener.parseData` forwarding to a Cue router when the plugin is
enabled (copy the pattern the WalkingAid `0x02` intercept already uses), and one
registration for `MusicCommandListener` interception. Anything beyond those two,
stop and reconsider.

This deletes v2's largest listed risk and is worth doing in the first hour.

### 7.2 The two audio paths, which is the thing to understand

**Path A, live microphone. Confirmed.** The glasses pair as a normal Bluetooth
headset. The phone hears the mic live. Real-time transcription works here.
**All of Cue's real-time behaviour lives here.**

**Path B, on-glasses recording. Confirmed but not live.** The glasses record to
internal storage. Files are pulled later in a batch transfer that hijacks the
phone's Wi-Fi.

Live conversation processing is feasible. Real-time processing of an on-glasses
recording is not. **Cue uses Path A exclusively and never touches Path B during a
session.** The repo's own source comment agrees: the vendor's raw audio protocol
is not confirmed for real-time use, so the Android SCO route is the supported
live path.

**The unresolved risk, elevated from v2's §12 to the top of this document.**
The CY-01 uses dual ENC microphones. Environmental noise cancellation on a
Bluetooth headset is tuned to isolate the *wearer* and suppress everything else —
correct for a phone call, and precisely backwards for Cue, whose entire P0 is
transcribing the people the wearer is talking to. SCO is also 8 kHz mono, which
is already marginal for diarization before you subtract beamforming.

v2 stated the mic placement as the project's best hardware fit. That claim is
unproven and may be inverted. **Spike A decides it, and the phone mic may be the
primary path rather than the fallback.** Do not put the hardware-fit claim in the
deck until Spike A passes.

### 7.3 Four channels

```
  ┌──────────────────────────────────────────────────────────┐
  │  CY-01 GLASSES  (no display, one mode at a time)         │
  │                                                           │
  │  [dual ENC mics] ──── A: BT headset, LIVE ──────┐        │
  │  [open-ear spkr] ◄─── D: BT audio out ──────────┤        │
  │  [8MP camera]    ──── C: AI photo, thumb / BLE ─┤        │
  │  [buttons+pad]   ──── B: BLE notify + music cmd ┤        │
  └─────────────────────────────────────────────────┼────────┘
                                                     │
  ┌──────────────────────────────────────────────────▼───────┐
  │  ANDROID PHONE  (all intelligence lives here)            │
  │                                                           │
  │   A. mic in ──► VAD ──┬──► streaming STT + diarization    │
  │                       └──► on-device audio classifier     │
  │                            │         (Tier 0.5)           │
  │                            ▼                              │
  │                     CONTEXT ENGINE (rolling state)        │
  │              roster · 60s transcript · addressee ·        │
  │              last photo · staleness clocks                │
  │                            │                              │
  │              ┌─────────────┼─────────────┐                │
  │              ▼             ▼             ▼                │
  │        fast path      slow path     photo path            │
  │        (local)        (Haiku)       (Sonnet + img)        │
  │              └─────────────┼─────────────┘                │
  │                            ▼                              │
  │      DIVERGENCE ARBITER ◄── gap detector                  │
  │      (§2 rule + confidence gate §5.3)                     │
  │                            │                              │
  │   D. TTS + earcon mixer ◄──┘                              │
  └───────────────────────────────────────────────────────────┘
```

**Channel A, audio in.** Bluetooth headset profile via `AudioRecord` on the SCO
route. Reuse the routing helpers in `media/autocapture/AmbientSpeechDetector.kt`,
which already solves `setCommunicationDevice` with a legacy `startBluetoothSco`
fallback. Copy the PCM streaming pattern from `ai/live/GeminiLiveClient.kt`.

**Do not use `plugins/PluginVoiceSupport.kt`.** Its `PluginVoiceRecognizer` wraps
Android `SpeechRecognizer`, which returns no speaker labels, segments on 1200ms of
silence against an 800ms budget, and holds a process-global single-listener lock.
It cannot do P0-1. Keep its routing code, drop the recognizer.

**Channel B, input events.** BLE notify plus the `MusicCommandRsp` stream. See §5.4.

**Channel C, photos.** The existing AI photo flow. See §7.5.

**Channel D, audio out.** Standard Bluetooth to the open-ear speakers. Open-ear
matters enormously: it does not occlude the ear canal, so the user still hears the
actual conversation. Bone conduction or in-ear would be disqualifying. **This, not
the mic, is the genuine hardware fit in this project** — and unlike the mic claim
it needs no spike to defend.

### 7.4 Yielding to the vendor assistant

New in v3. The SDK exposes `GlassesAiVoiceRsp.isOpen()` and
`GlassesAiVoicePlayStatusRsp.getStatus()`. CyanBridge references neither.

v2's guidance was "never use a wake word, do not fight the vendor assistant." You
can do better than avoidance: subscribe to these, and when the vendor assistant
opens or speaks, Cue goes fully silent and releases the mic, then resumes. A
collision becomes a handoff. Small work, and it removes a whole class of demo
failure.

`CameraNotifyRsp` similarly exposes `ACTION_INTO_CAMERA_UI`, `ACTION_TAKE_PHOTO`,
and `ACTION_FINISH`, letting you observe camera-mode entry and exit rather than
inferring it.

### 7.5 One mode at a time, and no media sync ever

The glasses are always in exactly one mode: photo, video, audio recording, media
transfer, firmware update, or AI conversation. **A command sent while busy is
rejected**, and the SDK reports which mode it is stuck in.

- Path A live mic is the headset profile, not a glasses mode, so ordinary
  listening never blocks. **Confirm in Spike C** — if taking a photo interrupts
  the mic stream, Cue goes deaf for the duration and needs defined behaviour.
- P0-6 is the only feature that claims a mode. Everything else is phone-side.
- **Every rejection gets the busy earcon and is dropped, never queued.** A queued
  action that fires 8 seconds later into a conversation that has moved on is worse
  than no action. This is a design decision, not a shortcut.

Implementation: `glasses/GlassesSessionCoordinator.kt` enforces single access
with leases plus short-lived permits for one-shot commands. **Any command Cue
sends must acquire a lease or permit or it will silently clobber an in-flight
operation.** Read that file first.

**No media sync during a session.** During a Wi-Fi Direct transfer the process is
bound to the P2P network and the phone is off the normal internet, so cloud AI
calls fail. For a product whose every answer is a cloud call, a media sync inside
a session is fatal. The way out is that Cue never needs one: the AI photo
thumbnail arrives over BLE, no transfer mode, no internet loss. Full resolution
media, if ever needed, syncs after the glasses come off.

This deletes the single worst constraint on the platform and it is the most
important architectural decision in this document.

### 7.6 Why P0 presence is audio only

"Sarah is on your left" needs vision. The mics are dual but you get mono, so
there is no direction of arrival to extract. Rather than fake it:

- **P0 says "Sarah joined."** True, useful, honest.
- **P1 adds position** via a photo on the enter event, inheriting every
  constraint in §7.5.

Do not claim spatial awareness until P1 works. A judge who asks "how do you know
she's on the left" and gets a hand-wave has found your weak point.

### 7.7 Speaker identification, the hackathon shortcut

Do not train voice embeddings. You do not have time and you do not need to.

1. Streaming STT with diarization (Deepgram or AssemblyAI, both give `speaker_0`,
   `speaker_1` labels at roughly 300ms).
2. People say their names out loud when introducing themselves, because that is
   what humans do.
3. One Haiku call: *"map each speaker label to a name based on this introduction
   transcript."*
4. Cache for the session, with a confidence per binding (§5.3).

That is the whole feature. It is a prompt, not a model. It also fails gracefully:
an unmapped speaker becomes "someone new," which is still useful.

**Insurance:** diarization degrades badly with overlapping speech and in noisy
rooms, and hackathon venues are noisy rooms. Pre-record a clean fallback session
behind a mode flag, and rehearse both.

### 7.8 Latency budget

Every number is the point at which the feature stops feeling like perception and
starts feeling like a computer.

| Path | Budget | How |
|---|---|---|
| Turn start to name whisper | **800ms** | Local diarization label to cached name. No LLM call. |
| Addressee departure to earcon | **8s** | Silence threshold on a roster state machine. |
| Button press to briefing start | **1.5s** | Haiku, streamed, state already in memory |
| Button press to photo answer start | **measure, then budget** | Thumbnail over BLE is unmeasured. Cover it with the working earcon. |
| Event to earcon | **200ms** | Preloaded PCM, no synthesis |

If the fast path ever needs a network call, the design is wrong.

---

## 8. The context engine

One rolling state object. This is what makes Cue answer questions rather than
describe pixels.

```kotlin
data class ConversationContext(
    val sessionStart: Instant,
    val wearerLabel: String?,           // §5.3: excluded from roster everywhere
    val roster: List<Person>,           // name, speakerLabel, confidence,
                                        // firstHeard, lastHeard, isPresent
    val addressee: Person?,             // §P0-4: who the user is talking TO
    val turns: RingBuffer<Turn>,        // speaker, text, startMs, endMs. Keep 60s.
    val ambientEvents: RingBuffer<AmbientEvent>,  // Tier 0.5, keep 60s
    val lastPhoto: PhotoContext?,       // thumbnail bytes, capturedAt, caption
    val lastOutput: SpokenOutput?,      // §P0-7 repeat-last cache
    val pendingQuestion: Boolean,
    val userLastSpokeMs: Long
)
```

**Staleness is first-class.** Every `Person` carries `lastHeard`. §2's divergence
test is computed against these clocks, not against events. A person who has not
spoken in 90 seconds is not necessarily gone, but the model's confidence that
they are present has decayed, and the arbiter should know that.

**Prompting principle.** Never send a photo alone. Always send photo plus last 30
seconds of transcript plus roster:

- Photo alone: "A man is holding a piece of paper."
- Photo plus context: "Grant is holding up the invoice he just mentioned. The
  total reads 4,200 dollars."

The second is the product. The first is the button the firmware already has.

**System prompt constraints, enforce hard:**

- Under 15 words unless explicitly asked to elaborate.
- Never describe anything the user did not ask about.
- Never open with "I see" or "The image shows."
- If uncertain, say the short uncertain thing. Never hedge across two sentences.
- Names, not descriptions, for anyone on the roster.

---

## 9. App accessibility, which is not optional

The companion app is used by a blind person. Most hackathon projects for blind
users ship an app the target user cannot operate. Do not be that project.

- Every control has a `contentDescription`, including icon buttons.
- Full TalkBack traversal, **tested with the screen off.**
- Touch targets 48dp minimum.
- Primary actions within two swipes of launch.
- No state communicated by colour alone.
- No timed dialogs that vanish.
- Speech rate configurable and **defaulted fast** (§3).
- Onboarding teaches the earcon vocabulary as audio with a replay control, not as
  a written list.
- The app must work when the glasses are disconnected, falling back to phone mic
  and speaker.

Budget 90 minutes and do it before demo polish, not after. If a judge picks up
the phone and turns on TalkBack, this becomes the whole story.

---

## 10. Privacy, consent, and the line you do not cross

**What Cue does.** Rolling 60 second in-memory audio buffer, discarded
continuously. No audio persisted to disk by default. Photo sent to the model only
on an explicit button press, never on a timer. Voice-to-name mappings held for
the session only, cleared when the glasses come off. Ambient classification runs
on-device and produces a category label, never a recording.

**What Cue does not do.** No face recognition against any stored database. No
biometric identification of anyone who has not opted in. No cloud storage of
images or audio. No continuous recording.

**Re-recognition.** Users want "who is this person I have met before," and that is
legitimate. But storing face or voice embeddings of bystanders is biometric
processing under GDPR Article 9 and Illinois BIPA, and consent belongs to the
person recognised, not your user. If you build P1-5: on-device only, explicit
self-enrollment, deletable, never a background scan.

**Two-party consent.** Recording laws in several US states and most of the EU
require all-party consent. A rolling in-memory buffer with no persistence is a
genuinely different legal posture from a recorder. Worth one slide.

**The asymmetry worth naming out loud:** a sighted person walks into a room and
identifies everyone instantly, and nobody calls it surveillance. Cue restores
parity, it does not create a new capability. That is honest, and it is your best
answer in Q and A.

---

## 11. Spikes: Hours 0 to 3

Nobody writes product code until these are answered.

**Spike A: the microphone. This is the one that can kill the product.**

Do not frame it as "is HFP too narrowband." Frame it as:

> Record three people in conversation through the glasses. Play it back. Are the
> two people who are **not** wearing the glasses intelligible?

Then, in the same sitting: does holding the SCO route open force output to 8 kHz
mono, and do the earcons still work through the glasses speaker under that
codec?

Outcomes: pass → Path A as designed. Fail → phone mic becomes primary, §7.2's
hardware-fit claim comes out of the deck, and you say so honestly on stage.

**Spike B: gap availability.** Record five minutes of real three-person
conversation. Histogram the inter-turn silences. Decide the whisper threshold
from data, not from §5.2's placeholder.

**Spike C: mode collision.** Does an AI photo interrupt the live mic? Do `0x0c`
and `0x12` actually arrive on this unit? Does `GlassesTouchSupportRsp` report
wear-check support? Watch `DeviceNotify`, which prints every decoded frame and is
the fastest way to learn the protocol on real hardware.

```bash
adb logcat -s DataDownload DeviceNotify WifiP2pManagerSingleton \
  WifiP2pBroadcastReceiver BleIpBridge LDHMethods
```

**Spike D: thumbnail legibility.** Largely answered — `0x02` is wired and
WalkingAid consumes it today. Run one capture, save the bitmap, check whether
Claude can read a printed chart from it. If not, P0-6 rescopes to people and
gross objects, which is still demoable.

Also at Hour 1: rip out the Vercel relay (`ai/router/AiProviderPrefs.kt:33`) and
point AI at Claude directly. One-file change, not optional — you are otherwise
shipping someone else's billing.

---

## 12. Risks

| Risk | Severity | Mitigation |
|---|---|---|
| **ENC mic array suppresses everyone but the wearer**, defeating all of P0 | **Critical** | Spike A at Hour 0. Phone mic as primary if it fails. This is the project's real risk and v2 understated it. |
| No blind user involved in a product for blind users | **High** | One 30-minute call before Hour 0. Non-negotiable. |
| Diarization collapses in a loud venue | **High** | Pre-recorded fallback behind a mode flag, rehearse both, demo in a quieter corner |
| Wrong name whispered confidently | **High** | Confidence gate, §5.3. Degrade to "someone new" then to earcon. |
| 400ms gaps are rare in real speech, so Cue says nothing | **High** | Spike B. Fall back to end-of-turn naming and say so. |
| Team builds the meeting summariser because the fork makes it easy | **High** | It is in Non-goals for a reason. Watch for this at hour 20. |
| Ambient events (Tier 0.5) turn Cue into the narrating product | Medium | Hard rate limit, earcon-only, suppressed during speech. Treat as correctness. |
| A Cue command clobbers an in-flight operation | Medium | Acquire a lease or permit from `GlassesSessionCoordinator` for every command. No exceptions. |
| Taking a photo interrupts the live mic stream | Medium, unmeasured | Spike C. Define the deaf window, cover it with the working earcon. |
| SDK capability classes exist but CY-01 does not support them | Medium | The AAR is shared with a smartwatch line — it contains heart rate, sleep, and menstruation commands. Class existence is not device support. Query `GlassesTouchSupportRsp` at connect. |
| Battery 270mAh, multimedia suspended below 10 percent | Medium | **Charge to 100 percent before demoing.** Real session length is short — treat it as a product constraint in §1, not just a demo risk. Show battery in a dev overlay. |
| Glasses power off 3 minutes after BLE disconnect | Medium | Reconnect watchdog with a hard 3 minute budget, plus the lost-glasses earcon |
| Vendor assistant competes for the mic | Low, now | Subscribe to `GlassesAiVoiceRsp` and yield (§7.4). Never use a wake word. |
| Single pair of glasses bottlenecks team QA | Medium | Schedule hardware time explicitly. Everything phone-side develops without it. |
| `MainActivity.kt` is 10,094 lines | **Low, now** | Downgraded from v2's High. Cue is a plugin and touches it twice. §7.1. |
| Untracked Compose files break the build | Medium, and it will waste an hour | Run `git status --short` and look for `??` under `ui/components/`, `ui/glasses/`, `ui/onboarding/`, `ui/plugins/` before blaming protocol code |
| Notify `0x09` code `255` mistaken for a failure | Low | It is noise. The official app sees it constantly and still completes transfers. |
| Vendor `.aar` is proprietary and the repo has no project-wide license | Low for hackathon, blocking for production | Do not redistribute. Note it on the roadmap slide. |

---

## 13. Build plan, 36 hours

**Before Hour 0.** One 30-minute conversation with one blind screen-reader user.
Ask them to rank P0-1 through P0-7. Build in their order, not this document's.

**Hours 0 to 3, spikes.** §11. Nobody writes product code until these are
answered. Rip out the Vercel relay at Hour 1.

**Hours 3 to 5, the skeleton.** Plugin registration (§7.1). Wire the empty `0x0c`
handler to speak a hardcoded string on pause press. This proves the entire input
story in two hours and is the cheapest possible win in the project.

**Hours 5 to 12, the spine.** VAD and gap detector *first*. Then mic stream,
streaming STT with diarization, passive name mapping, earcon mixer. At the end of
this block Cue names speakers out loud. P0-1 through P0-3.

**Hours 12 to 17, the arbiter.** Divergence rule, confidence gate, output
tiering. P0-4 lands here — it is a state machine over state you already have.
This is where it stops being a demo and starts feeling alive. Tune against a real
three-person conversation.

**Hours 17 to 21, buttons and the photo path.** P0-5, P0-6, P0-7.

**Hours 21 to 24, accessibility pass.** §9, screen off, no exceptions.

**Hours 24 to 30, rehearsal.** End to end at least six times with real people.
Every failure found here is one a judge does not find.

**Hours 30 to 36, buffer and pitch.** Slides, fallback path, Q and A prep on
privacy.

Ship nothing new after hour 30.

---

## 14. Demo script

Three minutes. Two volunteers, A and B. Do not hand the glasses to a judge — they
will not know the earcon vocabulary and it will read as broken.

1. **Setup, 20s.** "I'm going to have a conversation. I won't look at anything,
   and I won't touch my phone." Put the glasses on, session starts on its own.
   Phone face down, screen off, **and it stays there.**
2. **Roll call, 25s.** A and B introduce themselves to each other, not to the
   device. No command, no tap. The roster builds itself out of an ordinary human
   ritual. Point out that nothing was triggered.
3. **Conversation, 45s.** A and B talk. Each speaker change gets a whisper with
   the name. Mirror the glasses audio through a laptop speaker so the room hears
   what the user hears.
4. **The hero moment, 30s.** The presenter is mid-sentence, addressing A. **A
   silently walks away.** The doubled departure earcon fires while the presenter
   is still talking, and they stop and turn. Say to the room: *this is the thing
   every blind person I spoke to described, and it is the thing no product does.*
   **This is where the demo lands.** It is also the only moment in the product
   where interrupting is correct, and explaining why shows you understood the
   design problem.
5. **Entrance, 15s.** A third person walks in and speaks: earcon, then name.
   **Press pause.** Correct roster, spoken.
6. **Visual question, 25s.** B says "what do you think of this number here?"
   holding a printed chart. **Press the AI button.** The answer references both
   the image and what B just said.
7. **Close, 20s.** Zero-interruptions metric. Privacy posture, demonstrated by
   taking the glasses off and having the roster clear. One line of roadmap.

**What sells it:** the phone is face down and untouched for three straight
minutes, every interaction happens on the temple of a pair of sunglasses, and the
single loudest moment in the demo is the product *interrupting on purpose* after
three minutes of disciplined silence. That contrast is the argument, and it is
audible even though the product is not visual.

---

## 15. Metrics

| Metric | Target |
|---|---|
| Speaker attribution accuracy, 3 speakers | ≥ 85 percent of turns |
| **Incorrect names spoken above confidence threshold** | **0** |
| Whisper interruptions (speaking over a human) | **0** in a 3 minute session |
| Addressee departure to earcon | ≤ 8s |
| Turn start to name whisper, p50 | ≤ 800ms |
| Roll call to full roster | ≤ 30s, zero taps |
| Phone interactions during the demo | **0** |
| TalkBack-only task completion, screen off | 100 percent of primary flows |

The zero-interruptions metric is the one for the slide. It is counterintuitive, it
shows you understood the actual problem, and it is measurable live.

But pair it with the zero-wrong-names metric, or a cynical reader will notice that
a system which says nothing scores perfectly on the first one.

---

## 16. Open questions

1. **Does the ENC mic array capture non-wearers usably?** Blocks all of P0.
   Spike A. This is the only question that can end the project.
2. **Does SCO force 8 kHz mono output for the whole session?** Sets the earcon
   design constraint. Spike A.
3. **How frequent are 400ms+ gaps in real three-person speech?** Sets the whisper
   threshold and may force end-of-turn naming. Spike B.
4. **Do `0x0c` and `0x12` fire on this unit?** Blocks P0-5 and P0-7, both of which
   have a volume-key fallback. Spike C.
5. **Does `GlassesTouchSupportRsp` report wear-check support on CY-01?** Decides
   whether §5.5's lifecycle is wear-based or connection-based.
6. **Does a photo interrupt the live mic?** Defines the deaf window in §7.5.
   Spike C.
7. **Thumbnail quality at max setting.** Blocks P0-6 scope. Spike D. The only
   documented control is a coarse 0 to 6 setting; full-resolution options are
   undocumented.
8. **Can volume be set by the app, or only read?** `VolumeControlResponse` reads
   cleanly. Writing is unconfirmed. Affects whether Cue can duck its own output.

Not blocking, but worth knowing: glasses storage capacity, battery life under
continuous use, media sync speed, recording length limits, and firmware update
availability (the vendor server currently reports no update for this hardware, so
firmware fixes cannot be assumed).

---

## Appendix: name

**Cue** carries both meanings at once: the social cue the user is missing, and the
audio cue that delivers it. Alternatives if it collides: **Aside**,
**Peripheral**, **Roomtone**.

---

## Appendix: what changed from v2

| Area | v2 | v3 | Why |
|---|---|---|---|
| Framing | Interruption budget | Synchronization, with interruption budget as the cost side of a two-sided rule (§2) | Gives a decision procedure for "should I speak," and lets the product address surroundings without becoming a narrator |
| Codebase | Fork `MainActivity.kt`, listed as highest risk | New plugin, two edits to `MainActivity` | The repo has a plugin system and `plugins/walkingaid/` is a working template |
| Mic risk | Listed as Medium under "HFP too narrowband"; mic placement called the best hardware fit | Critical, top of the risk table, and the hardware-fit claim is withheld pending Spike A | ENC beamforming is designed to suppress exactly the people Cue must hear |
| Input events | "Received and ignored", inferred | Verified byte-level: `0x0c` is an empty `//to do`, `0x12` decodes fully then Toasts | Read from source |
| Input channels | One (BLE notify) | Two (BLE notify + `MusicCommandRsp` touchpad) | `MusicCommandListener` is referenced zero times in the app |
| Hero demo | Speaker identification | Addressee-departure detection (P0-4) | Higher divergence × consequence, unserved by any product, and it is the §1 story users actually tell |
| Confidence | Not addressed | §5.3 gate, and a zero-wrong-names metric | Diarizers revise labels retroactively and the user cannot check |
| Wearer | Not addressed | Excluded from roster at session start | The user talks constantly and the mic favours them |
| Ambient non-speech | Absent | Tier 0.5, P1-1, on-device, hard rate-limited | This is what "context of what's happening around" adds beyond conversation |
| Vendor assistant | Avoid | Detect and yield, via `GlassesAiVoiceRsp` | The event exists and nothing used it |
| Blind user in plan | Zero hours | Before Hour 0, and P0 ordering deferred to them | Largest credibility gap in v2 |
| Gap threshold | 400ms, asserted | 400ms, pending Spike B | An unmeasured threshold can silently zero out the product |
