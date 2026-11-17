# FRIDAY Windows Worker (v2)

Runs FRIDAY's prompts on a local Ollama model on your Windows PC (e.g. RTX 3060, 8 GB VRAM) and
streams replies back to the Android app over your home Wi-Fi. No cloud provider is involved: the
worker only talks to Ollama on `127.0.0.1`.

## 1. Install Ollama and a model

Install Ollama from https://ollama.com/download, then in PowerShell:

```powershell
ollama pull qwen2.5:3b      # fast default (~2 GB VRAM)
ollama pull qwen2.5:7b      # better quality, incl. Telugu (~4.7 GB VRAM, fits 8 GB)
```

Recommended for 8 GB VRAM / 16 GB RAM: keep one model loaded at a time. In *System → Environment
variables* add `OLLAMA_MAX_LOADED_MODELS=1`, then restart Ollama.

## 2. Start the worker

Install Python 3.11+, then double-click `start-friday.bat`. On first start it creates
`friday_token.txt` and prints a **pairing token**. Keep the window open.

Optional environment variables: `FRIDAY_MODEL` (default model, `qwen2.5:3b`), `FRIDAY_MAX_CONTEXT`
(cap for `num_ctx`, default 8192), `FRIDAY_KEEP_ALIVE` (how long Ollama keeps the model loaded,
default `10m`), `FRIDAY_TOKEN` (use your own token), `FRIDAY_LOG_LEVEL`.

## 3. Allow it through the firewall

When Windows asks, allow Python on **Private networks only**. Find the PC's address with `ipconfig`
(Wi-Fi adapter → IPv4, e.g. `192.168.1.20`).

## 4. Pair the phone

FRIDAY → Settings → Connectivity → enter `192.168.1.20:8765` and the pairing token → **Save & test**.
Then turn on *Use Windows worker for replies* (or say “use the Windows worker”).

The status indicator distinguishes: *Connected*, *Connecting*, *Worker unavailable* (PC/worker not
reachable), *Model unavailable* (Ollama down or model not pulled) and *Pairing token rejected*.

## API

| Endpoint | Auth | Purpose |
|---|---|---|
| `GET /ping` | none | liveness only, no system details |
| `GET /health` | Bearer | Ollama reachability, default model, installed models, busy flag |
| `GET /v1/models` | Bearer | installed models and supported options |
| `POST /v1/chat` | Bearer | `{request_id, model?, system, messages[], options{temperature, top_p, top_k, num_predict, num_ctx}, stream}` |

With `stream: true` the reply is NDJSON: `start` → `delta`… → `done` (or `error`). Every response
carries `X-Request-ID` for correlation with the Android log. Generation is serialized (one request
at a time on the GPU); a short queue returns `429 busy` when exceeded. Errors are JSON:
`{"error": {"code": "model_unavailable" | "ollama_unavailable" | "unauthorized" | "timeout" | "busy" | "invalid_request" | "upstream_error", "message": "…"}}`.

Logs contain request ids, model names, message counts and sizes — never prompt text or the token.

## Tests

```powershell
pip install -r requirements.txt -r requirements-dev.txt
python -m pytest -q tests
```

`tests/run_e2e.sh` (Linux/macOS/Git Bash) starts a fake Ollama plus the real worker and runs the
Android client's end-to-end test against it.

## Security notes

* Every request except `/ping` requires the pairing token (constant-time comparison). Delete
  `friday_token.txt` to rotate it.
* Traffic is plain HTTP on your LAN. The Android app only allows plain HTTP to private addresses
  (192.168.x.x, 10.x.x.x, 172.16–31.x.x, Tailscale 100.64–127.x.x). Use it on a trusted home
  network only — never port-forward 8765 to the internet. TLS with certificate pinning is a
  planned follow-up.
* The worker never executes commands; it only forwards chat requests to Ollama.
