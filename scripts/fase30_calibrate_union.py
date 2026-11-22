#!/usr/bin/env python3
"""FASE 30 - Calibra la regla de grounding REAL de produccion: semantica OR lexica.

    python scripts/fase30_calibrate_union.py

Los dos barridos anteriores miden cada rama por separado, y ninguno describe lo que hace
AskInsuranceKnowledgeUseCase#isSufficientEvidence, que fundamenta si:

    (semanticScore >= similarity-threshold)  OR
    (lexicalScore >= min-rank AND lexicalTermCoverage >= min-term-coverage)

Calibrar una rama ignorando la otra produce un numero que no existe en el sistema: un umbral
semantico con 12 falsos positivos no anade 12 falsos grounding si la rama lexica ya fundamentaba
9 de ellos. Este script mide la UNION sobre el corpus aislado, que es la unica cifra con la que
se puede decidir una configuracion de produccion.

La similitud semantica se reutiliza del CSV de fase30_calibrate_semantic.py (no se vuelve a
embeber: 255 embeddings ya medidos). La cobertura lexica se calcula aqui con el SQL del adaptador
de produccion, que no necesita modelo y es practicamente instantanea.
"""

from __future__ import annotations

import argparse
import csv
import json
import subprocess
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
DATASET = REPO / "docs" / "testing" / "evaluation_dataset.csv"
OUT_DIR = REPO / "target-audit" / "fase30"
EVAL = dict(container="fase30-eval-postgres", user="fase30_eval", db="fase30_eval")

MIN_RANK = 0.05
COVERAGES = [0.50, 0.55, 0.60, 0.65]
SEMANTIC = [0.55, 0.60, 0.62, 0.65, 0.68, 0.70, 0.99]  # 0.99 == rama semantica desactivada

# Copia literal del SQL de PostgresLexicalSearchAdapter#search, adaptado a la tabla aislada.
LEXICAL_SQL = """
WITH q AS (
    SELECT array_agg(DISTINCT lexeme) AS terms
    FROM unnest(tsvector_to_array(to_tsvector('spanish_unaccent', $Q$%(q)s$Q$))) AS lexeme
), tq AS (
    SELECT terms, (SELECT string_agg(quote_literal(t), ' | ')
                   FROM unnest(terms) AS t)::tsquery AS query FROM q
)
SELECT c.doc_slug,
       ts_rank_cd(c.content_tsv, tq.query) AS rank,
       cardinality(tq.terms) AS q_terms,
       (SELECT count(*) FROM unnest(tq.terms) AS t
        WHERE t = ANY (tsvector_to_array(c.content_tsv))) AS matched
FROM corpus_chunk c, tq
WHERE c.content_tsv @@ tq.query
ORDER BY rank DESC, c.id
LIMIT 8;
"""


def psql(sql: str) -> list[list[str]]:
    r = subprocess.run(["docker", "exec", "-i", EVAL["container"], "psql", "-U", EVAL["user"],
                        "-d", EVAL["db"], "-v", "ON_ERROR_STOP=1", "-tAF\t", "-c", sql],
                       capture_output=True, text=True, encoding="utf-8")
    if r.returncode != 0:
        raise RuntimeError(f"psql fallo: {r.stderr.strip()[:300]}")
    return [ln.split("\t") for ln in r.stdout.strip().splitlines() if ln.strip()]


def wanted(case: dict) -> set[str]:
    raw = (case.get("expected_document") or "").strip()
    return {s for s in (p.strip() for p in raw.split(";")) if s}


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--semantic-csv",
                    default="target-audit/fase30/semantic-calibration-bge_m3_latest.csv")
    a = ap.parse_args()

    sem = {r["id"]: r for r in csv.DictReader((REPO / a.semantic_csv).open(encoding="utf-8"))}
    cases = list(csv.DictReader(DATASET.open(encoding="utf-8")))
    print(f"==> Union semantica+lexica sobre corpus aislado, {len(cases)} casos")

    rows = []
    for i, case in enumerate(cases, 1):
        want = wanted(case)
        hits = psql(LEXICAL_SQL % {"q": case["question"].replace("$Q$", "")})

        best_cov = 0.0
        best_rank = 0.0
        rel_cov = None
        for slug, rank, q_terms, matched in ((h[0], float(h[1]), int(h[2]), int(h[3])) for h in hits):
            cov = (matched / q_terms) if q_terms else 0.0
            best_cov = max(best_cov, cov)
            best_rank = max(best_rank, rank)
            if slug in want:
                rel_cov = max(rel_cov or 0.0, cov)

        s = sem.get(case["id"], {})
        rows.append({
            "id": case["id"], "expected_grounding": case["expected_grounding"],
            "has_expected_doc": bool(want),
            "lex_best_coverage": round(best_cov, 4), "lex_best_rank": round(best_rank, 4),
            "lex_relevant_coverage": round(rel_cov, 4) if rel_cov is not None else None,
            "sem_best_overall": float(s["best_overall"]) if s.get("best_overall") else 0.0,
            "sem_best_relevant": float(s["best_relevant"]) if s.get("best_relevant") else None,
        })
        if i % 60 == 0:
            print(f"    {i}/{len(cases)}")

    with (OUT_DIR / "union-calibration.csv").open("w", encoding="utf-8", newline="") as fh:
        w = csv.DictWriter(fh, fieldnames=list(rows[0].keys()))
        w.writeheader()
        w.writerows(rows)

    def grounded(r: dict, sem_t: float, cov_t: float) -> bool:
        # Un caso GROUNDED solo cuenta como acierto si la evidencia proviene del documento
        # CORRECTO; para NO_ANSWER basta con que algo supere el umbral, que es lo que haria el
        # sistema real al fundamentar una pregunta que no debia responder.
        if r["expected_grounding"] == "GROUNDED":
            sem_ok = r["sem_best_relevant"] is not None and r["sem_best_relevant"] >= sem_t
            lex_ok = (r["lex_relevant_coverage"] is not None
                      and r["lex_best_rank"] >= MIN_RANK
                      and r["lex_relevant_coverage"] >= cov_t)
        else:
            sem_ok = r["sem_best_overall"] >= sem_t
            lex_ok = r["lex_best_rank"] >= MIN_RANK and r["lex_best_coverage"] >= cov_t
        return sem_ok or lex_ok

    table = []
    for cov_t in COVERAGES:
        for sem_t in SEMANTIC:
            tp = fn = fp = tn = 0
            for r in rows:
                if r["expected_grounding"] == "GROUNDED":
                    if not r["has_expected_doc"]:
                        continue
                    g = grounded(r, sem_t, cov_t)
                    tp += g
                    fn += not g
                elif r["expected_grounding"] == "NO_ANSWER":
                    g = grounded(r, sem_t, cov_t)
                    fp += g
                    tn += not g
            rec = tp / (tp + fn) if (tp + fn) else 0.0
            prec = tn / (tn + fp) if (tn + fp) else 0.0
            f1 = 2 * rec * prec / (rec + prec) if (rec + prec) else 0.0
            table.append({"min_term_coverage": cov_t, "similarity_threshold": sem_t,
                          "tp": tp, "false_no_answer": fn, "false_grounding": fp,
                          "correct_refusal": tn, "grounded_recall": round(rec, 4),
                          "refusal_precision": round(prec, 4), "f1": round(f1, 4)})

    (OUT_DIR / "union-sweep.json").write_text(
        json.dumps({"min_rank": MIN_RANK, "sweep": table}, indent=2), encoding="utf-8")

    print(f"\n{'cov':>5} {'sem':>6} {'TP':>5} {'falsoNO':>8} {'falsoGR':>8} "
          f"{'rechOK':>7} {'recall':>8} {'precRech':>9} {'F1':>7}")
    print("-" * 74)
    for r in table:
        mark = ""
        if r["min_term_coverage"] == 0.60 and r["similarity_threshold"] == 0.99:
            mark = "  <- solo lexica (FASE 29)"
        print(f"{r['min_term_coverage']:>5} {r['similarity_threshold']:>6} {r['tp']:>5} "
              f"{r['false_no_answer']:>8} {r['false_grounding']:>8} {r['correct_refusal']:>7} "
              f"{r['grounded_recall']:>8} {r['refusal_precision']:>9} {r['f1']:>7}{mark}")
    print(f"\nResultados en {OUT_DIR}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
