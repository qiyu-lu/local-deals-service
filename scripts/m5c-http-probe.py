#!/usr/bin/env python3
"""Bounded HTTP batch probe used only by the M5C isolated runner."""

import argparse
import concurrent.futures
import json
import math
import pathlib
import time
import urllib.error
import urllib.request


def percentile(values, fraction):
    if not values:
        return None
    values = sorted(values)
    return round(values[min(len(values) - 1, math.ceil(len(values) * fraction) - 1)], 3)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", required=True)
    parser.add_argument("--url", action="append", required=True)
    parser.add_argument("--count", type=int, required=True)
    parser.add_argument("--parallel", type=int, required=True)
    parser.add_argument("--method", default="GET")
    parser.add_argument("--token-base", type=int)
    parser.add_argument("--same-token", action="store_true")
    parser.add_argument("--ip-mode", choices=("none", "same", "unique"), default="none")
    parser.add_argument("--ip-base", type=int, default=1)
    parser.add_argument("--json-template")
    parser.add_argument("--timeout", type=float, default=3.0)
    args = parser.parse_args()
    if args.count < 1 or args.parallel < 1 or args.parallel > 128:
        raise SystemExit("count and parallel must be bounded positive values")

    root = pathlib.Path(args.output)
    root.mkdir(parents=True, exist_ok=True)

    def request(index):
        substitutions = {"index": index, "user": (args.token_base or 0) + index}
        url = args.url[index % len(args.url)].format(**substitutions)
        headers = {"Accept": "application/json"}
        if args.token_base is not None:
            user = args.token_base if args.same_token else args.token_base + index
            headers["authorization"] = "m5c-u-{}".format(user)
        if args.ip_mode != "none":
            suffix = args.ip_base if args.ip_mode == "same" else args.ip_base + index
            headers["X-Real-IP"] = "203.0.{}.{}".format((suffix // 250) % 250, suffix % 250 + 1)
        payload = None
        if args.json_template:
            rendered_json = args.json_template.replace("{index}", str(index))
            rendered_json = rendered_json.replace("{user}", str(substitutions["user"]))
            payload = rendered_json.encode("utf-8")
            headers["Content-Type"] = "application/json"
        started = time.monotonic()
        status = 0
        body = ""
        transport_error = None
        try:
            req = urllib.request.Request(url, data=payload, headers=headers, method=args.method)
            with urllib.request.urlopen(req, timeout=args.timeout) as response:
                status = response.status
                body = response.read().decode("utf-8", errors="replace")
        except urllib.error.HTTPError as error:
            status = error.code
            body = error.read().decode("utf-8", errors="replace")
        except Exception as error:  # evidence records transport failures verbatim
            transport_error = "{}: {}".format(type(error).__name__, error)
        elapsed_ms = (time.monotonic() - started) * 1000.0
        parsed = None
        try:
            parsed = json.loads(body)
        except Exception:
            pass
        record = {
            "index": index,
            "url": url,
            "status": status,
            "elapsed_ms": round(elapsed_ms, 3),
            "body": parsed if parsed is not None else body,
            "transport_error": transport_error,
        }
        (root / "{:04d}.json".format(index)).write_text(
            json.dumps(record, ensure_ascii=False, sort_keys=True), encoding="utf-8")
        return record

    with concurrent.futures.ThreadPoolExecutor(max_workers=args.parallel) as executor:
        records = list(executor.map(request, range(args.count)))

    status_counts = {}
    code_counts = {}
    errors = 0
    times = []
    for record in records:
        if record["transport_error"]:
            errors += 1
            continue
        times.append(record["elapsed_ms"])
        status = str(record["status"])
        status_counts[status] = status_counts.get(status, 0) + 1
        body = record["body"]
        code = body.get("code") if isinstance(body, dict) else None
        code_key = str(code) if code is not None else "null"
        code_counts[code_key] = code_counts.get(code_key, 0) + 1
    summary = {
        "total": args.count,
        "transport_errors": errors,
        "status_counts": status_counts,
        "code_counts": code_counts,
        "p50_ms": percentile(times, .50),
        "p95_ms": percentile(times, .95),
        "p99_ms": percentile(times, .99),
        "max_ms": round(max(times), 3) if times else None,
    }
    (root / "summary.json").write_text(
        json.dumps(summary, ensure_ascii=False, indent=2, sort_keys=True), encoding="utf-8")
    print(json.dumps(summary, ensure_ascii=False, sort_keys=True))


if __name__ == "__main__":
    main()
