# FRIDAY — Setup (v0.7)

## Product decisions
- **AI engines:** on-device LiteRT-LM on the phone (default) or the user's own Windows PC running Ollama through the FRIDAY worker. No cloud provider is connected.
- **Interaction:** text chat, push-to-talk or continuous voice conversation, English and Telugu (selected manually; never auto-switched).
- **Voice output:** Kokoro neural voice (offline, English) and the Android system voice for Telugu or as English fallback.
- **Memory:** conversation history in a private SQLite database; personal profile and worker token encrypted with Android Keystore.

## Build and install
1. GitHub → Actions → **Build FRIDAY Android APK** → run on your branch (or push).
2. Download artifact `friday-debug-apk` and install `app-debug.apk` (allow installs from your browser/files app).
3. Local build: install JDK 17 + Android SDK 35, download `sherpa-onnx-2.0.1.aar` into `app/libs/` (see the workflow), then `gradle assembleDebug` (or `.\gradlew.bat` from Android Studio). Install with `adb install -r app/build/outputs/apk/debug/app-debug.apk`.

## First run
1. Open **Settings → AI models**, pick a model and tap **Download selected model** (Wi-Fi, ~0.6–2.3 GB).
2. Open **Settings → Voice** and tap **Download Kokoro voice** for the offline English neural voice.
3. For Telugu: Android Settings → *Text-to-speech* → install Telugu voice data; Android *voice typing* → add Telugu (offline pack recommended). Settings → Language shows a checklist and shortcuts.
4. Tap the microphone and grant microphone permission. Try:
   - “Hey Friday, how are you?”
   - “Switch to Telugu.” / “ఇప్పుడు తెలుగు లో మాట్లాడు.”
   - “Change personality to JARVIS.” · “Set temperature to 0.7.” · “Repeat that.” · “Stop speaking.” · “Summarize this conversation.”

## Voice modes
- **Push to talk** — tap the mic per request (tap again to finish early).
- **Continuous** — after FRIDAY speaks it listens again; it pauses after a silent turn to protect battery.
- **Text only** — microphone and spoken replies are off.
- **Wake word** — not available (no on-device hotword engine). You can still begin requests with “Hey Friday”.

## Windows worker
See `windows-worker/README.md`. In FRIDAY: Settings → Connectivity → address + pairing token → **Save & test** → enable *Use Windows worker for replies*.

## Running the tests
- JVM unit tests: `gradle testDebugUnitTest` (voice pipeline, command routing, configuration, personalities, Markdown, worker client).
- Worker: `cd windows-worker && python -m pytest -q tests`.
- Phone ↔ worker end-to-end: `windows-worker/tests/run_e2e.sh`.
- Compose UI tests on a connected phone or emulator: `gradle connectedDebugAndroidTest`; screenshots land in `/sdcard/Android/data/com.friday.app/files/ui-screenshots`.

## Privacy
- Microphone audio goes only to Android's speech recognizer; FRIDAY does not record or store audio. Whether recognition is fully offline depends on the installed recognizer and language pack (FRIDAY requests offline and prefers the on-device engine).
- With the Windows worker active, prompts and recent conversation travel to your PC over the LAN. Document and Google context are **not** shared with the PC unless you enable it.
- Model and voice downloads come from Hugging Face / GitHub over HTTPS; the default Qwen3 model is SHA-256 verified.
