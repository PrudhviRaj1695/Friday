# FRIDAY — Phone-first build (v0.2)

## Product decisions
- **AI mode:** Hybrid architecture. On-device inference is the default. Cloud inference is disabled until a provider is configured and a per-request approval flow is implemented.
- **Interaction:** Text chat, Android speech recognition input, Android TextToSpeech output.
- **Memory:** Conversation history stored in a private SQLite database in the app sandbox.

## Current implementation
- Native Android app (Kotlin + Jetpack Compose).
- LiteRT-LM Android runtime: `com.google.ai.edge.litertlm:litertlm-android:0.18.0`.
- First model: `litert-community/Qwen3-0.6B`, file `Qwen3-0.6B.litertlm` (~586 MB).
- Model is downloaded on demand to app-private `filesDir/models`; it is not bundled in the APK.
- GPU initialization is attempted first; CPU is the fallback.
- Conversation replies stream into the UI and conversation turns persist locally.
- Android speech recognizer is asked to prefer offline recognition. Actual offline support depends on the installed speech recognition service and language pack; Android may still use a network service.
- Android TextToSpeech is used for spoken replies; offline voice availability depends on installed TTS voices.

## Build and install
1. Open GitHub Actions: https://github.com/PrudhviRaj1695/Friday/actions
2. Run **Build FRIDAY Android APK** on `main`.
3. Download artifact `friday-debug-apk` and install `app-debug.apk`.
4. Open FRIDAY and tap **Download local AI model**. Use Wi-Fi and keep at least 1.5 GB free. The model download is about 586 MB.
5. Wait for the model to initialize. Send a test prompt such as `Say hello and tell me you are running locally.`
6. Grant microphone permission when using voice input.

## Privacy and hybrid mode
- The local model and conversation database are stored inside the Android app's private sandbox.
- No cloud LLM provider is connected in this build. The cloud approval preference is only a future-policy setting; toggling it does not transmit data.
- Model download requires internet access and fetches the model file from Hugging Face.
- Speech input uses Android's system speech recognizer with an offline preference. This is not a guarantee of offline recognition. For strict offline voice, integrate a dedicated offline ASR model/runtime.
- Text-to-speech uses Android's system engine. Download an offline voice pack in Android settings if needed.
- Cloud provider credentials, per-request approval dialog, authenticated provider client, and audit log remain future work.

## Security limitations
This is an early personal prototype, not a production security boundary. Do not put secrets, financial credentials, or sensitive documents into it yet. Before adding agent actions or cloud calls, implement explicit per-action consent, encrypted backup/export policy, secure model integrity verification, and security testing.

## Next milestones
1. Verify LiteRT-LM API compile and model startup on the actual S25 Ultra.
2. Add strict offline ASR and fully offline TTS voice selection.
3. Add explicit per-message cloud consent and configure a provider (user chooses provider).
4. Add memory search, summaries, export/delete controls, and encrypted local storage.
5. Add deterministic tool registry and approval UI before any device actions.
