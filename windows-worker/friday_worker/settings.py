from __future__ import annotations

import os
from dataclasses import dataclass
from pathlib import Path


@dataclass(frozen=True)
class Settings:
    ollama_url: str = os.getenv("OLLAMA_URL", "http://127.0.0.1:11434").rstrip("/")
    ollama_model: str = os.getenv("FRIDAY_MODEL", "qwen2.5:3b")
    comfyui_url: str = os.getenv("COMFYUI_URL", "http://127.0.0.1:8188").rstrip("/")
    workflows_dir: Path = Path(os.getenv("FRIDAY_WORKFLOWS_DIR", "./workflows")).resolve()
    output_dir: Path = Path(os.getenv("FRIDAY_OUTPUT_DIR", "./data/media")).resolve()
    database_path: Path = Path(os.getenv("FRIDAY_DATABASE_PATH", "./data/friday.db")).resolve()
    api_token: str = os.getenv("FRIDAY_API_TOKEN", "")
    request_timeout: float = float(os.getenv("FRIDAY_PROVIDER_TIMEOUT", "900"))
    poll_interval: float = float(os.getenv("FRIDAY_JOB_POLL_INTERVAL", "1.5"))


settings = Settings()
