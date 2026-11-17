"""FRIDAY Windows worker.

A small authenticated LAN service that lets the FRIDAY Android app run prompts on a local
Ollama model on this PC (for example an RTX 3060 / 8 GB VRAM). Nothing is sent to a cloud
provider: the only outbound call is to Ollama on 127.0.0.1.

Endpoints (all JSON; every response carries X-Request-ID):
  GET  /ping          unauthenticated liveness probe (no model or system details)
  GET  /health        authenticated status: Ollama reachability, default model, availability
  GET  /v1/models     authenticated list of installed Ollama models
  POST /v1/chat       authenticated chat; stream=true returns NDJSON events
"""
from __future__ import annotations

import asyncio
import json
import logging
import os
import secrets
import time
import uuid
from pathlib import Path
from typing import AsyncIterator, Literal, Optional

import httpx
from fastapi import Depends, FastAPI, Header, Request
from fastapi.responses import JSONResponse, StreamingResponse
from pydantic import BaseModel, Field, ValidationError, field_validator

WORKER_VERSION = "2.0.0"
log = logging.getLogger("friday.worker")


# --------------------------------------------------------------------------- settings
class Settings(BaseModel):
    ollama_url: str = "http://127.0.0.1:11434"
    default_model: str = "qwen2.5:3b"
    token: str
    max_context: int = 8192
    keep_alive: str = "10m"
    request_timeout_s: float = 300.0
    max_queue: int = 2

    @staticmethod
    def from_env() -> "Settings":
        return Settings(
            ollama_url=os.getenv("OLLAMA_URL", "http://127.0.0.1:11434"),
            default_model=os.getenv("FRIDAY_MODEL", "qwen2.5:3b"),
            token=load_or_create_token(),
            max_context=int(os.getenv("FRIDAY_MAX_CONTEXT", "8192")),
            keep_alive=os.getenv("FRIDAY_KEEP_ALIVE", "10m"),
        )


def load_or_create_token() -> str:
    """Pairing token: FRIDAY_TOKEN env var, else a random token persisted next to this file."""
    env = os.getenv("FRIDAY_TOKEN", "").strip()
    if env:
        return env
    path = Path(os.getenv("FRIDAY_TOKEN_FILE", Path(__file__).with_name("friday_token.txt")))
    if path.exists() and path.read_text(encoding="utf-8").strip():
        return path.read_text(encoding="utf-8").strip()
    token = secrets.token_urlsafe(24)
    path.write_text(token, encoding="utf-8")
    return token


# --------------------------------------------------------------------------- schemas
class ChatMessage(BaseModel):
    role: Literal["user", "assistant"]
    content: str = Field(min_length=1, max_length=16000)


class GenerationOptions(BaseModel):
    temperature: Optional[float] = Field(default=None, ge=0.0, le=2.0)
    top_p: Optional[float] = Field(default=None, gt=0.0, le=1.0)
    top_k: Optional[int] = Field(default=None, ge=1, le=200)
    num_predict: Optional[int] = Field(default=None, ge=16, le=8192)
    num_ctx: Optional[int] = Field(default=None, ge=512, le=131072)


class ChatRequest(BaseModel):
    request_id: Optional[str] = Field(default=None, max_length=64)
    model: Optional[str] = Field(default=None, max_length=128)
    system: Optional[str] = Field(default=None, max_length=12000)
    messages: list[ChatMessage] = Field(default_factory=list, max_length=64)
    # Backwards compatibility with the v0.1 worker: {"message": "..."}
    message: Optional[str] = Field(default=None, max_length=16000)
    options: GenerationOptions = Field(default_factory=GenerationOptions)
    stream: bool = False

    @field_validator("messages")
    @classmethod
    def last_must_be_user(cls, v: list[ChatMessage]) -> list[ChatMessage]:
        if v and v[-1].role != "user":
            raise ValueError("the last message must come from the user")
        return v


class WorkerError(Exception):
    def __init__(self, status: int, code: str, message: str):
        super().__init__(message)
        self.status, self.code, self.message = status, code, message


# --------------------------------------------------------------------------- app factory
def create_app(settings: Optional[Settings] = None, transport: Optional[httpx.AsyncBaseTransport] = None) -> FastAPI:
    settings = settings or Settings.from_env()
    app = FastAPI(title="FRIDAY Local Worker", version=WORKER_VERSION)
    app.state.settings = settings
    gpu_lock = asyncio.Semaphore(1)  # one generation at a time: an 8 GB GPU cannot share well
    state = {"waiting": 0, "busy": False, "models_cache": (0.0, None)}

    def client(timeout: float) -> httpx.AsyncClient:
        return httpx.AsyncClient(base_url=settings.ollama_url, timeout=timeout, transport=transport)

    @app.middleware("http")
    async def request_id_middleware(request: Request, call_next):
        rid = request.headers.get("x-request-id") or uuid.uuid4().hex[:12]
        request.state.request_id = rid[:64]
        started = time.perf_counter()
        response = await call_next(request)
        response.headers["X-Request-ID"] = request.state.request_id
        log.info("%s %s -> %s rid=%s %.0fms", request.method, request.url.path, response.status_code,
                 request.state.request_id, (time.perf_counter() - started) * 1000)
        return response

    @app.exception_handler(WorkerError)
    async def worker_error_handler(request: Request, exc: WorkerError):
        log.warning("error code=%s rid=%s: %s", exc.code, getattr(request.state, "request_id", "-"), exc.message)
        return JSONResponse(status_code=exc.status, content={
            "error": {"code": exc.code, "message": exc.message},
            "request_id": getattr(request.state, "request_id", None)})

    def require_token(authorization: str = Header(default="")) -> None:
        supplied = authorization[7:].strip() if authorization.lower().startswith("bearer ") else ""
        if not supplied or not secrets.compare_digest(supplied, settings.token):
            raise WorkerError(401, "unauthorized", "Missing or invalid pairing token.")

    async def installed_models(force: bool = False) -> list[dict]:
        fetched_at, cached = state["models_cache"]
        if cached is not None and not force and time.monotonic() - fetched_at < 15:
            return cached
        try:
            async with client(5) as c:
                r = await c.get("/api/tags")
                r.raise_for_status()
                models = r.json().get("models", [])
        except httpx.HTTPError as exc:
            raise WorkerError(503, "ollama_unavailable", f"Ollama is not reachable at {settings.ollama_url}. Start Ollama and retry. ({type(exc).__name__})")
        state["models_cache"] = (time.monotonic(), models)
        return models

    def has_model(models: list[dict], name: str) -> bool:
        names = {m.get("name", "") for m in models} | {m.get("model", "") for m in models}
        return name in names or f"{name}:latest" in names

    @app.get("/ping")
    async def ping():
        return {"service": "friday-worker", "version": WORKER_VERSION}

    @app.get("/health", dependencies=[Depends(require_token)])
    async def health():
        try:
            models = await installed_models(force=True)
            ollama, available = "ok", has_model(models, settings.default_model)
        except WorkerError:
            models, ollama, available = [], "unavailable", False
        return {
            "status": "ok" if ollama == "ok" and available else "degraded",
            "worker_version": WORKER_VERSION,
            "ollama": ollama,
            "default_model": settings.default_model,
            "model_available": available,
            "models": [m.get("name") for m in models],
            "busy": state["busy"],
            "max_context": settings.max_context,
        }

    @app.get("/v1/models", dependencies=[Depends(require_token)])
    async def list_models():
        models = await installed_models()
        return {"default_model": settings.default_model, "max_context": settings.max_context, "models": [
            {"name": m.get("name"), "size_bytes": m.get("size"),
             "family": (m.get("details") or {}).get("family"),
             "parameter_size": (m.get("details") or {}).get("parameter_size"),
             "quantization": (m.get("details") or {}).get("quantization_level"),
             "supports": ["temperature", "top_p", "top_k", "num_predict", "num_ctx", "stream"]}
            for m in models]}

    @app.post("/v1/chat", dependencies=[Depends(require_token)])
    async def chat(request: Request):
        try:
            payload = ChatRequest.model_validate(await request.json())
        except (ValidationError, json.JSONDecodeError, ValueError) as exc:
            raise WorkerError(422, "invalid_request", str(exc)[:500])
        rid = payload.request_id or request.state.request_id
        request.state.request_id = rid
        messages = [m.model_dump() for m in payload.messages]
        if not messages and payload.message:
            messages = [{"role": "user", "content": payload.message}]
        if not messages:
            raise WorkerError(422, "invalid_request", "messages must contain at least one user message")
        model = payload.model or settings.default_model
        if not has_model(await installed_models(), model):
            raise WorkerError(404, "model_unavailable", f"Model '{model}' is not installed in Ollama. Run: ollama pull {model}")

        options = {k: v for k, v in payload.options.model_dump().items() if v is not None}
        if "num_ctx" in options and options["num_ctx"] > settings.max_context:
            options["num_ctx"] = settings.max_context
        system = payload.system or "You are FRIDAY, a helpful private personal AI assistant. Be accurate and concise."
        body = {"model": model, "stream": payload.stream, "keep_alive": settings.keep_alive, "options": options,
                "messages": [{"role": "system", "content": system}] + messages}
        prompt_chars = sum(len(m["content"]) for m in messages)
        log.info("chat rid=%s model=%s messages=%d prompt_chars=%d stream=%s options=%s",
                 rid, model, len(messages), prompt_chars, payload.stream, options)

        if state["waiting"] >= settings.max_queue:
            raise WorkerError(429, "busy", "The worker is busy with other requests. Try again shortly.")

        if not payload.stream:
            async with _slot():
                data = await _complete(body)
            return {"request_id": rid, "reply": data.get("message", {}).get("content", ""), "model": model,
                    "privacy": "local", "applied_options": options, "usage": _usage(data)}

        async def events() -> AsyncIterator[bytes]:
            def line(obj: dict) -> bytes:
                return (json.dumps(obj, ensure_ascii=False) + "\n").encode("utf-8")
            async with _slot():
                yield line({"type": "start", "request_id": rid, "model": model, "applied_options": options})
                try:
                    async with client(settings.request_timeout_s) as c:
                        async with c.stream("POST", "/api/chat", json=body) as r:
                            if r.status_code >= 400:
                                text = (await r.aread()).decode("utf-8", "replace")[:300]
                                yield line({"type": "error", "code": "upstream_error", "message": f"Ollama error {r.status_code}: {text}"})
                                return
                            async for raw in r.aiter_lines():
                                if not raw.strip():
                                    continue
                                chunk = json.loads(raw)
                                if chunk.get("error"):
                                    yield line({"type": "error", "code": "upstream_error", "message": str(chunk["error"])[:300]})
                                    return
                                delta = chunk.get("message", {}).get("content", "")
                                if delta:
                                    yield line({"type": "delta", "text": delta})
                                if chunk.get("done"):
                                    yield line({"type": "done", "usage": _usage(chunk)})
                                    return
                    yield line({"type": "done", "usage": {}})
                except httpx.TimeoutException:
                    yield line({"type": "error", "code": "timeout", "message": "The local model timed out."})
                except httpx.HTTPError as exc:
                    yield line({"type": "error", "code": "ollama_unavailable", "message": f"Cannot reach Ollama ({type(exc).__name__})."})

        return StreamingResponse(events(), media_type="application/x-ndjson", headers={"X-Request-ID": rid})

    class _slot:
        """Queue position + GPU lock, with busy/waiting bookkeeping for /health."""
        async def __aenter__(self):
            state["waiting"] += 1
            await gpu_lock.acquire()
            state["waiting"] -= 1
            state["busy"] = True

        async def __aexit__(self, *exc):
            state["busy"] = False
            gpu_lock.release()

    async def _complete(body: dict) -> dict:
        try:
            async with client(settings.request_timeout_s) as c:
                r = await c.post("/api/chat", json=body)
                r.raise_for_status()
                return r.json()
        except httpx.TimeoutException:
            raise WorkerError(504, "timeout", "The local model timed out.")
        except httpx.HTTPStatusError as exc:
            raise WorkerError(502, "upstream_error", f"Ollama error {exc.response.status_code}: {exc.response.text[:300]}")
        except httpx.HTTPError as exc:
            raise WorkerError(503, "ollama_unavailable", f"Cannot reach Ollama ({type(exc).__name__}).")

    return app


def _usage(data: dict) -> dict:
    return {"prompt_tokens": data.get("prompt_eval_count"), "completion_tokens": data.get("eval_count"),
            "total_duration_ms": int(data["total_duration"] / 1e6) if data.get("total_duration") else None}


def _create_default_app() -> FastAPI:
    logging.basicConfig(level=os.getenv("FRIDAY_LOG_LEVEL", "INFO"), format="%(asctime)s %(levelname)s %(name)s: %(message)s")
    settings = Settings.from_env()
    print("=" * 64)
    print(" FRIDAY worker", WORKER_VERSION, "| default model:", settings.default_model)
    print(" Pairing token (enter in FRIDAY > Settings > Connectivity):")
    print("   ", settings.token)
    print(" Keep this token private. Delete friday_token.txt to rotate it.")
    print("=" * 64)
    return create_app(settings)


# `uvicorn app:app` entry point. Tests import create_app() directly instead.
app = _create_default_app() if os.getenv("FRIDAY_NO_DEFAULT_APP") != "1" else None
