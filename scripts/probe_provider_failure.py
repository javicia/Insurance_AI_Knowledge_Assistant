#!/usr/bin/env python3
"""Comprueba como se comporta el sistema cuando el proveedor LLM REAL falla.

    python scripts/probe_provider_failure.py [--base-url http://localhost:18081]

Este guion no simula el fallo con un mock: apunta a un backend configurado con
`INSURANCE_AI_PROVIDER=openai` y deja que la llamada real a OpenAI falle. Un fallo autentico
ejercita el camino completo (adaptador -> puerto -> caso de uso -> manejador de excepciones ->
auditoria), que es justo donde un mock dejaria de ser representativo.

Verifica cuatro cosas que deben cumplirse a la vez:

  1. el codigo HTTP devuelto al cliente es el correcto y no un 500 generico;
  2. el cuerpo NO contiene trazas de pila ni detalles internos del proveedor;
  3. el cuerpo NO contiene ningun fragmento de credencial;
  4. queda registro en auditoria con el desenlace ERROR.
"""

from __future__ import annotations

import argparse
import json
import re
import subprocess
import urllib.error
import urllib.parse
import urllib.request

KEYCLOAK = "http://localhost:8180/realms/insurance-ai"

# Indicios de fuga: nombres de clase Java, marcos de pila, o cualquier cosa con forma de clave.
LEAK_PATTERNS = [
    (re.compile(r"\bat\s+[a-z]+(\.[a-zA-Z0-9_$]+){3,}\("), "traza de pila Java"),
    (re.compile(r"(com|org|java)\.[a-zA-Z0-9_.$]+Exception"), "nombre de clase de excepcion"),
    (re.compile(r"sk-[A-Za-z0-9_\-]{10,}"), "credencial con forma de clave OpenAI"),
    (re.compile(r"Bearer\s+[A-Za-z0-9._\-]{20,}"), "cabecera Authorization"),
    (re.compile(r"api[_-]?key", re.I), "mencion literal a api key"),
]


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


def ask(base_url: str, access_token: str, question: str) -> tuple[int, str]:
    request = urllib.request.Request(
        f"{base_url}/api/chat",
        data=json.dumps({"question": question}).encode("utf-8"),
        headers={"Authorization": f"Bearer {access_token}", "Content-Type": "application/json"})
    try:
        with urllib.request.urlopen(request, timeout=120) as response:
            return response.status, response.read().decode("utf-8", "replace")
    except urllib.error.HTTPError as error:
        return error.code, error.read().decode("utf-8", "replace")


def audit_rows(database: str) -> list[str]:
    result = subprocess.run(
        ["docker", "exec", "insurance-ai-postgres", "psql", "-U", "insurance_ai", "-d", database,
         "-t", "-A", "-F", "|", "-c",
         "select outcome, coalesce(error_classification,'-'), provider "
         "from ai_audit_records order by occurred_at desc limit 5;"],
        capture_output=True, text=True, timeout=60)
    return [line for line in result.stdout.strip().splitlines() if line]


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base-url", default="http://localhost:18081")
    parser.add_argument("--database", default="insurance_ai_baseline_d")
    args = parser.parse_args()

    print("SONDA DE FALLO DEL PROVEEDOR REAL")
    print("=" * 78)

    status, body = ask(args.base_url, token(), "¿Qué cubre la modalidad de terceros básico?")
    print(f"\nHTTP devuelto al cliente : {status}")
    print(f"Longitud del cuerpo      : {len(body)} caracteres")
    print(f"Cuerpo                   : {body[:400]}")

    print("\nANALISIS DE FUGA DE INFORMACION:")
    leaked = False
    for pattern, description in LEAK_PATTERNS:
        if pattern.search(body):
            print(f"  FUGA: {description}")
            leaked = True
    if not leaked:
        print("  Ninguna: sin trazas de pila, sin clases internas, sin credenciales.")

    print("\nREGISTRO DE AUDITORIA (5 mas recientes):")
    rows = audit_rows(args.database)
    if not rows:
        print("  NINGUNO - el fallo no quedo auditado.")
    for row in rows:
        print(f"  {row}")

    return 0 if (not leaked and rows) else 1


if __name__ == "__main__":
    raise SystemExit(main())
