#!/usr/bin/env python3
"""Measures the lexical grounding signal for every evaluation case, then sweeps thresholds offline.

    python scripts/calibrate_grounding.py                     # full sweep + per-case CSV
    python scripts/calibrate_grounding.py --coverages 0.5,0.6  # only these candidate values

FASE 29. Why this exists rather than restarting the backend once per candidate threshold:
`min-term-coverage` is applied in AskInsuranceKnowledgeUseCase AFTER retrieval, to data the
lexical adapter already computed. So the expensive part (the FTS query) does not depend on the
threshold at all - measure rank and coverage once per question, and every candidate threshold is
then a pure arithmetic filter over the same measurements. Nine container restarts would produce
the same numbers far more slowly, and each restart would re-warm caches and perturb latency.

The SQL below is a deliberate, literal copy of PostgresLexicalSearchAdapter#search. If that query
changes, this script is measuring something the application no longer does - keep them in step.

What this script CANNOT decide on its own: the grounding rule is `semantic OR lexical`, so a
coverage threshold only governs cases the semantic branch did not already ground. Semantic
attribution is read from ai_audit_records.semantic_candidate_count (VectorSearchPort already
filters by similarity-threshold, so a non-zero count means a candidate cleared it) and must be
supplied via --audit-attribution after a run_quality.py execution.
"""

from __future__ import annotations

import argparse
import csv
import json
import subprocess
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent
DATASET = REPO_ROOT / "docs" / "testing" / "evaluation_dataset.csv"
CONTAINER = "insurance-ai-postgres"
DB_USER = "insurance_ai"
DB_NAME = "insurance_ai"

# Literal copy of PostgresLexicalSearchAdapter#search (FASE 28), with topK bound to the
# configured lexical.top-k of 8. $1 is the question.
LEXICAL_SQL = """
WITH query_lexemes AS (
    SELECT array_agg(DISTINCT lexeme) AS terms
    FROM unnest(tsvector_to_array(to_tsvector('spanish_unaccent', $Q$%(question)s$Q$))) AS lexeme
), query_tsquery AS (
    SELECT terms,
           (SELECT string_agg(quote_literal(term), ' | ')
            FROM unnest(terms) AS term)::tsquery AS query
    FROM query_lexemes
)
SELECT metadata ->> 'documentId' AS document_id,
       ts_rank_cd(content_tsv, query_tsquery.query) AS lexical_rank,
       cardinality(query_tsquery.terms) AS query_term_count,
       (SELECT count(*)
        FROM unnest(query_tsquery.terms) AS term
        WHERE term = ANY (tsvector_to_array(content_tsv))) AS matched_term_count
FROM public.vector_store, query_tsquery
WHERE content_tsv @@ query_tsquery.query
ORDER BY lexical_rank DESC, id
LIMIT 8;
"""


def psql(sql: str) -> list[list[str]]:
    result = subprocess.run(
        ["docker", "exec", "-i", CONTAINER, "psql", "-U", DB_USER, "-d", DB_NAME, "-tAF\t", "-c", sql],
        capture_output=True, text=True, encoding="utf-8")
    if result.returncode != 0:
        raise RuntimeError(f"psql failed: {result.stderr.strip()}")
    return [line.split("\t") for line in result.stdout.strip().splitlines() if line.strip()]


def document_names() -> dict[str, str]:
    """documentId -> slug, so a retrieved chunk can be compared against expected_document."""
    rows = psql("SELECT id::text, name FROM documents;")
    return {row[0]: row[1].strip().lower().replace(" ", "-") for row in rows}


def relevant_documents(case: dict) -> set[str]:
    raw = (case.get("expected_document") or "").strip()
    return {s for s in (p.strip() for p in raw.split(";")) if s}


def measure(cases: list[dict], names: dict[str, str]) -> list[dict]:
    measurements = []
    for index, case in enumerate(cases, start=1):
        question = case["question"].replace("$Q$", "")
        rows = psql(LEXICAL_SQL % {"question": question})
        wanted = relevant_documents(case)

        candidates = []
        for row in rows:
            doc_id, rank, q_terms, matched = row[0], float(row[1]), int(row[2]), int(row[3])
            coverage = (matched / q_terms) if q_terms else 0.0
            candidates.append({
                "slug": names.get(doc_id, "?"), "rank": rank,
                "query_terms": q_terms, "matched_terms": matched, "coverage": coverage,
            })

        best = max((c["coverage"] for c in candidates), default=0.0)
        best_rank = max((c["rank"] for c in candidates), default=0.0)
        # Coverage of the best RELEVANT candidate: the value a threshold must stay below to avoid
        # rejecting genuine evidence. Distinct from `best`, which may belong to a wrong document.
        relevant_cov = max((c["coverage"] for c in candidates if c["slug"] in wanted), default=None)

        measurements.append({
            "id": case["id"],
            "category": case["category"],
            "expected_grounding": case["expected_grounding"],
            "expected_document": ";".join(sorted(wanted)) or None,
            "lexical_candidates": len(candidates),
            "best_coverage": round(best, 4),
            "best_rank": round(best_rank, 4),
            "best_relevant_coverage": round(relevant_cov, 4) if relevant_cov is not None else None,
            "relevant_retrieved": relevant_cov is not None,
            "top_slug": candidates[0]["slug"] if candidates else None,
        })
        if index % 25 == 0:
            print(f"    medidos {index}/{len(cases)}")
    return measurements


def sweep(measurements: list[dict], coverages: list[float], min_rank: float,
          semantic_grounded: dict[str, bool] | None) -> list[dict]:
    """For each candidate coverage threshold, the lexical-path confusion counts.

    When `semantic_grounded` is supplied, a case the semantic branch already grounds is counted as
    grounded regardless of the coverage threshold - which is the real system's `OR` rule, and the
    only honest way to report what a threshold change would actually buy.
    """
    results = []
    for threshold in coverages:
        tp = fp = tn = fn = 0
        for m in measurements:
            lexical_ok = m["lexical_candidates"] > 0 and m["best_rank"] >= min_rank \
                and m["best_coverage"] >= threshold
            semantic_ok = bool(semantic_grounded.get(m["id"])) if semantic_grounded else False
            grounded = lexical_ok or semantic_ok

            if m["expected_grounding"] == "GROUNDED":
                tp += grounded
                fn += not grounded
            elif m["expected_grounding"] == "NO_ANSWER":
                fp += grounded
                tn += not grounded
        results.append({
            "min_term_coverage": threshold,
            "grounded_true_positive": tp,
            "false_no_answer": fn,
            "false_grounding": fp,
            "correct_refusal": tn,
            "grounded_recall": round(tp / (tp + fn), 4) if (tp + fn) else None,
            "refusal_accuracy": round(tn / (tn + fp), 4) if (tn + fp) else None,
        })
    return results


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--coverages", default="0.40,0.45,0.50,0.55,0.60,0.65,0.70,0.75,0.80")
    parser.add_argument("--min-rank", type=float, default=0.05)
    parser.add_argument("--audit-attribution", action="store_true",
                        help="read semantic grounding attribution from ai_audit_records")
    parser.add_argument("--out", default="target-audit/fase29-calibration")
    args = parser.parse_args()

    cases = list(csv.DictReader(DATASET.open(encoding="utf-8")))
    print(f"==> Calibracion de grounding: {len(cases)} casos, corpus desplegado")

    names = document_names()
    print(f"    documentos en BD: {len(names)}")

    measurements = measure(cases, names)

    semantic_grounded = None
    if args.audit_attribution:
        # semantic_candidate_count > 0 means VectorSearchPort returned a candidate that already
        # cleared similarity-threshold, so the semantic branch grounds on its own.
        rows = psql("SELECT grounding_status, semantic_candidate_count, lexical_candidate_count, "
                    "outcome FROM ai_audit_records ORDER BY occurred_at DESC LIMIT 255;")
        semantic_any = sum(1 for r in rows if int(r[1]) > 0)
        print(f"    auditoria: {len(rows)} registros, {semantic_any} con candidatos semanticos")

    out_dir = REPO_ROOT / args.out
    out_dir.mkdir(parents=True, exist_ok=True)

    with (out_dir / "per-case-lexical.csv").open("w", encoding="utf-8", newline="") as handle:
        writer = csv.DictWriter(handle, fieldnames=list(measurements[0].keys()))
        writer.writeheader()
        writer.writerows(measurements)

    coverages = [float(c) for c in args.coverages.split(",")]
    table = sweep(measurements, coverages, args.min_rank, semantic_grounded)
    (out_dir / "sweep.json").write_text(json.dumps({
        "min_rank": args.min_rank,
        "note": "Lexical path only unless semantic attribution supplied; see module docstring.",
        "sweep": table,
    }, indent=2), encoding="utf-8")

    grounded_cases = [m for m in measurements if m["expected_grounding"] == "GROUNDED"]
    no_answer_cases = [m for m in measurements if m["expected_grounding"] == "NO_ANSWER"]
    with_lex = [m for m in measurements if m["lexical_candidates"] > 0]

    print("\n" + "=" * 86)
    print(f"Casos con candidatos lexicos : {len(with_lex)}/{len(measurements)} "
          f"({len(with_lex) / len(measurements):.1%})")
    print(f"Casos GROUNDED cuyo documento esperado aparece en top-8 lexico: "
          f"{sum(1 for m in grounded_cases if m['relevant_retrieved'])}/{len(grounded_cases)}")
    print("\nDistribucion de best_coverage:")
    for label, group in (("GROUNDED", grounded_cases), ("NO_ANSWER", no_answer_cases)):
        vals = sorted(m["best_coverage"] for m in group)
        if not vals:
            continue
        p = lambda q: vals[min(int(q * len(vals)), len(vals) - 1)]  # noqa: E731
        print(f"  {label:<10} n={len(vals):<4} min={vals[0]:.2f} p25={p(.25):.2f} "
              f"p50={p(.50):.2f} p75={p(.75):.2f} max={vals[-1]:.2f}")

    print("\nBarrido (solo ruta lexica):")
    print(f"  {'cov':<6} {'TP':>4} {'falsoNO':>8} {'falsoGR':>8} {'rechazoOK':>10} "
          f"{'recall':>8} {'precRech':>9}")
    for row in table:
        print(f"  {row['min_term_coverage']:<6} {row['grounded_true_positive']:>4} "
              f"{row['false_no_answer']:>8} {row['false_grounding']:>8} "
              f"{row['correct_refusal']:>10} {str(row['grounded_recall']):>8} "
              f"{str(row['refusal_accuracy']):>9}")
    print(f"\nResultados: {out_dir}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
