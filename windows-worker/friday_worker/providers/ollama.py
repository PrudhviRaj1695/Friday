from __future__ import annotations

from typing import Any

import httpx

from ..settings import settings


class OllamaProvider:
    def __init__(self) -> None:
        self.base_url = settings.ollama_url

    async def list_models(self) -> list[dict[str, Any]]:
        async with httpx.AsyncClient(timeout=10) as client:
            response = await client.get(f"{self.base_url}/api/tags")
            response.raise_for_status()
            models = response.json().get("models", [])
        return [
            {
                "id": item.get("name", ""),
                "provider": "ollama",
                "size_bytes": item.get("size"),
                "modified_at": item.get("modified_at"),
                "capabilities": ["chat"],
                "execution": "local",
            }
            for item in models
        ]

    async def chat(self, messages: list[dict[str, str]], model: str, options: dict[str, Any]) -> dict[str, Any]:
        payload: dict[str, Any] = {"model": model, "messages": messages, "stream": False}
        allowed = {"temperature", "top_p", "num_predict", "num_ctx", "seed"}
        selected = {key: value for key, value in options.items() if key in allowed and value is not None}
        if selected:
            payload["options"] = selected
        async with httpx.AsyncClient(timeout=180) as client:
            response = await client.post(f"{self.base_url}/api/chat", json=payload)
            response.raise_for_status()
            return response.json()
