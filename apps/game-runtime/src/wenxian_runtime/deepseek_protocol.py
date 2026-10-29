"""DeepSeek 显式请求协议适配；保留 LangChain 编排，不修复或猜测模型返回值。"""

from copy import deepcopy

from langchain_core.utils.function_calling import convert_to_openai_tool
from langchain_openai import ChatOpenAI


def strict_tool(tool):
    """保留字段/类型/枚举；供应商不支持的长度约束继续由原 Pydantic 协议校验。"""
    value = deepcopy(convert_to_openai_tool(tool, strict=True))

    def supported(schema):
        if not isinstance(schema, dict):
            return
        for key in ("minLength", "maxLength", "minItems", "maxItems"):
            schema.pop(key, None)
        # 遍历 schema 节点，不能把字段名当成 JSON Schema 关键字裁剪。
        for key in ("properties", "$defs", "definitions"):
            for child in schema.get(key, {}).values():
                supported(child)
        supported(schema.get("items"))
        for key in ("anyOf", "oneOf", "allOf", "prefixItems"):
            for child in schema.get(key, []):
                supported(child)

    supported(value["function"]["parameters"])
    return value


class DeepSeekChatModel(ChatOpenAI):
    """只适配官方 Chat Completions 参数名，网络失败与返回校验仍由原流程处理。"""

    use_responses_api: bool = False

    def _get_request_payload(self, *args, **kwargs):
        # 锁定版本的 ChatOpenAI 会改写为 OpenAI 新参数名；DeepSeek 官方协议仍为 max_tokens。
        payload = super()._get_request_payload(*args, **kwargs)
        if "max_completion_tokens" in payload:
            payload["max_tokens"] = payload.pop("max_completion_tokens")
        return payload


class DeepSeekStrictChatModel(DeepSeekChatModel):
    """在 LangChain 的工具绑定边界约束全部工具，保留 Agent、工具与类型结果处理。"""

    def bind_tools(self, tools, *, tool_choice=None, **kwargs):
        kwargs.pop("strict", None)
        return super().bind_tools([strict_tool(tool) for tool in tools],
                                  tool_choice=tool_choice, strict=True, **kwargs)


class DeepSeekJsonChatModel(DeepSeekChatModel):
    """显式请求官方 JSON 输出；资料工具仍由 Agent 执行，最终对象必须完整通过 Candidate 校验。"""

    def _get_request_payload(self, *args, **kwargs):
        payload = super()._get_request_payload(*args, **kwargs)
        # 由 SDK 合并到同一请求正文。避免 OpenAI 的自动解析入口强制要求工具 strict；
        # 此处使用 DeepSeek 的 JSON 输出，类型解析由预算中间件完整执行，不用 SDK 猜测。
        payload["extra_body"] = {**(payload.get("extra_body") or {}), "response_format": {"type": "json_object"}}
        return payload
