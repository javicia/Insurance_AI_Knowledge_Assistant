#!/usr/bin/env python3
"""FASE 30 - Calibra el umbral de similitud semantica para un modelo local concreto.

    python scripts/fase30_calibrate_semantic.py --model bge-m3:latest

POR QUE NO SE PUEDE REUTILIZAR 0,75: ese valor se calibro en FASE 5 contra
FakeEmbeddingModelAdapter, un hashing de bolsa de palabras cuyos cosenos no tienen ninguna
relacion con los de un modelo denso entrenado. Trasladarlo seria calibrar un instrumento con la
escala de otro. Este script mide la escala REAL del modelo sobre el corpus real.

QUE MIDE, por caso de los 255:
  - `best_overall`  : la similitud mas alta que alcanza CUALQUIER chunk. Para un caso NO_ANSWER
                      esto es un NEGATIVO: lo mas cerca que el corpus llega de una pregunta que
                      no debe responderse. Es el numero que un umbral tiene que dejar fuera.
  - `best_relevant` : la similitud mas alta entre los chunks del documento ESPERADO. Para un caso
                      GROUNDED es el POSITIVO, y es lo que un umbral demasiado alto rechazaria.
                      Sin esta columna no se puede cuantificar el coste de subir el umbral, solo
                      su beneficio - que es como se eligen umbrales demasiado altos.

Los dos no son el mismo numero ni en los casos GROUNDED: el chunk mas similar puede pertenecer a
un documento equivocado, y contar eso como evidencia valida seria medir otra cosa.
"""

from __future__ import annotations

import argparse
import csv
import json
import subprocess
import urllib.request
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
DATASET = REPO / "docs" / "testing" / "evaluation_dataset.csv"
OUT_DIR = REPO / "target-audit" / "fase30"
EVAL = dict(container="fase30-eval-postgres", user="fase30_eval", db="fase30_eval")
OLLAMA_EMBED = "http://localhost:11434/api/embed"

MODEL_QUERY_PREFIX = {"bge-m3:latest": "", "nomic-embed-text:latest": "search_query: "}
THRESHOLDS = [0.30, 0.35, 0.40, 0.45, 0.50, 0.55, 0.60, 0.65, 0.70, 0.75, 0.80]


def table_for(model: str) -> str:
    return "emb_" + model.replace(":", "_").replace("-", "_").replace(".", "_")


def psql(sql: str) -> list[list[str]]:
    r = subprocess.run(["docker", "exec", "-i", EVAL["container"], "psql", "-U", EVAL["user"],
                        "-d", EVAL["db"], "-v", "ON_ERROR_STOP=1", "-tAF\t", "-c", sql],
                       capture_output=True, text=True, encoding="utf-8")
    if r.returncode != 0:
        raise RuntimeError(f"psql fallo: {r.stderr.strip()[:400]}")
    return [ln.split("\t") for ln in r.stdout.strip().splitlines() if ln.strip()]


def embed(model: str, text: str) -> list[float]:
    body = json.dumps({"model": model, "input": MODEL_QUERY_PREFIX[model] + text}).encode()
    req = urllib.request.Request(OLLAMA_EMBED, data=body,
                                 headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=600) as r:
        return json.load(r)["embeddings"][0]


def wanted(case: dict) -> set[str]:
    raw = (case.get("expected_document") or "").strip()
    return {s for s in (p.strip() for p in raw.split(";")) if s}


def measure(model: str, cases: list[dict]) -> list[dict]:
    table = table_for(model)
    rows = []
    for i, case in enumerate(cases, 1):
        vec = "[" + ",".join(f"{x:.7f}" for x in embed(model, case["question"])) + "]"
        want = wanted(case)

        overall = psql(f"""
            SELECT c.doc_slug, 1 - (e.embedding <=> '{vec}'::vector) AS sim
            FROM {table} e JOIN corpus_chunk c ON c.id = e.chunk_id
            ORDER BY e.embedding <=> '{vec}'::vector LIMIT 1;
        """)
        best_overall = float(overall[0][1]) if overall else 0.0
        top_slug = overall[0][0] if overall else None

        best_relevant = None
        if want:
            slugs = ",".join("'" + s.replace("'", "''") + "'" for s in want)
            rel = psql(f"""
                SELECT 1 - (e.embedding <=> '{vec}'::vector) AS sim
                FROM {table} e JOIN corpus_chunk c ON c.id = e.chunk_id
                WHERE c.doc_slug IN ({slugs})
                ORDER BY e.embedding <=> '{vec}'::vector LIMIT 1;
            """)
            best_relevant = float(rel[0][0]) if rel else None

        rows.append({
            "id": case["id"], "category": case["category"],
            "expected_grounding": case["expected_grounding"],
            "expected_document": ";".join(sorted(want)) or None,
            "best_overall": round(best_overall, 6),
            "best_relevant": round(best_relevant, 6) if best_relevant is not None else None,
            "top_slug": top_slug,
            "top_is_relevant": (top_slug in want) if want else None,
        })
        if i % 40 == 0:
            print(f"    medidos {i}/{len(cases)}")
    return rows


def sweep(rows: list[dict]) -> list[dict]:
    """Matriz de confusion del PLANO SEMANTICO por umbral.

    Un caso GROUNDED cuenta como verdadero positivo solo si la similitud del documento CORRECTO
    supera el umbral: que un documento equivocado lo supere no es evidencia, es un falso
    positivo disfrazado. Los casos sin documento esperado quedan fuera del recuento, no se
    reparten arbitrariamente.
    """
    out = []
    for t in THRESHOLDS:
        tp = fn = fp = tn = 0
        for r in rows:
            if r["expected_grounding"] == "GROUNDED":
                if r["expected_document"] is None:
                    continue
                ok = r["best_relevant"] is not None and r["best_relevant"] >= t
                tp += ok
                fn += not ok
            elif r["expected_grounding"] == "NO_ANSWER":
                grounded = r["best_overall"] >= t
                fp += grounded
                tn += not grounded
        rec = tp / (tp + fn) if (tp + fn) else None
        prec_ref = tn / (tn + fp) if (tn + fp) else None
        f1 = (2 * rec * prec_ref / (rec + prec_ref)) if rec and prec_ref else None
        out.append({"threshold": t, "tp": tp, "false_no_answer": fn, "false_grounding": fp,
                    "correct_refusal": tn,
                    "grounded_recall": round(rec, 4) if rec else None,
                    "refusal_precision": round(prec_ref, 4) if prec_ref else None,
                    "f1": round(f1, 4) if f1 else None})
    return out


def pct(vals: list[float], q: float) -> float:
    s = sorted(vals)
    return s[min(int(q * len(s)), len(s) - 1)]


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--model", default="bge-m3:latest", choices=sorted(MODEL_QUERY_PREFIX))
    ap.add_argument("--limit", type=int)
    a = ap.parse_args()

    cases = list(csv.DictReader(DATASET.open(encoding="utf-8")))
    if a.limit:
        cases = cases[:a.limit]
    print(f"==> Calibracion semantica de {a.model} sobre {len(cases)} casos")

    rows = measure(a.model, cases)
    OUT_DIR.mkdir(parents=True, exist_ok=True)
    stem = a.model.replace(":", "_").replace("-", "_")
    with (OUT_DIR / f"semantic-calibration-{stem}.csv").open("w", encoding="utf-8", newline="") as fh:
        w = csv.DictWriter(fh, fieldnames=list(rows[0].keys()))
        w.writeheader()
        w.writerows(rows)

    pos = [r["best_relevant"] for r in rows
           if r["expected_grounding"] == "GROUNDED" and r["best_relevant"] is not None]
    neg = [r["best_overall"] for r in rows if r["expected_grounding"] == "NO_ANSWER"]

    print(f"\n--- Distribucion de similitud ---")
    print(f"  POSITIVOS (doc correcto, casos GROUNDED)  n={len(pos)}")
    print(f"    min={min(pos):.4f} p5={pct(pos,.05):.4f} p10={pct(pos,.10):.4f} "
          f"p25={pct(pos,.25):.4f} p50={pct(pos,.50):.4f} max={max(pos):.4f}")
    print(f"  NEGATIVOS (mejor chunk, casos NO_ANSWER)  n={len(neg)}")
    print(f"    min={min(neg):.4f} p50={pct(neg,.50):.4f} p75={pct(neg,.75):.4f} "
          f"p90={pct(neg,.90):.4f} max={max(neg):.4f}")
    print(f"  SOLAPE: p10(pos)={pct(pos,.10):.4f} vs p90(neg)={pct(neg,.90):.4f}")

    table = sweep(rows)
    (OUT_DIR / f"semantic-sweep-{stem}.json").write_text(
        json.dumps({"model": a.model, "sweep": table}, indent=2), encoding="utf-8")

    print(f"\n--- Barrido de umbral (plano semantico) ---")
    print(f"  {'umbral':<8}{'TP':>5}{'falsoNO':>9}{'falsoGR':>9}{'rechOK':>8}"
          f"{'recall':>9}{'precRech':>10}{'F1':>8}")
    for r in table:
        print(f"  {r['threshold']:<8}{r['tp']:>5}{r['false_no_answer']:>9}"
              f"{r['false_grounding']:>9}{r['correct_refusal']:>8}"
              f"{str(r['grounded_recall']):>9}{str(r['refusal_precision']):>10}{str(r['f1']):>8}")

    print(f"\n--- Casos criticos (peticiones no documentales / interdominio) ---")
    critical = {"FT-125", "FT-133", "FT-156", "FT-242", "FT-245", "FT-247", "FT-132", "FT-148"}
    for r in rows:
        if r["id"] in critical:
            print(f"  {r['id']:<8} esperado={r['expected_grounding']:<10} "
                  f"best_overall={r['best_overall']:.4f}  top={r['top_slug']}")

    print(f"\nResultados en {OUT_DIR}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
