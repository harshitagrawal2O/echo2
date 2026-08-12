# Cue — screen design prompts

Prompts to paste into a design tool, one per screen. Each is written to be used on its own, so
paste the **Foundation** block above whichever screen prompt you are working on.

## How to use these, and what they will not give you

The user of this app is blind. **The design artifact that decides whether the app works is the
screen-reader traversal order — the sequence of focus stops and how they are grouped — not the
visual layout.** No visual design tool models that, so a prompt that only asks for "an accessible
screen" returns a handsome layout that is still unusable.

Every prompt below therefore specifies the reading order first and the visuals second. The visuals
are not decoration: the persona is blind **or low vision**, and the low-vision half reads the screen
at high magnification. But if you have to choose, the reading order wins.

The acceptance test for every screen is the same and it is not negotiable: **complete the screen's
primary task with the display switched off.**

---

## Foundation

Paste this above any screen prompt.

```
You are designing one screen of an Android app called Cue. Cue gives blind and low-vision users
ambient awareness of the room: it names who is speaking, signals when someone arrives or quietly
leaves, and answers questions about what the user is looking at. It runs on smart glasses; the
phone screen is the configuration surface, not the product.

THE USER
- Blind or low vision, adult, professionally active, screen-reader fluent.
- Listens to TTS at 300 to 500 words per minute — far faster than feels comfortable to design for.
  Do not pad. Every announced word costs them time.
- Has abandoned assistive tools before because they were slow, conspicuous, or wrong.
- The low-vision half of this persona reads the screen at 200% magnification with reduced
  contrast sensitivity.

DESIGN THE READING ORDER FIRST
Before any visual layout, specify the screen-reader traversal as a numbered list of focus stops.
For each stop give the exact text announced, including its role and state, e.g.
  3. "Glasses microphone, selected, radio button"
Rules:
- Merge a label and its control into one focus stop. Never make the user hear the same words twice.
- Group related static text into a single stop rather than one stop per line.
- The primary action of the screen must be reachable within two swipes of arrival.
- State changes that matter must be announced (live region), not merely repainted.
- Every icon-only control has a text label naming what it acts on, not just what it is.
  "Settings" is wrong. "Cue settings" is right.

VISUAL REQUIREMENTS
- Body text contrast 7:1 minimum against its background; large text 4.5:1 minimum.
- Never encode state in colour alone. Selection, on/off, and error states each need a shape, an
  icon, or a word in addition to colour.
- Touch targets 48dp minimum with 8dp between them.
- Layout must survive system font scale at 200% with no clipping and no horizontal scrolling.
- Support both light and dark themes; do not assume either.

PROHIBITED
- Timed dialogs, toasts carrying information, anything that auto-dismisses.
- Carousels, swipe-only gestures, drag-to-reorder as the only path.
- Placeholder text as the only label for an input.
- Any information conveyed only by an image, colour, or position.

DELIVER
1. The numbered traversal order with exact announced strings.
2. The visual layout.
3. The empty, loading, error, and degraded states for every element that has them.
```

---

## Screen 1 — Plugin list (entry point)

**Job:** find Cue among roughly ten plugins and turn it on. This is the first screen that can fail;
if the user cannot identify which toggle belongs to Cue, nothing downstream matters.

```
Design a scrolling list of about ten "plugin" cards. Each card has a title, a one-word category
badge, a two-line description, an on/off switch, and a settings button.

The current implementation has a real defect to fix: the switch carries no label, so a screen
reader announces a bare "off, switch" and the user cannot tell which of ten plugins they are about
to enable. Every control must name its plugin.

Reduce focus stops. Ten cards at five stops each is fifty swipes to reach the last plugin. Target
three stops per card:
  1. Title, badge and description merged into one announcement.
  2. The switch, announced as "<Plugin name>, off, switch".
  3. The settings button, announced as "<Plugin name> settings, button".

Add a way to skip the list: either a filter that puts enabled plugins first, or a jump-to-letter
index. Say which you chose and why.

Unavailable plugins (hardware not present) must announce why they cannot be enabled, not merely
appear dimmed — dimming is invisible to a screen reader and ambiguous at low vision.
```

---

## Screen 2 — First-run onboarding (does not exist yet; highest value)

**Job:** teach the earcon vocabulary *as audio*, bind the user's own voice, and collect permissions
and keys. Cue's whole interaction model rests on the user recognising about ten short sounds.

```
Design a first-run flow that teaches a sound vocabulary. This is an audio lesson with a visual
accompaniment, not a visual tutorial.

Cue signals most events with "earcons" — non-speech sounds under 200 milliseconds — because a
spoken sentence during a live conversation makes the product worse. There are ten. The user learns
them in about ten minutes and then recognises them at near-zero attention cost, the same way they
already use screen-reader sounds.

The flow must be linear, resumable, and completable with the display off.

For each sound, one card: a large PLAY button (the primary action), the meaning in one short
sentence, and Previous / Next. Playing is the lesson; the text is the caption. Never present the
vocabulary as a written list — a list of descriptions of sounds teaches nothing.

Give one sound special treatment. "The person you were talking to has left" is the product's
central moment and is deliberately similar to "someone left" — two descending notes, doubled versus
single. Design a compare step that plays them alternately on demand until the user confirms they
can tell them apart. If they cannot, that is a finding, so give them a way to say so.

Other steps in the flow:
- Microphone and notification permission, each with one sentence of why, shown BEFORE the system
  dialog so the system prompt is not a surprise.
- "Teach Cue your voice": the user taps, then speaks. Confirm by sound and speech, never by a
  label change on a button they cannot see.
- Service keys — hand off to Screen 4.

Every step needs a Skip, and the flow must be re-openable later from settings.
```

---

## Screen 3 — Cue settings (exists; needs restructuring)

**Job:** answer "is it working right now", run the three eyes-free actions, and adjust behaviour.

```
Design a settings screen for an assistive listening product. Order sections by how often the user
needs them, not by logical tidiness.

1. ENABLE — a single switch.
2. STATUS — the most frequently asked question is "is it actually working". Write it as a spoken
   sentence, not indicator dots: "Listening on the glasses microphone. Speech recognition is live.
   Two people present." It must update while the screen is open and announce changes as a polite
   live region. A row of coloured dots is nothing at all to this user.
3. QUICK ACTIONS — three buttons: "Who is here", "Repeat last", "Teach Cue my voice". Each
   confirms audibly, because the user cannot see the button they just pressed.
4. SOUND VOCABULARY — a replay control for each of the ten earcons.
5. VOICE — speech rate for short whispers and for longer briefings, and sound volume. Default the
   rate FAST (1.6x). Do not tune it to what sounds comfortable to a sighted person; this user reads
   at 300 to 500 words per minute and a comfortable default makes Cue slower than their own screen
   reader.
6. LISTENING — microphone source (glasses or phone) and the silence threshold before Cue speaks,
   with the measured result shown next to it: "42 percent of pauses this session were long enough."
7. TIMING, KEYS, REHEARSAL — least frequent, last.

Sliders are a problem: a screen reader announces a slider position as a bare percentage, which is
meaningless for "400 milliseconds" or "1.6 times speed". Solve this — the announced value must be
the real value with its unit, and the visible label must not be read twice.

Two-option choices (glasses mic versus phone mic) must not be distinguished by colour alone.
```

---

## Screen 4 — Service key entry (currently the worst flow in the app)

**Job:** get two ~100-character secrets onto the phone. Typing one by screen reader is punishing,
and this is the step most likely to make someone give up.

```
Design a screen for entering two long API keys on a phone, for a blind user. Typing a
100-character random string character by character with a screen reader is the problem to solve;
treat manual entry as the last resort, not the default.

Offer, in this priority order:
1. PASTE — a large primary button, "Paste Anthropic key from clipboard". One tap.
2. SCAN — read the key from a QR code shown on a computer screen. The camera is on the phone.
   Give continuous audio guidance while aiming ("no code yet", "hold steady"), and succeed with a
   sound, not a visual flash.
3. TYPE — a normal text field, available but last.

Validation and feedback are the heart of this screen. After any entry, immediately say whether it
worked: "Anthropic key accepted" or "That does not look like a key — 8 characters pasted". Never
leave a masked field with no confirmation; the user cannot look at it to check.

Security detail that is easy to miss: DO NOT let the screen reader read the key aloud. It would be
announced in whatever room the user is standing in. Show and announce only a confirmation shape —
"accepted, ending 4f2a". The full value is never spoken.

Show clearly what each key unlocks and what still works without it: without the speech-recognition
key Cue still runs, using sounds and buttons, but cannot name people.
```

---

## Screen 5 — Live session view (does not exist yet)

**Job:** answer "what is Cue doing right now, who is here, and what did it just say" — the three
questions the user cannot answer by looking at the room.

```
Design a live status screen for a running assistive listening session, opened in one tap from the
ongoing notification.

The design problem underneath this screen: when Cue is broken it behaves identically to Cue in a
silent room. Silence is ambiguous, and a user who cannot distinguish "nobody is here" from "the
microphone died" will act on the wrong one. This screen exists to remove that ambiguity.

Content, in traversal order:
1. Health, as a sentence, in a polite live region. Degraded states must be stated plainly:
   "Names are off — no speech recognition key."
2. Who is present: a list of names, with anyone unnamed counted rather than described
   ("Sarah, Priya, and one other"). Each entry announces how long since they last spoke.
3. Last thing Cue said, with a large Replay button. Audio has no scrollback and the user will miss
   whispers constantly while attending to a human; every screen reader has a repeat command for
   exactly this reason.
4. Diagnostics, last: microphone in use, and what share of conversational pauses have been long
   enough for Cue to speak into.

Design the empty state carefully — "no one else yet" is a real and common answer, and must not look
like a loading failure.

Nothing on this screen may require sight to interpret. No waveform as the only signal, no colour
coding as the only status.
```

---

## Screen 6 — Permission priming

**Job:** explain the microphone and notification requests before Android's dialogs appear, so a
blind user is not ambushed by a system prompt with no context.

```
Design a short screen shown immediately before the Android microphone and notification permission
dialogs.

One sentence per permission, in plain language, saying what the app does with it and what happens
if it is refused:
- Microphone: "Cue listens to the conversation around you so it can tell you who is speaking. It
  keeps only the last 60 seconds, in memory, and never saves audio to your phone."
- Notifications: "Cue shows one ongoing notification while it is listening, so Android does not
  stop it. It never makes a sound."

The privacy posture is a selling point for this product, not fine print — a rolling in-memory
buffer with nothing written to disk is genuinely different from a recorder, and this is the screen
that says so.

Two buttons: Continue, and Not now. "Not now" must not be a dead end — say what still works
without the permission.

If a permission was previously denied permanently, detect it and offer a direct route to the system
settings page rather than re-requesting into a void.
```

---

## What these prompts will not cover

Three things a design tool cannot decide, which need a person:

- **Whether the earcons are distinguishable through the glasses speaker.** They are synthesised
  under 1.2 kHz so they survive an 8 kHz narrowband link, but "the doubled departure earcon reads
  as different from the single one" is a listening judgement.
- **Whether the traversal order is actually pleasant.** Reading orders look correct on paper and
  feel wrong in use. This needs thirty minutes with a screen-reader user.
- **What to cut.** Every one of these screens will come back longer than it should be. The
  strongest accessibility work on this app will be deletion.
