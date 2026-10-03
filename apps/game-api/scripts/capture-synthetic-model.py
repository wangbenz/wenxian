#!/usr/bin/env python3
"""隔离合成剧情的临时模型取证代理；不用于生产流量或真实玩家记录。

仅监听回环地址并转发至固定 DeepSeek HTTPS 端点，保持模型请求正文不变。
输出仅包含请求/响应正文，不保存 Authorization、Cookie 或其他请求头。
达到持久调用预算后拒绝新请求；网络失败不会自动重试。
仅显式实验开关可接受传输异常中完整且明确停止的 JSON；原传输异常仍留痕。
迁入新目录用 prior-calls 固化旧消耗；重启同目录保持原预算。原件必须写到仓库外。
"""

import argparse
import base64
import fcntl
import http.client
import json
import os
import re
import threading
import time
import urllib.error
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

MAX_BYTES = 4 * 1024 * 1024
UPSTREAM = "https://api.deepseek.com/chat/completions"


class BudgetExhausted(Exception):
    pass


class RejectRedirects(urllib.request.HTTPRedirectHandler):
    """固定供应商目的地，不携带认证头跟随任何重定向。"""
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


def write_new(path, value):
    """独占写入并刷盘；写入失败时不得继续发起供应商请求。"""
    with path.open("x", encoding="utf-8") as target:
        json.dump(value, target, ensure_ascii=False, indent=2)
        target.flush()
        os.fsync(target.fileno())
    directory = os.open(path.parent, os.O_RDONLY)
    try:
        os.fsync(directory)
    finally:
        os.close(directory)


class CaptureBudget:
    """本批私有文件计数器；所有预留均计入预算，失败或未知结果不会退还。"""
    def __init__(self, output, max_calls, prior_calls=0, accept_complete_json=False):
        if not 1 <= max_calls <= 100 or not 0 <= prior_calls <= max_calls:
            raise ValueError("invalid capture budget")
        self.output = output
        output.mkdir(mode=0o700, parents=True, exist_ok=True)
        self._process_lock = (output / ".proxy.lock").open("a")
        try:
            fcntl.flock(self._process_lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
            expected = {"formatVersion": 1, "maxCalls": max_calls, "priorCalls": prior_calls,
                        "acceptCompleteJsonOnTruncatedTransfer": accept_complete_json}
            manifest_path = output / "capture-manifest.json"
            if manifest_path.exists():
                existing = json.loads(manifest_path.read_text(encoding="utf-8"))
                # 早先清单未定义恢复开关，语义等同关闭，不能原地改为开启。
                existing.setdefault("acceptCompleteJsonOnTruncatedTransfer", False)
                if existing != expected:
                    raise ValueError("capture budget cannot change when resuming")
            else:
                if any(path.name != ".proxy.lock" for path in output.iterdir()):
                    raise ValueError("existing evidence directory has no capture budget manifest")
                write_new(manifest_path, expected)
            reserved = []
            for path in output.glob("*.reservation.json"):
                match = re.fullmatch(r"(\d{4})\.reservation\.json", path.name)
                if not match:
                    raise ValueError("invalid reservation filename")
                number = int(match.group(1))
                record = json.loads(path.read_text(encoding="utf-8"))
                if record.get("sequence") != number or record.get("status") != "RESERVED":
                    raise ValueError("invalid reservation record")
                reserved.append(number)
            if sorted(reserved) != list(range(prior_calls + 1, prior_calls + len(reserved) + 1)):
                raise ValueError("reservation sequence is not contiguous")
            for path in output.glob("[0-9][0-9][0-9][0-9].json"):
                if int(path.stem) not in reserved:
                    raise ValueError("response has no durable reservation")
            self.used = prior_calls + len(reserved)
            if self.used > max_calls:
                raise ValueError("capture budget already exceeded")
            self.maximum = max_calls
            self.accept_complete_json = accept_complete_json
            self._lock = threading.Lock()
        except BaseException:
            self._process_lock.close()
            raise

    def reserve(self, payload):
        with self._lock:
            if self.used >= self.maximum:
                raise BudgetExhausted()
            number = self.used + 1
            record = {"sequence": number, "startedAtEpoch": time.time(),
                      "request": payload, "status": "RESERVED"}
            write_new(self.output / f"{number:04d}.reservation.json", record)
            self.used = number
            return record

    def complete(self, reservation, status, body, metadata):
        record = dict(reservation)
        record.update(status=status, latencyMs=round((time.time() - record["startedAtEpoch"]) * 1000),
                      **metadata)
        try:
            record["response"] = json.loads(body)
        except (ValueError, UnicodeDecodeError):
            record["response"] = {"captureError": "non-JSON upstream response"}
        write_new(self.output / f"{record['sequence']:04d}.json", record)
        return record

    def close(self):
        self._process_lock.close()


def complete_stopped_json(body):
    """保守识别完整应用层响应；拒绝重复键、非标准数字、尾随内容和未明确停止的输出。"""
    def unique_object(pairs):
        result = {}
        for key, value in pairs:
            if key in result:
                raise ValueError("duplicate JSON property")
            result[key] = value
        return result

    def reject_constant(unused):
        raise ValueError("non-standard JSON number")

    try:
        value = json.loads(body.decode("utf-8"), object_pairs_hook=unique_object,
                           parse_constant=reject_constant)
    except (ValueError, UnicodeDecodeError):
        return False
    if not isinstance(value, dict) or value.get("object") != "chat.completion" or "error" in value:
        return False
    choices = value.get("choices")
    if not isinstance(choices, list) or len(choices) != 1 or not isinstance(choices[0], dict):
        return False
    choice = choices[0]
    if type(choice.get("index")) is not int or choice["index"] != 0 or choice.get("finish_reason") != "stop":
        return False
    message = choice.get("message")
    if not isinstance(message, dict) or message.get("role") != "assistant" \
            or not isinstance(message.get("content"), str) or not message["content"].strip() \
            or message.get("tool_calls") or message.get("function_call"):
        return False
    usage = value.get("usage")
    if not isinstance(usage, dict) or any(type(usage.get(key)) is not int or usage[key] < 0
                                        for key in ("prompt_tokens", "completion_tokens", "total_tokens")):
        return False
    return usage["prompt_tokens"] + usage["completion_tokens"] == usage["total_tokens"]


def forward(opener, raw, authorization, accept_complete_json=False):
    """只外发一次；仅显式开关允许 HTTP 200 的完整应用层 JSON，任何传输异常仍保留原件。"""
    upstream_status = None
    try:
        request = urllib.request.Request(UPSTREAM, data=raw, headers={
            "Content-Type": "application/json", "Authorization": authorization,
        })
        try:
            response = opener.open(request, timeout=120)
        except urllib.error.HTTPError as error:
            response = error
        with response:
            upstream_status = response.status
            body = response.read(MAX_BYTES + 1)
        if len(body) > MAX_BYTES:
            return 502, b'{"error":"upstream response too large"}', {
                "upstreamStatus": upstream_status, "transportError": "ResponseTooLarge"}
        return upstream_status, body, {"upstreamStatus": upstream_status}
    except http.client.IncompleteRead as error:
        partial = error.partial[:MAX_BYTES]
        metadata = {
            "upstreamStatus": upstream_status, "transportError": "IncompleteRead",
            "partialBodyBase64": base64.b64encode(partial).decode("ascii"),
            "partialBodyTruncated": len(error.partial) > MAX_BYTES,
        }
        recovered = accept_complete_json and upstream_status == 200 \
            and not metadata["partialBodyTruncated"] and complete_stopped_json(partial)
        metadata["recovered"] = recovered
        if recovered:
            metadata["transportRecoveryPolicy"] = "complete-json-stop-v1"
            return 200, partial, metadata
        return 502, b'{"error":"upstream response incomplete"}', metadata
    except (OSError, urllib.error.URLError, http.client.HTTPException) as error:
        return 502, b'{"error":"upstream request failed"}', {
            "upstreamStatus": upstream_status, "transportError": type(error).__name__}


def make_handler(budget, opener):

    class Handler(BaseHTTPRequestHandler):
        def log_message(self, *unused):
            pass

        def reply(self, status, body):
            try:
                self.send_response(status)
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(body)))
                self.end_headers()
                self.wfile.write(body)
            except OSError as error:
                # 原件在 reply 前已经落盘；下游断连不能抹掉供应商调用和取证。
                print(json.dumps({"clientReplyError": type(error).__name__}), flush=True)

        def do_POST(self):
            if self.path not in ("/chat/completions", "/v1/chat/completions"):
                self.reply(404, b'{"error":"unsupported path"}')
                return
            try:
                length = int(self.headers.get("Content-Length", "0"))
                if not 0 < length <= MAX_BYTES:
                    raise ValueError("length")
                raw = self.rfile.read(length)
                payload = json.loads(raw)
                if not isinstance(payload, dict) or payload.get("stream"):
                    raise ValueError("non-streaming JSON required")
            except (ValueError, json.JSONDecodeError):
                self.reply(400, b'{"error":"invalid capture request"}')
                return
            authorization = self.headers.get("Authorization", "")
            if not authorization.startswith("Bearer "):
                self.reply(401, b'{"error":"missing authorization"}')
                return
            try:
                reservation = budget.reserve(payload)
            except BudgetExhausted:
                self.reply(429, b'{"error":"synthetic replay budget exhausted"}')
                return
            except OSError:
                self.reply(500, b'{"error":"capture reservation could not be persisted"}')
                return
            status, body, metadata = forward(opener, raw, authorization, budget.accept_complete_json)
            record = budget.complete(reservation, status, body, metadata)
            self.reply(status, body)
            print(json.dumps({"sequence": record["sequence"], "status": status,
                              "latencyMs": record["latencyMs"]}), flush=True)

    return Handler


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--synthetic-data-only", action="store_true", required=True)
    parser.add_argument("--port", type=int, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--max-calls", type=int, default=24)
    parser.add_argument("--prior-calls", type=int, default=0,
                        help="迁入新目录前已消耗的调用数；写入清单后不可更改")
    parser.add_argument("--accept-complete-json-on-truncated-transfer", action="store_true",
                        help="仅本实验接受传输异常中完整且明确停止的 JSON，传输错误仍留痕")
    args = parser.parse_args()
    if not 1024 <= args.port <= 65535 or not 1 <= args.max_calls <= 100 or not 0 <= args.prior_calls <= args.max_calls:
        parser.error("端口或调用预算无效")
    output = args.output.resolve()
    repository = Path(__file__).resolve().parents[3]
    if output == repository or repository in output.parents:
        parser.error("完整合成剧情取证必须保存在仓库外")
    os.umask(0o077)
    budget = CaptureBudget(output, args.max_calls, args.prior_calls,
                           args.accept_complete_json_on_truncated_transfer)
    try:
        opener = urllib.request.build_opener(urllib.request.ProxyHandler({}), RejectRedirects())
        server = ThreadingHTTPServer(("127.0.0.1", args.port), make_handler(budget, opener))
        print(json.dumps({"listening": f"127.0.0.1:{args.port}", "maxCalls": args.max_calls,
                          "usedCalls": budget.used, "priorCalls": args.prior_calls, "output": str(output),
                          "acceptCompleteJsonOnTruncatedTransfer": budget.accept_complete_json}), flush=True)
        try:
            server.serve_forever()
        finally:
            server.server_close()
    finally:
        budget.close()


if __name__ == "__main__":
    main()
