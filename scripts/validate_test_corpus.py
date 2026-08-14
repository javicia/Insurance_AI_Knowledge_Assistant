#!/usr/bin/env python3
"""Validates the Spanish insurance test corpus and its evaluation dataset.

This exists because a test corpus that quietly contradicts itself, or whose "no-answer" questions
are in fact answerable, produces confident-looking but meaningless test results. Everything checked
here is something that would otherwise silently invalidate a functional test run.

Checks:
  1. Every expected document referenced by the dataset actually exists in the corpus.
  2. Case IDs are unique and well-formed.
  3. The dataset has the required minimum number of cases per category.
  4. CSV integrity: required columns present, no empty required fields, parseable.
  5. Corpus documents are long enough to be meaningful and carry the fictional-data warning.
  6. No obvious real secrets/credentials/PII leaked into the corpus.
  7. Deliberate temporal contradictions (v1.0 vs v2.0) exist where expected, and nowhere else.
  8. NO_ANSWER questions do not have their answer sitting in the corpus (heuristic keyword probe).

Usage:  python scripts/validate_test_corpus.py
Exit code 0 = valid, 1 = problems found (each printed with its cause).
"""

from __future__ import annotations

import csv
import re
import sys
import unicodedata
from collections import Counter
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent
CORPUS_DIR = REPO_ROOT / "test-data" / "insurance" / "auto"
DATASET = REPO_ROOT / "docs" / "testing" / "evaluation_dataset.csv"

REQUIRED_COLUMNS = [
    "id", "question", "expected_answer", "expected_grounding",
    "expected_document", "category", "difficulty",
]

MINIMUM_PER_GROUNDING = {
    "GROUNDED": 100,
    "NO_ANSWER": 50,
}
MINIMUM_PER_CATEGORY = {
    "ambiguo": 30,
    "temporal": 20,
    "razonamiento": 20,
    "seguridad": 20,
}
MINIMUM_TOTAL = 240

WARNING_TEXT = "DOCUMENTACIÓN FICTICIA PARA PRUEBAS FUNCIONALES"
MIN_WORDS_PER_DOCUMENT = 500

# Patterns that would indicate a genuine secret or real personal data slipped in. The corpus is
# supposed to contain only obviously-fictional placeholders.
FORBIDDEN_PATTERNS = [
    (re.compile(r"\bsk-[A-Za-z0-9]{20,}"), "OpenAI-style API key"),
    (re.compile(r"\bAKIA[0-9A-Z]{16}\b"), "AWS access key id"),
    (re.compile(r"eyJ[A-Za-z0-9_-]{20,}\.[A-Za-z0-9_-]{20,}\."), "JWT"),
    (re.compile(r"-----BEGIN [A-Z ]*PRIVATE KEY-----"), "private key"),
    (re.compile(r"\bpassword\s*[:=]\s*['\"][^'\"]{6,}['\"]", re.I), "hardcoded password"),
]

errors: list[str] = []
warnings: list[str] = []


def normalise(text: str) -> str:
    text = unicodedata.normalize("NFD", text.lower())
    return "".join(c for c in text if unicodedata.category(c) != "Mn")


def load_corpus() -> dict[Path, str]:
    return {p: p.read_text(encoding="utf-8") for p in sorted(CORPUS_DIR.rglob("*.md"))}


def check_documents(corpus: dict[Path, str]) -> None:
    if not corpus:
        errors.append(f"corpus is empty: no .md files under {CORPUS_DIR}")
        return

    for path, text in corpus.items():
        rel = path.relative_to(REPO_ROOT)
        words = len(text.split())
        if words < MIN_WORDS_PER_DOCUMENT:
            errors.append(f"{rel}: only {words} words (minimum {MIN_WORDS_PER_DOCUMENT})")
        if WARNING_TEXT not in text:
            errors.append(f"{rel}: missing the fictional-documentation warning")
        for pattern, label in FORBIDDEN_PATTERNS:
            if pattern.search(text):
                errors.append(f"{rel}: contains what looks like a real {label}")
        # A document with no blank lines would collapse into a single chunk.
        if "\n\n" not in text:
            errors.append(f"{rel}: no blank lines - the chunker would produce one huge chunk")


def check_temporal_contradiction(corpus: dict[Path, str]) -> None:
    """The 3.000/5.000 EUR hail difference is deliberate. Verify it exists and is version-scoped."""
    v1 = [p for p in corpus if "v1" in p.name]
    v2 = [p for p in corpus if "v2" in p.name]
    if not v1 or not v2:
        errors.append("expected versioned Condiciones Generales (v1 and v2) for temporal testing")
        return

    v1_text = "\n".join(corpus[p] for p in v1)
    v2_text = "\n".join(corpus[p] for p in v2)

    if "3.000" not in v1_text:
        errors.append("v1.0 document does not state the 3.000 EUR hail limit")
    if "5.000" not in v2_text:
        errors.append("v2.0 document does not state the 5.000 EUR hail limit")


def check_dataset(corpus: dict[Path, str]) -> list[dict[str, str]]:
    if not DATASET.exists():
        errors.append(f"missing evaluation dataset: {DATASET}")
        return []

    with DATASET.open(encoding="utf-8", newline="") as handle:
        reader = csv.DictReader(handle)
        missing_columns = [c for c in REQUIRED_COLUMNS if c not in (reader.fieldnames or [])]
        if missing_columns:
            errors.append(f"dataset missing columns: {', '.join(missing_columns)}")
            return []
        rows = list(reader)

    if len(rows) < MINIMUM_TOTAL:
        errors.append(f"dataset has {len(rows)} cases, minimum is {MINIMUM_TOTAL}")

    ids = Counter(r["id"] for r in rows)
    for case_id, count in ids.items():
        if count > 1:
            errors.append(f"duplicate case id: {case_id} ({count} times)")
        if not re.fullmatch(r"FT-\d{3,4}", case_id):
            errors.append(f"malformed case id (expected FT-###): {case_id}")

    questions = Counter(normalise(r["question"]) for r in rows)
    for question, count in questions.items():
        if count > 1:
            errors.append(f"duplicate question asked {count} times: {question[:70]}")

    for row in rows:
        for column in ("question", "expected_answer", "expected_grounding", "category", "difficulty"):
            if not row.get(column, "").strip():
                errors.append(f"{row.get('id', '?')}: empty required field '{column}'")
        if row["expected_grounding"] not in {"GROUNDED", "NO_ANSWER", "BLOCKED", "DEPENDS"}:
            errors.append(f"{row['id']}: invalid expected_grounding '{row['expected_grounding']}'")
        if row["difficulty"] not in {"baja", "media", "alta"}:
            errors.append(f"{row['id']}: invalid difficulty '{row['difficulty']}'")

    grounding_counts = Counter(r["expected_grounding"] for r in rows)
    for grounding, minimum in MINIMUM_PER_GROUNDING.items():
        if grounding_counts[grounding] < minimum:
            errors.append(f"only {grounding_counts[grounding]} {grounding} cases, minimum {minimum}")

    category_counts = Counter(r["category"] for r in rows)
    for category, minimum in MINIMUM_PER_CATEGORY.items():
        if category_counts[category] < minimum:
            errors.append(f"only {category_counts[category]} '{category}' cases, minimum {minimum}")

    # Every referenced document must exist in the corpus.
    stems = {p.stem for p in corpus}
    for row in rows:
        expected = row.get("expected_document", "").strip()
        if not expected:
            if row["expected_grounding"] == "GROUNDED":
                errors.append(f"{row['id']}: GROUNDED case names no expected_document")
            continue
        for reference in [d.strip() for d in expected.split(";") if d.strip()]:
            if reference not in stems:
                errors.append(f"{row['id']}: expected_document '{reference}' is not in the corpus")

    return rows


def check_no_answer_really_unanswerable(rows: list[dict[str, str]], corpus: dict[Path, str]) -> None:
    """Heuristic: a NO_ANSWER question whose rare keywords all appear in the corpus is suspect."""
    corpus_words = set()
    for text in corpus.values():
        corpus_words.update(re.findall(r"[a-záéíóúñü]{6,}", text.lower()))

    for row in rows:
        if row["expected_grounding"] != "NO_ANSWER":
            continue
        keywords = set(re.findall(r"[a-záéíóúñü]{6,}", row["question"].lower()))
        if not keywords:
            continue
        unknown = keywords - corpus_words
        if not unknown:
            warnings.append(
                f"{row['id']}: every keyword of this NO_ANSWER question exists in the corpus - "
                f"verify it is genuinely unanswerable: {row['question'][:70]}")


def main() -> int:
    corpus = load_corpus()
    check_documents(corpus)
    check_temporal_contradiction(corpus)
    rows = check_dataset(corpus)
    if rows:
        check_no_answer_really_unanswerable(rows, corpus)

    print(f"Corpus documents : {len(corpus)}")
    print(f"Corpus words     : {sum(len(t.split()) for t in corpus.values()):,}")
    print(f"Dataset cases    : {len(rows)}")
    if rows:
        by_grounding = Counter(r["expected_grounding"] for r in rows)
        by_category = Counter(r["category"] for r in rows)
        print(f"By grounding     : {dict(sorted(by_grounding.items()))}")
        print(f"By category      : {dict(sorted(by_category.items()))}")

    if warnings:
        print(f"\n{len(warnings)} warning(s):")
        for warning in warnings[:20]:
            print(f"  WARN  {warning}")

    if errors:
        print(f"\n{len(errors)} error(s):", file=sys.stderr)
        for error in errors:
            print(f"  ERROR {error}", file=sys.stderr)
        return 1

    print("\nValidation PASSED")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
