#!/usr/bin/env python3
"""FASE 31 - Carga el corpus curado + embeddings bge-m3 en la BD aislada del backend real.

    python scripts/fase31_load_isolated_corpus.py

De donde sale cada cosa, y por que:

  - `documents` y `document_versions` se copian de la BD PRINCIPAL (puerto 5433) en modo SOLO
    LECTURA. El camino de consulta las necesita: `AskInsuranceKnowledgeUseCase#buildSources`
    resuelve cada cita con `documentRepository.findById` y lanza `DocumentNotFoundException` si
    el documento no existe, asi que sin ellas los 255 casos fallarian con error en lugar de
    medir recuperacion.
  - `vector_store_bge_m3` se construye con los vectores YA generados en la BD de FASE 30
    (puerto 5434). Re-embeber los 1.650 chunks costaria otros ~9 minutos de CPU para obtener
    exactamente los mismos numeros: el modelo es determinista y es el mismo `bge-m3:latest`.

GARANTIAS DE NO DESTRUCCION:
  - Las dos bases de origen (5433 principal, 5434 FASE 30) se abren con
    `default_transaction_read_only=on`. Este script no contiene ninguna escritura dirigida a
    ellas.
  - Todo lo que escribe va a la BD de FASE 31 (puerto 5435), en su propio volumen y proyecto
    Compose, y unicamente a `vector_store_bge_m3` - nunca a `vector_store`, que en esa base
    queda vacia a proposito para que una consulta mal configurada no devuelva nada en lugar de
    devolver datos del modelo equivocado.
"""

from __future__ import annotations

import subprocess
import sys

MAIN = dict(container="insurance-ai-postgres", user="insurance_ai", db="insurance_ai")
F30 = dict(container="fase30-eval-postgres", user="fase30_eval", db="fase30_eval")
F31 = dict(container="fase31-eval-postgres", user="fase31_eval", db="fase31_eval")

EXPECTED_DOCS = 26
EXPECTED_CHUNKS = 1650
CURATED = "c.n > 10 AND d.name NOT LIKE 'Bench %'"


def read(target: dict, sql: str) -> list[list[str]]:
    r = subprocess.run(
        ["docker", "exec", "-i", "-e", "PGOPTIONS=-c default_transaction_read_only=on",
         target["container"], "psql", "-U", target["user"], "-d", target["db"],
         "-v", "ON_ERROR_STOP=1", "-tAF\t", "-c", sql],
        capture_output=True, text=True, encoding="utf-8")
    if r.returncode != 0:
        raise RuntimeError(f"lectura de {target['container']} fallo: {r.stderr.strip()[:400]}")
    return [ln.split("\t") for ln in r.stdout.strip().splitlines() if ln.strip()]


def write(sql: str) -> None:
    r = subprocess.run(
        ["docker", "exec", "-i", F31["container"], "psql", "-U", F31["user"], "-d", F31["db"],
         "-v", "ON_ERROR_STOP=1", "-q"],
        input=sql, capture_output=True, text=True, encoding="utf-8")
    if r.returncode != 0:
        raise RuntimeError(f"escritura en fase31 fallo: {r.stderr.strip()[:600]}")


def q(value: str | None) -> str:
    if value is None or value == "":
        return "NULL"
    return "'" + value.replace("'", "''") + "'"


def main() -> int:
    print("==> FASE 31: cargando corpus curado en la BD aislada (puerto 5435)")

    docs = read(MAIN, f"""
        SELECT DISTINCT d.id::text, d.name, d.type, coalesce(d.product,''), coalesce(d.country,''),
               coalesce(d.language,''), d.classification, d.source
        FROM documents d
        JOIN (SELECT metadata->>'documentId' AS doc_id, count(*) AS n
              FROM vector_store GROUP BY 1) c ON c.doc_id = d.id::text
        WHERE {CURATED}
        ORDER BY 1;
    """)
    print(f"    documentos curados leidos del principal (solo lectura): {len(docs)}")
    if len(docs) != EXPECTED_DOCS:
        sys.exit(f"ABORTA: se esperaban {EXPECTED_DOCS} documentos, hay {len(docs)}")

    doc_ids = {d[0] for d in docs}
    versions = read(MAIN, """
        SELECT id::text, document_id::text, version_major, version_minor, content_hash,
               effective_from::text, coalesce(effective_to::text,''), status
        FROM document_versions ORDER BY 1;
    """)
    versions = [v for v in versions if v[1] in doc_ids]
    print(f"    versiones correspondientes: {len(versions)}")

    write("TRUNCATE TABLE vector_store_bge_m3; DELETE FROM document_versions; DELETE FROM documents;")

    write("INSERT INTO documents (id, name, type, product, country, language, classification, source) VALUES "
          + ",".join(f"({q(d[0])}::uuid,{q(d[1])},{q(d[2])},{q(d[3])},{q(d[4])},{q(d[5])},{q(d[6])},{q(d[7])})"
                     for d in docs) + ";")
    print(f"    insertados {len(docs)} documentos")

    write("INSERT INTO document_versions (id, document_id, version_major, version_minor, content_hash, "
          "effective_from, effective_to, status) VALUES "
          + ",".join(f"({q(v[0])}::uuid,{q(v[1])}::uuid,{v[2]},{v[3]},{q(v[4])},"
                     f"{q(v[5])}::timestamptz,{q(v[6]) if v[6] else 'NULL'},{q(v[7])})"
                     for v in versions) + ";")
    print(f"    insertadas {len(versions)} versiones")

    # Chunks + vectores de la BD de FASE 30. Se traen juntos para que un chunk nunca llegue sin
    # su vector: una fila sin embedding seria invisible para la rama semantica y visible para la
    # lexica, sesgando la comparacion hibrida de forma silenciosa.
    rows = read(F30, """
        SELECT c.id::text, c.content, c.metadata::text, e.embedding::text
        FROM corpus_chunk c JOIN emb_bge_m3_latest e ON e.chunk_id = c.id
        ORDER BY c.id;
    """)
    print(f"    chunks+vectores bge-m3 leidos de FASE 30 (solo lectura): {len(rows)}")
    if len(rows) != EXPECTED_CHUNKS:
        sys.exit(f"ABORTA: se esperaban {EXPECTED_CHUNKS} chunks, hay {len(rows)}")

    for start in range(0, len(rows), 100):
        batch = rows[start:start + 100]
        write("INSERT INTO vector_store_bge_m3 (id, content, metadata, embedding) VALUES "
              + ",".join(f"({q(r[0])}::uuid,{q(r[1])},{q(r[2])}::json,{q(r[3])}::vector)"
                         for r in batch) + ";")
        print(f"    insertados {min(start + 100, len(rows))}/{len(rows)} vectores")

    check = read(F31, """
        SELECT (SELECT count(*) FROM documents), (SELECT count(*) FROM document_versions),
               (SELECT count(*) FROM vector_store_bge_m3),
               (SELECT count(*) FROM vector_store),
               (SELECT vector_dims(embedding) FROM vector_store_bge_m3 LIMIT 1);
    """)
    d, v, bge, legacy, dims = check[0]
    print(f"\n==> BD aislada FASE 31: documentos={d} versiones={v} "
          f"vector_store_bge_m3={bge} (dims={dims}) vector_store={legacy}")
    if int(bge) != EXPECTED_CHUNKS or int(dims) != 1024 or int(legacy) != 0:
        sys.exit("ABORTA: la carga no cuadra (se espera 1650 vectores de 1024 dims y "
                 "vector_store vacio).")
    print("    vector_store (1536 dims) queda VACIA a proposito: una consulta mal configurada "
          "devuelve cero en lugar de datos del modelo equivocado.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
