from __future__ import annotations

from typing import Any, Protocol


class ChatProvider(Protocol):
    async def list_models(self) -> list[dict[str, Any]]: ...
    async def chat(self, messages: list[dict[str, str]], model: str, options: dict[str, Any]) -> dict[str, Any]: ...
