#!/usr/bin/env python3
"""RAG quality benchmark: runs the whole Spanish evaluation dataset against the RUNNING stack.

    python benchmarks/run_quality.py            # all 255 cases (~8 min, rate-limit aware)
    python benchmarks/run_quality.py --limit 40 # a shorter slice for a smoke check

This measures retrieval and behaviour, not answer wording. Under the `fake` provider the answer
text is a stub, so any judgement of answer quality would be a judgement of the stub - those
metrics are reported as UNKNOWN rather than computed from data that cannot support them.

What IS genuinely measurable under the fake provider:
  - whether the expected document was retrieved, and at which rank (Recall@K, MRR)
  - whether the pipeline grounded, refused, or blocked, versus what the case expected
  - whether a grounded answer carried at least one citation
  - end-to-end latency of the full WAF -> Gateway -> Backend path

Retrieval quality here describes THIS configuration: the semantic branch uses
FakeEmbeddingModelAdapter (bag-of-words feature hashing), not a production embedding model. The
numbers are real measurements of the deployed system; they are not a prediction of what a real
embedding model would score. That distinction is recorded in every result file.
"""

from __future__ import annotations

import argparse
import csv
import json
import sys
import time
import urllib.error
import urllib.request
from collections import defaultdict
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent / "lib"))

from harness import (  # noqa: E402
    REPO_ROOT, LatencyStats, TokenProvider, environment_snapshot, load_config, write_csv,
    write_result,
)

CONFIG = load_config()

# Ranks at which recall is reported. 8 is the configured final candidate count, so Recall@8 is the
# ceiling this pipeline can reach - reporting a larger K would always look better for free.
RECALL_AT = (1, 3, 5, 8)


def document_slug(name: str) -> str:
    """`ingest_test_corpus.sh` uploads `terceros-basico.pdf` as the document `Terceros Basico`.

    The dataset records the file stem, so one of the two has to be normalized to compare them.
    """
    return name.strip().lower().replace(" ", "-")


def ask(base_url: str, token: str, question: str, timeout: int) -> tuple[dict | None, float, int, str | None]:
    payload = json.dumps({"question": question}).encode("utf-8")
    request = urllib.request.Request(
        f"{base_url}/api/chat", data=payload,
        headers={"Authorization": f"Bearer {token}", "Content-Type": "application/json"})
    started = time.perf_counter()
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            body = json.load(response)
            return body, (time.perf_counter() - started) * 1000, response.status, None
    except urllib.error.HTTPError as error:
        return None, (time.perf_counter() - started) * 1000, error.code, f"HTTP {error.code}"
    except Exception as error:  # noqa: BLE001
        return None, (time.perf_counter() - started) * 1000, 0, type(error).__name__


def relevant_documents(case: dict) -> set[str]:
    """`expected_document` holds a `;`-separated SET of acceptable documents, not a single one.

    Many questions are legitimately answerable from more than one document (a coverage question
    answered either from the specific coverage sheet or from the general conditions). Comparing
    the raw field against one retrieved slug would score every multi-document case as a miss and
    silently understate recall.
    """
    raw = (case.get("expected_document") or "").strip()
    return {slug for slug in (part.strip() for part in raw.split(";")) if slug}


def evaluate(case: dict, answer: dict) -> dict:
    """Scores one case. Every field here is derived from the response, never assumed.

    FASE 29 fix: this read `answer["blocked"]`, a boolean the backend REMOVED in FASE 28 when it
    introduced `answerMode` (see AnswerMode.java). `.get("blocked")` therefore returned None for
    every response, so `correct` was unconditionally False for all 14 BLOCKED cases - the suite
    would have reported a guardrail regression that did not exist. Scoring now reads `answerMode`,
    the field that actually carries the discriminator.

    `GENERAL_AI_UNVERIFIED` is scored as NOT satisfying a NO_ANSWER expectation, even though its
    grounding status is NOT_GROUNDED: the system did answer, from general knowledge. Treating it
    as a correct refusal is the same measurement defect FASE 28 recorded as DEF-05 for
    EvaluationRunnerService. The fallback should be disabled for evaluation runs anyway; this
    makes an accidental run visibly wrong instead of silently flattering.
    """
    expected = case["expected_grounding"]
    status = (answer.get("grounding") or {}).get("status")
    mode = answer.get("answerMode")
    blocked = mode == "BLOCKED"
    answered_from_general_ai = mode == "GENERAL_AI_UNVERIFIED"
    sources = answer.get("sources") or []
    retrieved = [document_slug(s.get("document", "")) for s in sources]

    wanted = relevant_documents(case)
    rank = None
    if wanted:
        for position, slug in enumerate(retrieved, start=1):
            if slug in wanted:
                rank = position
                break

    if expected == "GROUNDED":
        correct = mode == "DOCUMENT_GROUNDED" and len(sources) > 0
    elif expected == "NO_ANSWER":
        correct = mode == "NO_ANSWER"
    elif expected == "BLOCKED":
        correct = blocked
    elif expected == "DEPENDS":
        # An ambiguous question may legitimately be answered or refused. The only unacceptable
        # outcome is a confident grounded answer with nothing supporting it.
        correct = not (mode == "DOCUMENT_GROUNDED" and len(sources) == 0)
    else:
        correct = False

    return {
        "id": case["id"],
        "category": case["category"],
        "difficulty": case["difficulty"],
        "expected_grounding": expected,
        "actual_grounding": status,
        "answer_mode": mode,
        "blocked": blocked,
        "answered_from_general_ai": answered_from_general_ai,
        "pii_detected": bool(answer.get("piiDetected")),
        "citations": len(sources),
        "expected_document": ";".join(sorted(wanted)) or None,
        "expected_document_rank": rank,
        "retrieved_documents": "|".join(retrieved),
        "correct": correct,
    }


def _rate(numerator: int, denominator: int) -> float | None:
    """A rate over zero observations is UNKNOWN, not 0.0 - the difference matters in a report."""
    return round(numerator / denominator, 4) if denominator else None


def aggregate(rows: list[dict]) -> dict:
    graded = [r for r in rows if r.get("answer_mode") is not None]
    by_expected: dict[str, list[dict]] = defaultdict(list)
    for row in rows:
        by_expected[row["expected_grounding"]].append(row)

    # Recall/MRR are only meaningful where the dataset names the document that should be found.
    ranked = [r for r in rows if r["expected_grounding"] == "GROUNDED" and r["expected_document"]]
    recall = {
        f"recall_at_{k}": _rate(
            sum(1 for r in ranked if r["expected_document_rank"] and r["expected_document_rank"] <= k),
            len(ranked))
        for k in RECALL_AT
    }
    reciprocal = [1.0 / r["expected_document_rank"] for r in ranked if r["expected_document_rank"]]
    grounded = by_expected.get("GROUNDED", [])

    return {
        "cases_executed": len(rows),
        "cases_answered_by_api": len(graded),
        "overall_accuracy": _rate(sum(1 for r in rows if r["correct"]), len(rows)),
        "by_expected_behaviour": {
            behaviour: {
                "cases": len(items),
                "correct": sum(1 for r in items if r["correct"]),
                "accuracy": _rate(sum(1 for r in items if r["correct"]), len(items)),
            }
            for behaviour, items in sorted(by_expected.items())
        },
        "citation_presence_on_grounded_answers": _rate(
            sum(1 for r in grounded if r["citations"] > 0), len(grounded)),
        # The two error directions, reported separately because they are not interchangeable
        # (FASE 29). A false grounding is an unsupported claim about an insurance contract; a
        # false no-answer is a usability problem. Overall accuracy averages them into one number
        # that hides which one a configuration change traded away.
        "error_directions": {
            "false_grounding_rate": _rate(
                sum(1 for r in by_expected.get("NO_ANSWER", []) if r["answer_mode"] == "DOCUMENT_GROUNDED"),
                len(by_expected.get("NO_ANSWER", []))),
            "false_no_answer_rate": _rate(
                sum(1 for r in grounded if r["answer_mode"] == "NO_ANSWER"), len(grounded)),
            "general_ai_answers": sum(1 for r in rows if r["answered_from_general_ai"]),
        },
        "retrieval": {
            "cases_with_expected_document": len(ranked),
            **recall,
            # MRR is averaged over every ranked case, counting a miss as 0 - averaging only over
            # hits would report the rank quality of the cases that already worked.
            "mrr": round(sum(reciprocal) / len(ranked), 4) if ranked else None,
            "empty_retrieval_rate": _rate(sum(1 for r in rows if r["citations"] == 0), len(rows)),
        },
        "by_category": {
            category: {
                "cases": len(items),
                "accuracy": _rate(sum(1 for r in items if r["correct"]), len(items)),
            }
            for category, items in sorted(
                ((c, [r for r in rows if r["category"] == c])
                 for c in sorted({r["category"] for r in rows})))
        },
        "by_difficulty": {
            difficulty: {
                "cases": len(items),
                "accuracy": _rate(sum(1 for r in items if r["correct"]), len(items)),
            }
            for difficulty, items in sorted(
                ((d, [r for r in rows if r["difficulty"] == d])
                 for d in sorted({r["difficulty"] for r in rows})))
        },
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--limit", type=int, default=None, help="run only the first N cases")
    parser.add_argument("--topology", default="waf", choices=sorted(CONFIG["topologies"]))
    args = parser.parse_args()

    base_url = CONFIG["topologies"][args.topology]["base_url"]
    timeout = CONFIG["workload"]["request_timeout_seconds"]
    budget = CONFIG["rate_limits"]["chat_per_minute"]
    tokens = TokenProvider(CONFIG["auth"])

    dataset = REPO_ROOT / CONFIG["dataset"]["path"]
    with dataset.open(encoding="utf-8", newline="") as handle:
        cases = list(csv.DictReader(handle))
    if args.limit:
        cases = cases[:args.limit]

    print(f"==> Calidad RAG: {len(cases)} casos contra {base_url} ({args.topology})")

    rows: list[dict] = []
    latencies: list[float] = []
    failures: list[dict] = []
    window_started = time.time()
    issued = 0

    for index, case in enumerate(cases, start=1):
        # Stay strictly inside the gateway's per-minute chat budget: exceeding it would measure
        # the rate limiter and report its 429s as pipeline errors.
        if issued and issued % (budget - 5) == 0:
            elapsed = time.time() - window_started
            if elapsed < 60:
                pause = 60 - elapsed
                print(f"    (pausa {pause:.0f}s para respetar {budget} req/min del gateway)")
                time.sleep(pause)
            window_started = time.time()

        answer, latency_ms, status, error = ask(base_url, tokens.token(), case["question"], timeout)
        issued += 1

        if answer is None and status == 429:
            time.sleep(30)
            answer, latency_ms, status, error = ask(base_url, tokens.token(), case["question"], timeout)

        if answer is None:
            failures.append({"id": case["id"], "status": status, "error": error})
            rows.append({
                "id": case["id"], "category": case["category"], "difficulty": case["difficulty"],
                "expected_grounding": case["expected_grounding"], "actual_grounding": None,
                "answer_mode": None, "blocked": False, "answered_from_general_ai": False,
                "pii_detected": False, "citations": 0,
                "expected_document": ";".join(sorted(relevant_documents(case))) or None,
                "expected_document_rank": None, "retrieved_documents": "",
                "correct": False,
            })
            print(f"  [{index:>3}/{len(cases)}] {case['id']}  ERROR {error}")
            continue

        latencies.append(latency_ms)
        row = evaluate(case, answer)
        rows.append(row)
        mark = "OK  " if row["correct"] else "FALLA"
        print(f"  [{index:>3}/{len(cases)}] {case['id']} {mark} "
              f"esperado={row['expected_grounding']:<9} obtenido={row['actual_grounding']} "
              f"citas={row['citations']} rank={row['expected_document_rank']}")

    summary = aggregate(rows)
    latency_stats = LatencyStats.of(latencies)

    payload = {
        "benchmark": "quality",
        "environment": environment_snapshot(CONFIG),
        "topology": args.topology,
        "dataset": str(dataset.relative_to(REPO_ROOT)).replace("\\", "/"),
        "measurement_caveats": [
            "El proveedor LLM activo determina si estas cifras son extrapolables. Con `fake`, la "
            "rama semantica usa FakeEmbeddingModelAdapter (hashing de bolsa de palabras), no un "
            "modelo de embeddings real: las cifras son medidas reales de ESTE despliegue, no una "
            "prediccion del rendimiento con embeddings de produccion.",
            "No se evalua la redaccion de la respuesta: con el proveedor fake el texto es un "
            "sustituto fijo. Cualquier metrica de calidad de redaccion seria UNKNOWN.",
            "El ranking compite contra TODO el espacio de busqueda embebido, no solo contra el "
            "corpus curado - ver environment.corpus en este mismo fichero.",
        ],
        "requests_failed": failures,
        "latency": latency_stats.__dict__ if latency_stats else None,
        "summary": summary,
    }

    json_path = write_result("quality", payload)
    csv_path = write_csv("quality-cases", rows, list(rows[0].keys())) if rows else None

    print("\n" + "=" * 78)
    print(f"Exactitud global            : {summary['overall_accuracy']}")
    for behaviour, stats in summary["by_expected_behaviour"].items():
        print(f"  {behaviour:<10} {stats['correct']:>3}/{stats['cases']:<4} = {stats['accuracy']}")
    print(f"Citas en respuestas GROUNDED: {summary['citation_presence_on_grounded_answers']}")
    print(f"MRR                         : {summary['retrieval']['mrr']}")
    for k in RECALL_AT:
        print(f"Recall@{k}                    : {summary['retrieval'][f'recall_at_{k}']}")
    if latency_stats:
        print(f"Latencia p50/p95/p99 (ms)   : {latency_stats.p50_ms} / "
              f"{latency_stats.p95_ms} / {latency_stats.p99_ms}")
    if failures:
        print(f"Peticiones fallidas         : {len(failures)}")
    print(f"\nResultados: {json_path.name}" + (f" / {csv_path.name}" if csv_path else ""))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
