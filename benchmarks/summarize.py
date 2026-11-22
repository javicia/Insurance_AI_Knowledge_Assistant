#!/usr/bin/env python3
"""Summarises benchmark results, and compares two runs to detect regressions.

    python benchmarks/summarize.py                       # summarise the latest run of each kind
    python benchmarks/summarize.py compare A.json B.json  # A = baseline, B = candidate

Regression is reported as a percentage difference against the baseline:

    diff % = (candidate - baseline) / baseline * 100

Nothing here declares PASS or FAIL. This project has no agreed performance SLA, so a verdict
would be invented. Differences are labelled IMPROVED / REGRESSED / UNCHANGED against an explicit
noise band, and the band is stated in the output so the reader can disagree with it.
"""

from __future__ import annotations

import glob
import json
import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent
RESULTS = REPO_ROOT / "benchmarks" / "results"

# Below this, a difference is indistinguishable from run-to-run variance on a developer machine
# with other containers running. Measured basis: repeated identical phases in this project's own
# topology runs differed by roughly this much with no code change whatsoever.
NOISE_BAND_PERCENT = 15.0


def latest(kind: str) -> Path | None:
    matches = sorted(glob.glob(str(RESULTS / f"*{kind}.json")))
    return Path(matches[-1]) if matches else None


def _load(path: Path) -> dict:
    return json.loads(path.read_text(encoding="utf-8"))


def summarise() -> None:
    print("=" * 78)
    print("RESUMEN DE BENCHMARKS")
    print("=" * 78)

    concurrency = latest("concurrency")
    if concurrency:
        data = _load(concurrency)
        env = data["environment"]
        print(f"\nEntorno: proveedor={env['llm_provider']}  "
              f"umbral_semantico={env['semantic_similarity_threshold']}  commit={env['git_commit']}")
        print(f"\nCONCURRENCIA ({concurrency.name})")
        print(f"{'conc':>5} {'rps':>8} {'p50':>9} {'p95':>9} {'p99':>9} {'max':>9} {'err':>6} {'grounding':>10}")
        for phase in data["phases"]:
            lat = phase["latency"] or {}
            print(f"{phase['concurrency']:>5} {phase['throughput_rps']:>8} "
                  f"{lat.get('p50_ms', 0):>9} {lat.get('p95_ms', 0):>9} {lat.get('p99_ms', 0):>9} "
                  f"{lat.get('max_ms', 0):>9} {phase['error_rate']:>6.1%} "
                  f"{str(phase['rag'].get('grounding_rate')):>10}")

    topology = latest("topology")
    if topology:
        data = _load(topology)
        print(f"\nTOPOLOGIA ({topology.name})")
        for phase in data["phases"]:
            lat = phase["latency"] or {}
            print(f"  {phase['name']:26s} p50={lat.get('p50_ms'):>8} p95={lat.get('p95_ms'):>8} "
                  f"n={phase['successes']}")
        if data.get("overhead_ms"):
            print("  Deltas observados (ms):", json.dumps(data["overhead_ms"]))
        for note in data.get("notes", []):
            print(f"  NOTA: {note}")

    ingestion = latest("ingestion")
    if ingestion:
        data = _load(ingestion)
        summary = data.get("summary", {})
        print(f"\nINGESTA ({ingestion.name})")
        print(f"  documentos medidos      : {summary.get('documents_measured')}")
        print(f"  subida media            : {summary.get('mean_upload_ms')} ms")
        print(f"  media hasta EMBEDDED    : {summary.get('mean_total_to_embedded_ms')} ms")
        print(f"  documentos/minuto       : {summary.get('documents_per_minute_observed')}")

    cold = latest("cold-vs-warm")
    if cold:
        data = _load(cold)
        print(f"\nARRANQUE EN FRIO ({cold.name})")
        print(f"  contenedor healthy tras : {data.get('container_healthy_after_seconds')} s")
        print(f"  primera peticion        : {round(data['cold_first_request']['latency_ms'], 1)} ms")
        warm = (data.get("warm") or {}).get("latency") or {}
        print(f"  caliente p50/p95        : {warm.get('p50_ms')} / {warm.get('p95_ms')} ms")
        print(f"  penalizacion en frio    : {data.get('cold_penalty_ms')} ms")

    resources = latest("resources-idle")
    if resources:
        data = _load(resources)
        print(f"\nRECURSOS EN REPOSO ({resources.name})")
        total_mem = 0.0
        for name, values in data["summary"].items():
            if values["mem_mib_mean"]:
                total_mem += values["mem_mib_mean"]
            print(f"  {name:32s} CPU {str(values['cpu_percent_mean']):>6}%  "
                  f"MEM {str(values['mem_mib_mean']):>8} MiB")
        print(f"  {'TOTAL':32s} {'':>10}  MEM {total_mem:>8.1f} MiB")

    print("\n" + "=" * 78)


def _percent(baseline: float, candidate: float) -> float:
    if baseline == 0:
        return 0.0
    return (candidate - baseline) / baseline * 100


def _verdict(diff: float, lower_is_better: bool = True) -> str:
    if abs(diff) < NOISE_BAND_PERCENT:
        return "UNCHANGED"
    improved = diff < 0 if lower_is_better else diff > 0
    return "IMPROVED" if improved else "REGRESSED"


def compare(baseline_path: Path, candidate_path: Path) -> None:
    baseline, candidate = _load(baseline_path), _load(candidate_path)
    print(f"Baseline : {baseline_path.name}")
    print(f"Candidato: {candidate_path.name}")
    print(f"Banda de ruido considerada: +/-{NOISE_BAND_PERCENT}%  "
          f"(por debajo de esto no se afirma cambio)\n")

    for label, path in (("baseline", baseline), ("candidato", candidate)):
        env = path.get("environment", {})
        print(f"  {label}: proveedor={env.get('llm_provider')} "
              f"umbral={env.get('semantic_similarity_threshold')} commit={env.get('git_commit')}")
    if (baseline.get("environment", {}).get("llm_provider")
            != candidate.get("environment", {}).get("llm_provider")):
        print("\n  AVISO: los proveedores LLM difieren - las latencias NO son comparables.")

    base_phases = {p["concurrency"]: p for p in baseline.get("phases", [])}
    cand_phases = {p["concurrency"]: p for p in candidate.get("phases", [])}

    print(f"\n{'conc':>5} {'metrica':<12} {'baseline':>10} {'candidato':>10} {'diff %':>9}  veredicto")
    for level in sorted(set(base_phases) & set(cand_phases)):
        b, c = base_phases[level], cand_phases[level]
        bl, cl = b.get("latency") or {}, c.get("latency") or {}
        for metric, lower_better in (("p50_ms", True), ("p95_ms", True), ("p99_ms", True)):
            if bl.get(metric) and cl.get(metric):
                diff = _percent(bl[metric], cl[metric])
                print(f"{level:>5} {metric:<12} {bl[metric]:>10} {cl[metric]:>10} "
                      f"{diff:>+8.1f}%  {_verdict(diff, lower_better)}")
        diff = _percent(b["throughput_rps"], c["throughput_rps"])
        print(f"{level:>5} {'throughput':<12} {b['throughput_rps']:>10} {c['throughput_rps']:>10} "
              f"{diff:>+8.1f}%  {_verdict(diff, lower_is_better=False)}")


def main() -> int:
    if len(sys.argv) >= 4 and sys.argv[1] == "compare":
        compare(Path(sys.argv[2]), Path(sys.argv[3]))
        return 0
    summarise()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
