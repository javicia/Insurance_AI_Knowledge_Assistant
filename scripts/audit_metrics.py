#!/usr/bin/env python3
"""Audits RAG metrics across the three states they can be in, which are routinely confused.

  1. DECLARED  - the metric name appears in the source tree.
  2. DEPLOYED  - the name is baked into the jar the container is actually running.
  3. LIVE      - Micrometer has registered the meter and /actuator/metrics returns it.

Collapsing these produces false claims in both directions. A metric declared but not deployed means
the running image predates the instrumentation (a rebuild is missing). A metric deployed but not
live means nothing has exercised that code path yet, because Micrometer registers meters lazily on
first record - which is a test-coverage gap, not a deployment problem. Only reading the source, or
only reading the actuator, cannot tell those two apart.

Reads the actuator from INSIDE the container: the backend's port may be taken on the host by an
unrelated application, in which case a host-side curl silently answers from that other app.

/actuator/** requires the GOVERNANCE_READ authority (SecurityConfiguration), so this authenticates
rather than treating the resulting 401 as "the backend has no metrics".
"""

from __future__ import annotations

import json
import re
import subprocess
import sys
import tempfile
import urllib.parse
import urllib.request
import zipfile
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent
BACKEND_SRC = REPO_ROOT / "backend" / "src" / "main" / "java"
CONTAINER = "insurance-ai-backend"
KEYCLOAK = "http://localhost:8180/realms/insurance-ai"
JAR_IN_CONTAINER = "/app/app.jar"
CLASS_PREFIX = "BOOT-INF/classes/com/rag/springai/insuranceai/"

METRIC_LITERAL = re.compile(r'"(rag\.[a-z._]+)"')
METRIC_IN_BYTECODE = re.compile(rb"rag\.[a-z._]+")


def token() -> str:
    """AI_USER carries GOVERNANCE_READ (JwtAuthoritiesConverter), which is what /actuator needs."""
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


def declared_in_code() -> dict[str, list[str]]:
    found: dict[str, list[str]] = {}
    for java in BACKEND_SRC.rglob("*.java"):
        for name in METRIC_LITERAL.findall(java.read_text(encoding="utf-8", errors="ignore")):
            found.setdefault(name, []).append(java.relative_to(BACKEND_SRC).as_posix())
    return found


def baked_into_deployed_jar(names: set[str]) -> set[str] | None:
    """Which of `names` are actually present in the jar the container runs, read from the artifact.

    The jar is copied out because the runtime image ships no unzip, and the string constants are
    deflated inside the class files, so grepping the jar bytes directly would find nothing.
    """
    with tempfile.TemporaryDirectory() as workdir:
        local = Path(workdir) / "app.jar"
        copied = subprocess.run(
            ["docker", "cp", f"{CONTAINER}:{JAR_IN_CONTAINER}", str(local)],
            capture_output=True, text=True, timeout=600)
        if copied.returncode != 0 or not local.exists():
            return None
        present: set[str] = set()
        with zipfile.ZipFile(local) as jar:
            for entry in jar.namelist():
                if entry.startswith(CLASS_PREFIX) and entry.endswith(".class"):
                    for raw in METRIC_IN_BYTECODE.findall(jar.read(entry)):
                        candidate = raw.decode()
                        if candidate in names:
                            present.add(candidate)
        return present


def actuator(path: str, access_token: str) -> str:
    result = subprocess.run(
        ["docker", "exec", CONTAINER, "curl", "-s", "-H", f"Authorization: Bearer {access_token}",
         f"http://localhost:8080/actuator/{path}"],
        capture_output=True, text=True, timeout=60)
    return result.stdout if result.returncode == 0 else ""


def exposed_by_running_backend(access_token: str) -> list[str] | None:
    raw = actuator("metrics", access_token)
    try:
        return json.loads(raw)["names"]
    except (json.JSONDecodeError, KeyError):
        return None


def metric_value(name: str, access_token: str) -> str:
    """A declared+exposed metric can still have COUNT 0 - meaning nothing has exercised it yet."""
    try:
        data = json.loads(actuator(f"metrics/{name}", access_token))
        parts = {m["statistic"]: m["value"] for m in data.get("measurements", [])}
        count = parts.get("COUNT")
        total = parts.get("TOTAL_TIME") or parts.get("TOTAL")
        maximum = parts.get("MAX")
        if count is None:
            return ", ".join(f"{k}={v}" for k, v in parts.items()) or "sin medidas"
        detail = f"count={int(count)}"
        if total is not None:
            detail += f" total={total:.4f}"
        if maximum:
            detail += f" max={maximum:.4f}"
        return detail
    except (json.JSONDecodeError, KeyError, TypeError):
        return "no legible"


def main() -> int:
    declared = declared_in_code()
    access_token = token()
    exposed = exposed_by_running_backend(access_token)

    print("AUDITORIA DE METRICAS RAG")
    print("=" * 78)

    if exposed is None:
        print("\nNO SE PUDO CONSULTAR EL BACKEND EN EJECUCION.")
        print("Las metricas declaradas en codigo NO pueden confirmarse como activas.")
        for name in sorted(declared):
            print(f"  DECLARADA (sin confirmar): {name}")
        return 1

    rag_exposed = sorted(n for n in exposed if n.startswith("rag"))
    deployed = baked_into_deployed_jar(set(declared) | set(rag_exposed))

    print(f"\nDeclaradas en el codigo fuente     : {len(declared)}")
    if deployed is None:
        print("Presentes en el JAR desplegado     : NO SE PUDO LEER EL JAR")
    else:
        print(f"Presentes en el JAR desplegado     : {len(deployed)}")
    print(f"Vivas en el actuator               : {len(rag_exposed)}")
    print(f"Total de metricas del actuator     : {len(exposed)}")

    def jar_state(name: str) -> str:
        if deployed is None:
            return "?"
        return "si" if name in deployed else "NO"

    print(f"\n{'metrica':<28} {'codigo':<8} {'jar':<6} {'vivo':<6} valor actual")
    print("-" * 84)
    for name in sorted(set(declared) | set(rag_exposed)):
        in_code = "si" if name in declared else "NO"
        live = "si" if name in rag_exposed else "NO"
        value = metric_value(name, access_token) if name in rag_exposed else "-"
        print(f"{name:<28} {in_code:<8} {jar_state(name):<6} {live:<6} {value}")

    if deployed is not None:
        stale = sorted(set(declared) - deployed)
        if stale:
            print("\nDECLARADAS PERO AUSENTES DEL JAR DESPLEGADO (imagen obsoleta):")
            for name in stale:
                print(f"  {name}   <- {', '.join(declared[name][:1])}")
            print("\n  La instrumentacion existe en el codigo pero no en el artefacto en ejecucion.")
            print("  Requiere `docker compose build backend` + redespliegue.")

        unexercised = sorted(deployed - set(rag_exposed))
        if unexercised:
            print("\nDESPLEGADAS PERO NO REGISTRADAS TODAVIA (nada las ha ejercitado):")
            for name in unexercised:
                print(f"  {name}")
            print("\n  Micrometer registra cada medidor en su primer registro, asi que esto es una")
            print("  laguna de cobertura del trafico generado, no un problema de despliegue.")

    print("\nOTRAS METRICAS RELEVANTES DEL ACTUATOR:")
    for prefix in ("http.server.requests", "jvm.memory.used", "hikaricp.connections",
                   "spring.kafka", "jdbc.connections"):
        matches = [n for n in exposed if n.startswith(prefix)]
        print(f"  {prefix:<28} {'presente' if matches else 'AUSENTE'}")

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
