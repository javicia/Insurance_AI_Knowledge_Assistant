#!/usr/bin/env python3
"""Benchmark runner for the Insurance Knowledge Assistant.

    python benchmarks/run_benchmark.py quick        # warm-up + 1/5/10 concurrency  (~3 min)
    python benchmarks/run_benchmark.py full         # adds 25/50 + sustained load   (~15 min)
    python benchmarks/run_benchmark.py topology     # WAF vs Gateway vs backend-direct
    python benchmarks/run_benchmark.py coldwarm     # cold first request vs warm
    python benchmarks/run_benchmark.py spike        # 1 -> 10 -> 50 -> 100 burst
    python benchmarks/run_benchmark.py ingestion    # document upload -> EMBEDDED
    python benchmarks/run_benchmark.py resources    # idle resource baseline

Every run writes a machine-readable JSON (and CSV where a table is the natural shape) under
benchmarks/results/, stamped with the git commit, the LLM provider actually in use, and the
retrieval threshold actually configured - so a result can never be silently misattributed to a
configuration it was not produced under.
"""

from __future__ import annotations

import itertools
import json
import subprocess
import sys
import time
import urllib.error
from dataclasses import asdict
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent / "lib"))

from harness import (  # noqa: E402
    REPO_ROOT, PhaseResult, ResourceSampler, TokenProvider, environment_snapshot,
    load_config, load_questions, post_chat, run_phase, write_csv, write_result,
)

CONFIG = load_config()


def _chat_caller(base_url: str, tokens: TokenProvider, questions: list[str], timeout: int):
    def call(index: int):
        return post_chat(base_url, tokens.token(), questions[index % len(questions)], timeout)
    return call


def _respect_chat_budget(requests_issued: int) -> None:
    """The gateway allows 60 chat requests/min per identity. Exceeding it measures the rate
    limiter, not the RAG pipeline, so the runner pauses instead of producing 429-polluted data."""
    limit = CONFIG["rate_limits"]["chat_per_minute"]
    if requests_issued and requests_issued % limit == 0:
        print(f"    (pausa 60s para respetar el límite de {limit} req/min del gateway)")
        time.sleep(60)


def warmup(base_url: str, tokens: TokenProvider, questions: list[str]) -> dict:
    """A cold JVM/connection pool/page cache makes the first requests unrepresentative."""
    count = CONFIG["workload"]["warmup_requests"]
    timeout = CONFIG["workload"]["request_timeout_seconds"]
    print(f"==> Calentamiento: {count} peticiones")
    result = run_phase("warmup", 1, count, _chat_caller(base_url, tokens, questions, timeout))
    print(f"    p50={result.latency['p50_ms'] if result.latency else 'n/a'} ms, "
          f"errores={result.errors}")
    return asdict(result)


def concurrency_sweep(levels: list[int]) -> dict:
    base_url = CONFIG["topologies"]["waf"]["base_url"]
    tokens = TokenProvider(CONFIG["auth"])
    questions = load_questions(CONFIG)
    timeout = CONFIG["workload"]["request_timeout_seconds"]
    per_level = CONFIG["workload"]["requests_per_level"]

    payload = {
        "benchmark": "concurrency",
        "environment": environment_snapshot(CONFIG),
        "topology": "waf",
        "questions_available": len(questions),
        "requests_per_level": per_level,
        "phases": [],
        "resources": {},
    }
    payload["warmup"] = warmup(base_url, tokens, questions)

    for level in levels:
        print(f"==> Concurrencia {level}: {per_level} peticiones")
        with ResourceSampler(CONFIG["resources"]["containers"],
                             CONFIG["resources"]["sample_interval_seconds"]) as sampler:
            result = run_phase(f"concurrency-{level}", level, per_level,
                               _chat_caller(base_url, tokens, questions, timeout))
        payload["phases"].append(asdict(result))
        payload["resources"][f"concurrency-{level}"] = sampler.summary()

        latency = result.latency or {}
        print(f"    throughput={result.throughput_rps} rps  p50={latency.get('p50_ms')} "
              f"p95={latency.get('p95_ms')} p99={latency.get('p99_ms')} ms  "
              f"errores={result.errors} ({result.error_rate:.1%})")

        # If the environment (not the application) has become the limiting factor, stop climbing
        # and say so, rather than reporting numbers that describe Docker Desktop's scheduler.
        if result.error_rate > 0.5:
            payload["phases"][-1]["notes"].append(
                "Escalado detenido: tasa de error >50%, el entorno deja de ser representativo.")
            print("    (escalado detenido: tasa de error >50%)")
            break
        _respect_chat_budget(per_level)

    path = write_result("concurrency", payload)
    rows = []
    for phase in payload["phases"]:
        latency = phase["latency"] or {}
        rows.append({
            "concurrency": phase["concurrency"], "requests": phase["requests"],
            "errors": phase["errors"], "error_rate": phase["error_rate"],
            "throughput_rps": phase["throughput_rps"],
            "p50_ms": latency.get("p50_ms"), "p95_ms": latency.get("p95_ms"),
            "p99_ms": latency.get("p99_ms"), "max_ms": latency.get("max_ms"),
            "grounding_rate": phase["rag"].get("grounding_rate"),
        })
    csv_path = write_csv("concurrency", rows, list(rows[0].keys()) if rows else ["concurrency"])
    print(f"\nResultados: {path.name}, {csv_path.name}")
    return payload


def topology_comparison() -> dict:
    """Isolates the cost of each security layer by measuring the same workload at three entry points."""
    tokens = TokenProvider(CONFIG["auth"])
    questions = load_questions(CONFIG)
    timeout = CONFIG["workload"]["request_timeout_seconds"]
    requests = 30

    payload = {
        "benchmark": "topology",
        "environment": environment_snapshot(CONFIG),
        "requests_per_topology": requests,
        "phases": [],
        "notes": [],
    }

    # Measured in this order deliberately: each step removes one security layer, so the deltas
    # attribute overhead to a specific component rather than to "the stack".
    for name in ("waf", "gateway", "backend"):
        base_url = CONFIG["topologies"][name].get("base_url")
        if not base_url:
            payload["notes"].append(f"{name} NO MEDIDO - sin base_url configurada.")
            continue
        print(f"==> Topología {name} ({base_url})")
        try:
            warmup(base_url, tokens, questions[:5])
            result = run_phase(f"topology-{name}", 1, requests,
                               _chat_caller(base_url, tokens, questions, timeout))
        except Exception as error:  # noqa: BLE001
            payload["notes"].append(f"{name} NO MEDIDO - {type(error).__name__}: {error}")
            print(f"    NO MEDIDO: {type(error).__name__}")
            continue
        entry = asdict(result)
        entry["description"] = CONFIG["topologies"][name]["description"]
        payload["phases"].append(entry)
        latency = result.latency or {}
        print(f"    p50={latency.get('p50_ms')} p95={latency.get('p95_ms')} ms, errores={result.errors}")
        _respect_chat_budget(requests)

    # Attribute the overhead of each layer, but only where both sides were actually measured.
    by_name = {p["name"]: p for p in payload["phases"] if p.get("latency")}
    def delta(outer: str, inner: str, label: str) -> None:
        a, b = by_name.get(f"topology-{outer}"), by_name.get(f"topology-{inner}")
        if a and b:
            payload.setdefault("overhead_ms", {})[label] = {
                "p50": round(a["latency"]["p50_ms"] - b["latency"]["p50_ms"], 2),
                "p95": round(a["latency"]["p95_ms"] - b["latency"]["p95_ms"], 2),
            }
    delta("waf", "gateway", "waf_layer")
    delta("gateway", "backend", "gateway_layer")
    delta("waf", "backend", "waf_plus_gateway")

    path = write_result("topology", payload)
    print(f"\nResultados: {path.name}")
    return payload


def cold_versus_warm() -> dict:
    """The first request after a restart pays for JIT, connection pools and caches."""
    base_url = CONFIG["topologies"]["waf"]["base_url"]
    tokens = TokenProvider(CONFIG["auth"])
    questions = load_questions(CONFIG)
    timeout = CONFIG["workload"]["request_timeout_seconds"]

    print("==> Reiniciando el backend para medir arranque en frío")
    restart_started = time.perf_counter()
    subprocess.run(["docker", "compose", "-f", "docker-compose.yml",
                    "-f", "docker-compose.corpus-es.yml", "restart", "backend"],
                   cwd=REPO_ROOT, capture_output=True, text=True, timeout=300)

    # Wait for the container to report healthy - that is the earliest a request can succeed.
    healthy_after = None
    for _ in range(120):
        probe = subprocess.run(
            ["docker", "inspect", "insurance-ai-backend", "--format", "{{.State.Health.Status}}"],
            capture_output=True, text=True, timeout=20)
        if probe.stdout.strip() == "healthy":
            healthy_after = time.perf_counter() - restart_started
            break
        time.sleep(2)

    print(f"    contenedor healthy tras {healthy_after:.1f}s" if healthy_after
          else "    el contenedor no llegó a healthy")

    first = post_chat(base_url, tokens.token(), questions[0], timeout)
    print(f"    primera petición (fría): {first.latency_ms:.0f} ms, status={first.status}")

    warm = run_phase("warm", 1, 20, _chat_caller(base_url, tokens, questions, timeout))
    latency = warm.latency or {}
    print(f"    caliente: p50={latency.get('p50_ms')} p95={latency.get('p95_ms')} ms")

    payload = {
        "benchmark": "cold_vs_warm",
        "environment": environment_snapshot(CONFIG),
        "container_healthy_after_seconds": round(healthy_after, 1) if healthy_after else None,
        "cold_first_request": asdict(first),
        "warm": asdict(warm),
        "cold_penalty_ms": round(first.latency_ms - (latency.get("p50_ms") or 0), 1)
                            if first.ok and latency else None,
    }
    path = write_result("cold-vs-warm", payload)
    print(f"\nResultados: {path.name}")
    return payload


def spike_test() -> dict:
    base_url = CONFIG["topologies"]["waf"]["base_url"]
    tokens = TokenProvider(CONFIG["auth"])
    questions = load_questions(CONFIG)
    timeout = CONFIG["workload"]["request_timeout_seconds"]

    payload = {"benchmark": "spike", "environment": environment_snapshot(CONFIG), "phases": []}
    for level in CONFIG["workload"]["spike_levels"]:
        print(f"==> Pico a {level} usuarios concurrentes")
        with ResourceSampler(CONFIG["resources"]["containers"], 1.0) as sampler:
            result = run_phase(f"spike-{level}", level, max(level, 20),
                               _chat_caller(base_url, tokens, questions, timeout))
        entry = asdict(result)
        entry["resources"] = sampler.summary()
        payload["phases"].append(entry)
        latency = result.latency or {}
        print(f"    p95={latency.get('p95_ms')} ms  errores={result.errors} "
              f"({result.error_rate:.1%})  códigos={result.status_codes}")
        _respect_chat_budget(max(level, 20))

    path = write_result("spike", payload)
    print(f"\nResultados: {path.name}")
    return payload


def sustained_load() -> dict:
    base_url = CONFIG["topologies"]["waf"]["base_url"]
    tokens = TokenProvider(CONFIG["auth"])
    questions = load_questions(CONFIG)
    timeout = CONFIG["workload"]["request_timeout_seconds"]
    duration = CONFIG["workload"]["sustained_duration_seconds"]

    # Stay just under the gateway's 60/min so the run measures the service, not the limiter.
    concurrency = 5
    budget = max(1, int((duration / 60) * CONFIG["rate_limits"]["chat_per_minute"] * 0.9))
    print(f"==> Carga sostenida ~{duration}s, concurrencia {concurrency}, {budget} peticiones")

    with ResourceSampler(CONFIG["resources"]["containers"], 5.0) as sampler:
        result = run_phase("sustained", concurrency, budget,
                           _chat_caller(base_url, tokens, questions, timeout))

    samples = sampler.samples
    backend = [s for s in samples if s["container"] == "insurance-ai-backend" and s["mem_mib"]]
    drift = None
    if len(backend) >= 4:
        half = len(backend) // 2
        first_half = sum(s["mem_mib"] for s in backend[:half]) / half
        second_half = sum(s["mem_mib"] for s in backend[half:]) / (len(backend) - half)
        drift = round(second_half - first_half, 1)

    payload = {
        "benchmark": "sustained",
        "environment": environment_snapshot(CONFIG),
        "phase": asdict(result),
        "resources": sampler.summary(),
        "backend_memory_drift_mib": drift,
        "memory_interpretation": (
            "Una deriva positiva no prueba una fuga: la JVM crece hasta su heap objetivo antes de "
            "que el GC la recorte. Sólo una deriva sostenida entre ejecuciones lo sugeriría."),
    }
    path = write_result("sustained", payload)
    latency = result.latency or {}
    print(f"    p50={latency.get('p50_ms')} p95={latency.get('p95_ms')} ms, "
          f"errores={result.errors}, deriva memoria backend={drift} MiB")
    print(f"\nResultados: {path.name}")
    return payload


def idle_resources() -> dict:
    print("==> Muestreando recursos en reposo (30s)")
    with ResourceSampler(CONFIG["resources"]["containers"], 2.0) as sampler:
        time.sleep(30)
    summary = sampler.summary()
    payload = {
        "benchmark": "resources_idle",
        "environment": environment_snapshot(CONFIG),
        "summary": summary,
    }
    path = write_result("resources-idle", payload)
    rows = [{"container": name, **values} for name, values in summary.items()]
    csv_path = write_csv("resources-idle", rows,
                         ["container", "cpu_percent_mean", "cpu_percent_peak",
                          "mem_mib_mean", "mem_mib_peak", "samples"])
    for name, values in summary.items():
        print(f"    {name:32s} CPU media {values['cpu_percent_mean']}%  "
              f"MEM media {values['mem_mib_mean']} MiB")
    print(f"\nResultados: {path.name}, {csv_path.name}")
    return payload


def _unique_pdf(source: Path) -> Path:
    """Rewrites a corpus document with a unique marker so it cannot be deduplicated.

    Reuses the project's own Markdown->PDF builder so the generated document goes through exactly
    the same layout the real corpus does - otherwise the benchmark would measure a different
    shape of document from the one the system is actually fed.
    """
    import importlib.util

    spec = importlib.util.spec_from_file_location(
        "corpus_pdf", REPO_ROOT / "scripts" / "build_test_corpus_pdfs.py")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)

    markdown_source = source.with_suffix(".md")
    marker = f"\n\nIdentificador único de ejecución de benchmark: {int(time.time() * 1000)}-{source.stem}.\n"
    text = markdown_source.read_text(encoding="utf-8") + marker

    destination = REPO_ROOT / "benchmarks" / "results" / ".tmp" / f"{source.stem}.pdf"
    destination.parent.mkdir(parents=True, exist_ok=True)
    module.render_pdf(module.parse_markdown(text), destination, source.stem)
    return destination


def ingestion_benchmark() -> dict:
    """Uploads freshly-generated PDFs and times the whole asynchronous path to EMBEDDED."""
    import urllib.request
    base_url = CONFIG["topologies"]["waf"]["base_url"]
    tokens = TokenProvider(CONFIG["auth"])

    sources = sorted((REPO_ROOT / "test-data" / "insurance" / "auto").rglob("*.pdf"))[:5]
    if not sources:
        return {"benchmark": "ingestion", "error": "NO MEDIDO - no hay PDFs del corpus"}

    # The backend deduplicates by CONTENT hash, so re-uploading a corpus PDF returns the existing
    # document in milliseconds and measures nothing. An earlier version of this benchmark did
    # exactly that and reported a bogus "34 ms to EMBEDDED". Each run therefore generates a fresh
    # PDF whose text carries a unique marker, forcing a genuine extract -> chunk -> embed -> persist
    # cycle. The documents are otherwise realistic in size and structure.
    pdfs = [_unique_pdf(source) for source in sources]

    results = []
    for pdf in pdfs:
        name = f"Bench {pdf.stem}"
        boundary = "----benchmarkboundary"
        parts = []
        for field_name, value in (("name", name), ("type", "POLICY"),
                                  ("classification", "INTERNAL"), ("language", "es")):
            parts.append(f"--{boundary}\r\nContent-Disposition: form-data; name=\"{field_name}\"\r\n\r\n{value}\r\n")
        head = "".join(parts).encode()
        file_head = (f"--{boundary}\r\nContent-Disposition: form-data; name=\"file\"; "
                     f"filename=\"{pdf.name}\"\r\nContent-Type: application/pdf\r\n\r\n").encode()
        body = head + file_head + pdf.read_bytes() + f"\r\n--{boundary}--\r\n".encode()

        request = urllib.request.Request(f"{base_url}/api/documents", data=body, headers={
            "Authorization": f"Bearer {tokens.token()}",
            "Content-Type": f"multipart/form-data; boundary={boundary}",
        })
        # /api/documents allows 10/min per identity. A 429 is the rate limiter doing its job, not
        # an ingestion failure, so wait it out rather than recording a bogus error.
        created = None
        upload_ms = 0.0
        for _ in range(6):
            started = time.perf_counter()
            try:
                with urllib.request.urlopen(request, timeout=180) as response:
                    created = json.load(response)
                upload_ms = (time.perf_counter() - started) * 1000
                break
            except urllib.error.HTTPError as error:
                if error.code == 429:
                    wait = int(error.headers.get("Retry-After", "15"))
                    print(f"    (429: esperando {wait}s por el límite de subidas)")
                    time.sleep(wait)
                    request = urllib.request.Request(f"{base_url}/api/documents", data=body, headers={
                        "Authorization": f"Bearer {tokens.token()}",
                        "Content-Type": f"multipart/form-data; boundary={boundary}",
                    })
                    continue
                results.append({"document": pdf.name, "error": f"HTTP {error.code}"})
                break
            except Exception as error:  # noqa: BLE001
                results.append({"document": pdf.name, "error": f"{type(error).__name__}"})
                break
        if created is None:
            continue

        # GET /api/documents/{id} shares the same 10/min budget as the upload, so a tight poll
        # loop exhausts it and 429s. Poll sparsely and treat a 429 as "not ready yet".
        #
        # CONSEQUENCE, stated plainly: the poll interval bounds the resolution of the
        # time-to-EMBEDDED figure. A document that finishes in 2s and one that finishes in 7s can
        # both be reported as ~8s. These numbers are therefore an UPPER BOUND on ingestion
        # latency, not a precise measurement - the precise figure would need the Kafka consumer's
        # own spans (they exist in Jaeger; see the report).
        document_id = created["id"]
        embedded_ms = None
        status = "UNKNOWN"
        poll_interval = 8
        for _ in range(30):
            time.sleep(poll_interval)
            probe = urllib.request.Request(f"{base_url}/api/documents/{document_id}",
                                           headers={"Authorization": f"Bearer {tokens.token()}"})
            try:
                with urllib.request.urlopen(probe, timeout=60) as response:
                    status = json.load(response)["versions"][0]["status"]
            except urllib.error.HTTPError as error:
                if error.code == 429:
                    continue
                raise
            if status in {"EMBEDDED", "FAILED"}:
                embedded_ms = (time.perf_counter() - started) * 1000
                break

        size_kib = round(pdf.stat().st_size / 1024, 1)
        results.append({
            "document": pdf.name, "size_kib": size_kib,
            "upload_ms": round(upload_ms, 1),
            "total_to_embedded_ms": round(embedded_ms, 1) if embedded_ms else None,
            "final_status": status,
        })
        print(f"    {pdf.name:44s} subida {upload_ms:7.0f} ms  hasta EMBEDDED "
              f"{embedded_ms:8.0f} ms" if embedded_ms else f"    {pdf.name} sin completar")
        time.sleep(7)  # respeta el limite de 10/min de /api/documents

    completed = [r for r in results if r.get("total_to_embedded_ms")]
    payload = {
        "benchmark": "ingestion",
        "environment": environment_snapshot(CONFIG),
        "documents": results,
        "summary": {
            "documents_measured": len(completed),
            "mean_upload_ms": round(sum(r["upload_ms"] for r in completed) / len(completed), 1)
                              if completed else None,
            "mean_total_to_embedded_ms": round(
                sum(r["total_to_embedded_ms"] for r in completed) / len(completed), 1)
                if completed else None,
            "documents_per_minute_observed": round(
                60000 / (sum(r["total_to_embedded_ms"] for r in completed) / len(completed)), 2)
                if completed else None,
        },
    }
    path = write_result("ingestion", payload)
    print(f"\nResultados: {path.name}")
    return payload


COMMANDS = {
    "quick": lambda: concurrency_sweep([1, 5, 10]),
    "full": lambda: concurrency_sweep(CONFIG["workload"]["concurrency_levels"]),
    "topology": topology_comparison,
    "coldwarm": cold_versus_warm,
    "spike": spike_test,
    "sustained": sustained_load,
    "resources": idle_resources,
    "ingestion": ingestion_benchmark,
}


def main() -> int:
    if len(sys.argv) < 2 or sys.argv[1] not in COMMANDS:
        print(__doc__)
        print("Comandos:", ", ".join(COMMANDS))
        return 2
    COMMANDS[sys.argv[1]]()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
