import pytest

from friday_worker.providers.comfyui import ComfyUIProvider, ProviderError


def test_prepare_workflow_maps_prompt_and_overrides():
    source = {
        "6": {"class_type": "CLIPTextEncode", "inputs": {"text": "old"}},
        "3": {"class_type": "KSampler", "inputs": {"seed": 1, "steps": 20}},
    }
    result = ComfyUIProvider.prepare_workflow(
        workflow=source,
        prompt="A mountain at dawn",
        negative_prompt=None,
        prompt_node_id="6",
        prompt_input_name="text",
        negative_node_id=None,
        negative_input_name="text",
        input_overrides={"3": {"seed": 42, "steps": 4}},
    )
    assert result["6"]["inputs"]["text"] == "A mountain at dawn"
    assert result["3"]["inputs"] == {"seed": 42, "steps": 4}
    assert source["6"]["inputs"]["text"] == "old"


def test_prepare_workflow_rejects_missing_prompt_node():
    with pytest.raises(ProviderError):
        ComfyUIProvider.prepare_workflow(
            workflow={"1": {"class_type": "Node", "inputs": {}}},
            prompt="hello",
            negative_prompt=None,
            prompt_node_id="missing",
            prompt_input_name="text",
            negative_node_id=None,
            negative_input_name="text",
            input_overrides=None,
        )


def test_prepare_workflow_rejects_missing_override_node():
    with pytest.raises(ProviderError):
        ComfyUIProvider.prepare_workflow(
            workflow={"1": {"class_type": "Node", "inputs": {}}},
            prompt="hello",
            negative_prompt=None,
            prompt_node_id=None,
            prompt_input_name="text",
            negative_node_id=None,
            negative_input_name="text",
            input_overrides={"missing": {"seed": 3}},
        )
