"""Local helper for Bash orchestration; stdlib only, credentials stay in memory."""
import argparse
import base64
import csv
import datetime as dt
import hashlib
import hmac
import http.client
import ipaddress
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid


class Failure(Exception):
    def __init__(self, code):
        self.code = code
        super().__init__(code)


def require(condition, code):
    if not condition:
        raise Failure(code)


def unique_object(pairs):
    obj = {}
    for key, value in pairs:
        require(key not in obj, "DUPLICATE_JSON_KEY")
        obj[key] = value
    return obj


def decode_json(data):
    try:
        return json.loads(data, object_pairs_hook=unique_object,
                          parse_constant=lambda _: (_ for _ in ()).throw(Failure("INVALID_JSON_NUMBER")))
    except (ValueError, UnicodeError):
        raise Failure("INVALID_JSON") from None


def write_json(path, obj):
    temporary = path.with_suffix(path.suffix + ".tmp")
    temporary.write_text(json.dumps(obj, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    temporary.replace(path)


def read_config(directory):
    return decode_json((directory / "run.json").read_bytes())


def event(directory, obj):
    with (directory / "metrics.jsonl").open("a", encoding="utf-8") as stream:
        stream.write(json.dumps(obj, ensure_ascii=False) + "\n")


def now():
    return dt.datetime.now(dt.timezone.utc).isoformat()


def begin(directory, stage, batch=None):
    result = {"stage": stage, "batchIndex": batch, "phase": "STARTED", "startedAt": now(),
              "stepId": str(uuid.uuid4()), "httpStatus": None}
    event(directory, result)
    return result, time.perf_counter_ns()


def end(directory, result, start, error=None):
    result.update(phase="FINISHED", status="FAILED" if error else "PASSED", finishedAt=now(),
                  elapsedMs=round((time.perf_counter_ns() - start) / 1e6, 3))
    if error:
        result["error"] = error
    event(directory, result)
    print(f"{result['status']} {result['stage']} batch={result['batchIndex']} "
          f"http={result.get('httpStatus')} ms={result['elapsedMs']}", flush=True)


def validate_url(url, auth, region):
    try:
        parts = urllib.parse.urlsplit(url)
        require(parts.hostname and not parts.username and not parts.password and not parts.query and not parts.fragment,
                "INVALID_ENDPOINT")
        _ = parts.port
        if auth == "none":
            try:
                loopback = ipaddress.ip_address(parts.hostname).is_loopback
            except ValueError:
                loopback = parts.hostname == "localhost"
            require(loopback and parts.scheme == "http", "UNSIGNED_MODE_REQUIRES_LOOPBACK_HTTP")
        else:
            require(parts.scheme == "https" and region and
                    re.fullmatch(r"[a-z0-9]+\.lambda-url\." + re.escape(region) + r"\.on\.aws", parts.hostname),
                    "IAM_REQUIRES_REGIONAL_FUNCTION_URL")
        return parts
    except ValueError:
        raise Failure("INVALID_ENDPOINT") from None


def validate_query(query):
    require(isinstance(query, dict) and set(query) <= {"filters", "groupBy", "metrics"}, "INVALID_QUERY_CONFIG")
    filters = query.get("filters", {})
    groups = query.get("groupBy", [])
    metrics = query.get("metrics", ["count", "sum", "avg"])
    require(isinstance(filters, dict) and set(filters) <= {"state", "year"}
            and all(isinstance(value, str) for value in filters.values()), "INVALID_QUERY_FILTERS")
    require(isinstance(groups, list) and all(isinstance(value, str) and value in ("state", "year") for value in groups)
            and len(set(groups)) == len(groups), "INVALID_QUERY_GROUPS")
    require(isinstance(metrics, list) and metrics and all(isinstance(value, str) and value in ("count", "sum", "avg") for value in metrics)
            and len(set(metrics)) == len(metrics), "INVALID_QUERY_METRICS")


def clean_aws_environment():
    environment = os.environ.copy()
    for key in ("AWS_ACCESS_KEY_ID", "AWS_SECRET_ACCESS_KEY", "AWS_SESSION_TOKEN", "AWS_SECURITY_TOKEN",
                "AWS_PROFILE", "AWS_DEFAULT_PROFILE", "AWS_ROLE_ARN", "AWS_WEB_IDENTITY_TOKEN_FILE"):
        environment.pop(key, None)
    return environment


def aws_json(profile, region, arguments, sensitive=False):
    require(profile and region, "EXPLICIT_PROFILE_AND_REGION_REQUIRED")
    executable = os.environ.get("TLQ_AWS", "aws")
    try:
        result = subprocess.run([executable, *arguments, "--profile", profile, "--region", region,
                                 "--output", "json", "--no-cli-pager"], capture_output=True,
                                env=clean_aws_environment(), timeout=60)
        require(result.returncode == 0, "CREDENTIAL_PROVIDER_FAILED" if sensitive else "IDENTITY_CHECK_FAILED")
        return decode_json(result.stdout)
    except (OSError, subprocess.TimeoutExpired):
        raise Failure("CREDENTIAL_PROVIDER_UNAVAILABLE" if sensitive else "IDENTITY_CHECK_UNAVAILABLE") from None


def temporary_credentials(profile, region):
    identity = aws_json(profile, region, ["sts", "get-caller-identity"])
    arn = identity.get("Arn", "")
    require(isinstance(arn, str) and re.fullmatch(r"arn:aws:(?:iam|sts)::[0-9]{12}:(?:user|assumed-role)/.+", arn),
            "NON_ROOT_DEVELOPMENT_IDENTITY_REQUIRED")
    credentials = aws_json(profile, region, ["configure", "export-credentials", "--format", "process"], sensitive=True)
    require(all(isinstance(credentials.get(name), str) and credentials[name]
                for name in ("AccessKeyId", "SecretAccessKey", "SessionToken")), "TEMPORARY_CREDENTIALS_REQUIRED")
    return credentials


def signed_headers(url, body, content_type, run_id, region, credentials, timestamp=None):
    parts = urllib.parse.urlsplit(url)
    timestamp = timestamp or dt.datetime.now(dt.timezone.utc)
    date = timestamp.strftime("%Y%m%d")
    amz_date = timestamp.strftime("%Y%m%dT%H%M%SZ")
    digest = hashlib.sha256(body).hexdigest()
    headers = {"content-type": content_type, "host": parts.netloc, "x-tlq-run-id": run_id,
               "x-amz-date": amz_date, "x-amz-content-sha256": digest,
               "x-amz-security-token": credentials["SessionToken"]}
    names = ";".join(sorted(headers))
    canonical_headers = "".join(name + ":" + headers[name].strip() + "\n" for name in sorted(headers))
    canonical = "POST\n" + (parts.path or "/") + "\n\n" + canonical_headers + "\n" + names + "\n" + digest
    scope = date + "/" + region + "/lambda/aws4_request"
    string_to_sign = "AWS4-HMAC-SHA256\n" + amz_date + "\n" + scope + "\n" + hashlib.sha256(canonical.encode()).hexdigest()
    def sign(key, text):
        return hmac.new(key, text.encode(), hashlib.sha256).digest()
    key = sign(sign(sign(sign(("AWS4" + credentials["SecretAccessKey"]).encode(), date), region), "lambda"), "aws4_request")
    signature = hmac.new(key, string_to_sign.encode(), hashlib.sha256).hexdigest()
    headers["authorization"] = "AWS4-HMAC-SHA256 Credential=" + credentials["AccessKeyId"] + "/" + scope + ", SignedHeaders=" + names + ", Signature=" + signature
    return headers


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, request, fp, code, msg, headers, new_url):
        return None


def prepare(args, directory):
    result, start = begin(directory, "prepare")
    try:
        require(args.auth in ("none", "aws-iam"), "INVALID_AUTH_MODE")
        require(args.batch_size.isdecimal() and 0 < int(args.batch_size) <= 2_147_483_647, "INVALID_BATCH_SIZE")
        require(args.timeout.isdecimal() and 0 < int(args.timeout) <= 3600, "INVALID_TIMEOUT")
        require(args.csv and Path(args.csv).is_file(), "CSV_NOT_FOUND")
        require(Path(args.jar).is_file(), "JAR_NOT_FOUND")
        endpoints = {}
        for stage in ("transform", "load", "query"):
            url, region = getattr(args, stage + "_url"), getattr(args, stage + "_region")
            validate_url(url, args.auth, region)
            endpoints[stage] = {"url": url, "region": region}
        require(args.auth == "none" or args.profile, "EXPLICIT_PROFILE_REQUIRED")
        query = decode_json(Path(args.query_file).read_bytes()) if args.query_file else {"metrics": ["count", "sum", "avg"]}
        validate_query(query)
        write_json(directory / "query-request.json", query)
        config = read_config(directory)
        config.update(csv=str(Path(args.csv).resolve()), jar=str(Path(args.jar).resolve()), java=args.java,
                      batchSize=int(args.batch_size), timeoutSeconds=int(args.timeout), auth=args.auth,
                      profile=args.profile, endpoints=endpoints)
        write_json(directory / "run.json", config)
        end(directory, result, start)
    except Exception as failure:
        end(directory, result, start, failure.code if isinstance(failure, Failure) else "PREPARE_FAILED")
        raise Failure("PREPARE_FAILED") from None


def batch(directory):
    result, start = begin(directory, "batch")
    try:
        config = read_config(directory)
        completed = subprocess.run([config["java"], "-cp", config["jar"], "edu.uw.tcss562.tlq.BatchMain",
                                    config["csv"], str(directory / "batches"), str(config["batchSize"])],
                                   capture_output=True)
        require(completed.returncode == 0, "CSV_BATCHER_FAILED")
        manifest = decode_json((directory / "batches/manifest.json").read_bytes())
        next_record, lines = 1, []
        with Path(config["csv"]).open(encoding="utf-8", newline="") as stream:
            source = csv.reader(stream, strict=True)
            header = next(source)
            for index, item in enumerate(manifest["batches"], 1):
                count = item["recordCount"]
                require(item["index"] == index and item["firstRecord"] == next_record
                        and type(count) is int and 0 < count <= config["batchSize"]
                        and item["lastRecord"] == next_record + count - 1, "INVALID_BATCH_MANIFEST")
                require(item["csvFile"] == f"batch-{index:06d}.csv" and item["jsonFile"] == f"batch-{index:06d}.json",
                        "INVALID_BATCH_FILENAME")
                with (directory / "batches" / item["csvFile"]).open(encoding="utf-8", newline="") as batch_stream:
                    split_rows = csv.reader(batch_stream, strict=True)
                    require(next(split_rows) == header, "BATCH_HEADER_MISMATCH")
                    actual_count = 0
                    for row in split_rows:
                        require(row == next(source), "BATCH_SOURCE_RECORD_MISMATCH")
                        actual_count += 1
                    require(actual_count == count, "BATCH_RECORD_COUNT_MISMATCH")
                lines.append(f"{index}\t{count}\t{item['csvFile']}\t{item['jsonFile']}\n")
                next_record += count
            require(next(source, None) is None and manifest["totalRecords"] == next_record - 1, "BATCH_TOTAL_MISMATCH")
        # Git Bash read preserves a native Windows CR at the last TSV field.
        (directory / "batches.tsv").write_text("".join(lines), encoding="utf-8", newline="\n")
        result.update(recordCount=manifest["totalRecords"], batchCount=len(lines))
        end(directory, result, start)
    except Exception as failure:
        end(directory, result, start, failure.code if isinstance(failure, Failure) else "BATCH_FAILED")
        raise Failure("BATCH_FAILED") from None


def finished_metrics(directory):
    if not (directory / "metrics.jsonl").exists():
        return []
    return [decode_json(line) for line in (directory / "metrics.jsonl").read_text(encoding="utf-8").splitlines()
            if line.strip()]


def ensure_loads_complete(directory):
    manifest = decode_json((directory / "batches/manifest.json").read_bytes())
    loads = [item for item in finished_metrics(directory) if item["stage"] == "load" and item["phase"] == "FINISHED"]
    require(len(loads) == len(manifest["batches"]), "QUERY_BEFORE_ALL_LOADS")
    for load, batch_item in zip(loads, manifest["batches"]):
        require(load["status"] == "PASSED" and load["batchIndex"] == batch_item["index"]
                and load.get("committedRecords") == batch_item["recordCount"], "QUERY_BEFORE_ALL_LOADS")


def request(args, directory):
    result, start = begin(directory, args.stage, args.batch_index)
    stage = args.stage
    result.update(expectedRecords=args.expected_count, requestSent=False)
    try:
        config = read_config(directory)
        endpoint = config["endpoints"][stage]
        validate_url(endpoint["url"], config["auth"], endpoint["region"])
        if stage == "query":
            ensure_loads_complete(directory)
        body = Path(args.input).read_bytes()
        result["requestBytes"] = len(body)
        content_type = "text/csv" if stage == "transform" else "application/json"
        auth_start = time.perf_counter_ns()
        if config["auth"] == "aws-iam":
            credentials = temporary_credentials(config["profile"], endpoint["region"])
            headers = signed_headers(endpoint["url"], body, content_type, config["runId"], endpoint["region"], credentials)
            del credentials
        else:
            headers = {"content-type": content_type, "x-tlq-run-id": config["runId"]}
        result["authElapsedMs"] = round((time.perf_counter_ns() - auth_start) / 1e6, 3)
        opener = urllib.request.build_opener(urllib.request.ProxyHandler({}), NoRedirect())
        http_start = time.perf_counter_ns()
        result["requestSent"] = True
        try:
            try:
                response = opener.open(urllib.request.Request(endpoint["url"], data=body, headers=headers, method="POST"),
                                       timeout=config["timeoutSeconds"])
            except urllib.error.HTTPError as http_error:
                response = http_error
            with response:
                result["httpStatus"] = response.code
                response_body = response.read(16 * 1024 * 1024 + 1)
                result["responseBytes"] = len(response_body)
        finally:
            result["httpElapsedMs"] = round((time.perf_counter_ns() - http_start) / 1e6, 3)
            del headers
        require(len(response_body) <= 16 * 1024 * 1024, "RESPONSE_EXCEEDS_CLIENT_BUDGET")
        output = directory / "responses" / ("query.json" if stage == "query" else f"batch-{args.batch_index:06d}-{stage}.json")
        output.write_bytes(response_body)
        result["responseFile"] = output.relative_to(directory).as_posix()
        require(result["httpStatus"] == 200, stage.upper() + "_HTTP_ERROR")
        validation_start = time.perf_counter_ns()
        payload = decode_json(response_body)
        if stage == "transform":
            expected = decode_json(Path(args.expected).read_bytes())
            require(isinstance(payload, list) and len(payload) == args.expected_count, "TRANSFORM_RECORD_COUNT_MISMATCH")
            require(payload == expected, "TRANSFORM_CONTENT_MISMATCH")
            result["recordCount"] = len(payload)
        elif stage == "load":
            require(isinstance(payload, dict) and type(payload.get("committedRecords")) is int, "INVALID_LOAD_ACKNOWLEDGEMENT")
            result["committedRecords"] = payload["committedRecords"]
            require(payload["committedRecords"] == args.expected_count, "LOAD_COMMITTED_COUNT_MISMATCH")
            result.update(recordCount=payload["committedRecords"], commitOutcome="ACKNOWLEDGED")
        else:
            require(isinstance(payload, dict) and payload.get("runId") == config["runId"]
                    and isinstance(payload.get("results"), list), "INVALID_QUERY_RESPONSE")
            query = decode_json((directory / "query-request.json").read_bytes())
            groups = query.get("groupBy", [])
            metrics = query.get("metrics", ["count", "sum", "avg"])
            for row in payload["results"]:
                require(isinstance(row, dict) and all(key in row for key in groups + metrics), "INVALID_QUERY_RESPONSE")
                if "count" in metrics:
                    require(type(row["count"]) is int and row["count"] >= 0, "INVALID_QUERY_RESPONSE")
                for metric in ("sum", "avg"):
                    if metric in metrics:
                        require(row[metric] is None or isinstance(row[metric], str), "INVALID_QUERY_RESPONSE")
            result["groupCount"] = len(payload["results"])
            if "count" in metrics:
                result["matchedRecords"] = sum(row["count"] for row in payload["results"])
                if not query.get("filters"):
                    total = decode_json((directory / "batches/manifest.json").read_bytes())["totalRecords"]
                    require(result["matchedRecords"] == total, "QUERY_TOTAL_RECORD_COUNT_MISMATCH")
        result["validationElapsedMs"] = round((time.perf_counter_ns() - validation_start) / 1e6, 3)
        end(directory, result, start)
    except Exception as failure:
        code = failure.code if isinstance(failure, Failure) else "HTTP_TRANSPORT_OR_CLIENT_ERROR"
        result["errorType"] = type(failure).__name__
        if stage == "load":
            result["commitOutcome"] = "UNKNOWN_STOP_RUN" if result["requestSent"] else "NOT_SENT"
        end(directory, result, start, code)
        raise Failure(code) from None


def finalize(args, directory):
    config = read_config(directory)
    events = finished_metrics(directory)
    started = {item["stepId"]: item for item in events if item["phase"] == "STARTED"}
    finished = [item for item in events if item["phase"] == "FINISHED"]
    unfinished = [item for key, item in started.items() if key not in {value["stepId"] for value in finished}]
    for item in unfinished:
        if item["stage"] == "load":
            item["commitOutcome"] = "UNKNOWN_STOP_RUN"
    loads = [item for item in finished if item["stage"] == "load" and item["status"] == "PASSED"]
    success = args.exit_code == 0 and not unfinished and all(item["status"] == "PASSED" for item in finished)
    success = success and any(item["stage"] == "query" for item in finished)
    summary = {"runId": config["runId"], "status": "PASSED" if success else "FAILED", "exitCode": args.exit_code,
               "startedAt": config["startedAt"], "finishedAt": now(),
               "totalElapsedMs": round((time.perf_counter_ns() - config["startedPerfNs"]) / 1e6, 3),
               "acknowledgedRecords": sum(item["committedRecords"] for item in loads),
               "successfulLoadBatches": len(loads), "failedSteps": [item for item in finished if item["status"] == "FAILED"],
               "unfinishedSteps": unfinished, "steps": finished,
               "validationScope": "client HTTP orchestration only; cloud end-to-end not established by local tests"}
    manifest = directory / "batches/manifest.json"
    if manifest.exists():
        summary["expectedRecords"] = decode_json(manifest.read_bytes())["totalRecords"]
    write_json(directory / "summary.json", summary)
    config["status"] = summary["status"]
    write_json(directory / "run.json", config)
    print(f"Run {config['runId']}: {summary['status']}; acknowledged={summary['acknowledgedRecords']}; totalMs={summary['totalElapsedMs']}")


def parser():
    root = argparse.ArgumentParser()
    commands = root.add_subparsers(dest="command", required=True)
    init = commands.add_parser("init"); init.add_argument("--output-root", required=True)
    prepare_parser = commands.add_parser("prepare")
    for name in ("run-dir", "csv", "jar", "java", "query-file", "batch-size", "timeout", "auth", "profile",
                 "transform-url", "load-url", "query-url", "transform-region", "load-region", "query-region"):
        prepare_parser.add_argument("--" + name, required=True)
    batch_parser = commands.add_parser("batch"); batch_parser.add_argument("--run-dir", required=True)
    request_parser = commands.add_parser("request"); request_parser.add_argument("--run-dir", required=True)
    request_parser.add_argument("--stage", choices=("transform", "load", "query"), required=True)
    request_parser.add_argument("--input", required=True)
    request_parser.add_argument("--expected", default="")
    request_parser.add_argument("--batch-index", type=int)
    request_parser.add_argument("--expected-count", type=int)
    final_parser = commands.add_parser("finalize"); final_parser.add_argument("--run-dir", required=True)
    final_parser.add_argument("--exit-code", type=int, required=True)
    return root


def main():
    args = parser().parse_args()
    try:
        if args.command == "init":
            run_id = "run-" + dt.datetime.now(dt.timezone.utc).strftime("%Y%m%dT%H%M%SZ") + "-" + uuid.uuid4().hex
            directory = Path(args.output_root).resolve() / run_id
            directory.mkdir(parents=True, exist_ok=False)
            (directory / "responses").mkdir()
            write_json(directory / "run.json", {"runId": run_id, "startedAt": now(), "startedPerfNs": time.perf_counter_ns(), "status": "RUNNING"})
            print(directory.as_posix())
        else:
            directory = Path(args.run_dir)
            if args.command == "prepare": prepare(args, directory)
            elif args.command == "batch": batch(directory)
            elif args.command == "request": request(args, directory)
            else: finalize(args, directory)
    except Exception as failure:
        print(f"TLQ stopped: {failure.code if isinstance(failure, Failure) else 'LOCAL_CLIENT_ERROR'}; no automatic retry", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
