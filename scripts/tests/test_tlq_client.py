"""Real Bash + loopback HTTP tests. No AWS or database connections."""
import base64
import contextlib
import csv
import datetime as dt
import hashlib
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import importlib.util
import json
import os
from pathlib import Path
import re
import secrets
import shutil
import socket
import subprocess
import sys
import tempfile
import threading
import types
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location("tlq_client", ROOT / "scripts/tlq_client.py")
CLIENT = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(CLIENT)
JAVA = os.environ.get("TLQ_JAVA", "java")
BASH = os.environ.get("TLQ_BASH", "bash")
JAR = ROOT / "target/client-sync-tlq.jar"


def schema():
    text = (ROOT / "src/main/java/edu/uw/tcss562/tlq/PaymentSchema.java").read_text(encoding="utf-8")
    def values(name):
        return re.findall(r'"([^"]+)"', re.search(name + r" = List.of\(([\s\S]*?)\);", text)[1])
    return values("HEADERS"), values("JSON_FIELDS")


@contextlib.contextmanager
def server(mode, directory):
    state = {"calls": [], "loaded": [], "transformResponses": [], "runIds": []}
    headers, fields = schema()

    class Handler(BaseHTTPRequestHandler):
        def log_message(self, *_):
            pass

        def respond(self, status, payload, **extra):
            data = json.dumps(payload, ensure_ascii=False, separators=(",", ":")).encode()
            self.send_response(status)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(data)))
            for key, value in extra.items():
                self.send_header(key, value)
            self.end_headers()
            self.wfile.write(data)
            return data

        def do_POST(self):
            payload = self.rfile.read(int(self.headers["Content-Length"]))
            run_id = self.headers.get("X-TLQ-Run-Id")
            state["calls"].append(self.path)
            state["runIds"].append(run_id)
            if self.path == "/transform":
                if mode == "transform-error":
                    self.respond(400, {"error": "INVALID_CSV"}); return
                self.assert_content_type("text/csv")
                # Local HTTP service uses the actual Java conversion JAR, not a duplicate converter.
                source = directory / f"http-input-{len(state['transformResponses'])}.csv"
                source.write_bytes(payload)
                process = subprocess.run([JAVA, "-jar", str(JAR), str(source)], capture_output=True)
                if process.returncode:
                    self.respond(400, {"error": "LOCAL_TRANSFORM_FAILED"}); return
                records = json.loads(process.stdout)
                if mode == "transform-count": records.pop()
                if mode == "transform-content": records[0][fields[0]] = "changed"
                response = self.respond(200, records)
                state["transformResponses"].append(response)
            elif self.path == "/load":
                self.assert_content_type("application/json")
                if mode == "load-error":
                    self.respond(500, {"error": "COMMIT_OUTCOME_UNKNOWN"}); return
                if mode == "load-redirect":
                    self.respond(307, {}, Location=f"http://127.0.0.1:{self.server.server_port}/redirect-target"); return
                records = json.loads(payload)
                if payload != state["transformResponses"][-1]:
                    self.respond(400, {"error": "BODY_WAS_NOT_FORWARDED_EXACTLY"}); return
                state["loaded"].extend(records)
                if mode == "load-disconnect":
                    # Model an accepted write whose HTTP acknowledgement is lost.
                    self.close_connection = True
                    self.connection.shutdown(socket.SHUT_RDWR)
                    self.connection.close()
                    return
                self.respond(200, {"committedRecords": len(records) - (1 if mode == "load-count" else 0)})
            elif self.path == "/query":
                state["query"] = json.loads(payload)
                if mode == "query-error":
                    self.respond(422, {"error": "INVALID_AMOUNT"}); return
                self.respond(200, {"runId": "wrong-run" if mode == "query-run" else run_id,
                                   "rulesVersion": "local-test", "results": [{"count": len(state["loaded"]) - (1 if mode == "query-count" else 0)}]})
            else:
                self.respond(500, {"error": "UNEXPECTED_REQUEST"})

        def assert_content_type(self, expected):
            if self.headers.get("Content-Type") != expected:
                raise AssertionError("Unexpected content type")

    instance = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
    thread = threading.Thread(target=instance.serve_forever, daemon=True)
    thread.start()
    try:
        yield f"http://127.0.0.1:{instance.server_port}", state
    finally:
        instance.shutdown(); instance.server_close(); thread.join(timeout=5)


class BashHttpTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix="tlq-client-test-")
        self.directory = Path(self.temporary.name)
        self.csv = self.directory / "source with spaces.csv"
        self.output = self.directory / "output with spaces"
        self.query = self.directory / "query.json"
        self.query.write_text('{"groupBy":[],"metrics":["count"]}', encoding="utf-8")
        headers, fields = schema()
        self.rows = []
        with self.csv.open("w", encoding="utf-8", newline="") as stream:
            printer = csv.writer(stream)
            printer.writerow(headers)
            for index in range(401):
                values = [""] * 75
                values[0] = "NEW"
                values[6] = '名字, "quoted"\nsecond line'
                values[30] = "1.00"
                # Deliberate duplicate first two rows: client must preserve both.
                values[45] = "0000" if index < 2 else str(index)
                printer.writerow(values)
                self.rows.append(dict(zip(fields, values)))

    def tearDown(self):
        self.temporary.cleanup()

    def run_script(self, base, extra=None):
        environment = os.environ.copy()
        environment.update(TLQ_PYTHON=sys.executable.replace("\\", "/"), TLQ_JAVA=JAVA)
        # A real AWS executable is not needed or called in none mode.
        environment["TLQ_AWS"] = str(self.directory / "DO_NOT_CALL_AWS")
        result = subprocess.run([BASH, str(ROOT / "scripts/tlq-sync.sh").replace("\\", "/"),
            "--csv", self.csv.as_posix(), "--transform-url", base + "/transform", "--load-url", base + "/load",
            "--query-url", base + "/query", "--query-file", self.query.as_posix(), "--auth", "none",
            "--output-root", self.output.as_posix(), "--jar", JAR.as_posix(), *(extra or [])],
            capture_output=True, env=environment, timeout=90)
        directories = list(self.output.glob("run-*"))
        self.assertTrue(directories, result.stdout.decode(errors="replace") + result.stderr.decode(errors="replace"))
        latest = max(directories, key=lambda item: item.stat().st_mtime_ns)
        self.assertTrue((latest / "summary.json").exists(), result.stdout.decode(errors="replace") + result.stderr.decode(errors="replace"))
        summary = json.loads((latest / "summary.json").read_bytes())
        if os.environ.get("TLQ_TEST_ARTIFACTS"):
            destination = Path(os.environ["TLQ_TEST_ARTIFACTS"]) / latest.name
            shutil.copytree(latest, destination)
        return result, summary, latest

    def test_success_three_batches_exact_forwarding_and_unique_runs(self):
        with server("success", self.directory) as (base, state):
            process, summary, output = self.run_script(base)
            self.assertEqual(0, process.returncode, process.stderr.decode(errors="replace"))
            self.assertEqual("PASSED", summary["status"])
            self.assertEqual(["/transform", "/load"] * 3 + ["/query"], state["calls"])
            self.assertEqual(self.rows, state["loaded"])
            self.assertEqual(1, len(set(state["runIds"])))
            self.assertEqual(401, summary["acknowledgedRecords"])
            self.assertEqual(3, summary["successfulLoadBatches"])
            self.assertEqual({"groupBy": [], "metrics": ["count"]}, state["query"])
            self.assertTrue((output / "responses/query.json").exists())
            self.assertGreater(summary["totalElapsedMs"], 0)
            http_steps = [step for step in summary["steps"] if step["stage"] in ("transform", "load", "query")]
            self.assertEqual(7, len(http_steps))
            for step in http_steps:
                self.assertEqual(200, step["httpStatus"])
                for name in ("elapsedMs", "authElapsedMs", "httpElapsedMs", "validationElapsedMs"):
                    self.assertGreaterEqual(step[name], 0)
        with server("success", self.directory) as (base, _):
            process, second, _ = self.run_script(base, ["--batch-size", "500"])
            self.assertEqual(0, process.returncode)
            self.assertNotEqual(summary["runId"], second["runId"])
            self.assertEqual(1, second["successfulLoadBatches"])

    def test_transform_failure_stops_before_load(self):
        for mode in ("transform-error", "transform-count", "transform-content"):
            with self.subTest(mode=mode), server(mode, self.directory) as (base, state):
                process, summary, _ = self.run_script(base)
                self.assertNotEqual(0, process.returncode)
                self.assertEqual("FAILED", summary["status"])
                self.assertEqual(["/transform"], state["calls"])
                self.assertEqual(0, summary["acknowledgedRecords"])

    def test_load_failure_count_mismatch_disconnect_and_redirect_never_retry(self):
        for mode in ("load-error", "load-count", "load-disconnect", "load-redirect"):
            with self.subTest(mode=mode), server(mode, self.directory) as (base, state):
                process, summary, _ = self.run_script(base)
                self.assertNotEqual(0, process.returncode)
                self.assertEqual("FAILED", summary["status"])
                self.assertEqual(["/transform", "/load"], state["calls"])
                failed = summary["failedSteps"][-1]
                self.assertEqual("load", failed["stage"])
                self.assertEqual("UNKNOWN_STOP_RUN", failed["commitOutcome"])
                if mode == "load-disconnect":
                    self.assertEqual(200, len(state["loaded"]))
                    self.assertIsNone(failed["httpStatus"])

    def test_query_failure_preserves_successful_load_metrics(self):
        for mode in ("query-error", "query-run", "query-count"):
            with self.subTest(mode=mode), server(mode, self.directory) as (base, state):
                process, summary, _ = self.run_script(base, ["--batch-size", "500"])
                self.assertNotEqual(0, process.returncode)
                self.assertEqual("FAILED", summary["status"])
                self.assertEqual(["/transform", "/load", "/query"], state["calls"])
                self.assertEqual(401, summary["acknowledgedRecords"])

    def test_unsigned_remote_endpoint_rejected_without_network_or_aws(self):
        process, summary, _ = self.run_script("http://example.invalid")
        self.assertNotEqual(0, process.returncode)
        self.assertEqual("FAILED", summary["status"])
        self.assertEqual("UNSIGNED_MODE_REQUIRES_LOOPBACK_HTTP", summary["failedSteps"][0]["error"])


class SigningAndSafetyTests(unittest.TestCase):
    def test_signing_uses_temporary_credentials_without_printing_or_files(self):
        credentials = {"AccessKeyId": secrets.token_hex(8), "SecretAccessKey": secrets.token_hex(32), "SessionToken": secrets.token_hex(24)}
        body = '中文 CSV\n'.encode()
        headers = CLIENT.signed_headers("https://example.lambda-url.us-west-2.on.aws/", body, "text/csv", "run-test", "us-west-2", credentials,
                                        dt.datetime(2026, 11, 9, tzinfo=dt.timezone.utc))
        self.assertEqual(hashlib.sha256(body).hexdigest(), headers["x-amz-content-sha256"])
        self.assertEqual(credentials["SessionToken"], headers["x-amz-security-token"])
        self.assertIn("/20261109/us-west-2/lambda/aws4_request", headers["authorization"])
        self.assertIn("x-tlq-run-id", headers["authorization"])
        self.assertNotIn(credentials["SecretAccessKey"], headers["authorization"])

    def test_profile_and_region_are_explicit_and_root_is_rejected(self):
        def fake_run(arguments, **kwargs):
            self.assertIn("--profile", arguments); self.assertIn("project-profile", arguments)
            self.assertIn("--region", arguments); self.assertIn("us-east-2", arguments)
            self.assertNotIn("AWS_ACCESS_KEY_ID", kwargs["env"])
            return types.SimpleNamespace(returncode=0, stdout=json.dumps({"Arn": "arn:aws:iam::123456789012:root"}).encode())
        with patch.object(CLIENT.subprocess, "run", side_effect=fake_run), patch.dict(os.environ, {"AWS_ACCESS_KEY_ID": secrets.token_hex(8)}):
            with self.assertRaises(CLIENT.Failure) as caught:
                CLIENT.temporary_credentials("project-profile", "us-east-2")
            self.assertEqual("NON_ROOT_DEVELOPMENT_IDENTITY_REQUIRED", caught.exception.code)

    def test_query_cannot_run_before_all_load_acknowledgements(self):
        with tempfile.TemporaryDirectory() as path:
            directory = Path(path); (directory / "batches").mkdir()
            (directory / "batches/manifest.json").write_text('{"batches":[{"index":1,"recordCount":200}]}')
            with self.assertRaises(CLIENT.Failure): CLIENT.ensure_loads_complete(directory)


if __name__ == "__main__":
    unittest.main(verbosity=2)
