"""取证代理离线测试：假响应与私有临时文件，不创建 HTTP 服务或访问供应商。"""

import base64
import concurrent.futures
import http.client
import importlib.util
import io
import json
import tempfile
import unittest
import urllib.error
from pathlib import Path
from unittest import mock


SPEC = importlib.util.spec_from_file_location(
    "capture_synthetic_model", Path(__file__).with_name("capture-synthetic-model.py"))
capture = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(capture)


class Response:
    status = 200

    def __init__(self, body=b'{"choices": []}', error=None):
        self.body = body
        self.error = error
        self.closed = False

    def read(self, limit):
        if self.error:
            raise self.error
        return self.body[:limit]

    def close(self):
        self.closed = True

    def __enter__(self):
        return self

    def __exit__(self, *unused):
        self.close()


class Opener:
    def __init__(self, response):
        self.response = response
        self.calls = 0

    def open(self, *unused, **kwargs):
        self.calls += 1
        if isinstance(self.response, Exception):
            raise self.response
        return self.response


class CaptureSyntheticModelTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary.name) / "capture"
        self.budgets = []

    def tearDown(self):
        for budget in self.budgets:
            budget.close()
        self.temporary.cleanup()

    def budget(self, maximum=18, prior=5, accept_complete_json=False):
        budget = capture.CaptureBudget(self.root, maximum, prior, accept_complete_json)
        self.budgets.append(budget)
        return budget

    def handler(self, budget, opener):
        handler = object.__new__(capture.make_handler(budget, opener))
        raw = b'{"model":"synthetic","messages":[]}'
        handler.path = "/v1/chat/completions"
        handler.headers = {"Content-Length": str(len(raw)), "Authorization": "Bearer test-only-secret"}
        handler.rfile = io.BytesIO(raw)
        handler.reply = mock.Mock()
        return handler

    def test_restart_keeps_prior_and_reserved_calls_even_without_responses(self):
        budget = self.budget()
        self.assertEqual(6, budget.reserve({"synthetic": True})["sequence"])
        budget.close()
        resumed = self.budget()
        self.assertEqual(6, resumed.used)
        self.assertEqual(7, resumed.reserve({"synthetic": True})["sequence"])
        manifest = json.loads((self.root / "capture-manifest.json").read_text())
        self.assertEqual({"formatVersion": 1, "maxCalls": 18, "priorCalls": 5,
                          "acceptCompleteJsonOnTruncatedTransfer": False}, manifest)

    def test_restart_cannot_change_prior_or_total_budget(self):
        budget = self.budget()
        budget.close()
        for maximum, prior in ((19, 5), (18, 0)):
            with self.assertRaisesRegex(ValueError, "cannot change"):
                self.budget(maximum, prior)

    def test_same_directory_rejects_second_proxy_owner(self):
        self.budget()
        with self.assertRaises(BlockingIOError):
            self.budget()

    def test_concurrent_reservations_never_exceed_budget_or_reuse_numbers(self):
        budget = self.budget()

        def reserve(unused):
            try:
                return budget.reserve({"synthetic": True})["sequence"]
            except capture.BudgetExhausted:
                return None

        with concurrent.futures.ThreadPoolExecutor(max_workers=12) as pool:
            results = list(pool.map(reserve, range(20)))
        self.assertEqual(list(range(6, 19)), sorted(value for value in results if value is not None))
        self.assertEqual(7, results.count(None))
        self.assertEqual(18, budget.used)
        self.assertEqual(13, len(list(self.root.glob("*.reservation.json"))))

    def test_budget_exhaustion_replies_429_without_external_call(self):
        opener = Opener(Response())
        handler = self.handler(self.budget(maximum=5), opener)
        handler.do_POST()
        self.assertEqual(0, opener.calls)
        self.assertEqual(429, handler.reply.call_args.args[0])

    def test_redirects_are_not_followed_to_any_destination(self):
        request = capture.urllib.request.Request(capture.UPSTREAM, headers={"Authorization": "Bearer synthetic"})
        for destination in ("https://other.invalid/chat/completions", capture.UPSTREAM):
            self.assertIsNone(capture.RejectRedirects().redirect_request(
                request, None, 302, "synthetic redirect", {}, destination))

    def test_reservation_is_fsynced_before_forward_and_credentials_are_not_recorded(self):
        budget = self.budget()
        opener = Opener(Response())
        original_open = opener.open

        def open_after_reservation(*args, **kwargs):
            self.assertTrue((self.root / "0006.reservation.json").is_file())
            self.assertGreaterEqual(fsync.call_count, 2)
            return original_open(*args, **kwargs)

        opener.open = open_after_reservation
        with mock.patch.object(capture.os, "fsync", wraps=capture.os.fsync) as fsync:
            handler = self.handler(budget, opener)
            handler.do_POST()
        self.assertEqual(1, opener.calls)
        self.assertEqual(200, handler.reply.call_args.args[0])
        self.assertNotIn("test-only-secret", (self.root / "0006.json").read_text())
        self.assertNotIn("Authorization", (self.root / "0006.reservation.json").read_text())

    def test_reservation_disk_error_prevents_external_call(self):
        budget = self.budget()
        opener = Opener(Response())
        handler = self.handler(budget, opener)
        with mock.patch.object(capture, "write_new", side_effect=OSError("synthetic disk failure")):
            handler.do_POST()
        self.assertEqual(0, opener.calls)
        self.assertEqual(500, handler.reply.call_args.args[0])

    def test_incomplete_success_body_is_recorded_and_never_salvaged_even_if_json_complete(self):
        partial = b'{"choices":[{"message":{"content":"complete JSON"}}]}'
        opener = Opener(Response(error=http.client.IncompleteRead(partial)))
        handler = self.handler(self.budget(), opener)
        handler.do_POST()

        record = json.loads((self.root / "0006.json").read_text())
        self.assertEqual(1, opener.calls)
        self.assertEqual(502, handler.reply.call_args.args[0])
        self.assertEqual(200, record["upstreamStatus"])
        self.assertEqual("IncompleteRead", record["transportError"])
        self.assertEqual(partial, base64.b64decode(record["partialBodyBase64"]))
        self.assertFalse(record["partialBodyTruncated"])

    def test_http_error_body_truncation_is_captured_by_same_failure_boundary(self):
        partial = b'{"error":'
        error = urllib.error.HTTPError(capture.UPSTREAM, 503, "synthetic upstream error", {},
                                       Response(error=http.client.IncompleteRead(partial)))
        opener = Opener(error)
        status, body, metadata = capture.forward(opener, b'{}', "Bearer synthetic")
        self.assertEqual(1, opener.calls)
        self.assertEqual(502, status)
        self.assertEqual(503, metadata["upstreamStatus"])
        self.assertEqual("IncompleteRead", metadata["transportError"])
        self.assertEqual(partial, base64.b64decode(metadata["partialBodyBase64"]))

    def stopped_json(self):
        return {"object": "chat.completion", "choices": [{"index": 0, "finish_reason": "stop",
                "message": {"role": "assistant", "content": "{\"synthetic\":true}"}}],
                "usage": {"prompt_tokens": 10, "completion_tokens": 5, "total_tokens": 15}}

    def test_explicit_recovery_preserves_original_bytes_transport_error_and_budget(self):
        original = json.dumps(self.stopped_json()).encode()
        opener = Opener(Response(error=http.client.IncompleteRead(original)))
        budget = self.budget(prior=6, accept_complete_json=True)
        handler = self.handler(budget, opener)
        handler.do_POST()
        self.assertEqual((200, original), handler.reply.call_args.args)
        record = json.loads((self.root / "0007.json").read_text())
        self.assertTrue(record["recovered"])
        self.assertEqual("IncompleteRead", record["transportError"])
        self.assertEqual(original, base64.b64decode(record["partialBodyBase64"]))
        self.assertEqual("complete-json-stop-v1", record["transportRecoveryPolicy"])
        self.assertEqual(7, budget.used)
        self.assertEqual(1, opener.calls)

    def test_complete_application_json_is_still_failure_when_recovery_is_off(self):
        original = json.dumps(self.stopped_json()).encode()
        opener = Opener(Response(error=http.client.IncompleteRead(original)))
        status, body, metadata = capture.forward(opener, b'{}', "Bearer synthetic")
        self.assertEqual(502, status)
        self.assertFalse(metadata["recovered"])
        self.assertEqual(1, opener.calls)

    def test_recovery_rejects_ambiguous_invalid_or_unfinished_application_envelopes(self):
        raw = json.dumps(self.stopped_json()).encode()
        cases = {"truncated JSON": raw[:-1], "trailing JSON": raw + b'{}',
                 "duplicate field": raw.replace(b'"object":', b'"object":"other","object":', 1),
                 "non-standard number": raw.replace(b'"total_tokens": 15', b'"total_tokens": NaN'),
                 "non-UTF8": raw + b'\xff'}
        for label, edit in (
                ("unfinished output", lambda v: v["choices"][0].update(finish_reason="length")),
                ("multiple choices", lambda v: v["choices"].append(dict(v["choices"][0]))),
                ("wrong role", lambda v: v["choices"][0]["message"].update(role="system")),
                ("empty content", lambda v: v["choices"][0]["message"].update(content="")),
                ("tool call", lambda v: v["choices"][0]["message"].update(tool_calls=[{}])),
                ("boolean usage", lambda v: v["usage"].update(prompt_tokens=True)),
                ("negative usage", lambda v: v["usage"].update(prompt_tokens=-1)),
                ("wrong usage total", lambda v: v["usage"].update(total_tokens=16)),
                ("missing usage", lambda v: v.pop("usage")),
                ("wrong object", lambda v: v.update(object="chat.completion.chunk"))):
            value = self.stopped_json()
            edit(value)
            cases[label] = json.dumps(value).encode()
        for label, original in cases.items():
            with self.subTest(label=label):
                opener = Opener(Response(error=http.client.IncompleteRead(original)))
                status, body, metadata = capture.forward(opener, b'{}', "Bearer synthetic", True)
                self.assertEqual(502, status)
                self.assertFalse(metadata["recovered"])
                self.assertEqual(1, opener.calls)

    def test_recovery_cannot_promote_http_error_even_with_complete_stopped_json(self):
        original = json.dumps(self.stopped_json()).encode()
        error = urllib.error.HTTPError(capture.UPSTREAM, 503, "synthetic", {},
                                       Response(error=http.client.IncompleteRead(original)))
        status, body, metadata = capture.forward(Opener(error), b'{}', "Bearer synthetic", True)
        self.assertEqual(502, status)
        self.assertEqual(503, metadata["upstreamStatus"])
        self.assertFalse(metadata["recovered"])

    def test_recovery_flag_is_frozen_and_absent_legacy_flag_means_false(self):
        budget = self.budget()
        budget.close()
        manifest_path = self.root / "capture-manifest.json"
        manifest = json.loads(manifest_path.read_text())
        manifest.pop("acceptCompleteJsonOnTruncatedTransfer")
        manifest_path.write_text(json.dumps(manifest))
        resumed = self.budget()
        self.assertFalse(resumed.accept_complete_json)
        resumed.close()
        with self.assertRaisesRegex(ValueError, "cannot change"):
            self.budget(accept_complete_json=True)

    def test_network_failure_keeps_reservation_and_safe_error_class(self):
        opener = Opener(urllib.error.URLError("synthetic secret must not reach trace"))
        handler = self.handler(self.budget(), opener)
        handler.do_POST()
        record_text = (self.root / "0006.json").read_text()
        self.assertEqual("URLError", json.loads(record_text)["transportError"])
        self.assertNotIn("synthetic secret", record_text)
        self.assertEqual(1, opener.calls)

    def test_client_disconnect_does_not_remove_completed_evidence(self):
        budget = self.budget()
        opener = Opener(Response())
        handler = self.handler(budget, opener)
        handler.reply = capture.make_handler(budget, opener).reply.__get__(handler)
        handler.send_response = mock.Mock(side_effect=BrokenPipeError())
        handler.do_POST()
        self.assertTrue((self.root / "0006.json").is_file())
        self.assertTrue((self.root / "0006.reservation.json").is_file())
        self.assertEqual(1, opener.calls)

    def test_missing_reservation_or_truncated_reservation_fails_closed_on_restart(self):
        budget = self.budget()
        budget.reserve({"synthetic": True})
        budget.reserve({"synthetic": True})
        budget.close()
        (self.root / "0006.reservation.json").unlink()
        with self.assertRaisesRegex(ValueError, "not contiguous"):
            self.budget()
        (self.root / "0006.reservation.json").write_text("{")
        with self.assertRaises(json.JSONDecodeError):
            self.budget()

    def test_response_cannot_mask_a_deleted_last_reservation_on_restart(self):
        budget = self.budget()
        reservation = budget.reserve({"synthetic": True})
        budget.complete(reservation, 200, b'{}', {"upstreamStatus": 200})
        budget.close()
        (self.root / "0006.reservation.json").unlink()
        with self.assertRaisesRegex(ValueError, "no durable reservation"):
            self.budget()


if __name__ == "__main__":
    unittest.main()
