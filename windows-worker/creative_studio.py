from __future__ import annotations

import asyncio
import json
import mimetypes
from pathlib import Path
from typing import Any, Literal

from fastapi import APIRouter, Depends, HTTPException
from fastapi.responses import FileResponse, StreamingResponse
from pydantic import BaseModel, Field

from friday_worker.jobs import manager
from friday_worker.providers.comfyui import ComfyUIProvider, ProviderError
from friday_worker.security import require_local_token
from friday_worker.settings import settings

router = APIRouter(prefix="/creative", tags=["creative-studio"], dependencies=[Depends(require_local_token)])
comfy = ComfyUIProvider()


class GenerationRequest(BaseModel):
    prompt: str = Field(min_length=1, max_length=12000)
    negative_prompt: str | None = Field(default=None, max_length=6000)
    workflow_name: str = Field(min_length=1, max_length=80, pattern=r"^[A-Za-z0-9_-]+$")
    prompt_node_id: str | None = None
    prompt_input_name: str = "text"
    negative_node_id: str | None = None
    negative_input_name: str = "text"
    input_overrides: dict[str, dict[str, Any]] = Field(default_factory=dict)


class JobAccepted(BaseModel):
    job_id: str
    status: str
    task_type: str
    provider: str = "comfyui"


@router.get("/providers")
async def providers():
    ollama_status: dict[str, Any]
    try:
        import httpx
        async with httpx.AsyncClient(timeout=5) as client:
            response = await client.get(f"{settings.ollama_url}/api/tags")
            response.raise_for_status()
            models = response.json().get("models", [])
            ollama_status = {"available": True, "models": [m.get("name") for m in models]}
    except Exception as exc:
        ollama_status = {"available": False, "error": str(exc)}
    return {
        "execution_mode": "local-first",
        "llm": {"provider": "ollama", **ollama_status},
        "creative": {"provider": "comfyui", **(await comfy.health())},
    }


@router.get("/models")
async def discover_models():
    health = await comfy.health()
    if not health["available"]:
        raise HTTPException(status_code=503, detail=f"ComfyUI unavailable: {health.get('error')}")
    try:
        data = await comfy.discover()
    except Exception as exc:
        raise HTTPException(status_code=502, detail=f"ComfyUI model discovery failed: {exc}") from exc
    return {
        **data,
        "note": "ComfyUI node metadata is available. Checkpoint/model names are exposed by the relevant loader node inputs; model support is workflow-dependent.",
    }


@router.get("/workflows")
async def list_workflows():
    settings.workflows_dir.mkdir(parents=True, exist_ok=True)
    workflows = []
    for path in sorted(settings.workflows_dir.glob("*.json")):
        try:
            data = json.loads(path.read_text(encoding="utf-8"))
            workflows.append({"name": path.stem, "valid": isinstance(data, dict), "node_count": len(data) if isinstance(data, dict) else 0})
        except (OSError, json.JSONDecodeError):
            workflows.append({"name": path.stem, "valid": False, "node_count": 0})
    return {"workflows": workflows}


@router.post("/images/generate", response_model=JobAccepted, status_code=202)
async def generate_image(payload: GenerationRequest):
    request = payload.model_dump(exclude_none=True)
    job_id = manager.submit("text-to-image", payload.workflow_name, payload.prompt, request)
    return JobAccepted(job_id=job_id, status="queued", task_type="text-to-image")


@router.post("/images/edit", response_model=JobAccepted, status_code=202)
async def edit_image(payload: GenerationRequest):
    request = payload.model_dump(exclude_none=True)
    job_id = manager.submit("image-edit", payload.workflow_name, payload.prompt, request)
    return JobAccepted(job_id=job_id, status="queued", task_type="image-edit")


@router.post("/videos/generate", response_model=JobAccepted, status_code=202)
async def generate_video(payload: GenerationRequest):
    request = payload.model_dump(exclude_none=True)
    job_id = manager.submit("text-to-video", payload.workflow_name, payload.prompt, request)
    return JobAccepted(job_id=job_id, status="queued", task_type="text-to-video")


@router.post("/videos/image-to-video", response_model=JobAccepted, status_code=202)
async def image_to_video(payload: GenerationRequest):
    request = payload.model_dump(exclude_none=True)
    job_id = manager.submit("image-to-video", payload.workflow_name, payload.prompt, request)
    return JobAccepted(job_id=job_id, status="queued", task_type="image-to-video")


@router.get("/jobs")
async def list_jobs(limit: int = 50):
    return {"jobs": manager.store.list(max(1, min(limit, 200)))}


@router.get("/jobs/{job_id}")
async def get_job(job_id: str):
    job = manager.store.get(job_id)
    if not job:
        raise HTTPException(status_code=404, detail="Generation job not found")
    return job


@router.post("/jobs/{job_id}/cancel")
async def cancel_job(job_id: str):
    if not manager.cancel(job_id):
        raise HTTPException(status_code=409, detail="Job is not active or cannot be cancelled")
    return {"job_id": job_id, "status": "cancelled"}


@router.get("/jobs/{job_id}/events")
async def job_events(job_id: str):
    if not manager.store.get(job_id):
        raise HTTPException(status_code=404, detail="Generation job not found")

    async def events():
        previous = None
        while True:
            job = manager.store.get(job_id)
            if not job:
                yield "event: error\ndata: {\"detail\":\"Job disappeared\"}\n\n"
                break
            payload = json.dumps(job)
            if payload != previous:
                yield f"event: update\ndata: {payload}\n\n"
                previous = payload
            if job["status"] in ("completed", "failed", "cancelled", "interrupted"):
                break
            await asyncio.sleep(1)
    return StreamingResponse(events(), media_type="text/event-stream", headers={"Cache-Control": "no-cache", "X-Accel-Buffering": "no"})


@router.get("/media/{filename}")
async def get_media(filename: str):
    if Path(filename).name != filename:
        raise HTTPException(status_code=400, detail="Invalid media filename")
    path = (settings.output_dir / filename).resolve()
    if settings.output_dir.resolve() not in path.parents or not path.is_file():
        raise HTTPException(status_code=404, detail="Media not found")
    return FileResponse(path, media_type=mimetypes.guess_type(path.name)[0] or "application/octet-stream", filename=path.name)
