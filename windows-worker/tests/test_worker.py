"""Worker tests against a fake Ollama (httpx.MockTransport). Run: python -m pytest -q"""
import json
import os
import sys
from pathlib import Path

import httpx
import pytest

os.environ["FRIDAY_NO_DEFAULT_APP"] = "1"
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from fastapi.testclient import TestClient  # noqa: E402

import app as worker  # noqa: E402

TOKEN = "test-token-123"
AUTH = {"Authorization": f"Bearer {TOKEN}"}


class FakeOllama:
    def __init__(self, models=("qwen2.5:3b",), down=False, stream_chunks=None, status=200):
        self.models, self.down, self.status = list(models), down, status
        self.stream_chunks = stream_chunks or ["Hel", "lo", "!"]
        self.requests = []

    def handler(self, request: httpx.Request) -> httpx.Response:
        if self.down:
            raise httpx.ConnectError("refused", request=request)
        if request.url.path == "/api/tags":
            return httpx.Response(200, json={"models": [{"name": m, "size": 123, "details": {"family": "qwen2"}} for m in self.models]})
        if request.url.path == "/api/chat":
            body = json.loads(request.content)
            self.requests.append(body)
            if self.status != 200:
                return httpx.Response(self.status, text="model exploded")
            if body["stream"]:
                lines = [json.dumps({"message": {"content": c}, "done": False}) for c in self.stream_chunks]
                lines.append(json.dumps({"message": {"content": ""}, "done": True, "eval_count": 3, "prompt_eval_count": 9, "total_duration": 5_000_000}))
                return httpx.Response(200, content=("\n".join(lines) + "\n").encode())
            return httpx.Response(200, json={"message": {"content": "Hello!"}, "done": True, "eval_count": 2})
        return httpx.Response(404)


def make(fake: FakeOllama, **kw):
    settings = worker.Settings(token=TOKEN, **kw)
    return TestClient(worker.create_app(settings, transport=httpx.MockTransport(fake.handler)))


def test_ping_is_public_and_minimal():
    r = make(FakeOllama()).get("/ping")
    assert r.status_code == 200
    assert r.json() == {"service": "friday-worker", "version": worker.WORKER_VERSION}
    assert "X-Request-ID" in r.headers


@pytest.mark.parametrize("headers", [{}, {"Authorization": "Bearer wrong"}, {"Authorization": TOKEN}])
def test_auth_required(headers):
    c = make(FakeOllama())
    for method, path in [("get", "/health"), ("get", "/v1/models"), ("post", "/v1/chat")]:
        r = getattr(c, method)(path, headers=headers, **({"json": {"message": "hi"}} if method == "post" else {}))
        assert r.status_code == 401, path
        assert r.json()["error"]["code"] == "unauthorized"


def test_health_ok_and_model_available():
    r = make(FakeOllama()).get("/health", headers=AUTH)
    body = r.json()
    assert body["status"] == "ok" and body["ollama"] == "ok" and body["model_available"] is True


def test_health_reports_model_unavailable():
    body = make(FakeOllama(models=["llama3:8b"])).get("/health", headers=AUTH).json()
    assert body["status"] == "degraded" and body["model_available"] is False and body["ollama"] == "ok"


def test_health_reports_ollama_down_without_500():
    r = make(FakeOllama(down=True)).get("/health", headers=AUTH)
    assert r.status_code == 200
    assert r.json()["ollama"] == "unavailable"


def test_models_listing():
    body = make(FakeOllama(models=["qwen2.5:3b", "qwen2.5:7b"])).get("/v1/models", headers=AUTH).json()
    assert [m["name"] for m in body["models"]] == ["qwen2.5:3b", "qwen2.5:7b"]
    assert "temperature" in body["models"][0]["supports"]


def test_chat_passes_history_system_and_options():
    fake = FakeOllama()
    r = make(fake).post("/v1/chat", headers={**AUTH, "X-Request-ID": "abc"}, json={
        "request_id": "req-1", "system": "Be JARVIS",
        "messages": [{"role": "user", "content": "hi"}, {"role": "assistant", "content": "hello"}, {"role": "user", "content": "again"}],
        "options": {"temperature": 0.2, "top_p": 0.9, "top_k": 20, "num_predict": 128, "num_ctx": 999999}})
    assert r.status_code == 422  # num_ctx above schema limit is rejected, not silently applied
    r = make(fake).post("/v1/chat", headers=AUTH, json={
        "request_id": "req-1", "system": "Be JARVIS",
        "messages": [{"role": "user", "content": "hi"}, {"role": "assistant", "content": "hello"}, {"role": "user", "content": "again"}],
        "options": {"temperature": 0.2, "top_p": 0.9, "top_k": 20, "num_predict": 128, "num_ctx": 16384}})
    assert r.status_code == 200
    body = r.json()
    assert body["reply"] == "Hello!" and body["request_id"] == "req-1"
    sent = fake.requests[-1]
    assert sent["messages"][0] == {"role": "system", "content": "Be JARVIS"}
    assert [m["role"] for m in sent["messages"]] == ["system", "user", "assistant", "user"]
    assert sent["options"] == {"temperature": 0.2, "top_p": 0.9, "top_k": 20, "num_predict": 128, "num_ctx": 8192}
    assert body["applied_options"]["num_ctx"] == 8192  # clamped to worker max and reported


def test_legacy_single_message_payload_still_works():
    r = make(FakeOllama()).post("/v1/chat", headers=AUTH, json={"message": "hi"})
    assert r.status_code == 200 and r.json()["reply"] == "Hello!"


def test_invalid_options_rejected():
    c = make(FakeOllama())
    for opts in [{"temperature": 5}, {"top_p": 0}, {"top_k": 0}]:
        r = c.post("/v1/chat", headers=AUTH, json={"message": "hi", "options": opts})
        assert r.status_code == 422 and r.json()["error"]["code"] == "invalid_request"
    r = c.post("/v1/chat", headers=AUTH, json={"messages": [{"role": "assistant", "content": "x"}]})
    assert r.status_code == 422


def test_model_unavailable():
    r = make(FakeOllama()).post("/v1/chat", headers=AUTH, json={"message": "hi", "model": "missing:1b"})
    assert r.status_code == 404 and r.json()["error"]["code"] == "model_unavailable"


def test_ollama_down_on_chat():
    r = make(FakeOllama(down=True)).post("/v1/chat", headers=AUTH, json={"message": "hi"})
    assert r.status_code == 503 and r.json()["error"]["code"] == "ollama_unavailable"


def test_upstream_error_on_chat():
    r = make(FakeOllama(status=500)).post("/v1/chat", headers=AUTH, json={"message": "hi"})
    assert r.status_code == 502 and r.json()["error"]["code"] == "upstream_error"


def test_streaming_ndjson_events():
    with make(FakeOllama(stream_chunks=["నమ", "స్తే", " world"])).stream("POST", "/v1/chat", headers=AUTH, json={"message": "hi", "stream": True, "request_id": "s1"}) as r:
        assert r.status_code == 200
        assert r.headers["content-type"].startswith("application/x-ndjson")
        events = [json.loads(line) for line in r.iter_lines() if line]
    assert events[0]["type"] == "start" and events[0]["request_id"] == "s1"
    assert "".join(e["text"] for e in events if e["type"] == "delta") == "నమస్తే world"
    assert events[-1]["type"] == "done" and events[-1]["usage"]["completion_tokens"] == 3


def test_streaming_upstream_error_event():
    with make(FakeOllama(status=500)).stream("POST", "/v1/chat", headers=AUTH, json={"message": "hi", "stream": True}) as r:
        events = [json.loads(line) for line in r.iter_lines() if line]
    assert events[-1]["type"] == "error" and events[-1]["code"] == "upstream_error"


def test_token_file_created_and_reused(tmp_path, monkeypatch):
    monkeypatch.delenv("FRIDAY_TOKEN", raising=False)
    monkeypatch.setenv("FRIDAY_TOKEN_FILE", str(tmp_path / "t.txt"))
    first = worker.load_or_create_token()
    assert len(first) >= 24 and worker.load_or_create_token() == first
