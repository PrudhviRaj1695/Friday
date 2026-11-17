import asyncio

import app as worker_app


def test_chat_controls_validate_ranges():
    payload = worker_app.ChatRequest(
        message="hello",
        temperature=0.8,
        top_p=0.9,
        top_k=40,
        repeat_penalty=1.1,
        policy_mode="mature_language",
        additional_instructions="Be concise.",
    )
    assert payload.top_k == 40
    assert payload.repeat_penalty == 1.1
    assert payload.policy_mode == "mature_language"


def test_chat_injects_policy_and_forwards_sampling(monkeypatch):
    captured = {}

    async def fake_chat(messages, model, options):
        captured["messages"] = messages
        captured["model"] = model
        captured["options"] = options
        return {"message": {"content": "local reply"}}

    monkeypatch.setattr(worker_app.ollama, "chat", fake_chat)
    payload = worker_app.ChatRequest(
        message="Explain a harmless experiment",
        model="test-local",
        temperature=0.7,
        top_p=0.92,
        top_k=32,
        repeat_penalty=1.08,
        policy_mode="mature_language",
        additional_instructions="Prefer practical examples.",
    )
    response = asyncio.run(worker_app.chat(payload))

    system_prompt = captured["messages"][0]["content"]
    assert "respect privacy and consent" in system_prompt
    assert "Ordinary adult vocabulary" in system_prompt
    assert "Prefer practical examples." in system_prompt
    assert captured["messages"][-1]["content"] == "Explain a harmless experiment"
    assert captured["options"]["top_k"] == 32
    assert captured["options"]["repeat_penalty"] == 1.08
    assert response.reply == "local reply"
