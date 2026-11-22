#!/usr/bin/env python3
"""Tests whether OR-semantics lexical search can separate in-corpus from out-of-corpus questions.

Why this measurement has to exist before the lexical branch is changed
---------------------------------------------------------------------
`insurance-ai.rag.lexical.min-rank` was calibrated under AND-semantics, where an out-of-corpus
question matched ZERO chunks: the match operator itself rejected it, so the threshold never had to
discriminate anything. Under OR-semantics that is no longer true - every question matches many
chunks, so the threshold becomes the ONLY thing standing between a question the corpus cannot
answer and a confidently grounded answer.

`AskInsuranceKnowledgeUseCase` grounds a request when a final candidate clears the semantic
threshold OR clears `lexical.min-rank`. So adopting OR-semantics without re-deriving that value
would risk converting correct refusals (58 NO_ANSWER cases) into false grounded answers. This
script measures both populations and reports whether a separating value exists at all - and says
so plainly if it does not, rather than picking a number that merely looks reasonable.

    python scripts/calibrate_lexical_or_threshold.py
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


def psql(sql: str) -> str:
    result = subprocess.run(
        ["docker", "exec", "insurance-ai-postgres", "psql", "-U", "insurance_ai",
         "-d", "insurance_ai", "-t", "-A", "-c", sql],
        capture_output=True, text=True, timeout=120)
    if result.returncode != 0:
        raise RuntimeError(result.stderr.strip())
    return result.stdout.strip()


def sql_literal(text: str) -> str:
    return "'" + text.replace("'", "''") + "'"


def top_rank(question: str) -> float:
    """The best ts_rank_cd any chunk achieves for this question under spanish + OR.

    The best rank is what matters: grounding needs only ONE candidate to clear the threshold.
    """
    terms = [w.lower() for w in WORD.findall(question) if len(w) > 2]
    if not terms:
        return 0.0
    expression = (f"to_tsquery('spanish', {sql_literal(' | '.join(terms))})")
    value = psql(f"""
        SELECT coalesce(max(ts_rank_cd(to_tsvector('spanish', content), q.query)), 0)
        FROM public.vector_store
        CROSS JOIN LATERAL (SELECT {expression} AS query) q
        WHERE to_tsvector('spanish', content) @@ q.query;
    """)
    return float(value) if value else 0.0


def describe(name: str, values: list[float]) -> None:
    if not values:
        print(f"  {name:<24} SIN DATOS")
        return
    ordered = sorted(values)
    print(f"  {name:<24} n={len(ordered):<4} min={ordered[0]:.4f} p25={ordered[len(ordered)//4]:.4f} "
          f"mediana={statistics.median(ordered):.4f} p75={ordered[3*len(ordered)//4]:.4f} "
          f"max={ordered[-1]:.4f}")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--limit", type=int, default=40,
                        help="questions sampled from EACH population")
    args = parser.parse_args()

    with DATASET.open(encoding="utf-8", newline="") as handle:
        rows = list(csv.DictReader(handle))

    in_corpus = [r for r in rows if r["expected_grounding"] == "GROUNDED"][:args.limit]
    out_corpus = [r for r in rows if r["expected_grounding"] == "NO_ANSWER"][:args.limit]

    print("CALIBRACION DE min-rank BAJO SEMANTICA OR (configuracion 'spanish')")
    print("=" * 78)
    print(f"\nPreguntas dentro del corpus : {len(in_corpus)}")
    print(f"Preguntas fuera del corpus  : {len(out_corpus)}\n")

    inside = [top_rank(r["question"]) for r in in_corpus]
    outside = [top_rank(r["question"]) for r in out_corpus]

    print("DISTRIBUCION DEL MEJOR ts_rank_cd POR PREGUNTA:")
    describe("dentro del corpus", inside)
    describe("fuera del corpus", outside)

    best_outside = max(outside) if outside else 0.0
    worst_inside = min(inside) if inside else 0.0

    print(f"\nPeor pregunta legitima      : {worst_inside:.4f}")
    print(f"Mejor pregunta fuera        : {best_outside:.4f}")

    if worst_inside > best_outside:
        print(f"\nEXISTE un umbral separador: cualquier valor en ({best_outside:.4f}, "
              f"{worst_inside:.4f}] clasifica correctamente las dos poblaciones medidas.")
        return 0

    overlap_inside = sum(1 for v in inside if v <= best_outside)
    overlap_outside = sum(1 for v in outside if v >= worst_inside)
    print("\nNO EXISTE un umbral que separe ambas poblaciones: los rangos se solapan.")
    print(f"  preguntas legitimas por debajo del mejor 'fuera' : {overlap_inside}/{len(inside)}")
    print(f"  preguntas fuera por encima de la peor legitima   : {overlap_outside}/{len(outside)}")
    print("\n  Consecuencia: bajo semantica OR, ts_rank_cd por si solo NO puede decidir si el")
    print("  corpus contiene la respuesta. Adoptar OR exigiria que el rechazo dependiera de otra")
    print("  senal (la rama semantica), no de min-rank.")
    return 1


if __name__ == "__main__":
    raise SystemExit(main())
