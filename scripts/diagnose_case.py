#!/usr/bin/env python3
"""Diagnoses why a specific evaluation case retrieves (or fails to retrieve).

    python scripts/diagnose_case.py FT-254

Prints the question, what the API actually returned, and the retrieval diagnostics the backend
logged for that request - so a failure can be attributed to a concrete cause (nothing cleared the
similarity threshold, wrong document retrieved, guardrail blocked it, ...) instead of guessed at.
"""

from __future__ import annotations

import csv
import json
import subprocess
import sys
import time
import urllib.parse
import urllib.request
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent
DATASET = REPO_ROOT / "docs" / "testing" / "evaluation_dataset.csv"
WAF = "http://localhost:8000"
KEYCLOAK = "http://localhost:8180/realms/insurance-ai"


def token() -> str:
    body = urllib.parse.urlencode({
        "grant_type": "password",
        "client_id": "insurance-ai-e2e-test",
        "client_secret": "e2e-test-dev-secret-never-used-in-production",
        "username": "alice.user",
        "password": "alice-password",
    }).encode()
    request = urllib.request.Request(f"{KEYCLOAK}/protocol/openid-connect/token", data=body)
    request.add_header("Content-Type", "application/x-www-form-urlencoded")
    with urllib.request.urlopen(request, timeout=30) as response:
        return json.load(response)["access_token"]


def main() -> int:
    if len(sys.argv) < 2:
        print(__doc__)
        return 2
    case_id = sys.argv[1]

    with DATASET.open(encoding="utf-8", newline="") as handle:
        case = next((r for r in csv.DictReader(handle) if r["id"] == case_id), None)
    if not case:
        print(f"caso {case_id} no encontrado")
        return 1

    trace = f"diag-{case_id}-{int(time.time())}"
    payload = json.dumps({"question": case["question"]}).encode("utf-8")
    request = urllib.request.Request(f"{WAF}/api/chat", data=payload, headers={
        "Authorization": f"Bearer {token()}",
        "Content-Type": "application/json",
        "X-Trace-Id": trace,
    })
    with urllib.request.urlopen(request, timeout=120) as response:
        answer = json.load(response)

    print(f"CASO      : {case_id}  [{case['category']}, dificultad {case['difficulty']}]")
    print(f"PREGUNTA  : {case['question']}")
    print(f"ESPERADO  : {case['expected_grounding']} <- {case['expected_document']}")
    print(f"ESPERABA  : {case['expected_answer'][:150]}")
    print()
    print(f"OBTENIDO  : {answer['grounding']['status']}  blocked={answer.get('blocked')}  "
          f"citas={len(answer['sources'])}")
    for source in answer["sources"]:
        print(f"   cita   : {source['document']} v{source['version']} pag {source['page']}")
    print(f"RESPUESTA : {answer['answer'][:220]}")

    time.sleep(2)
    logs = subprocess.run(
        ["docker", "logs", "insurance-ai-backend", "--since", "2m"],
        capture_output=True, text=True, timeout=60)
    for line in (logs.stdout + logs.stderr).splitlines():
        if "Hybrid retrieval diagnostics" in line:
            print("\nDIAGNOSTICO DEL BACKEND (ultimo):")
            print("   " + line.split("Hybrid retrieval diagnostics:")[-1].strip())
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
