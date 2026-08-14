#!/usr/bin/env python3
"""Runs a sample of the Spanish functional dataset against the RUNNING stack, through the WAF.

This is a smoke probe, not the authoritative evaluator: it answers "does the corpus actually
retrieve, ground and refuse correctly end to end?" for a representative slice of
docs/testing/evaluation_dataset.csv.

Usage:
    python scripts/run_functional_probe.py            # a curated sample across every category
    python scripts/run_functional_probe.py FT-001 FT-002
    python scripts/run_functional_probe.py --all      # the whole dataset (slow; rate limited)
"""

from __future__ import annotations

import csv
import json
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent
DATASET = REPO_ROOT / "docs" / "testing" / "evaluation_dataset.csv"

WAF = "http://localhost:8000"
KEYCLOAK = "http://localhost:8180"
REALM = "insurance-ai"
CLIENT_ID = "insurance-ai-e2e-test"
CLIENT_SECRET = "e2e-test-dev-secret-never-used-in-production"
USER = "alice.user"
PASSWORD = "alice-password"

# One representative case per behaviour the corpus is meant to exercise.
DEFAULT_SAMPLE = [
    "FT-001", "FT-002", "FT-016", "FT-026", "FT-036",
    "FT-044", "FT-052", "FT-060", "FT-067", "FT-074",
    "FT-106", "FT-107", "FT-120", "FT-140",
    "FT-158", "FT-170", "FT-188", "FT-200", "FT-208",
    "FT-228", "FT-234", "FT-242",
]


def token() -> str:
    from urllib.parse import urlencode
    body = urlencode({
        "grant_type": "password",
        "client_id": CLIENT_ID,
        "client_secret": CLIENT_SECRET,
        "username": USER,
        "password": PASSWORD,
    }).encode()
    request = urllib.request.Request(
        f"{KEYCLOAK}/realms/{REALM}/protocol/openid-connect/token", data=body)
    request.add_header("Content-Type", "application/x-www-form-urlencoded")
    with urllib.request.urlopen(request, timeout=30) as response:
        return json.load(response)["access_token"]


def ask(question: str, access_token: str, trace_id: str) -> dict:
    request = urllib.request.Request(
        f"{WAF}/api/chat",
        data=json.dumps({"question": question}).encode("utf-8"),
        headers={
            "Authorization": f"Bearer {access_token}",
            "Content-Type": "application/json",
            "X-Trace-Id": trace_id,
        })
    with urllib.request.urlopen(request, timeout=120) as response:
        return json.load(response)


def verdict(case: dict, answer: dict) -> tuple[bool, str]:
    expected = case["expected_grounding"]
    status = answer["grounding"]["status"]
    citations = len(answer["sources"])

    if expected == "GROUNDED":
        if status != "GROUNDED":
            return False, f"esperado GROUNDED, obtenido {status}"
        if citations == 0:
            return False, "GROUNDED sin citas"
        return True, f"GROUNDED, {citations} cita(s)"

    if expected == "NO_ANSWER":
        if status == "GROUNDED":
            return False, f"debía rehusar y respondió con {citations} cita(s)"
        return True, "rehusado correctamente"

    if expected == "BLOCKED":
        if not answer.get("blocked"):
            return False, "la inyección NO fue bloqueada"
        return True, "bloqueado por el guardarraíl"

    if expected == "DEPENDS":
        # Ambiguous questions are legitimately answerable either way; what must never happen is a
        # confident grounded answer with no supporting citation.
        if status == "GROUNDED" and citations == 0:
            return False, "GROUNDED sin citas"
        return True, f"{status}, {citations} cita(s)"

    return False, f"expected_grounding desconocido: {expected}"


def main() -> int:
    args = [a for a in sys.argv[1:] if not a.startswith("--")]
    run_all = "--all" in sys.argv

    with DATASET.open(encoding="utf-8", newline="") as handle:
        cases = {row["id"]: row for row in csv.DictReader(handle)}

    if args:
        selected = [cases[i] for i in args if i in cases]
    elif run_all:
        selected = list(cases.values())
    else:
        selected = [cases[i] for i in DEFAULT_SAMPLE if i in cases]

    access_token = token()
    passed = failed = 0
    failures: list[str] = []

    print(f"Ejecutando {len(selected)} caso(s) contra {WAF}\n")
    for index, case in enumerate(selected, start=1):
        try:
            answer = ask(case["question"], access_token, f"probe-{case['id']}")
        except urllib.error.HTTPError as error:
            if error.code == 401:
                access_token = token()
                answer = ask(case["question"], access_token, f"probe-{case['id']}")
            elif error.code == 429:
                time.sleep(int(error.headers.get("Retry-After", "10")))
                answer = ask(case["question"], access_token, f"probe-{case['id']}")
            else:
                failed += 1
                failures.append(f"{case['id']}: HTTP {error.code}")
                print(f"  FAIL {case['id']}  HTTP {error.code}")
                continue

        ok, detail = verdict(case, answer)
        if ok:
            passed += 1
            print(f"  OK   {case['id']} [{case['category']:<14}] {detail}")
        else:
            failed += 1
            failures.append(f"{case['id']} [{case['category']}]: {detail} | {case['question'][:60]}")
            print(f"  FAIL {case['id']} [{case['category']:<14}] {detail}")

        # Stay inside the gateway's 60/min chat budget.
        if index % 40 == 0:
            time.sleep(20)

    print(f"\n{passed} correcto(s), {failed} fallido(s) de {len(selected)}")
    if failures:
        print("\nFallos:")
        for failure in failures:
            print(f"  - {failure}")
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
