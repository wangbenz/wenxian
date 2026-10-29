"""真实 LangChain Agent 编排，替换底层 chat model；完全不发外部请求。"""

import httpx
import pytest
from langchain.agents.structured_output import StructuredOutputValidationError
from langchain_core.language_models.chat_models import BaseChatModel
from langchain_core.messages import AIMessage
from langchain_core.outputs import ChatGeneration, ChatResult
from pydantic import Field

from wenxian_runtime.domain import opening
from wenxian_runtime.gateway import InvalidToolArguments, LangChainGateway, MissingStructuredResponse
from wenxian_runtime.library import LibraryReader
from wenxian_runtime.response_audit import response_shape


def test_response_shape_contains_no_model_text_arguments_or_unknown_names():
    secret = "private-sentinel-never-store"
    message = AIMessage(content=secret, response_metadata={"finish_reason": secret, "raw": secret},
                        tool_calls=[{"name": secret, "args": {"secret": secret}, "id": secret},
                                    {"name": "Candidate", "args": {"narrative": [secret]}, "id": "safe-id"}])
    result = response_shape([message], True)
    assert secret not in str(result)
    assert result["messages"][0]["tool_names"] == ["other", "Candidate"]
    assert result["messages"][0]["finish_reason"] == "other"


class ScriptedChatModel(BaseChatModel):
    responses: list = Field(default_factory=list)
    index: int = 0

    @property
    def _llm_type(self):
        return "offline-typed-gateway-test"

    def bind_tools(self, tools, **kwargs):
        return self

    def _generate(self, messages, stop=None, run_manager=None, **kwargs):
        response = self.responses[self.index]
        self.index += 1
        return ChatResult(generations=[ChatGeneration(message=response)])


@pytest.mark.parametrize("valid_calls", [[], [{"name": "read_history", "args": {}, "id": "history-1"}]])
def test_invalid_tool_arguments_stop_even_with_other_valid_calls(monkeypatch, valid_calls):
    model = ScriptedChatModel(responses=[AIMessage(content="", tool_calls=valid_calls,
        invalid_tool_calls=[{"name": "Candidate", "args": "sensitive-invalid-json", "error": "private-parse-error", "id": "invalid-1"}])])
    import langchain_openai
    monkeypatch.setattr(langchain_openai, "ChatOpenAI", lambda **kwargs: model)
    records, budget = [], []
    with pytest.raises(InvalidToolArguments):
        LangChainGateway(model="offline", key="never-sent").generate(
            opening(), "仅离线复现", type("Reader", (), {"reads": {}})(), lambda before, limit: [],
            lambda: budget.append(1) or 1, lambda call_id, duration_ms, **kw: records.append(kw))
    assert model.index == len(budget) == len(records) == 1
    assert records[0]["response_shape"]["messages"][0]["invalid_tool_names"] == ["Candidate"]
    assert records[0]["validation_errors"] == [{"loc": ["tool_calls"], "type": "invalid_arguments"}]
    assert "sensitive-invalid-json" not in str(records) and "private-parse-error" not in str(records)


@pytest.mark.parametrize("content", [
    "掌柜解释了核货条件，主角没有答应。",
    '{"scene_title":"不应从正文直接提交","narrative":["普通文本里的状态建议"]}',
    "",
])
def test_missing_candidate_is_rejected_and_audited_without_retry_or_text_fallback(monkeypatch, content):
    monkeypatch.setenv("WENXIAN_MODEL", "offline-test")
    monkeypatch.setenv("WENXIAN_MODEL_API_KEY", "fake-key-never-sent")
    model = ScriptedChatModel(responses=[AIMessage(
        content=content, usage_metadata={"input_tokens": 5, "output_tokens": 1, "total_tokens": 6},
    )])
    import langchain_openai

    monkeypatch.setattr(langchain_openai, "ChatOpenAI", lambda **kwargs: model)
    records, budget = [], []
    reader = LibraryReader("http://unused.invalid", opening(), httpx.MockTransport(
        lambda request: pytest.fail("缺少类型结果时不应额外查询资料")
    ))
    try:
        with pytest.raises(MissingStructuredResponse) as failure:
            LangChainGateway().generate(
                opening(), "只问条件", reader, lambda before, limit: [],
                lambda: budget.append(1) or 1,
                lambda call_id, duration_ms, **kwargs: records.append(kwargs),
            )
        assert failure.value.code == "STRUCTURED_RESULT_MISSING"
        assert model.index == len(budget) == len(records) == 1
        assert records[0]["error_type"] == "MissingStructuredResponse"
        assert records[0]["validation_errors"] == [{"loc": ["structured_response"], "type": "missing"}]
        assert records[0]["usage"]["total_tokens"] == 6
        assert records[0]["response_shape"]["structured"] is False
        assert records[0]["response_shape"]["messages"][0]["tool_names"] == []
        assert records[0]["response_shape"]["messages"][0]["content_length"] == len(content)
        assert "普通文本里的状态建议" not in str(records)
    finally:
        reader.close()


def test_langchain_tool_and_typed_candidate_are_budgeted(monkeypatch):
    monkeypatch.setenv("WENXIAN_MODEL", "offline-test")
    monkeypatch.setenv("WENXIAN_MODEL_API_KEY", "fake-key-never-sent")
    model = ScriptedChatModel(
        responses=[
            AIMessage(
                content="",
                tool_calls=[
                    {"name": "read_history", "args": {"before_turn": 1, "limit": 2}, "id": "history-1"},
                    {
                        "name": "read_definition",
                        "args": {"category": "arts", "key": "basic-breathing"},
                        "id": "definition-1",
                    },
                ],
            ),
            AIMessage(
                content="",
                tool_calls=[
                    {
                        "name": "Candidate",
                        "args": {
                            "scene_title": "类型化测试回应",
                            "narrative": ["确认收到本轮授权行动。"],
                            "choices": [],
                            "change": None,
                            "information": [],
                        },
                        "id": "candidate-1",
                    }
                ],
            ),
        ]
    )
    import langchain_openai

    monkeypatch.setattr(langchain_openai, "ChatOpenAI", lambda **kwargs: model)
    reader = LibraryReader(
        "http://library.test",
        opening(),
        httpx.MockTransport(
            lambda req: httpx.Response(
                200, json={"key": "basic-breathing", "revision": 2, "content_hash": "a" * 64}
            )
        ),
    )
    budget, history = [], []
    try:
        candidate = LangChainGateway().generate(
            opening(),
            "问清条件",
            reader,
            lambda before, limit: history.append((before, limit)) or [],
            lambda: budget.append("call"),
        )
        assert candidate.scene_title == "类型化测试回应"
        assert len(budget) == 2
        assert (1, 2) in history
        assert reader.reads["arts:basic-breathing"]["revision"] == 2
    finally:
        reader.close()


def test_invalid_structured_output_records_field_errors_without_values(monkeypatch):
    monkeypatch.setenv("WENXIAN_MODEL", "offline-test")
    monkeypatch.setenv("WENXIAN_MODEL_API_KEY", "fake-key-never-sent")
    model = ScriptedChatModel(
        responses=[
            AIMessage(
                content="",
                usage_metadata={"input_tokens": 25, "output_tokens": 1, "total_tokens": 26},
                tool_calls=[
                    {
                        "name": "Candidate",
                        "args": {
                            "scene_title": "测试",
                            "narrative": [],
                            "change": None,
                            "information": [],
                            "choices": [],
                        },
                        "id": "invalid-1",
                    }
                ],
            )
        ]
    )
    import langchain_openai

    monkeypatch.setattr(langchain_openai, "ChatOpenAI", lambda **kwargs: model)
    reader = LibraryReader(
        "http://library.test",
        opening(),
        httpx.MockTransport(
            lambda req: httpx.Response(
                200, json={"key": "basic-breathing", "revision": 2, "content_hash": "a" * 64}
            )
        ),
    )
    records = []
    try:
        with pytest.raises(StructuredOutputValidationError):
            LangChainGateway().generate(
                opening(),
                "测试",
                reader,
                lambda before, limit: [],
                lambda: 99,
                lambda call_id, duration_ms, **kwargs: records.append((call_id, kwargs)),
            )
        assert records[0][0] == 99
        metadata = records[0][1]
        assert metadata["usage"]["total_tokens"] == 26
        assert metadata["validation_errors"] == [{"loc": ["narrative"], "type": "too_short"}]
        assert "测试" not in str(metadata)
    finally:
        reader.close()


def test_history_tool_accepts_latest_null_and_preserves_numeric_range(monkeypatch):
    monkeypatch.setenv("WENXIAN_MODEL", "offline-test")
    monkeypatch.setenv("WENXIAN_MODEL_API_KEY", "fake-key-never-sent")
    model = ScriptedChatModel(
        responses=[
            AIMessage(
                content="",
                tool_calls=[
                    {
                        "name": "read_history",
                        "args": {"before_turn": None, "limit": 3},
                        "id": "latest-history",
                    }
                ],
            ),
            AIMessage(
                content="",
                tool_calls=[
                    {
                        "name": "Candidate",
                        "args": {"scene_title": "已查询历史", "narrative": ["历史已核对。"]},
                        "id": "typed-result",
                    }
                ],
            ),
        ]
    )
    import langchain_openai

    monkeypatch.setattr(langchain_openai, "ChatOpenAI", lambda **kwargs: model)
    reader = LibraryReader(
        "http://library.test",
        opening(),
        httpx.MockTransport(
            lambda req: httpx.Response(
                200, json={"key": "basic-breathing", "revision": 2, "content_hash": "a" * 64}
            )
        ),
    )
    queries = []
    try:
        result = LangChainGateway().generate(
            opening(),
            "查询最终约定",
            reader,
            lambda before, limit: queries.append((before, limit)) or [],
            lambda: None,
        )
        assert result.scene_title == "已查询历史"
        assert queries == [(None, 3), (None, 3)]
    finally:
        reader.close()
