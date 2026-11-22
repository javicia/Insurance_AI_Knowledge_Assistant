#!/usr/bin/env python3
"""FASE 30 - Compara recuperacion lexica, semantica e hibrida sobre el corpus aislado.

    python scripts/fase30_compare_retrieval.py --variant lexical
    python scripts/fase30_compare_retrieval.py --variant semantic --model bge-m3:latest
    python scripts/fase30_compare_retrieval.py --variant hybrid   --model bge-m3:latest
    python scripts/fase30_compare_retrieval.py --all

QUE MIDE Y QUE NO: esto mide RECUPERACION, no grounding ni calidad de respuesta. La pregunta
que responde es "aparece el documento esperado, y en que posicion" (Recall@K, MRR). Es
deliberadamente independiente del umbral de grounding: mezclar ambas cosas fue el defecto que la
FASE 28 tuvo que desenredar, y volver a juntarlas aqui haria imposible saber si una mejora viene
del modelo o del umbral. La calibracion del umbral semantico va en
`fase30_calibrate_semantic.py`, sobre las mismas mediciones.

COMPARABILIDAD: la linea base de la FASE 29 (Recall@8 0,5621) se midio sobre 2.037 chunks, que
incluian 7 duplicados `Bench *` compitiendo por las 8 posiciones de cita. Esta evaluacion usa
1.650 chunks curados. Por eso la variante `lexical` se ejecuta tambien aqui: es la UNICA linea
base con la que las variantes semanticas son comparables. Atribuir a los embeddings la diferencia
contra la cifra de la FASE 29 seria atribuirles tambien la descontaminacion del corpus.

El SQL lexico es copia literal de PostgresLexicalSearchAdapter#search (FASE 28), de modo que la
variante A reproduce la rama lexica de produccion y no una aproximacion.
"""

from __future__ import annotations

import argparse
import csv
import json
import statistics
import subprocess
import sys
import time
import urllib.request
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
DATASET = REPO / "docs" / "testing" / "evaluation_dataset.csv"
OUT_DIR = REPO / "target-audit" / "fase30"
EVAL = dict(container="fase30-eval-postgres", user="fase30_eval", db="fase30_eval")
OLLAMA_EMBED = "http://localhost:11434/api/embed"

TOP_K = 8
RECALL_AT = (1, 3, 5, 8)
RRF_K = 60.0  # mismo valor que insurance-ai.rag.hybrid.rrf-k

MODEL_DIMS = {"bge-m3:latest": 1024, "nomic-embed-text:latest": 768}
MODEL_QUERY_PREFIX = {"bge-m3:latest": "", "nomic-embed-text:latest": "search_query: "}


def table_for(model: str) -> str:
    return "emb_" + model.replace(":", "_").replace("-", "_").replace(".", "_")


def psql(sql: str) -> list[list[str]]:
    r = subprocess.run(["docker", "exec", "-i", EVAL["container"], "psql", "-U", EVAL["user"],
                        "-d", EVAL["db"], "-v", "ON_ERROR_STOP=1", "-tAF\t", "-c", sql],
                       capture_output=True, text=True, encoding="utf-8")
    if r.returncode != 0:
        raise RuntimeError(f"psql fallo: {r.stderr.strip()[:400]}")
    return [ln.split("\t") for ln in r.stdout.strip().splitlines() if ln.strip()]


def embed_query(model: str, text: str) -> list[float]:
    prefix = MODEL_QUERY_PREFIX[model]
    body = json.dumps({"model": model, "input": prefix + text}).encode()
    req = urllib.request.Request(OLLAMA_EMBED, data=body,
                                 headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=600) as r:
        return json.load(r)["embeddings"][0]


LEXICAL_SQL = """
WITH query_lexemes AS (
    SELECT array_agg(DISTINCT lexeme) AS terms
    FROM unnest(tsvector_to_array(to_tsvector('spanish_unaccent', $Q$%(q)s$Q$))) AS lexeme
), query_tsquery AS (
    SELECT terms, (SELECT string_agg(quote_literal(term), ' | ')
                   FROM unnest(terms) AS term)::tsquery AS query
    FROM query_lexemes
)
SELECT doc_slug, ts_rank_cd(content_tsv, query_tsquery.query) AS score,
       cardinality(query_tsquery.terms) AS q_terms,
       (SELECT count(*) FROM unnest(query_tsquery.terms) AS t
        WHERE t = ANY (tsvector_to_array(content_tsv))) AS matched
FROM corpus_chunk, query_tsquery
WHERE content_tsv @@ query_tsquery.query
ORDER BY score DESC, id
LIMIT %(k)d;
"""

SEMANTIC_SQL = """
SELECT c.doc_slug, 1 - (e.embedding <=> '%(vec)s'::vector) AS score, 0, 0
FROM %(table)s e JOIN corpus_chunk c ON c.id = e.chunk_id
ORDER BY e.embedding <=> '%(vec)s'::vector
LIMIT %(k)d;
"""


def lexical_hits(question: str, k: int = TOP_K) -> list[tuple[str, float, int, int]]:
    q = question.replace("$Q$", "")
    return [(r[0], float(r[1]), int(r[2]), int(r[3])) for r in psql(LEXICAL_SQL % {"q": q, "k": k})]


def semantic_hits(model: str, question: str, k: int = TOP_K) -> list[tuple[str, float, int, int]]:
    vec = "[" + ",".join(f"{x:.7f}" for x in embed_query(model, question)) + "]"
    rows = psql(SEMANTIC_SQL % {"vec": vec, "table": table_for(model), "k": k})
    return [(r[0], float(r[1]), 0, 0) for r in rows]


def rrf_fuse(a: list[tuple], b: list[tuple], k: int = TOP_K) -> list[tuple[str, float, int, int]]:
    """Reciprocal Rank Fusion sobre SLUGS de documento, con el mismo rrf-k de produccion.

    Se fusiona por rango y no por score porque `ts_rank_cd` (escala propia de PostgreSQL, no
    acotada) y la similitud coseno ([-1,1]) no son comparables directamente - es la misma razon
    documentada en ScoreFusion y en ADR-006.
    """
    scores: dict[str, float] = {}
    for rank, (slug, *_rest) in enumerate(a, start=1):
        scores[slug] = scores.get(slug, 0.0) + 1.0 / (RRF_K + rank)
    for rank, (slug, *_rest) in enumerate(b, start=1):
        scores[slug] = scores.get(slug, 0.0) + 1.0 / (RRF_K + rank)
    ranked = sorted(scores.items(), key=lambda kv: (-kv[1], kv[0]))
    return [(slug, score, 0, 0) for slug, score in ranked[:k]]


def wanted(case: dict) -> set[str]:
    raw = (case.get("expected_document") or "").strip()
    return {s for s in (p.strip() for p in raw.split(";")) if s}


def rank_of(hits: list[tuple], want: set[str]) -> int | None:
    """Primera posicion en que aparece CUALQUIER documento aceptable, deduplicando por slug.

    Se deduplica porque un documento con tres chunks en el top-8 ocupa tres posiciones pero es
    un unico acierto documental: contar la posicion del chunk y no la del documento inflaria o
    deprimiria Recall@K segun la fragmentacion, no segun la calidad.
    """
    seen: list[str] = []
    for slug, *_ in hits:
        if slug not in seen:
            seen.append(slug)
    for i, slug in enumerate(seen, start=1):
        if slug in want:
            return i
    return None


def evaluate(variant: str, model: str | None, cases: list[dict]) -> dict:
    ranked, latencies = [], []
    per_case = []
    for i, case in enumerate(cases, 1):
        want = wanted(case)
        t0 = time.perf_counter()
        if variant == "lexical":
            hits = lexical_hits(case["question"])
        elif variant == "semantic":
            hits = semantic_hits(model, case["question"])
        else:
            hits = rrf_fuse(lexical_hits(case["question"]), semantic_hits(model, case["question"]))
        latencies.append((time.perf_counter() - t0) * 1000)

        r = rank_of(hits, want) if want else None
        if want and case["expected_grounding"] == "GROUNDED":
            ranked.append(r)
        per_case.append({
            "id": case["id"], "category": case["category"],
            "expected_grounding": case["expected_grounding"],
            "expected_document": ";".join(sorted(want)) or None,
            "rank": r, "top_slug": hits[0][0] if hits else None,
            "top_score": round(hits[0][1], 6) if hits else None,
            "retrieved": "|".join(dict.fromkeys(s for s, *_ in hits)),
        })
        if i % 50 == 0:
            print(f"    {i}/{len(cases)}")

    n = len(ranked)
    recall = {f"recall_at_{k}": round(sum(1 for r in ranked if r and r <= k) / n, 4) if n else None
              for k in RECALL_AT}
    mrr = round(sum(1.0 / r for r in ranked if r) / n, 4) if n else None
    latencies.sort()
    pc = lambda q: round(latencies[min(int(q * len(latencies)), len(latencies) - 1)], 1)  # noqa: E731
    return {
        "variant": variant, "model": model, "ranked_cases": n,
        **recall, "mrr": mrr,
        "latency_ms": {"p50": pc(.50), "p95": pc(.95), "p99": pc(.99),
                       "mean": round(statistics.mean(latencies), 1)},
        "per_case": per_case,
    }


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--variant", choices=["lexical", "semantic", "hybrid"])
    ap.add_argument("--model", choices=sorted(MODEL_DIMS))
    ap.add_argument("--all", action="store_true")
    ap.add_argument("--limit", type=int)
    a = ap.parse_args()

    cases = list(csv.DictReader(DATASET.open(encoding="utf-8")))
    if a.limit:
        cases = cases[:a.limit]
    OUT_DIR.mkdir(parents=True, exist_ok=True)

    plan = []
    if a.all:
        plan = [("lexical", None)]
        for m in sorted(MODEL_DIMS):
            if psql(f"SELECT count(*) FROM {table_for(m)};")[0][0] != "0":
                plan += [("semantic", m), ("hybrid", m)]
            else:
                print(f"  (omitido {m}: su tabla esta vacia)")
    else:
        if not a.variant:
            ap.error("indica --variant o --all")
        if a.variant != "lexical" and not a.model:
            ap.error("--variant semantic/hybrid requiere --model")
        plan = [(a.variant, a.model)]

    results = []
    for variant, model in plan:
        label = f"{variant}" + (f" / {model}" if model else "")
        print(f"\n==> {label}  ({len(cases)} casos)")
        res = evaluate(variant, model, cases)
        results.append(res)
        print(f"    Recall@1={res['recall_at_1']} @3={res['recall_at_3']} "
              f"@5={res['recall_at_5']} @8={res['recall_at_8']} MRR={res['mrr']}")
        print(f"    latencia p50={res['latency_ms']['p50']}ms p95={res['latency_ms']['p95']}ms")

        stem = variant + ("_" + model.replace(":", "_").replace("-", "_") if model else "")
        (OUT_DIR / f"retrieval-{stem}.json").write_text(
            json.dumps({k: v for k, v in res.items() if k != "per_case"}, indent=2),
            encoding="utf-8")
        with (OUT_DIR / f"retrieval-{stem}.csv").open("w", encoding="utf-8", newline="") as fh:
            w = csv.DictWriter(fh, fieldnames=list(res["per_case"][0].keys()))
            w.writeheader()
            w.writerows(res["per_case"])

    print("\n" + "=" * 100)
    print(f"{'variante':<34} {'R@1':>7} {'R@3':>7} {'R@5':>7} {'R@8':>7} {'MRR':>7} {'p50ms':>8}")
    for r in results:
        name = r["variant"] + (f" / {r['model']}" if r["model"] else "")
        print(f"{name:<34} {r['recall_at_1']:>7} {r['recall_at_3']:>7} {r['recall_at_5']:>7} "
              f"{r['recall_at_8']:>7} {r['mrr']:>7} {r['latency_ms']['p50']:>8}")
    print(f"\nResultados en {OUT_DIR}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
