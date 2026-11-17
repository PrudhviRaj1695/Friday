import os
from typing import Literal

import httpx
from fastapi import Depends, FastAPI, HTTPException
from pydantic import BaseModel, Field

from friday_worker.providers.ollama import OllamaProvider
from friday_worker.security import require_local_token
from friday_worker.settings import settings
from creative_studio import router as creative_router

OLLAMA_URL = settings.ollama_url
MODEL = settings.ollama_model
HOST = os.getenv("FRIDAY_HOST", "127.0.0.1")
if HOST not in ("127.0.0.1", "localhost", "::1") and not settings.api_token:
    raise RuntimeError("Set FRIDAY_API_TOKEN before binding FRIDAY_HOST to a non-loopback interface.")

app = FastAPI(title="FRIDAY Local AI Worker", version="0.2.0")
ollama = OllamaProvider()
app.include_router(creative_router, prefix="/v1")


class ChatMessage(BaseModel):
    role: Literal["system", "user", "assistant"]
    content: str = Field(min_length=1, max_length=12000)


class ChatRequest(BaseModel):
    message: str | None = Field(default=None, max_length=12000)
    messages: list[ChatMessage] = Field(default_factory=list, max_length=32)
    model: str | None = Field(default=None, max_length=160)
    system_prompt: str = Field(
        default="You are FRIDAY, a helpful private personal AI assistant. Be accurate, practical, concise, and never claim actions you have not performed.",
        max_length=12000,
    )
    temperature: float | None = Field(default=None, ge=0, le=2)
    top_p: float | None = Field(default=None, gt=0, le=1)
    max_tokens: int | None = Field(default=None, ge=1, le=32768)
    context_window: int | None = Field(default=None, ge=256, le=262144)
    seed: int | None = None


class ChatResponse(BaseModel):
    reply: str
    model: str
    provider: Literal["ollama"] = "ollama"
    privacy: Literal["local"] = "local"


@app.get("/health")
async def health():
    try:
        models = await ollama.list_models()
    except httpx.HTTPError as exc:
        raise HTTPException(status_code=503, detail=f"Ollama is unavailable: {exc}") from exc
    return {
        "status": "ok",
        "model": MODEL,
        "model_available": any(m["id"] == MODEL or m["id"].startswith(MODEL + ":") for m in models),
    }


@app.get("/v1/models", dependencies=[Depends(require_local_token)])
async def list_models():
    try:
        return {"models": await ollama.list_models()}
    except httpx.HTTPError as exc:
        raise HTTPException(status_code=503, detail=f"Cannot discover local Ollama models: {exc}") from exc


@app.post("/v1/chat", response_model=ChatResponse, dependencies=[Depends(require_local_token)])
async def chat(payload: ChatRequest):
    model = payload.model or MODEL
    messages = [item.model_dump() for item in payload.messages]
    if payload.message:
        if not messages:
            messages = [{"role": "system", "content": payload.system_prompt}]
        messages.append({"role": "user", "content": payload.message})
    if not messages:
        raise HTTPException(status_code=422, detail="Provide message or messages")
    options = {
        "temperature": payload.temperature,
        "top_p": payload.top_p,
        "num_predict": payload.max_tokens,
        "num_ctx": payload.context_window,
        "seed": payload.seed,
    }
    try:
        data = await ollama.chat(messages, model, options)
    except httpx.TimeoutException as exc:
        raise HTTPException(status_code=504, detail="Local model timed out") from exc
    except httpx.HTTPStatusError as exc:
        raise HTTPException(status_code=502, detail=f"Ollama error: {exc.response.text}") from exc
    except httpx.HTTPError as exc:
        raise HTTPException(status_code=503, detail=f"Cannot reach Ollama: {exc}") from exc
    return ChatResponse(reply=data.get("message", {}).get("content", ""), model=model)
