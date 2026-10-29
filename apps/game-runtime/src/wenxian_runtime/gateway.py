"""模型层只返回候选；fixture 明确回显，真实层按预算使用 LangChain 工具与类型协议。"""

import hashlib
import json
import os
import time
from pathlib import Path

from pydantic import ValidationError

from .domain import Candidate, Category, Choice, GameState, Information, RuntimeProblem
from .response_audit import response_shape


class MissingStructuredResponse(RuntimeProblem):
    """模型既未提交类型化结果也未提出可执行工具调用；失败即停止，不从正文提取状态或付费修复。"""

    def __init__(self):
        super().__init__(
            "STRUCTURED_RESULT_MISSING", "这一回的结果尚未能确认，存档没有变化，原行动已保留。", 503
        )


class DisabledGateway:
    mode = "disabled"

    def generate(self, state, action, library, history, spend, record=None):
        raise RuntimeProblem("MODEL_NOT_CONFIGURED", "本地试玩尚未启用真实续写，原行动已经保留。", 503)


class InvalidToolArguments(RuntimeProblem):
    """出现未能解析的工具参数即停，不把异常反馈成额外的付费格式修复回合。"""

    def __init__(self):
        super().__init__("MODEL_TOOL_ARGUMENTS_INVALID", "这一回的结果尚未能确认，存档没有变化，原行动已保留。", 503)


class FixtureGateway:
    """纯工程回显，无关键词裁决、预设游戏结果或模拟成长。"""

    mode = "fixture"

    def generate(self, state, action, library, history, spend, record=None):
        time.sleep(0.8)
        library.get("arts", "basic-breathing")
        return Candidate(
            scene_title="模拟联调回应",
            narrative=[
                f"本地联调已收到你的行动：{action}",
                "这是一条模拟回应用于验证保存、恢复和继续；尚未演绎游戏后果，人物、行囊与修为保持原状态。",
            ],
            choices=[Choice(id="continue", label="继续测试下一次行动", hint="你也可以写下自己的行动。")],
            information=[
                Information(
                    kind="observation",
                    subject="模拟联调",
                    text="本次行动已完成工程联调记录。",
                    source="明确标注的工程回显",
                )
            ],
        )


class LangChainGateway:
    """一个独立回合一个 Agent，工具仅闭包读取当前命卷和资料；预算在每次模型请求前执行。"""

    mode = "langchain"

    def __init__(self, *, model=None, key=None, base_url=None, reasoning_effort=None, tool_protocol=None):
        if model is None:
            self.model = os.getenv("WENXIAN_MODEL", "")
            self.key = os.getenv("WENXIAN_MODEL_API_KEY", "")
            self.base_url = os.getenv("WENXIAN_MODEL_BASE_URL") or None
            self.reasoning_effort = os.getenv("WENXIAN_MODEL_REASONING_EFFORT") or None
            self.tool_protocol = os.getenv("WENXIAN_MODEL_TOOL_PROTOCOL", "standard")
        else:
            self.model, self.key = model, key or ""
            self.base_url, self.reasoning_effort = base_url, reasoning_effort
            self.tool_protocol = tool_protocol or "standard"
        if self.tool_protocol not in {"standard", "deepseek-strict", "deepseek-json"}:
            raise ValueError("未知的模型工具协议")
        if self.tool_protocol == "deepseek-strict" and (
            (self.base_url or "").rstrip("/") != "https://api.deepseek.com/beta" or self.reasoning_effort != "none"
        ):
            raise ValueError("DeepSeek 严格工具协议需显式配置官方 beta 地址及 none 推理模式")
        if self.tool_protocol == "deepseek-json" and (
            (self.base_url or "").rstrip("/") != "https://api.deepseek.com" or self.reasoning_effort != "none"
        ):
            raise ValueError("DeepSeek JSON 输出需显式配置官方地址及 none 推理模式")

    def audit_metadata(self):
        prompt = (Path(__file__).resolve().parents[2] / "resources/narrator.md").read_bytes()
        return {
            "model": self.model,
            "prompt_hash": hashlib.sha256(prompt).hexdigest(),
            "protocol": "Candidate",
            "reasoning_effort": self.reasoning_effort,
            "tool_protocol": self.tool_protocol,
        }

    def generate(self, state: GameState, action, library, history, spend, record=None):
        if not self.model or not self.key:
            raise RuntimeProblem("MODEL_NOT_CONFIGURED", "本地试玩尚未启用真实续写，原行动已经保留。", 503)
        from langchain.agents import create_agent
        from langchain.agents.middleware import wrap_model_call
        from langchain.agents.structured_output import ToolStrategy
        from langchain_core.tools import tool
        from langchain_openai import ChatOpenAI

        json_output = self.tool_protocol == "deepseek-json"
        parsed_candidate = None

        @tool
        def find_definitions(category: Category, name: str) -> str:
            """按类别与名称检索公共定义候选，不能由匹配裁决结果或拥有。"""
            return json.dumps(library.search(category, name), ensure_ascii=False)

        @tool
        def read_definition(category: Category, key: str) -> str:
            """读取公共定义完整内容；已固定的本局版本通过历史修订读取。"""
            try:
                return json.dumps(library.get(category, key), ensure_ascii=False)
            except RuntimeProblem as exc:
                if exc.code != "DEFINITION_NOT_ADOPTED":
                    raise
                return json.dumps({"status": "not_adopted", "message": exc.message}, ensure_ascii=False)

        @tool
        def read_history(before_turn: int | None = None, limit: int = 3) -> str:
            """读取本命卷确认回合；null/省略表示最近回合，整数表示该回合之前；最多20回。"""
            return json.dumps(history(before_turn, limit), ensure_ascii=False)

        @wrap_model_call
        def enforce_budget(request, handler):
            nonlocal parsed_candidate
            call_id = spend()
            started = time.monotonic()
            response = None
            try:
                response = handler(request)
                if any(getattr(message, "invalid_tool_calls", []) for message in response.result):
                    raise InvalidToolArguments()
                has_tools = any(getattr(message, "tool_calls", []) for message in response.result)
                if not has_tools and json_output:
                    # 这是显式输出合同，不是从普通叙事抽取状态；整段必须为合法 Candidate JSON。
                    if len(response.result) != 1 or not isinstance(response.result[0].content, str) or not response.result[0].content.strip():
                        raise MissingStructuredResponse()
                    parsed_candidate = Candidate.model_validate_json(response.result[0].content)
                elif response.structured_response is None and not has_tools:
                    raise MissingStructuredResponse()
            except Exception as exc:
                if record:
                    status = getattr(exc, "status_code", None)
                    message = getattr(exc, "ai_message", None)
                    if message is None and response is not None:
                        message = next(
                            (item for item in response.result if getattr(item, "usage_metadata", None)), None
                        )
                    diagnostics = (
                        [{"loc": ["structured_response"], "type": "missing"}]
                        if isinstance(exc, MissingStructuredResponse) else []
                    )
                    if isinstance(exc, InvalidToolArguments):
                        diagnostics = [{"loc": ["tool_calls"], "type": "invalid_arguments"}]
                    if isinstance(exc, ValidationError):
                        diagnostics = [
                            {"loc": list(error["loc"]), "type": error["type"]}
                            for error in exc.errors(include_input=False, include_url=False)
                        ]
                    for call in getattr(message, "tool_calls", []):
                        if call["name"] == "Candidate":
                            try:
                                Candidate.model_validate(call["args"])
                            except ValidationError as invalid:
                                diagnostics = [
                                    {"loc": list(error["loc"]), "type": error["type"]}
                                    for error in invalid.errors(include_input=False, include_url=False)
                                ]
                    record(
                        call_id,
                        int((time.monotonic() - started) * 1000),
                        usage=getattr(message, "usage_metadata", None),
                        error_type=type(exc).__name__,
                        provider_status=status if isinstance(status, int) else None,
                        validation_errors=diagnostics,
                        response_shape=response_shape(response.result if response is not None else [message] if message is not None else [],
                                                      response is not None and response.structured_response is not None),
                    )
                raise
            if record:
                usage = next(
                    (
                        message.usage_metadata
                        for message in response.result
                        if getattr(message, "usage_metadata", None)
                    ),
                    {},
                )
                record(call_id, int((time.monotonic() - started) * 1000), usage=usage,
                       response_shape=response_shape(response.result, parsed_candidate is not None or response.structured_response is not None))
            return response

        model_class = ChatOpenAI
        if self.tool_protocol == "deepseek-strict":
            from .deepseek_protocol import DeepSeekStrictChatModel
            model_class = DeepSeekStrictChatModel
        elif json_output:
            from .deepseek_protocol import DeepSeekJsonChatModel
            model_class = DeepSeekJsonChatModel
        model = model_class(
            model=self.model,
            api_key=self.key,
            base_url=self.base_url,
            timeout=40,
            max_retries=0,
            max_tokens=4000,
            reasoning_effort=self.reasoning_effort,
        )
        agent = create_agent(
            model,
            tools=[find_definitions, read_definition, read_history],
            middleware=[enforce_budget],
            response_format=None if json_output else ToolStrategy(Candidate, handle_errors=False),
            system_prompt=(Path(__file__).resolve().parents[2] / "resources/narrator.md").read_text(),
        )
        # 公共定义按行动需要通过工具读取，不预装所有能力资料来阻断无关的交谈。
        recent_history = history(None, 3)
        context = {
            "current_turn": max((turn["turn"] for turn in recent_history), default=0),
            "confirmed_state": state.model_dump(mode="json", exclude={"definition_pins"}),
            "committed_history": recent_history,
            "definitions": list(library.reads.values()),
            "authorized_action": action,
        }
        if json_output:
            context["output_protocol"] = "candidate-json"
            context["response_schema"] = Candidate.model_json_schema()
        if getattr(library, "world_context", None):
            context["world_setting"] = library.world_context
            background = {(record["category"], record["key"]) for record in library.world_context["background_definitions"]}
            context["definitions"] = [record for record in context["definitions"]
                                      if (record["category"], record["key"]) not in background]
        result = agent.invoke(
            {"messages": [{"role": "user", "content": json.dumps(context, ensure_ascii=False)}]},
            config={"recursion_limit": 12},
        )
        if json_output:
            if parsed_candidate is None:
                raise MissingStructuredResponse()
            return parsed_candidate
        if result.get("structured_response") is None:
            raise MissingStructuredResponse()
        return Candidate.model_validate(result["structured_response"])


def configured_gateway():
    mode = os.getenv("WENXIAN_GATEWAY", "disabled")
    if mode == "fixture":
        raise ValueError("模拟网关仅允许在必要单元测试中显式注入，不能作为玩家服务启动")
    if mode == "langchain":
        return LangChainGateway()
    if mode != "disabled":
        raise ValueError("未知的 WENXIAN_GATEWAY")
    return DisabledGateway()
