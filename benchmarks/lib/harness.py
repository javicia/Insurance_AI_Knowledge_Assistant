"""Shared benchmark machinery: config, auth, load generation, percentiles, result files.

Why a purpose-built harness rather than k6/Gatling/JMeter
--------------------------------------------------------
None of those is installed on this machine and none is a dependency of the project. Adding one
would mean either a network install that may not be reproducible here, or a whole JVM/Node
toolchain for what this project actually needs: authenticated HTTP against a rate-limited API,
with token refresh, Docker resource sampling, and result files that line up with the existing
evaluation dataset. That is a few hundred lines of standard-library Python.

The trade-off is stated honestly rather than hidden: this harness does NOT give the scheduling
fidelity of a mature load tool (no open-model arrival rates, no distributed generators). It uses a
closed model - N concurrent workers issuing requests back to back - which is appropriate for
measuring service latency under a fixed concurrency, and is the model the reports describe.
"""

from __future__ import annotations

import concurrent.futures
import csv
import json
import platform
import statistics
import subprocess
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
from dataclasses import dataclass, field, asdict
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Callable, Iterable

import yaml

REPO_ROOT = Path(__file__).resolve().parent.parent.parent
CONFIG_PATH = REPO_ROOT / "benchmarks" / "config" / "benchmark.yaml"
RESULTS_DIR = REPO_ROOT / "benchmarks" / "results"


def load_config(path: Path | None = None) -> dict:
    with (path or CONFIG_PATH).open(encoding="utf-8") as handle:
        return yaml.safe_load(handle)


# --------------------------------------------------------------------------------------------
# Authentication
# --------------------------------------------------------------------------------------------

class TokenProvider:
    """Fetches and refreshes a Keycloak access token.

    Tokens live ~5 minutes. A benchmark that runs longer than that - which every sustained or
    concurrency sweep does - will otherwise start returning 401 halfway through and report it as
    an application error rate, which would be a fabricated result.
    """

    def __init__(self, auth: dict):
        self._auth = auth
        self._lock = threading.Lock()
        self._token: str | None = None
        self._fetched_at = 0.0
        self._refresh_after = float(auth.get("refresh_seconds", 240))

    def token(self) -> str:
        with self._lock:
            if self._token is None or (time.time() - self._fetched_at) > self._refresh_after:
                self._token = self._fetch()
                self._fetched_at = time.time()
            return self._token

    def _fetch(self) -> str:
        body = urllib.parse.urlencode({
            "grant_type": "password",
            "client_id": self._auth["client_id"],
            "client_secret": self._auth["client_secret"],
            "username": self._auth["username"],
            "password": self._auth["password"],
        }).encode()
        request = urllib.request.Request(
            f"{self._auth['issuer']}/protocol/openid-connect/token", data=body)
        request.add_header("Content-Type", "application/x-www-form-urlencoded")
        with urllib.request.urlopen(request, timeout=30) as response:
            return json.load(response)["access_token"]


# --------------------------------------------------------------------------------------------
# Measurement primitives
# --------------------------------------------------------------------------------------------

@dataclass
class RequestOutcome:
    latency_ms: float
    status: int
    ok: bool
    error: str | None = None
    # RAG-specific observations, present only for /api/chat responses.
    grounding: str | None = None
    citations: int | None = None
    blocked: bool | None = None
    trace_id: str | None = None


@dataclass
class LatencyStats:
    count: int
    min_ms: float
    p50_ms: float
    p75_ms: float
    p90_ms: float
    p95_ms: float
    p99_ms: float
    max_ms: float
    mean_ms: float
    stdev_ms: float

    @staticmethod
    def of(samples: list[float]) -> "LatencyStats | None":
        if not samples:
            return None
        ordered = sorted(samples)

        def percentile(fraction: float) -> float:
            # Nearest-rank percentile: with the small sample sizes used here it is honest and
            # reproducible, and it never invents a value that was not actually observed.
            if len(ordered) == 1:
                return ordered[0]
            rank = max(1, int(round(fraction * len(ordered))))
            return ordered[min(rank, len(ordered)) - 1]

        return LatencyStats(
            count=len(ordered),
            min_ms=round(ordered[0], 2),
            p50_ms=round(percentile(0.50), 2),
            p75_ms=round(percentile(0.75), 2),
            p90_ms=round(percentile(0.90), 2),
            p95_ms=round(percentile(0.95), 2),
            p99_ms=round(percentile(0.99), 2),
            max_ms=round(ordered[-1], 2),
            mean_ms=round(statistics.fmean(ordered), 2),
            stdev_ms=round(statistics.stdev(ordered), 2) if len(ordered) > 1 else 0.0,
        )


@dataclass
class PhaseResult:
    name: str
    concurrency: int
    requests: int
    successes: int
    errors: int
    error_rate: float
    duration_seconds: float
    throughput_rps: float
    latency: dict | None
    status_codes: dict[str, int] = field(default_factory=dict)
    rag: dict = field(default_factory=dict)
    notes: list[str] = field(default_factory=list)


def post_chat(base_url: str, token: str, question: str, timeout: int) -> RequestOutcome:
    payload = json.dumps({"question": question}).encode("utf-8")
    request = urllib.request.Request(f"{base_url}/api/chat", data=payload, headers={
        "Authorization": f"Bearer {token}",
        "Content-Type": "application/json",
    })
    started = time.perf_counter()
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            body = json.load(response)
            elapsed = (time.perf_counter() - started) * 1000
            return RequestOutcome(
                latency_ms=elapsed,
                status=response.status,
                ok=True,
                grounding=(body.get("grounding") or {}).get("status"),
                citations=len(body.get("sources") or []),
                blocked=body.get("blocked"),
                trace_id=body.get("traceId"),
            )
    except urllib.error.HTTPError as error:
        elapsed = (time.perf_counter() - started) * 1000
        return RequestOutcome(elapsed, error.code, False, error=f"HTTP {error.code}")
    except Exception as error:  # noqa: BLE001 - any transport failure is a measurement result
        elapsed = (time.perf_counter() - started) * 1000
        return RequestOutcome(elapsed, 0, False, error=type(error).__name__)


def run_phase(name: str, concurrency: int, total_requests: int,
              call: Callable[[int], RequestOutcome]) -> PhaseResult:
    """Closed-model load: `concurrency` workers issue requests back to back until the budget runs out."""
    outcomes: list[RequestOutcome] = []
    lock = threading.Lock()
    counter = {"issued": 0}

    def worker() -> None:
        while True:
            with lock:
                if counter["issued"] >= total_requests:
                    return
                index = counter["issued"]
                counter["issued"] += 1
            outcome = call(index)
            with lock:
                outcomes.append(outcome)

    started = time.perf_counter()
    with concurrent.futures.ThreadPoolExecutor(max_workers=concurrency) as pool:
        for _ in range(concurrency):
            pool.submit(worker)
    duration = time.perf_counter() - started

    successes = [o for o in outcomes if o.ok]
    statuses: dict[str, int] = {}
    for outcome in outcomes:
        key = str(outcome.status)
        statuses[key] = statuses.get(key, 0) + 1

    grounded = sum(1 for o in successes if o.grounding == "GROUNDED")
    not_grounded = sum(1 for o in successes if o.grounding == "NOT_GROUNDED")
    citations = [o.citations for o in successes if o.citations is not None]

    return PhaseResult(
        name=name,
        concurrency=concurrency,
        requests=len(outcomes),
        successes=len(successes),
        errors=len(outcomes) - len(successes),
        error_rate=round((len(outcomes) - len(successes)) / len(outcomes), 4) if outcomes else 0.0,
        duration_seconds=round(duration, 2),
        throughput_rps=round(len(outcomes) / duration, 2) if duration > 0 else 0.0,
        latency=asdict(LatencyStats.of([o.latency_ms for o in successes])) if successes else None,
        status_codes=statuses,
        rag={
            "grounded": grounded,
            "not_grounded": not_grounded,
            "grounding_rate": round(grounded / len(successes), 4) if successes else None,
            "mean_citations": round(statistics.fmean(citations), 2) if citations else None,
        },
    )


# --------------------------------------------------------------------------------------------
# Docker resource sampling
# --------------------------------------------------------------------------------------------

class ResourceSampler:
    """Samples `docker stats` in the background for the duration of a phase.

    `docker stats --no-stream` is one process spawn per sample, which is why the interval is
    seconds rather than milliseconds. It is the same source the project already uses to reason
    about container limits, so the numbers are comparable with what an operator would see.
    """

    def __init__(self, containers: list[str], interval: float = 2.0):
        self._containers = containers
        self._interval = interval
        self._samples: list[dict] = []
        self._stop = threading.Event()
        self._thread: threading.Thread | None = None

    def __enter__(self) -> "ResourceSampler":
        self._thread = threading.Thread(target=self._loop, daemon=True)
        self._thread.start()
        return self

    def __exit__(self, *_: Any) -> None:
        self._stop.set()
        if self._thread:
            self._thread.join(timeout=10)

    def _loop(self) -> None:
        while not self._stop.is_set():
            try:
                completed = subprocess.run(
                    ["docker", "stats", "--no-stream", "--format",
                     "{{.Name}};{{.CPUPerc}};{{.MemUsage}};{{.MemPerc}};{{.NetIO}};{{.BlockIO}};{{.PIDs}}",
                     *self._containers],
                    capture_output=True, text=True, timeout=30)
                timestamp = datetime.now(timezone.utc).isoformat()
                for line in completed.stdout.strip().splitlines():
                    parts = line.split(";")
                    if len(parts) != 7:
                        continue
                    self._samples.append({
                        "timestamp": timestamp,
                        "container": parts[0],
                        "cpu_percent": _percent(parts[1]),
                        "mem_usage": parts[2],
                        "mem_mib": _mem_to_mib(parts[2]),
                        "mem_percent": _percent(parts[3]),
                        "net_io": parts[4],
                        "block_io": parts[5],
                        "pids": _int(parts[6]),
                    })
            except Exception:  # noqa: BLE001 - sampling must never break the benchmark
                pass
            self._stop.wait(self._interval)

    @property
    def samples(self) -> list[dict]:
        return list(self._samples)

    def summary(self) -> dict:
        """Peak and mean per container - the two numbers that actually inform sizing."""
        by_container: dict[str, dict] = {}
        for sample in self._samples:
            entry = by_container.setdefault(sample["container"], {"cpu": [], "mem": []})
            if sample["cpu_percent"] is not None:
                entry["cpu"].append(sample["cpu_percent"])
            if sample["mem_mib"] is not None:
                entry["mem"].append(sample["mem_mib"])
        return {
            name: {
                "cpu_percent_mean": round(statistics.fmean(v["cpu"]), 2) if v["cpu"] else None,
                "cpu_percent_peak": round(max(v["cpu"]), 2) if v["cpu"] else None,
                "mem_mib_mean": round(statistics.fmean(v["mem"]), 1) if v["mem"] else None,
                "mem_mib_peak": round(max(v["mem"]), 1) if v["mem"] else None,
                "samples": len(v["cpu"]),
            }
            for name, v in sorted(by_container.items())
        }


def _percent(text: str) -> float | None:
    try:
        return float(text.strip().rstrip("%"))
    except (ValueError, AttributeError):
        return None


def _int(text: str) -> int | None:
    try:
        return int(text.strip())
    except (ValueError, AttributeError):
        return None


def _mem_to_mib(text: str) -> float | None:
    """`docker stats` reports e.g. "350.7MiB / 1.5GiB" - take the used side, normalised to MiB."""
    try:
        used = text.split("/")[0].strip()
        for suffix, factor in (("GiB", 1024.0), ("MiB", 1.0), ("KiB", 1 / 1024.0), ("B", 1 / 1048576.0)):
            if used.endswith(suffix):
                return float(used[: -len(suffix)]) * factor
        return None
    except (ValueError, AttributeError, IndexError):
        return None


# --------------------------------------------------------------------------------------------
# Environment capture and result files
# --------------------------------------------------------------------------------------------

def git_commit() -> str | None:
    try:
        completed = subprocess.run(["git", "rev-parse", "--short", "HEAD"],
                                   cwd=REPO_ROOT, capture_output=True, text=True, timeout=15)
        return completed.stdout.strip() or None
    except Exception:  # noqa: BLE001
        return None


def backend_provider() -> str | None:
    """Reads the provider from the running container - never assumed, because a benchmark run
    against the `fake` provider labelled as a real one would be worthless."""
    try:
        completed = subprocess.run(
            ["docker", "exec", "insurance-ai-backend", "printenv", "INSURANCE_AI_PROVIDER"],
            capture_output=True, text=True, timeout=20)
        return completed.stdout.strip() or "openai (default)"
    except Exception:  # noqa: BLE001
        return None


def backend_property(name: str) -> str | None:
    try:
        completed = subprocess.run(["docker", "exec", "insurance-ai-backend", "printenv", name],
                                   capture_output=True, text=True, timeout=20)
        return completed.stdout.strip() or None
    except Exception:  # noqa: BLE001
        return None


def environment_snapshot(config: dict) -> dict:
    return {
        "captured_at": datetime.now(timezone.utc).isoformat(),
        "git_commit": git_commit(),
        "host_os": platform.platform(),
        "python": platform.python_version(),
        "environment_name": config.get("environment", {}).get("name"),
        "environment_notes": config.get("environment", {}).get("notes"),
        "llm_provider": backend_provider(),
        "semantic_similarity_threshold": backend_property("INSURANCE_AI_RAG_SEMANTIC_SIMILARITY_THRESHOLD")
                                          or "0.75 (default from application.yaml)",
    }


def write_result(name: str, payload: dict) -> Path:
    RESULTS_DIR.mkdir(parents=True, exist_ok=True)
    stamp = datetime.now(timezone.utc).strftime("%Y-%m-%dT%H%M%SZ")
    path = RESULTS_DIR / f"{stamp}-{name}.json"
    path.write_text(json.dumps(payload, indent=2, ensure_ascii=False), encoding="utf-8")
    return path


def write_csv(name: str, rows: Iterable[dict], fieldnames: list[str]) -> Path:
    RESULTS_DIR.mkdir(parents=True, exist_ok=True)
    stamp = datetime.now(timezone.utc).strftime("%Y-%m-%dT%H%M%SZ")
    path = RESULTS_DIR / f"{stamp}-{name}.csv"
    with path.open("w", encoding="utf-8", newline="") as handle:
        writer = csv.DictWriter(handle, fieldnames=fieldnames, extrasaction="ignore")
        writer.writeheader()
        writer.writerows(rows)
    return path


def load_questions(config: dict, limit: int | None = None) -> list[str]:
    """Load-test questions from the Spanish functional dataset.

    Only answerable/refusable cases are used. BLOCKED (prompt-injection) cases are excluded on
    purpose: they short-circuit before retrieval and before the LLM, so mixing them in would make
    the pipeline look faster than it is.
    """
    dataset = REPO_ROOT / config["dataset"]["path"]
    wanted = set(config["dataset"]["categories_for_load"])
    questions: list[str] = []
    with dataset.open(encoding="utf-8", newline="") as handle:
        for row in csv.DictReader(handle):
            if row["category"] in wanted and row["expected_grounding"] in {"GROUNDED", "NO_ANSWER"}:
                questions.append(row["question"])
    if limit:
        questions = questions[:limit]
    return questions
