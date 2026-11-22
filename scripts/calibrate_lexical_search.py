#!/usr/bin/env python3
"""Measures the lexical branch against real corpus questions, for four query strategies.

Motivation: the deployed configuration indexes Spanish chunks with PostgreSQL's `english` text
search configuration and queries them with `websearch_to_tsquery`, whose semantics are AND. On the
running system this combination produced ZERO lexical candidates on every observed retrieval, which
makes "hybrid" search semantic-only in practice.

Two independent causes are separable and are measured separately here:

  configuration  `english` does not strip Spanish stopwords and does not fold Spanish accents, so
                 a question contributes lexemes like 'la', 'de' and 'qué' that must then be found
                 in the chunk. `spanish` strips them.
  semantics      AND requires EVERY content lexeme of the question to occur in ONE chunk. For a
                 natural-language question that is a very strong demand regardless of language.

Changing retrieval semantics in production must be justified by measurement rather than intuition,
so this reports, per strategy: how many questions match anything at all, how many chunks match, and
how often the document the evaluation dataset expects is actually among the matches.

    python scripts/calibrate_lexical_search.py            # 40 GROUNDED questions
    python scripts/calibrate_lexical_search.py --limit 80
"""

from __future__ import annotations

import argparse
import csv
import json
import re
import statistics
import subprocess
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent
DATASET = REPO_ROOT / "docs" / "testing" / "evaluation_dataset.csv"

WORD = re.compile(r"[0-9A-Za-zÁÉÍÓÚÜÑáéíóúüñ]+")

STRATEGIES = {
    "english_AND": ("english", "and"),
    "spanish_AND": ("spanish", "and"),
    "english_OR": ("english", "or"),
    "spanish_OR": ("spanish", "or"),
}


def psql_json(sql: str) -> list[dict]:
    result = subprocess.run(
        ["docker", "exec", "insurance-ai-postgres", "psql", "-U", "insurance_ai",
         "-d", "insurance_ai", "-t", "-A", "-c", sql],
        capture_output=True, text=True, timeout=120)
    if result.returncode != 0:
        raise RuntimeError(result.stderr.strip())
    payload = result.stdout.strip()
    return json.loads(payload) if payload and payload != "" else []


def sql_literal(text: str) -> str:
    return "'" + text.replace("'", "''") + "'"


def query_expression(question: str, config: str, semantics: str) -> str:
    """AND is what `websearch_to_tsquery` already does; OR has to be built from the lexemes."""
    if semantics == "and":
        return f"websearch_to_tsquery({sql_literal(config)}, {sql_literal(question)})"
    terms = [w.lower() for w in WORD.findall(question) if len(w) > 2]
    if not terms:
        return "NULL::tsquery"
    joined = " | ".join(terms)
    return f"to_tsquery({sql_literal(config)}, {sql_literal(joined)})"


def measure(question: str, expected: set[str], config: str, semantics: str) -> dict:
    expression = query_expression(question, config, semantics)
    # `english_*` strategies use the stored generated column, which is what the deployed adapter
    # reads. `spanish_*` must compute the vector on the fly, because no spanish column exists yet -
    # that difference is the point of the measurement.
    vector = "content_tsv" if config == "english" else "to_tsvector('spanish', v.content)"
    # vector_store's metadata carries documentId, not the document name, so the human-readable
    # name the evaluation dataset refers to only exists after joining `documents`.
    sql = f"""
        SELECT coalesce(json_agg(row_to_json(t)), '[]'::json)::text FROM (
          SELECT d.name AS document, ts_rank_cd({vector}, q.query) AS rank
          FROM public.vector_store v
          JOIN documents d ON d.id = (v.metadata ->> 'documentId')::uuid
          CROSS JOIN LATERAL (SELECT {expression} AS query) q
          WHERE {vector} @@ q.query
          ORDER BY rank DESC
          LIMIT 8
        ) t;
    """
    rows = psql_json(sql)
    documents = [(r.get("document") or "").strip().lower().replace(" ", "-") for r in rows]
    hit = any(d in expected for d in documents) if expected else None
    return {
        "matches": len(rows),
        "ranks": [float(r["rank"]) for r in rows],
        "expected_found": hit,
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--limit", type=int, default=40)
    args = parser.parse_args()

    with DATASET.open(encoding="utf-8", newline="") as handle:
        cases = [r for r in csv.DictReader(handle)
                 if r["expected_grounding"] == "GROUNDED" and r.get("expected_document")]
    cases = cases[:args.limit]

    print(f"Calibracion lexica sobre {len(cases)} preguntas GROUNDED reales")
    print("=" * 78)

    results: dict[str, list[dict]] = {name: [] for name in STRATEGIES}
    for case in cases:
        expected = {s.strip() for s in case["expected_document"].split(";") if s.strip()}
        for name, (config, semantics) in STRATEGIES.items():
            try:
                results[name].append(measure(case["question"], expected, config, semantics))
            except RuntimeError as error:
                results[name].append({"matches": 0, "ranks": [], "expected_found": False,
                                      "error": str(error)[:80]})

    print(f"\n{'estrategia':<14} {'preguntas':>10} {'con algun':>10} {'chunks':>8} {'doc esperado':>13}")
    print(f"{'':<14} {'medidas':>10} {'match':>10} {'medios':>8} {'en el top-8':>13}")
    print("-" * 78)
    for name in STRATEGIES:
        rows = results[name]
        with_match = sum(1 for r in rows if r["matches"] > 0)
        found = sum(1 for r in rows if r.get("expected_found"))
        mean_matches = statistics.fmean([r["matches"] for r in rows]) if rows else 0
        print(f"{name:<14} {len(rows):>10} {with_match:>10} {mean_matches:>8.2f} "
              f"{found:>7} ({found / len(rows):.0%})" if rows else name)

    print("\nDISTRIBUCION DE ts_rank_cd (solo coincidencias reales):")
    for name in STRATEGIES:
        ranks = [r for row in results[name] for r in row["ranks"]]
        if not ranks:
            print(f"  {name:<14} SIN COINCIDENCIAS - no hay rangos que describir")
            continue
        ordered = sorted(ranks)
        print(f"  {name:<14} n={len(ranks):<5} min={ordered[0]:.4f} "
              f"mediana={statistics.median(ordered):.4f} max={ordered[-1]:.4f}")

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
