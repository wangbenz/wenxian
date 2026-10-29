"""提供商响应结构诊断：只投影固定枚举、布尔和数量，未知名称统一归为 other。"""

import hashlib
import json

TOOLS = {"Candidate", "find_definitions", "read_definition", "read_history"}
FINISH_REASONS = {"stop", "length", "tool_calls", "function_call", "content_filter"}


def invalid_arguments_shape(arguments):
    """用相同 JSON 解码方式定位失败，仅保存散列和位置，不保留参数或异常正文。"""
    if not isinstance(arguments, str):
        return {"argument_type": "other"}
    result = {"argument_type": "text", "length": len(arguments),
              "sha256": hashlib.sha256(arguments.encode()).hexdigest()}
    try:
        value = json.loads(arguments, strict=False)
        result["json_status"] = "object" if isinstance(value, dict) else "non_object"
    except json.JSONDecodeError as error:
        kinds = {"Expecting property name enclosed in double quotes": "property_name", "Expecting ',' delimiter": "comma",
                 "Expecting ':' delimiter": "colon", "Extra data": "trailing_data", "Expecting value": "value"}
        result.update(json_status="invalid", error_kind=kinds.get(error.msg, "string" if error.msg.startswith("Unterminated string") else "other"),
                      position=error.pos, line=error.lineno, column=error.colno)
    return result


def response_shape(messages, structured):
    """只导出结构与解析位置；原始正文、参数值及异常正文绝不进入诊断输出。"""
    result = []
    for message in messages or []:
        content = getattr(message, "content", None)
        metadata = getattr(message, "response_metadata", {}) or {}
        calls = getattr(message, "tool_calls", []) or []
        kind = getattr(message, "type", None)
        finish = metadata.get("finish_reason")
        result.append({
            "type": kind if kind in {"ai", "tool", "human", "system"} else "other",
            "content_type": "text" if isinstance(content, str) else "blocks" if isinstance(content, list) else "other",
            "content_length": len(content) if isinstance(content, (str, list)) else 0,
            "tool_names": [call.get("name") if call.get("name") in TOOLS else "other" for call in calls],
            "invalid_tool_call_count": len(getattr(message, "invalid_tool_calls", []) or []),
            "invalid_tool_names": [call.get("name") if call.get("name") in TOOLS else "other"
                                   for call in getattr(message, "invalid_tool_calls", []) or []],
            "invalid_arguments": [invalid_arguments_shape(call.get("args")) for call in getattr(message, "invalid_tool_calls", []) or []],
            "finish_reason": finish if isinstance(finish, str) and finish in FINISH_REASONS else "other",
        })
    return {"structured": bool(structured), "messages": result}
