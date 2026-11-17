# FRIDAY Unified AI Creative Studio — Architecture Decision Record

Date: 2026-11-17

## Existing repository assessment

The existing product is a native Android application, not a web application:

- Android: Kotlin, Jetpack Compose, Android SDK 35, min SDK 28.
- On-device LLM: LiteRT-LM Android runtime, with downloadable model artifacts.
- Voice: Kokoro offline neural TTS and Android speech recognition integration.
- Local persistence: app-private SQLite-backed conversation/document stores.
- Windows worker: Python 3 + FastAPI, currently used as a local Ollama bridge.
- Existing UX: a single FRIDAY assistant surface with model setup, camera, voice, documents, personalization and settings.

The Windows worker is a separate local process. The Android app and worker do not yet have a complete authenticated pairing/transport implementation.

## Architecture decision

Keep Android as the primary product and preserve on-device chat. Extend the Windows worker as a local AI orchestration service.

~~~text
Galaxy S25 Ultra
  Native Android / Jetpack Compose
  - On-device LiteRT-LM chat
  - Kokoro voice
  - Camera and local document features
  - Creative Studio screens (next integration milestone)
             |
             | Explicitly configured and authenticated connection
             v
Windows FRIDAY Worker
  FastAPI
  - Provider registry and model discovery
  - Prompt/personality orchestration
  - Generation job manager
  - SQLite job/history store
  - Usage and media metadata
             |
       +-----+------+
       |            |
       v            v
    Ollama        ComfyUI
    local LLM     image/video workflows
       |            |
       +------ local media storage ------+
~~~

No cloud provider is enabled by default. Future cloud providers must be individually configured and require per-request user approval. Do not silently fall back from a selected local model to a cloud model.

## Current implementation slice

The feat/creative-studio-foundation branch adds:

- Ollama provider adapter and local model discovery.
- Configurable ComfyUI base URL.
- API-format workflow loading from a configured directory.
- Workflow-specific input capability inspection using ComfyUI /object_info.
- Async image, image-edit, text-to-video and image-to-video job endpoints.
- SQLite job metadata and restart interruption handling.
- Job polling, SSE updates, best-effort cancellation and generated-media retrieval.
- Optional bearer token; worker refuses non-loopback binding without a configured token.
- Python compile and unit-test GitHub Actions workflow.

The Android client is not yet connected to these endpoints. Do not describe the feature as fully integrated until the Android pairing, UI, transport security and on-device testing milestones are complete.

## Dependencies

### Existing Android dependencies

Keep the current Compose, Lifecycle, Kotlin Coroutines, LiteRT-LM, CameraX and ML Kit stack. Add Android networking/media dependencies only when the client integration design is finalized. Do not add a large web stack or server database to the APK.

### Windows worker dependencies

Runtime:
- FastAPI
- Uvicorn
- HTTPX
- Pydantic v2

Development:
- pytest

ComfyUI remains its own Python environment and dependency tree. Do not install ComfyUI packages into the FRIDAY worker environment.

## Hardware planning

Hardware requirements are workflow- and quantization-dependent. The ComfyUI documentation supports NVIDIA, AMD, Intel, Apple Silicon and CPU execution, but GPU acceleration is strongly preferred for practical creative generation.

Suggested planning tiers (not hard provider guarantees):

| Workload | Practical starting point | Recommended |
|---|---|---|
| FRIDAY on-device chat/voice | Existing Galaxy S25 Ultra | Existing device; select models that fit available mobile memory |
| Basic image generation (SD 1.5 / similar) | 6–8 GB VRAM | 8–12 GB VRAM |
| FLUX-family image workflows | Quantized/offloaded workflow; verify exact graph | 16–24 GB VRAM |
| Wan 2.2 TI2V 5B | ComfyUI native offloading can fit around 8 GB VRAM per official ComfyUI guidance | 12–16 GB VRAM for more comfortable experimentation |
| Wan 2.2 14B video workflows | High-memory GPU or aggressive quantization/offloading | 24–48 GB+ VRAM depending on workflow, frames and resolution |

Windows worker recommendation: 32 GB system RAM as a practical floor for experimentation, 64 GB for heavier video workflows, NVMe storage, and 100–300 GB free disk if keeping multiple model families. GPU VRAM and workflow quantization are the primary constraints. Generation speed, thermal limits and electricity use must be measured on the actual host.

The official ComfyUI Wan 2.2 guide documents a 5B hybrid workflow that can fit around 8 GB VRAM with native offloading. This must not be generalized to the 14B T2V/I2V variants or longer/higher-resolution videos.

## Security decisions

- Keep the worker bound to loopback by default.
- Require a bearer token before binding to a LAN interface.
- Do not expose ComfyUI or the worker directly to the public internet.
- The Android-to-PC transport must use authenticated encryption. Do not weaken Android cleartext policy globally just to make LAN HTTP work.
- Add pairing, token rotation, replay protection, file validation, storage quotas and explicit consent before production use.
- Cloud provider keys must be encrypted at rest and cloud use must be visibly approved.

## Implementation plan

1. Worker foundation (current branch): provider adapters, ComfyUI workflow execution, job records, status, output retrieval and tests.
2. Secure pairing: establish Android-to-Windows pairing, encrypted transport, token storage/rotation and worker connection diagnostics.
3. Android navigation/UI: add AI Chat, Image Studio, Video Studio, Model Library, Prompt Library, History, Usage and Settings without replacing the current chat.
4. Image workflows: ship and test API-format workflows for an initial image model, then editing/inpainting/upscaling.
5. Video workflows: add Wan 2.2 5B first where host hardware allows, then optional 14B workflows.
6. Prompt/personality library: persist reusable profiles and model-aware controls locally.
7. Usage and cost accounting: track provider, execution time, model, output bytes, failure and estimated local/cloud costs.
8. Hardening: upload validation, safe media handling, consent/moderation, retention/export/delete controls, API and UI tests.
9. Device validation: build APK, connect a real Windows host and ComfyUI, test on Galaxy S25 Ultra, verify latency, thermal behavior and end-to-end output.

## Required inputs before selecting final model packs

Record the Windows host GPU model and VRAM, system RAM, free NVMe space, Windows version, and whether ComfyUI is already installed. Choose workflows only after this inventory; avoid downloading large model weights before confirming hardware fit.
