#!/usr/bin/env python3
"""Audits the REAL state of the corpus: files on disk, dataset rows, and what is actually in the
database - distinguishing the curated corpus from everything else that has been ingested.

This exists because "documents in the database" and "documents in the corpus" are not the same
number, and conflating them produces figures that look authoritative and are wrong. Benchmark runs
and E2E tests also ingest documents; they are real rows but they are not the corpus.
"""

from __future__ import annotations

import csv
import subprocess
from collections import Counter
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent
CORPUS = REPO_ROOT / "test-data" / "insurance" / "auto"
DATASET = REPO_ROOT / "docs" / "testing" / "evaluation_dataset.csv"


def psql(sql: str) -> str:
    result = subprocess.run(
        ["docker", "exec", "insurance-ai-postgres", "psql", "-U", "insurance_ai",
         "-d", "insurance_ai", "-t", "-A", "-c", sql],
        capture_output=True, text=True, timeout=60)
    return result.stdout.strip()


def main() -> int:
    markdown = sorted(CORPUS.rglob("*.md"))
    pdfs = sorted(CORPUS.rglob("*.pdf"))
    words = sum(len(p.read_text(encoding="utf-8").split()) for p in markdown)

    pages = None
    try:
        from pypdf import PdfReader
        pages = sum(len(PdfReader(str(p)).pages) for p in pdfs)
    except ImportError:
        pass

    print("FICHEROS EN DISCO")
    print(f"  documentos .md      : {len(markdown)}")
    print(f"  documentos .pdf     : {len(pdfs)}")
    print(f"  paginas PDF         : {pages if pages is not None else 'NO MEDIDO (falta pypdf)'}")
    print(f"  palabras            : {words:,}")

    with DATASET.open(encoding="utf-8", newline="") as handle:
        rows = list(csv.DictReader(handle))
    grounding = Counter(r["expected_grounding"] for r in rows)
    print("\nDATASET DE EVALUACION")
    print(f"  casos               : {len(rows)}")
    for key in sorted(grounding):
        print(f"  {key:<20}: {grounding[key]}")

    # The corpus documents are the ones whose name matches a corpus file stem, rendered by
    # ingest_test_corpus.sh as Title Case with spaces.
    expected_names = {p.stem.replace("-", " ").title() for p in pdfs}

    print("\nBASE DE DATOS (estado real)")
    total_docs = psql("select count(*) from documents;")
    print(f"  documentos totales  : {total_docs}")
    for line in psql("select v.status, count(*) from document_versions v group by v.status;").splitlines():
        if line:
            status, count = line.split("|")
            print(f"  version {status:<12}: {count}")
    print(f"  chunks totales      : {psql('select count(*) from document_chunks;')}")
    vectors = psql("select count(*) || '|' || count(embedding) from public.vector_store;")
    if "|" in vectors:
        total, embedded = vectors.split("|")
        print(f"  vectores            : {total} (con embedding: {embedded})")

    db_names = [n for n in psql("select name from documents order by name;").splitlines() if n]
    corpus_in_db = [n for n in db_names if n in expected_names]
    other_in_db = [n for n in db_names if n not in expected_names]

    print(f"\n  del corpus curado   : {len(corpus_in_db)} / {len(pdfs)}")
    missing = sorted(expected_names - set(db_names))
    if missing:
        print(f"  NO INGERIDOS        : {', '.join(missing)}")
    print(f"  ajenos al corpus    : {len(other_in_db)} (benchmark / E2E / pruebas previas)")
    for name in other_in_db[:12]:
        print(f"      - {name}")
    if len(other_in_db) > 12:
        print(f"      ... y {len(other_in_db) - 12} mas")

    # Chunks attributable to the curated corpus only.
    if corpus_in_db:
        quoted = ",".join("'" + n.replace("'", "''") + "'" for n in corpus_in_db)
        corpus_chunks = psql(
            f"select count(*) from document_chunks c "
            f"join documents d on d.id = c.document_id where d.name in ({quoted});")
        print(f"\n  chunks SOLO del corpus curado: {corpus_chunks}")

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
