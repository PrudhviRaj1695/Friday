# FRIDAY Creative Studio — Local Worker Foundation

This is the first backend integration slice for the existing Android FRIDAY application. It adds real ComfyUI workflow submission, job persistence, status/events, cancellation requests, media retrieval, provider health and workflow discovery. It does not generate placeholder media.

## Architecture

- Android app remains the primary interface and on-device LiteRT-LM remains available independently.
- This worker runs on the user's Windows machine.
- Ollama is an optional local LLM provider.
- ComfyUI is the image/video execution backend.
- SQLite stores generation jobs and metadata.
- Generated media is stored under `FRIDAY_OUTPUT_DIR`.
- No cloud provider is called by this worker.

## Install

From `windows-worker`:

```powershell
py -3 -m venv .venv
.\.venv\Scripts\Activate.ps1
pip install -r requirements.txt
Copy-Item .env.example .env
```

Set `COMFYUI_URL` to the configured ComfyUI base URL. Do not assume a fixed installation path or address. Start Ollama and/or ComfyUI separately.

Start the worker:

```powershell
$env:FRIDAY_HOST="127.0.0.1"
$env:FRIDAY_PORT="8765"
uvicorn app:app --host $env:FRIDAY_HOST --port $env:FRIDAY_PORT
```

For phone-to-PC access over a trusted LAN, bind to `0.0.0.0`, set a strong `FRIDAY_API_TOKEN`, configure Windows Firewall for the private network only, and use the PC's LAN address in FRIDAY settings. Do not expose this worker to the public internet.

## Advanced local LLM controls

The local Ollama chat endpoint accepts model-specific sampling parameters:

- `temperature` (0–2), `top_p` (0–1), `top_k` (1–200)
- `repeat_penalty` (0.5–2), `seed`, `max_tokens`, and `context_window`
- `policy_mode`: `balanced`, `mature_language`, or `strict`
- `additional_instructions`: user-controlled response preferences (up to 4,000 characters)

Example request body:

```json
{
  "message": "Explain this Python code",
  "temperature": 0.7,
  "top_p": 0.9,
  "top_k": 40,
  "repeat_penalty": 1.08,
  "policy_mode": "mature_language",
  "additional_instructions": "Be direct and include a practical example."
}
```

Policy modes adjust prompt-level tone and behavior. They are not a hard moderation firewall and do not change model weights. The Android LiteRT-LM path currently exposes prompt-guided creativity/personality controls; native token sampling controls are not claimed for that runtime. Android response profiles and behavior preferences are saved locally on the device.

## ComfyUI workflows

Export workflows from ComfyUI in **API format** and place the JSON files in `FRIDAY_WORKFLOWS_DIR` (default `./workflows`). Give each workflow a stable filename such as `flux_schnell` or `wan_t2v`.

The generation request selects a workflow by name. Configure `prompt_node_id`, `prompt_input_name`, optional negative-prompt node and `input_overrides` to map prompt and generation controls to that workflow's actual node IDs. This is deliberate: FLUX, SDXL and Wan graphs have different nodes and parameters. Unsupported controls must not be sent to a workflow.

Example request:

```json
{
  "prompt": "A cinematic landscape at sunrise",
  "workflow_name": "flux_schnell",
  "prompt_node_id": "6",
  "prompt_input_name": "text",
  "input_overrides": {
    "3": {"seed": 12345, "steps": 4, "cfg": 1.0, "width": 1024, "height": 1024}
  }
}
```

The node IDs and input names above are illustrative. Use the values from your exported workflow. Inspect the exact node/input schema for a selected graph with `GET /v1/creative/workflows/{workflow_name}/capabilities`. Generation controls are supplied through `input_overrides`; only values supported by that workflow should be sent. The API intentionally does not pretend that a generic seed, CFG, steps, resolution or duration field works for every graph.

## API

All routes below are prefixed with `/v1/creative`.

- `GET /providers` — Ollama and ComfyUI availability.
- `GET /models` — ComfyUI node metadata discovery.
- `GET /workflows` — local API workflow inventory.
- `POST /images/generate` — asynchronous image generation.
- `POST /images/edit` — image-edit workflow execution.
- `POST /videos/generate` — asynchronous text-to-video.
- `POST /videos/image-to-video` — asynchronous image-to-video.
- `GET /jobs`, `GET /jobs/{job_id}` — job status/history.
- `GET /jobs/{job_id}/events` — Server-Sent Events updates.
- `POST /jobs/{job_id}/cancel` — best-effort cancellation.
- `GET /media/{filename}` — retrieve generated media.

Generation endpoints return HTTP 202 with a job ID. A job is marked completed only after ComfyUI returns output files and those files are successfully retrieved. ComfyUI does not expose a universal progress percentage for every workflow, so progress may be unknown while queue state is tracked.

## Current limitations

- A workflow must be exported in ComfyUI API format and mapped to its prompt/parameter nodes.
- Model discovery currently exposes ComfyUI node metadata, not a universal semantic catalog. Model availability is workflow-specific.
- Cancellation uses ComfyUI interrupt plus queue deletion; interrupt behavior can vary with the running node.
- The job runner is an in-process asyncio task manager backed by SQLite. A process restart marks unfinished jobs as interrupted; it does not transparently resume GPU execution.
- Authentication is optional only for loopback use. Set a token before LAN binding.
- The Android UI has not yet been wired to these endpoints in this foundation commit.
