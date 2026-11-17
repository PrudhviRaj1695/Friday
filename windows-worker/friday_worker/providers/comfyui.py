from __future__ import annotations

import asyncio
import json
import re
import uuid
from pathlib import Path
from typing import Any

import httpx

from ..settings import settings


class ProviderError(RuntimeError):
    pass


class ComfyUIProvider:
    def __init__(self) -> None:
        self.base_url = settings.comfyui_url

    async def health(self) -> dict[str, Any]:
        try:
            async with httpx.AsyncClient(timeout=5) as client:
                response = await client.get(f"{self.base_url}/system_stats")
                response.raise_for_status()
                stats = response.json()
            return {"available": True, "system": stats}
        except (httpx.HTTPError, ValueError) as exc:
            return {"available": False, "error": str(exc)}

    async def discover(self) -> dict[str, Any]:
        async with httpx.AsyncClient(timeout=20) as client:
            response = await client.get(f"{self.base_url}/object_info")
            response.raise_for_status()
            info = response.json()
        return {
            "provider": "comfyui",
            "node_count": len(info),
            "nodes": sorted(info.keys()),
        }

    def load_workflow(self, workflow_name: str) -> dict[str, Any]:
        if not re.fullmatch(r"[A-Za-z0-9_-]{1,80}", workflow_name):
            raise ProviderError("Invalid workflow name")
        path = (settings.workflows_dir / f"{workflow_name}.json").resolve()
        if settings.workflows_dir not in path.parents or not path.is_file():
            raise ProviderError(f"Workflow '{workflow_name}' was not found in {settings.workflows_dir}")
        try:
            workflow = json.loads(path.read_text(encoding="utf-8"))
        except (OSError, json.JSONDecodeError) as exc:
            raise ProviderError(f"Cannot read workflow '{workflow_name}': {exc}") from exc
        if not isinstance(workflow, dict) or not workflow:
            raise ProviderError("Workflow must be a non-empty ComfyUI API-format object")
        return workflow

    @staticmethod
    def prepare_workflow(
        workflow: dict[str, Any],
        prompt: str,
        negative_prompt: str | None,
        prompt_node_id: str | None,
        prompt_input_name: str,
        negative_node_id: str | None,
        negative_input_name: str,
        input_overrides: dict[str, dict[str, Any]] | None,
    ) -> dict[str, Any]:
        graph = json.loads(json.dumps(workflow))
        if prompt_node_id:
            node = graph.get(str(prompt_node_id))
            if not isinstance(node, dict) or not isinstance(node.get("inputs"), dict):
                raise ProviderError(f"Prompt node '{prompt_node_id}' does not exist or has no inputs")
            node["inputs"][prompt_input_name] = prompt
        if negative_prompt and negative_node_id:
            node = graph.get(str(negative_node_id))
            if not isinstance(node, dict) or not isinstance(node.get("inputs"), dict):
                raise ProviderError(f"Negative prompt node '{negative_node_id}' does not exist or has no inputs")
            node["inputs"][negative_input_name] = negative_prompt
        for node_id, values in (input_overrides or {}).items():
            node = graph.get(str(node_id))
            if not isinstance(node, dict) or not isinstance(node.get("inputs"), dict):
                raise ProviderError(f"Override node '{node_id}' does not exist or has no inputs")
            if not isinstance(values, dict):
                raise ProviderError("Each node override must be an object")
            node["inputs"].update(values)
        return graph

    async def run(
        self,
        workflow: dict[str, Any],
        output_dir: Path,
        timeout: float,
        poll_interval: float,
        on_update,
    ) -> dict[str, Any]:
        client_id = str(uuid.uuid4())
        async with httpx.AsyncClient(timeout=30) as client:
            try:
                response = await client.post(
                    f"{self.base_url}/prompt",
                    json={"prompt": workflow, "client_id": client_id},
                )
                response.raise_for_status()
                prompt_id = response.json().get("prompt_id")
                if not prompt_id:
                    raise ProviderError("ComfyUI did not return a prompt_id")
            except httpx.HTTPError as exc:
                raise ProviderError(f"Could not submit workflow to ComfyUI: {exc}") from exc

        on_update({"status": "running", "provider_job_id": prompt_id, "progress": None})
        deadline = asyncio.get_running_loop().time() + timeout
        while asyncio.get_running_loop().time() < deadline:
            async with httpx.AsyncClient(timeout=15) as client:
                try:
                    response = await client.get(f"{self.base_url}/history/{prompt_id}")
                    response.raise_for_status()
                    history = response.json()
                    record = history.get(prompt_id)
                    if record:
                        status_data = record.get("status", {})
                        if status_data.get("status_str") == "error" or status_data.get("completed") is False and status_data.get("messages"):
                            raise ProviderError(f"ComfyUI workflow failed: {status_data.get('messages', [])}")
                        outputs = record.get("outputs", {})
                        if outputs:
                            files = await self._collect_outputs(client, outputs, output_dir)
                            if not files:
                                raise ProviderError("ComfyUI completed but returned no downloadable media")
                            return {"provider_job_id": prompt_id, "outputs": files, "raw_status": status_data}
                    queue_response = await client.get(f"{self.base_url}/queue")
                    queue_response.raise_for_status()
                    queue = queue_response.json()
                    waiting = queue.get("queue_pending", [])
                    running = queue.get("queue_running", [])
                    position = next((i + 1 for i, item in enumerate(waiting) if len(item) > 1 and item[1] == prompt_id), None)
                    on_update({
                        "status": "running",
                        "provider_job_id": prompt_id,
                        "queue_position": position,
                        "provider_running": any(len(item) > 1 and item[1] == prompt_id for item in running),
                        "progress": None,
                    })
                except httpx.HTTPError as exc:
                    raise ProviderError(f"Lost connection while checking ComfyUI job: {exc}") from exc
            await asyncio.sleep(poll_interval)
        raise ProviderError(f"ComfyUI job {prompt_id} timed out after {timeout:g} seconds")

    async def _collect_outputs(self, client: httpx.AsyncClient, outputs: dict[str, Any], output_dir: Path) -> list[dict[str, Any]]:
        output_dir.mkdir(parents=True, exist_ok=True)
        saved: list[dict[str, Any]] = []
        for node_id, node_output in outputs.items():
            for kind in ("images", "gifs", "videos"):
                for item in node_output.get(kind, []):
                    filename = item.get("filename")
                    if not filename:
                        continue
                    params = {
                        "filename": filename,
                        "subfolder": item.get("subfolder", ""),
                        "type": item.get("type", "output"),
                    }
                    response = await client.get(f"{self.base_url}/view", params=params, timeout=120)
                    response.raise_for_status()
                    suffix = Path(filename).suffix or (".mp4" if kind in ("gifs", "videos") else ".png")
                    target = output_dir / f"{uuid.uuid4().hex}{suffix}"
                    target.write_bytes(response.content)
                    saved.append({
                        "path": str(target),
                        "filename": target.name,
                        "media_type": "video" if kind in ("gifs", "videos") else "image",
                        "source_node": str(node_id),
                        "bytes": target.stat().st_size,
                    })
        return saved

    async def cancel(self, prompt_id: str) -> bool:
        async with httpx.AsyncClient(timeout=10) as client:
            try:
                response = await client.post(f"{self.base_url}/interrupt")
                response.raise_for_status()
                await client.post(f"{self.base_url}/queue", json={"delete": [prompt_id]})
                return True
            except httpx.HTTPError:
                return False
