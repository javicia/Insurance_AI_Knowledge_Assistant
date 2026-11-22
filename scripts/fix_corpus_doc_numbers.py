#!/usr/bin/env python3
"""One-off: brings every corpus figure in the documentation in line with the measured state.

The corpus grew from 25 to 26 documents (COB-DAPRO-01 was missing against the required list), so
page, word, chunk and case counts all moved. Rather than hand-editing six files and risking a
missed occurrence, the substitutions are listed here and applied exactly once each, failing loudly
if an expected string is absent - a silent no-op would leave a stale number in place.

Benchmark documents keep their ORIGINAL corpus figures where they describe the state at the time
that benchmark ran; those are annotated as historical instead of rewritten, because rewriting them
would misattribute measurements to a corpus that did not exist yet.
"""

from __future__ import annotations

import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent

# (file, old, new). Every entry must match exactly once.
SUBSTITUTIONS: list[tuple[str, str, str]] = [
    ("docs/testing/GUIA_PRUEBAS_FUNCIONALES_ES.md",
     "sobre seguros de automóvil (`test-data/insurance/auto/`, 25 documentos, 115 páginas) y su dataset\n"
     "de evaluación (`docs/testing/evaluation_dataset.csv`, 247 casos).",
     "sobre seguros de automóvil (`test-data/insurance/auto/`, 26 documentos, 119 páginas) y su dataset\n"
     "de evaluación (`docs/testing/evaluation_dataset.csv`, 255 casos)."),
    ("docs/testing/GUIA_PRUEBAS_FUNCIONALES_ES.md",
     "`docs/testing/evaluation_dataset.csv` (247 casos) es la batería funcional.",
     "`docs/testing/evaluation_dataset.csv` (255 casos) es la batería funcional."),
    ("docs/testing/GUIA_PRUEBAS_FUNCIONALES_ES.md",
     "| Ingestión | los 25 documentos en `EMBEDDED` |",
     "| Ingestión | los 26 documentos en `EMBEDDED` |"),
    ("docs/testing/GUIA_PRUEBAS_FUNCIONALES_ES.md",
     "- [ ] 25 documentos ingeridos y `EMBEDDED`",
     "- [ ] 26 documentos ingeridos y `EMBEDDED`"),

    ("docs/testing/FUNCTIONAL_TEST_MATRIX_ES.md",
     "`docs/testing/evaluation_dataset.csv` (247 casos) y conservan su **identificador `FT-xxx` y su",
     "`docs/testing/evaluation_dataset.csv` (255 casos) y conservan su **identificador `FT-xxx` y su"),
    ("docs/testing/FUNCTIONAL_TEST_MATRIX_ES.md",
     "2. Ingerir el corpus (`bash scripts/ingest_test_corpus.sh`), 25 documentos hasta `EMBEDDED`.",
     "2. Ingerir el corpus (`bash scripts/ingest_test_corpus.sh`), 26 documentos hasta `EMBEDDED`."),
    ("docs/testing/FUNCTIONAL_TEST_MATRIX_ES.md",
     "coherente con las 115 páginas del corpus",
     "coherente con las 119 páginas del corpus"),
    ("docs/testing/FUNCTIONAL_TEST_MATRIX_ES.md",
     "4. **Ingestión completa.** Los 25 documentos del corpus en estado `EMBEDDED`, con `document_chunks`",
     "4. **Ingestión completa.** Los 26 documentos del corpus en estado `EMBEDDED`, con `document_chunks`"),

    ("docs/testing/REGRESSION_TEST_CASES_ES.md",
     "bash scripts/ingest_test_corpus.sh      # 25 documentos hasta EMBEDDED",
     "bash scripts/ingest_test_corpus.sh      # 26 documentos hasta EMBEDDED"),
    ("docs/testing/REGRESSION_TEST_CASES_ES.md",
     "- `docs/testing/evaluation_dataset.csv` — dataset de 247 casos",
     "- `docs/testing/evaluation_dataset.csv` — dataset de 255 casos"),

    ("docs/testing/INSURANCE_AUTO_TEST_CORPUS_REPORT_ES.md",
     "| Fragmentos (chunks) tras la ingestión | **1.591** |\n"
     "| Vectores con embedding | **1.591 / 1.591** |",
     "| Fragmentos (chunks) del corpus curado | **1.650** |\n"
     "| Vectores con embedding | **1.650 / 1.650** |"),
    ("docs/testing/INSURANCE_AUTO_TEST_CORPUS_REPORT_ES.md",
     "  evaluation_dataset.csv             247 casos",
     "  evaluation_dataset.csv             255 casos"),
    ("docs/testing/INSURANCE_AUTO_TEST_CORPUS_REPORT_ES.md",
     "| Chunks y embeddings | **1.591 / 1.591** |",
     "| Chunks y embeddings | **1.650 / 1.650** (sólo el corpus curado) |"),

    # Benchmark documents: annotate as historical rather than rewrite - the measurements were
    # genuinely taken against the 25-document corpus and must not be reattributed.
    ("docs/benchmarks/BASELINE.md",
     "| Corpus | 25 documentos españoles, 1.591 fragmentos |",
     "| Corpus | 25 documentos españoles, 1.591 fragmentos *(estado del corpus en la fecha de esta línea base; posteriormente ampliado a 26 documentos / 1.650 fragmentos)* |"),
    ("FINAL_BENCHMARK_REPORT.md",
     "- Corpus: 25 documentos españoles, 1.591 fragmentos, 1.591 embeddings.",
     "- Corpus: 25 documentos españoles, 1.591 fragmentos, 1.591 embeddings. *(Estado en el momento\n"
     "  de la medición. El corpus se amplió después a 26 documentos y 1.650 fragmentos; las cifras\n"
     "  de rendimiento no se re-midieron, así que se dejan atribuidas al corpus que las produjo.)*"),
]


def main() -> int:
    failures: list[str] = []
    applied = 0

    for relative, old, new in SUBSTITUTIONS:
        path = REPO_ROOT / relative
        if not path.exists():
            failures.append(f"{relative}: no existe")
            continue
        text = path.read_text(encoding="utf-8")
        occurrences = text.count(old)
        if occurrences != 1:
            failures.append(f"{relative}: se esperaba 1 coincidencia, hay {occurrences} -> {old[:60]!r}")
            continue
        path.write_text(text.replace(old, new), encoding="utf-8")
        applied += 1

    print(f"sustituciones aplicadas: {applied}/{len(SUBSTITUTIONS)}")
    for failure in failures:
        print(f"  FALLO: {failure}")
    return 1 if failures else 0


if __name__ == "__main__":
    raise SystemExit(main())
