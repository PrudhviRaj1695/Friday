import os
from typing import Literal

import httpx
from fastapi import FastAPI, HTTPException
from pydantic import BaseModel, Field

OLLAMA_URL = os.getenv("OLLAMA_URL", "http://127.0.0.1:11434")
MODEL = os.getenv("FRIDAY_MODEL", "qwen2.5:3b")
app = FastAPI(title="FRIDAY Local Worker", version="0.1.0")


class ChatRequest(BaseModel):
    message: str = Field(min_length=1, max_length=12000)


class ChatResponse(BaseModel):
    reply: str
    model: str
    privacy: Literal["local"] = "local"


@app.get("/health")
async def health():
    async with httpx.AsyncClient(timeout=4) as client:
        try:
            response = await client.get(f"{OLLAMA_URL}/api/tags")
            response.raise_for_status()
            data = response.json()
        except Exception as exc:
            raise HTTPException(status_code=503, detail=f"Ollama is unavailable: {exc}")
    models = [item.get("name", "") for item in data.get("models", [])]
    return {"status": "ok", "model": MODEL, "model_available": any(m == MODEL or m.startswith(MODEL + ":") for m in models)}


@app.post("/v1/chat", response_model=ChatResponse)
async def chat(payload: ChatRequest):
    async with httpx.AsyncClient(timeout=180) as client:
        try:
            response = await client.post(
                f"{OLLAMA_URL}/api/chat",
                json={"model": MODEL, "stream": False, "messages": [
                    {"role": "system", "content": "You are FRIDAY, a helpful private personal AI assistant. Be accurate, practical, concise, and never claim actions you have not performed."},
                    {"role": "user", "content": payload.message},
                ]},
            )
            response.raise_for_status()
            data = response.json()
        except httpx.TimeoutException:
            raise HTTPException(status_code=504, detail="Local model timed out")
        except httpx.HTTPStatusError as exc:
            raise HTTPException(status_code=502, detail=f"Ollama error: {exc.response.text}")
        except httpx.HTTPError as exc:
            raise HTTPException(status_code=503, detail=f"Cannot reach Ollama: {exc}")
    return ChatResponse(reply=data.get("message", {}).get("content", ""), model=MODEL)
