"""独立创角 LangChain 类型化生成链；一次请求一次模型调用，不自动修复、重试或授予状态。"""

import json

from langchain_core.prompts import ChatPromptTemplate
from langchain_openai import ChatOpenAI

from .creation_domain import NameSuggestions, PathSuggestions
from .deepseek_protocol import DeepSeekJsonChatModel
from .repository import ROOT, dump


class CreationGateway:
    """提示词文件与 schema 装配候选；提供商或类型错误交给应用层记录，绝不回填静态候选。"""

    def generate(self, body, context, route, key):
        result_type = NameSuggestions if body.kind == "names" else PathSuggestions
        model_type = DeepSeekJsonChatModel if route.get("tool_protocol") == "deepseek-json" else ChatOpenAI
        model = model_type(model=route["model"], api_key=key, base_url=route["base_url"],
                           reasoning_effort=route.get("reasoning_effort"), temperature=0.8,
                           max_tokens=1800, timeout=75, max_retries=0)
        if model_type is ChatOpenAI:
            model = model.bind(response_format={"type": "json_object"})
        prompt = ChatPromptTemplate.from_messages([
            ("system", (ROOT / "resources/creation-suggestions.md").read_text()), ("human", "{request_json}"),
        ])
        message = (prompt | model).invoke({
            "schema": json.dumps(result_type.model_json_schema(), ensure_ascii=False),
            "request_json": dump({"request": body.model_dump(exclude={"request_id"}), "context": context}),
        })
        # 完整显式 JSON 合同，不从普通正文提取人物字段，不容忍不合法结果或另发修复调用。
        result = result_type.model_validate_json(message.content)
        if body.kind == "names" and set(result.names) & set(body.exclude_names):
            raise ValueError("模型重复了已排除的姓名")
        return result, message.usage_metadata or {}
