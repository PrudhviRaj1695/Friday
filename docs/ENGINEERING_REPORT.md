# FRIDAY v0.7 — Engineering Report

## 1. Architecture

```
Android (Kotlin/Compose)
  MainActivity ─ permissions, lifecycle, navigation
  ui/ HomeScreen · SettingsScreen (9 categories) · VoiceOrb · MarkdownText  ← FridayActions interface
  FridayViewModel ─ state, routing, persistence
     │
     ├─ core/VoiceCoordinator + VoiceStateMachine      (IDLE/LISTENING/PROCESSING/SPEAKING/INTERRUPTED/ERROR)
     │     ├─ voice/AndroidSpeechRecognizer  (mic + STT, en-US / te-IN)
     │     └─ voice/AndroidSpeechSynthesizer (Kokoro EN, pipelined · SystemTts TE/fallback · audio focus)
     ├─ core/CommandRouter  ─ deterministic commands (EN + TE) never reach the LLM
     ├─ core/GenerationSettings + Validator + AiPreset, core/Personality(+Repository), core/PromptComposer
     ├─ ON_DEVICE: LiteRT-LM Engine/Conversation (SamplerConfig, maxOutputToken, maxNumTokens)
     └─ WINDOWS_WORKER: worker/WorkerClient ── HTTP+Bearer, NDJSON stream ──▶ windows-worker/app.py ──▶ Ollama (127.0.0.1)
```

Flow: Microphone → AndroidSpeechRecognizer → VoiceCoordinator(PROCESSING) → CommandRouter →
(command → fixed localized reply) | (conversation → LiteRT-LM or Worker → streamed reply) →
VoiceCoordinator.speak → SpeechText (Markdown → speakable, sentence chunks) → Kokoro / System TTS → AudioTrack.

## 2. Original state (before this change)

* Single-module Compose app; `MainActivity.kt` (800 lines) held all UI and the speech recognizer.
* On-device LLM only. **The Windows worker existed but the Android app had no client for it**, and
  `usesCleartextTraffic="false"` would have blocked it anyway. Worker had no auth.
* Voice output: Kokoro only (system TTS removed); synthesized the *whole* answer before playing.
* "Creativity" slider only changed system-prompt wording; no real sampling controls.
* Personalities: 4 hard-coded presets + one custom slot.

## 3. Bugs found

| # | Severity | Bug |
|---|---|---|
| 1 | Critical | `EXTRA_LANGUAGE` passed a `java.util.Locale` object; recognizers read a String, so the language was never applied (Telugu input impossible). |
| 2 | Critical | On-device recognizer chosen whenever "available", with no fallback when its language pack is missing → permanent `SPEECH ERROR • 12/13`. |
| 3 | Critical | Telugu replies could not be spoken (Kokoro model is English-only; system TTS had been removed). |
| 4 | High | Without the Kokoro download, voice output silently did nothing except a status line in Settings. |
| 5 | High | No voice state machine: mic could start while TTS was playing (FRIDAY hears itself), no watchdog for recognizers that never call back, no protection against stale callbacks. |
| 6 | High | Mic disabled during generation but not during speech; no barge-in or stop-and-listen. |
| 7 | High | Every utterance went to the LLM, including "stop speaking", "switch to Telugu" etc. |
| 8 | High | Android↔Windows integration missing (no client, cleartext blocked, no auth). |
| 9 | Medium | Long answers: whole-text Kokoro synthesis → long silence before speech. |
| 10 | Medium | No audio focus: speech continued over calls/media. |
| 11 | Medium | Diagnostics called `ConnectivityManager.activeNetwork` without `ACCESS_NETWORK_STATE` → SecurityException. |
| 12 | Medium | No MaterialTheme: dialogs/text fields used default light-purple Material colors; launch theme was Light (white flash). |
| 13 | Medium | Markdown was rendered by stripping `**`; code blocks/tables/lists unreadable; no copy or selection. |
| 14 | Low | Telugu text clipped at tight line heights (Compose disables font padding by default). |
| 15 | Low | Recognizer kept open when the app went to background. |

**Root cause of the voice failure:** #1 + #2 broke speech input (wrong extra type and no fallback from
the on-device recognizer), and #3/#4 meant replies were often not spoken; #5–#7 made the remaining
path unreliable.

## 4. Changes

### Voice (PASS on JVM tests; device validation pending)
* `VoiceStateMachine` with an explicit transition table; duplicate starts rejected.
* `VoiceCoordinator`: debounced mic (rapid clicks → one session), session tokens drop stale callbacks,
  15 s watchdog, permission flow, barge-in, interrupt, pause/resume (Kokoro), background → mic closed,
  continuous mode that pauses after a silent turn. Fully unit-tested with fake STT/TTS.
* `AndroidSpeechRecognizer`: BCP-47 string language, fresh recognizer per session, on-device →
  standard fallback on 11/12/13/CLIENT/SERVER, silence-length extras, RMS level for the UI.
* `AndroidSpeechSynthesizer`: Kokoro sentence pipeline (synthesize N+1 while N plays), Android system
  TTS for Telugu (offline voices preferred), engine preference Auto/Kokoro/System, speed + pitch,
  transient audio focus, callbacks marshalled to the main thread, clear messages when a voice is missing.
* `CommandRouter`: deterministic EN/TE commands — language, personality, temperature, top-p, max
  tokens, voice mode, engine, stop, repeat, summarize, status; out-of-range values rejected with a reason.
* Wake word: **not implemented** and shown as unavailable in Settings (no on-device hotword engine).

### Android ↔ Windows
* Worker v2: Bearer pairing token (generated, persisted, constant-time compare), `/ping` `/health`
  `/v1/models` `/v1/chat`, options (temperature/top_p/top_k/num_predict/num_ctx) validated and clamped,
  NDJSON streaming, request-id correlation, single-GPU serialization with bounded queue (429),
  structured error codes, logs without prompt text or token, backward compatible `{"message": ...}`.
* `WorkerClient`: timeouts, typed errors → `Connected / Connecting / Worker unavailable / Model unavailable /
  Pairing token rejected`, cancellation, streaming. Health polling with exponential backoff only while
  the worker backend is active and the app is in the foreground.
* Plain HTTP restricted in code to private/link-local/Tailscale addresses (`LanAddressPolicy`); token stored with Android Keystore.
* Document/Google context is **not** sent to the PC unless the user enables it.

### LLM configuration
* Real temperature / top-p / top-k / max output tokens / context length / streaming / system prompt,
  applied via LiteRT-LM `SamplerConfig`, `maxOutputToken`, `maxNumTokens` (on-device, with reload and
  fallback to the model's built-in context) and Ollama options (worker).
* `GenerationSettingsValidator` clamps to model capabilities and lists every adjustment in the UI.
* Saved presets (3 built-in + user), reset to defaults; legacy "creativity" migrated to temperature.
* Personality Studio: 6 built-ins (Friendly, JARVIS, Professional Engineer, Critical Thinking Advisor,
  Creative Director, Personal Companion) + custom create/edit/duplicate/delete/activate with name,
  description, system prompt, tone, language preference, temperature override, response length.
  The previous custom personality is migrated into an editable profile.

### UI/UX
* Design system (graphite + electric blue/cyan tokens, typography, custom line-icon set), dark launch theme.
* Command Center: identity, live status chips (connection, model, language, personality, voice mode),
  state-driven voice orb (listening rings follow mic level; thinking arcs; speaking waveform; error ring),
  live transcript banner, stop/pause controls, actionable error banner (Retry / Settings).
* Conversation: Markdown (headings, lists, quotes, aligned tables, code blocks with copy), text selection,
  copy and read-aloud per reply, streaming indicator, smart auto-scroll + jump-to-latest, Telugu-aware line height.
* Settings in 9 categories; content width capped on tablets.

## 5. Tests executed (Linux sandbox)

| Suite | Result |
|---|---|
| JVM unit tests: state machine (5), command routing EN/TE (8), voice pipeline with fakes (18), config/personalities (11), text/Markdown/LAN policy (7) | **49 passed** |
| WorkerClient vs mock server: health states, auth, streaming (Telugu), error events, truncation, model unavailable, busy, timeout, cancel (12) | **12 passed** |
| Paparazzi screenshot tests: 20 renders — phone, small phone, tablet; idle/listening/thinking/speaking/error/text-only/worker-unavailable/Telugu; all settings pages | **20 passed** (images in `app/src/test/snapshots/images`) |
| Python worker with fake Ollama (17) | **17 passed** |
| End-to-end: Android `WorkerClient` → real worker (uvicorn) → fake Ollama | **passed** |
| `assembleDebug`, `assembleDebugAndroidTest` | **built** |
| Compose instrumented UI tests (`app/src/androidTest`, 20 tests) | **compiled, NOT executed** (no emulator/KVM here; runs in the new CI `ui-tests` job or `connectedDebugAndroidTest` on a phone) |

Not executed anywhere yet (needs a real phone): actual microphone capture, real English/Telugu speech
recognition, real Kokoro/system TTS playback and audio focus, LiteRT-LM sampling on device, and the
worker on Windows with a real Ollama model.

## 6. PASS / FAIL by requirement

| Requirement | Status |
|---|---|
| Architecture inspected and documented | PASS |
| Voice pipeline root cause fixed | PASS in code + JVM tests · device verification PENDING |
| Voice state machine, modes (PTT / continuous / text-only) | PASS (JVM) |
| Wake word | NOT IMPLEMENTED (by design, shown as unavailable) |
| Deterministic command routing EN + TE | PASS |
| Telugu STT/TTS | Implemented; depends on installed Android language packs — PENDING device test |
| Android ↔ Windows link (auth, timeouts, streaming, status, reconnection) | PASS (E2E on Linux) · Windows run PENDING |
| Real sampling controls, validation, presets | PASS |
| Personality Studio | PASS |
| Premium UI redesign | PASS (rendered and reviewed via screenshots) |
| Instrumented UI tests | WRITTEN, not executed here |
| Logging without secrets/content | PASS (by review) |

## 7. Remaining limitations

* LAN traffic to the worker is HTTP + token, not TLS; use a trusted network. TLS with pinning is the next step.
* Speech recognition offline-ness depends on Google/Samsung recognizers and installed packs; FRIDAY requests offline but cannot guarantee it.
* Kokoro is English-only; Telugu speech uses the Android system voice. Kokoro speech is ~3–8 s of CPU per long sentence on mid-range phones (first sentence latency improved by pipelining).
* Small on-device models (0.6B–1.5B) produce weak Telugu; use the worker with qwen2.5:7b for Telugu.
* Pause/resume works for Kokoro only (Android TTS has no pause API).
* Debug APK is ~360 MB because native libraries for all ABIs are packaged; add ABI splits for release.
* Context-length changes on-device need a model reload; some `.litertlm` files have a fixed KV size (FRIDAY falls back and says so).

## 8. Build & install

See `PHONE_FIRST_SETUP.md` (Android) and `windows-worker/README.md` (PC). Summary:

```bash
mkdir -p app/libs && curl -L -o app/libs/sherpa-onnx-2.0.1.aar \
  https://github.com/k2-fsa/sherpa-onnx/releases/download/v2.0.1/sherpa-onnx-2.0.1.aar
gradle testDebugUnitTest assembleDebug          # JDK 17, Android SDK 35
adb install -r app/build/outputs/apk/debug/app-debug.apk
gradle connectedDebugAndroidTest                # UI tests on the connected phone
gradle recordPaparazziDebug                     # refresh reference screenshots
```
