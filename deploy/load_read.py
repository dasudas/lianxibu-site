"""Bounded public-GET measurements; not a full browser or production capacity qualification."""
from __future__ import annotations
import argparse
from concurrent.futures import ThreadPoolExecutor
import ipaddress
import json
import math
import re
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
from collections import Counter

class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None

def target(base, path):
    parsed = urllib.parse.urlsplit(base)
    try:
        loopback = ipaddress.ip_address(parsed.hostname or "").is_loopback
    except ValueError:
        loopback = parsed.hostname == "localhost"
    if parsed.scheme not in {"https", "http"} or parsed.scheme == "http" and not loopback or not parsed.hostname or parsed.username or parsed.password or parsed.query or parsed.fragment:
        raise ValueError("an HTTPS or loopback HTTP base without credentials is required")
    if parsed.path.rstrip("/") not in {"", "/api/business/v1", "/api/business"}:
        raise ValueError("public API base required")
    if not re.fullmatch(r"/(?:home|capabilities|reports|interpretations)(?:/[A-Za-z0-9._:-]{1,64})?", path):
        raise ValueError("only public read paths without query data are accepted")
    return base.rstrip("/") + path

def run(base, paths, users, duration, timeout, think, network_profile=None, error_limit=None, p95_limit=None):
    urls = [target(base, path) for path in paths]
    if not urls or len(urls) > 20 or type(users) is not int or not 1 <= users <= 200:
        raise ValueError("one to twenty public paths and one to two hundred users required")
    if any(not math.isfinite(value) for value in (duration, timeout, think)) or not 0 < duration <= 3600 or not 0 < timeout <= 60 or not 0.001 <= think <= 60:
        raise ValueError("bounded duration, timeout and pacing required")
    if error_limit is not None and (not math.isfinite(error_limit) or not 0 <= error_limit <= 1):
        raise ValueError("error limit must be a fraction")
    if p95_limit is not None and (not math.isfinite(p95_limit) or p95_limit <= 0):
        raise ValueError("positive p95 limit required")
    if network_profile is not None and not re.fullmatch(r"[A-Za-z0-9._-]{1,80}", network_profile):
        raise ValueError("use a nonsecret label for the measured network profile")
    # Per-user streaming histograms bound memory even during long runs.
    barrier = threading.Barrier(users + 1)
    start = [0.0]
    active = [0, 0]
    lock = threading.Lock()
    def worker(index):
        opener = urllib.request.build_opener(urllib.request.ProxyHandler({}), NoRedirect())
        histogram, statuses = Counter(), Counter()
        errors = 0
        barrier.wait()
        count = 0
        while time.monotonic() < start[0] + duration:
            before = time.monotonic()
            with lock:
                active[0] += 1
                active[1] = max(active[1], active[0])
            status = "transport_error"
            try:
                request = urllib.request.Request(urls[(index + count) % len(urls)], method="GET", headers={"Accept": "application/json"})
                with opener.open(request, timeout=timeout) as response:
                    # Bound network/memory use; oversized responses are errors, never truncated successes.
                    size = 0
                    while True:
                        if time.monotonic() - before > timeout:
                            raise TimeoutError()
                        # One socket read per iteration: a trickling peer cannot keep a
                        # read-until-buffer-full call alive beyond repeated deadline checks.
                        block = response.read1(65536)
                        size += len(block)
                        if size > 8 * 1024 * 1024:
                            raise ValueError("response bound")
                        if not block:
                            break
                    status = str(response.status)
            except urllib.error.HTTPError as error:
                status = str(error.code)
                error.close()
            except (OSError, ValueError):
                pass
            finally:
                with lock:
                    active[0] -= 1
            elapsed = math.ceil((time.monotonic() - before) * 1000)
            histogram[min(elapsed, 120000)] += 1
            statuses[status] += 1
            errors += status != "200"
            count += 1
            remaining = start[0] + duration - time.monotonic()
            if remaining > 0:
                time.sleep(min(think, remaining))
        return histogram, statuses, errors
    with ThreadPoolExecutor(max_workers=users) as executor:
        futures = [executor.submit(worker, index) for index in range(users)]
        start[0] = time.monotonic()
        barrier.wait()
        results = [future.result() for future in futures]
    histogram, statuses = Counter(), Counter()
    errors = 0
    for hist, codes, failed in results:
        histogram.update(hist); statuses.update(codes); errors += failed
    requests = sum(statuses.values())
    def rank(fraction):
        desired = math.ceil(requests * fraction)
        count = 0
        for milliseconds, amount in sorted(histogram.items()):
            count += amount
            if count >= desired:
                return milliseconds
        return None
    p95 = rank(.95)
    error_rate = errors / requests if requests else None
    evaluated = bool(requests and network_profile and error_limit is not None and p95_limit is not None)
    return {"configured_users": users, "peak_inflight_requests": active[1], "duration_seconds": duration,
            "wall_seconds": round(time.monotonic() - start[0], 3), "requests": requests, "errors": errors,
            "error_rate": error_rate, "p50_ms": rank(.50), "p95_ms": p95, "p99_ms": rank(.99),
            "status_counts": dict(statuses), "network_profile": network_profile,
            "criteria_evaluated": evaluated, "criteria_met": error_rate <= error_limit and p95 <= p95_limit if evaluated else None,
            "limits": {"error_rate": error_limit, "p95_ms": p95_limit}, "capacity_qualified": False,
            "scope": "public_get_only"}

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base", required=True)
    parser.add_argument("--paths", nargs="+", required=True)
    parser.add_argument("--users", type=int, default=200)
    parser.add_argument("--duration", type=float, required=True)
    parser.add_argument("--timeout", type=float, required=True)
    parser.add_argument("--think", type=float, required=True)
    parser.add_argument("--network-profile")
    parser.add_argument("--error-limit", type=float)
    parser.add_argument("--p95-limit", type=float)
    args = parser.parse_args()
    try:
        result = run(args.base, args.paths, args.users, args.duration, args.timeout, args.think, args.network_profile, args.error_limit, args.p95_limit)
    except (ValueError, OSError):
        print(json.dumps({"error": "invalid_measurement_input", "capacity_qualified": False}))
        return 2
    print(json.dumps(result, sort_keys=True))
    return 0

if __name__ == "__main__": raise SystemExit(main())
