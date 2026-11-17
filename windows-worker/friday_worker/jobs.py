from __future__ import annotations

import asyncio
import json
import sqlite3
import time
import uuid
from pathlib import Path
from typing import Any

from .providers.comfyui import ComfyUIProvider, ProviderError
from .settings import settings


class JobStore:
    def __init__(self) -> None:
        settings.database_path.parent.mkdir(parents=True, exist_ok=True)
        self.db_path = settings.database_path
        self._initialize()

    def _connect(self):
        connection = sqlite3.connect(self.db_path, timeout=20)
        connection.row_factory = sqlite3.Row
        return connection

    def _initialize(self) -> None:
        with self._connect() as db:
            db.execute("""
                CREATE TABLE IF NOT EXISTS generation_jobs (
                    id TEXT PRIMARY KEY,
                    task_type TEXT NOT NULL,
                    provider TEXT NOT NULL,
                    workflow_name TEXT NOT NULL,
                    status TEXT NOT NULL,
                    progress REAL,
                    queue_position INTEGER,
                    prompt TEXT NOT NULL,
                    request_json TEXT NOT NULL,
                    result_json TEXT,
                    error TEXT,
                    provider_job_id TEXT,
                    created_at REAL NOT NULL,
                    updated_at REAL NOT NULL
                )
            """)
            db.execute("UPDATE generation_jobs SET status='interrupted', error='Worker restarted before completion', updated_at=? WHERE status IN ('queued','running')", (time.time(),))

    def create(self, task_type: str, workflow_name: str, prompt: str, request: dict[str, Any]) -> str:
        job_id = str(uuid.uuid4())
        now = time.time()
        with self._connect() as db:
            db.execute(
                """INSERT INTO generation_jobs
                (id,task_type,provider,workflow_name,status,prompt,request_json,created_at,updated_at)
                VALUES (?,?,?,?,?,?,?,?,?)""",
                (job_id, task_type, "comfyui", workflow_name, "queued", prompt, json.dumps(request), now, now),
            )
        return job_id

    def update(self, job_id: str, **fields: Any) -> None:
        allowed = {"status", "progress", "queue_position", "result_json", "error", "provider_job_id"}
        fields = {key: value for key, value in fields.items() if key in allowed}
        if not fields:
            return
        fields["updated_at"] = time.time()
        assignments = ", ".join(f"{key}=?" for key in fields)
        values = [json.dumps(v) if key == "result_json" and v is not None else v for key, v in fields.items()]
        with self._connect() as db:
            db.execute(f"UPDATE generation_jobs SET {assignments} WHERE id=?", (*values, job_id))

    def get(self, job_id: str) -> dict[str, Any] | None:
        with self._connect() as db:
            row = db.execute("SELECT * FROM generation_jobs WHERE id=?", (job_id,)).fetchone()
        return self._decode(row) if row else None

    def list(self, limit: int = 50) -> list[dict[str, Any]]:
        with self._connect() as db:
            rows = db.execute("SELECT * FROM generation_jobs ORDER BY created_at DESC LIMIT ?", (limit,)).fetchall()
        return [self._decode(row) for row in rows]

    @staticmethod
    def _decode(row) -> dict[str, Any]:
        result = dict(row)
        result["request"] = json.loads(result.pop("request_json"))
        result["result"] = json.loads(result.pop("result_json")) if result.get("result_json") else None
        return result


class GenerationManager:
    def __init__(self) -> None:
        self.store = JobStore()
        self.provider = ComfyUIProvider()
        self.tasks: dict[str, asyncio.Task] = {}

    def submit(self, task_type: str, workflow_name: str, prompt: str, request: dict[str, Any]) -> str:
        job_id = self.store.create(task_type, workflow_name, prompt, request)
        task = asyncio.create_task(self._execute(job_id, request), name=f"friday-generation-{job_id}")
        self.tasks[job_id] = task
        task.add_done_callback(lambda _: self.tasks.pop(job_id, None))
        return job_id

    async def _execute(self, job_id: str, request: dict[str, Any]) -> None:
        self.store.update(job_id, status="running")
        try:
            workflow = self.provider.load_workflow(request["workflow_name"])
            graph = self.provider.prepare_workflow(
                workflow=workflow,
                prompt=request["prompt"],
                negative_prompt=request.get("negative_prompt"),
                prompt_node_id=request.get("prompt_node_id"),
                prompt_input_name=request.get("prompt_input_name", "text"),
                negative_node_id=request.get("negative_node_id"),
                negative_input_name=request.get("negative_input_name", "text"),
                input_overrides=request.get("input_overrides"),
            )

            def on_update(data: dict[str, Any]) -> None:
                mapped = {k: data[k] for k in ("status", "progress", "queue_position", "provider_job_id") if k in data}
                self.store.update(job_id, **mapped)

            result = await self.provider.run(
                graph,
                settings.output_dir,
                settings.request_timeout,
                settings.poll_interval,
                on_update,
            )
            self.store.update(job_id, status="completed", progress=1.0, result_json=result)
        except asyncio.CancelledError:
            self.store.update(job_id, status="cancelled", error="Cancelled by user")
            raise
        except (ProviderError, OSError, ValueError, KeyError) as exc:
            self.store.update(job_id, status="failed", error=str(exc))

    def cancel(self, job_id: str) -> bool:
        job = self.store.get(job_id)
        if not job or job["status"] not in ("queued", "running"):
            return False
        task = self.tasks.get(job_id)
        if task:
            task.cancel()
        self.store.update(job_id, status="cancelled", error="Cancellation requested")
        provider_id = job.get("provider_job_id")
        if provider_id:
            asyncio.create_task(self.provider.cancel(provider_id))
        return True


manager = GenerationManager()
