"""协议边界单元检查：真实 LangChain/OpenAI 序列化接离线 HTTP，不作为玩家或玩法验收。"""

import json

import httpx
import pytest

from wenxian_runtime import deepseek_protocol
from wenxian_runtime.domain import Candidate, opening
from wenxian_runtime.gateway import (
    InvalidToolArguments,
    LangChainGateway,
    MissingStructuredResponse,
    configured_gateway,
)
from wenxian_runtime.library import LibraryReader
from wenxian_runtime.response_audit import invalid_arguments_shape


def assert_strict(schema):
    if isinstance(schema, dict):
        if schema.get("type") == "object":
            assert schema["additionalProperties"] is False
            assert set(schema["required"]) == set(schema["properties"])
        assert not {"minLength", "maxLength", "minItems", "maxItems"} & schema.keys()
        for value in schema.values():
            assert_strict(value)
    elif isinstance(schema, list):
        for value in schema:
            assert_strict(value)


@pytest.mark.parametrize("malformed", [False, True, "unescaped-dialogue"])
def test_agent_serializes_strict_tools_and_never_repairs_invalid_arguments(monkeypatch, malformed):
    sent, records, spent = [], [], []
    model_class = deepseek_protocol.DeepSeekStrictChatModel

    def handle(request):
        body = json.loads(request.content)
        sent.append(body)
        assert request.url == "https://api.deepseek.com/beta/chat/completions"
        assert body["model"] == "deepseek-flash" and body["reasoning_effort"] == "none"
        assert body["max_tokens"] == 4000 and "max_completion_tokens" not in body
        assert body["tool_choice"] == "required" and "response_format" not in body
        assert {tool["function"]["name"] for tool in body["tools"]} == {"Candidate", "read_history", "read_definition", "find_definitions"}
        for tool in body["tools"]:
            assert tool["function"]["strict"] is True
            assert_strict(tool["function"]["parameters"])
        candidate = Candidate(scene_title="离线协议检查", narrative=["仅验证请求编码与返回解析。"])
        if len(sent) == 1 and not malformed:
            function = {"name": "read_history", "arguments": json.dumps({"before_turn": None, "limit": 3})}
        else:
            invalid = ('{"scene_title":"单元协议","narrative":"怎样激活？"你问,"choices":[]}'
                       if malformed == "unescaped-dialogue" else '{"narrative": "private-sentinel"')
            function = {"name": "Candidate", "arguments": invalid if malformed else candidate.model_dump_json()}
        return httpx.Response(200, json={"id": "offline-wire", "object": "chat.completion", "created": 0,
            "model": "deepseek-flash", "choices": [{"index": 0, "finish_reason": "tool_calls",
            "message": {"role": "assistant", "content": None, "tool_calls": [{"id": f"call-{len(sent)}", "type": "function", "function": function}]}}],
            "usage": {"prompt_tokens": 1, "completion_tokens": 1, "total_tokens": 2}})

    with httpx.Client(transport=httpx.MockTransport(handle)) as client:
        monkeypatch.setattr(deepseek_protocol, "DeepSeekStrictChatModel", lambda **kwargs: model_class(http_client=client, **kwargs))
        reader = LibraryReader("http://unused.invalid", opening(), httpx.MockTransport(lambda req: pytest.fail("本单元检查不读取网络资料")))
        try:
            gateway = LangChainGateway(model="deepseek-flash", key="synthetic-never-sent", base_url="https://api.deepseek.com/beta",
                                       reasoning_effort="none", tool_protocol="deepseek-strict")
            def invoke():
                return gateway.generate(opening(), "仅检查协议", reader, lambda before, limit: [],
                                        lambda: spent.append(1) or len(spent), lambda call_id, duration_ms, **kw: records.append(kw))
            if malformed:
                with pytest.raises(InvalidToolArguments):
                    invoke()
                assert len(sent) == len(spent) == 1
                assert records[0]["response_shape"]["messages"][0]["invalid_arguments"][0]["json_status"] == "invalid"
                assert "private-sentinel" not in str(records)
            else:
                assert invoke().scene_title == "离线协议检查"
                assert len(sent) == len(spent) == 2
                assert records[-1]["response_shape"]["structured"]
        finally:
            reader.close()


def test_original_domain_limits_remain_and_diagnostics_do_not_return_source():
    original = Candidate.model_json_schema()
    converted = deepseek_protocol.strict_tool(Candidate)
    assert Candidate.model_json_schema() == original
    assert "null" in json.dumps(converted)
    with pytest.raises(ValueError):
        Candidate(scene_title="x", narrative=["x"] * 13)
    result = invalid_arguments_shape('{"secret": "unclosed-private-value')
    assert result["json_status"] == "invalid" and "private-value" not in str(result)
    with pytest.raises(ValueError):
        LangChainGateway(model="deepseek-flash", key="unused", base_url="https://api.example.test/beta",
                         reasoning_effort="none", tool_protocol="deepseek-strict")


def test_fixture_cannot_be_launched_as_a_player_service(monkeypatch):
    monkeypatch.setenv("WENXIAN_GATEWAY", "fixture")
    with pytest.raises(ValueError, match="单元测试"):
        configured_gateway()


@pytest.mark.parametrize("outcome", ["valid", "invalid-json", "wrong-narrative", "plain", "empty", "invalid-tool"])
def test_native_json_keeps_agent_tools_and_validates_whole_final_object_once(monkeypatch, outcome):
    """实际序列化与 Agent 循环接离线传输；失败不续费、不提取正文、不输出返回值诊断。"""
    from pydantic import ValidationError

    sent, records, spent = [], [], []
    model_class = deepseek_protocol.DeepSeekJsonChatModel
    secret = "private-json-sentinel"

    def handle(request):
        body = json.loads(request.content)
        sent.append(body)
        assert request.url == "https://api.deepseek.com/chat/completions"
        assert body["response_format"] == {"type": "json_object"}
        assert body["max_tokens"] == 4000 and "max_completion_tokens" not in body
        assert body.get("tool_choice", "auto") == "auto"
        assert body["reasoning_effort"] == "none"
        assert {t["function"]["name"] for t in body["tools"]} == {"read_history", "read_definition", "find_definitions"}
        context = json.loads(next(m["content"] for m in body["messages"] if m["role"] == "user"))
        assert context["output_protocol"] == "candidate-json"
        assert context["response_schema"] == Candidate.model_json_schema()
        message, finish = {"role": "assistant", "content": None}, "stop"
        if (outcome == "valid" and len(sent) == 1) or outcome == "invalid-tool":
            message["tool_calls"] = [{"id": "read-history", "type": "function", "function": {
                "name": "read_history", "arguments": "{" if outcome == "invalid-tool" else '{"before_turn":null,"limit":3}'}}]
            finish = "tool_calls"
        else:
            candidate = Candidate(scene_title="离线 JSON 协议", narrative=["只验证传输与严格返回校验。"])
            message["content"] = {
                "valid": candidate.model_dump_json(),
                "invalid-json": '{"scene_title":"' + secret + '","narrative": 「未引用的正文」}',
                "wrong-narrative": json.dumps({"scene_title": "离线", "narrative": secret}),
                "plain": secret,
                "empty": "",
            }[outcome]
        return httpx.Response(200, json={"id": "offline-json-wire", "object": "chat.completion", "created": 0,
            "model": "deepseek-flash", "choices": [{"index": 0, "finish_reason": finish, "message": message}],
            "usage": {"prompt_tokens": 1, "completion_tokens": 1, "total_tokens": 2}})

    with httpx.Client(transport=httpx.MockTransport(handle)) as client:
        monkeypatch.setattr(deepseek_protocol, "DeepSeekJsonChatModel", lambda **kw: model_class(http_client=client, **kw))
        reader = LibraryReader("http://unused.invalid", opening(), httpx.MockTransport(lambda req: pytest.fail("不应查询额外资料")))
        try:
            gateway = LangChainGateway(model="deepseek-flash", key="synthetic-never-sent", base_url="https://api.deepseek.com",
                                       reasoning_effort="none", tool_protocol="deepseek-json")
            def invoke():
                return gateway.generate(opening(), "仅离线协议检查", reader, lambda before, limit: [],
                                        lambda: spent.append(1) or len(spent), lambda call_id, duration_ms, **kw: records.append(kw))
            if outcome == "valid":
                assert invoke().scene_title == "离线 JSON 协议"
                assert len(sent) == len(spent) == len(records) == 2
                assert records[-1]["response_shape"]["structured"] is True
            else:
                error = InvalidToolArguments if outcome == "invalid-tool" else MissingStructuredResponse if outcome == "empty" else ValidationError
                with pytest.raises(error):
                    invoke()
                assert len(sent) == len(spent) == len(records) == 1
                assert records[0]["validation_errors"]
                assert records[0]["response_shape"]["structured"] is False
            assert secret not in str(records)
        finally:
            reader.close()


@pytest.mark.parametrize("base,reasoning", [("https://example.test", "none"), ("https://api.deepseek.com/beta", "none"), ("https://api.deepseek.com", None)])
def test_native_json_requires_explicit_official_endpoint_and_reasoning(base, reasoning):
    with pytest.raises(ValueError, match="JSON"):
        LangChainGateway(model="deepseek-flash", key="unused", base_url=base, reasoning_effort=reasoning, tool_protocol="deepseek-json")
