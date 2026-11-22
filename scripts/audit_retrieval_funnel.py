#!/usr/bin/env python3
"""Aggregates the retrieval funnel (semantic -> lexical -> fused -> reranked -> final) from the
RUNNING backend's logs.

    python scripts/audit_retrieval_funnel.py                  # everything still in the log buffer
    python scripts/audit_retrieval_funnel.py --since 20m      # only the last 20 minutes

Why the logs and not the database or the actuator:

  - `ai_audit_records` persists only three of the five stage counts (semantic, lexical, final).
    The fused and reranked counts are computed by HybridRetrievalService and never stored, so a
    SQL query cannot reconstruct the middle of the funnel.
  - `/actuator/metrics` exposes retrieval LATENCY, not candidate counts, and Micrometer keeps no
    per-request history anyway.

HybridRetrievalService logs all five at INFO on every single retrieval, which makes the container
log the only complete source. This reads it rather than inferring the missing stages, which would
be invention.
"""

from __future__ import annotations

import argparse
import re
import statistics
import subprocess
from collections import Counter

CONTAINER = "insurance-ai-backend"

DIAGNOSTICS = re.compile(
    r"Hybrid retrieval diagnostics: outcome=(?P<outcome>\S+) "
    r"semanticCandidates=(?P<semantic>\d+) lexicalCandidates=(?P<lexical>\d+) "
    r"fusedCandidates=(?P<fused>\d+) rerankedCandidates=(?P<reranked>\d+) "
    r"finalCandidates=(?P<final>\d+)")

STAGES = ("semantic", "lexical", "fused", "reranked", "final")


def container_logs(since: str | None) -> str:
    command = ["docker", "logs", CONTAINER]
    if since:
        command += ["--since", since]
    result = subprocess.run(command, capture_output=True, text=True, timeout=300)
    # The backend writes application logs to stderr via Logback's console appender.
    return result.stdout + result.stderr


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--since", default=None,
                        help="docker logs --since value, e.g. 20m or 2026-08-14T13:00:00")
    args = parser.parse_args()

    matches = [m.groupdict() for m in DIAGNOSTICS.finditer(container_logs(args.since))]
    if not matches:
        print("NO SE ENCONTRARON DIAGNOSTICOS DE RECUPERACION en la ventana solicitada.")
        print("El embudo NO puede reportarse: no hay datos, que no es lo mismo que ceros.")
        return 1

    print("EMBUDO DE RECUPERACION (medido en los logs del backend en ejecucion)")
    print("=" * 78)
    print(f"\nRecuperaciones observadas: {len(matches)}\n")

    print(f"{'etapa':<12} {'media':>8} {'mediana':>8} {'min':>6} {'max':>6} {'=0':>6}")
    print("-" * 52)
    for stage in STAGES:
        values = [int(m[stage]) for m in matches]
        empty = sum(1 for v in values if v == 0)
        print(f"{stage:<12} {statistics.fmean(values):>8.2f} {statistics.median(values):>8.1f} "
              f"{min(values):>6} {max(values):>6} {empty:>6}")

    print("\nDESENLACE DE LA RECUPERACION:")
    outcomes = Counter(m["outcome"] for m in matches)
    for outcome, count in outcomes.most_common():
        print(f"  {outcome:<28} {count:>5}  ({count / len(matches):.1%})")

    empty_final = sum(1 for m in matches if int(m["final"]) == 0)
    print(f"\nTasa de recuperacion vacia (finalCandidates=0): {empty_final / len(matches):.1%} "
          f"({empty_final}/{len(matches)})")

    only_semantic = sum(1 for m in matches if int(m["lexical"]) == 0 and int(m["semantic"]) > 0)
    only_lexical = sum(1 for m in matches if int(m["semantic"]) == 0 and int(m["lexical"]) > 0)
    print(f"Solo aporto la rama semantica                 : {only_semantic}")
    print(f"Solo aporto la rama lexica                    : {only_lexical}")

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
