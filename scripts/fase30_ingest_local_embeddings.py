#!/usr/bin/env python3
"""FASE 30 - Copia el corpus curado al entorno aislado y lo re-embebe con modelos locales.

    python scripts/fase30_ingest_local_embeddings.py --copy-corpus
    python scripts/fase30_ingest_local_embeddings.py --model bge-m3:latest
    python scripts/fase30_ingest_local_embeddings.py --model nomic-embed-text:latest

GARANTIAS DE NO DESTRUCCION, que son el requisito duro de esta fase:

  - La base ORIGINAL (puerto 5433) se abre SOLO para SELECT. Este script no contiene ninguna
    sentencia de escritura dirigida a ella: ni INSERT, ni UPDATE, ni DELETE, ni DDL. Los
    embeddings originales `v1` no se tocan.
  - Todo lo que se escribe va a la base de EVALUACION (puerto 5434), que vive en su propio
    volumen y su propio proyecto Compose (ver docker-compose.fase30-eval.yml).
  - Cada modelo escribe en SU PROPIA tabla, con la dimension exacta de ese modelo. No existe
    ninguna tabla que pueda contener vectores de dos modelos a la vez, de modo que una busqueda
    no puede mezclarlos ni por error de consulta.

SELECCION DEL CORPUS CURADO: se excluyen los 7 duplicados `Bench *` y los 11 documentos de
prueba heredados. La regla es estructural, no una lista a mano: un documento curado tiene mas de
10 chunks y su nombre no empieza por `Bench `. Los 11 heredados tienen exactamente 1 chunk cada
uno. El script ABORTA si el recuento no da exactamente 26 documentos / 1.650 chunks, para que una
deriva del corpus se note en lugar de producir un benchmark silenciosamente distinto.
"""

from __future__ import annotations

import argparse
import json
import math
import subprocess
import sys
import time
import urllib.request

SOURCE = dict(container="insurance-ai-postgres", user="insurance_ai", db="insurance_ai")
EVAL = dict(container="fase30-eval-postgres", user="fase30_eval", db="fase30_eval")
OLLAMA_EMBED = "http://localhost:11434/api/embed"

EXPECTED_DOCS = 26
EXPECTED_CHUNKS = 1650

# Dimension declarada por cada modelo, verificada empiricamente antes de ingestar. Si Ollama
# devolviese otra, el script aborta: una tabla vector(N) rechaza cualquier vector de otra
# dimension, pero fallar aqui da un mensaje util en lugar de un error de pgvector.
MODEL_DIMS = {"bge-m3:latest": 1024, "nomic-embed-text:latest": 768}

# nomic-embed-text documenta prefijos de tarea obligatorios; bge-m3 no los usa. Forman parte de
# la IDENTIDAD del embedding: el mismo modelo con otros prefijos produce otro espacio vectorial.
MODEL_PREFIXES = {
    "bge-m3:latest": ("", ""),
    "nomic-embed-text:latest": ("search_query: ", "search_document: "),
}

CURATED_FILTER = "c.n > 10 AND d.name NOT LIKE 'Bench %'"


def psql(target: dict, sql: str, read_only: bool = True) -> list[list[str]]:
    args = ["docker", "exec", "-i"]
    if read_only and target is SOURCE:
        # Cinturon y tirantes: la sesion contra el ORIGEN se abre con
        # default_transaction_read_only=on, de modo que incluso un error de programacion en este
        # script no podria escribir en la base de produccion. Se pasa por PGOPTIONS y no como
        # `SET` en el SQL porque `SET` emite una fila de salida que contaminaria el parseo.
        args += ["-e", "PGOPTIONS=-c default_transaction_read_only=on"]
    args += [target["container"], "psql", "-U", target["user"],
             "-d", target["db"], "-v", "ON_ERROR_STOP=1", "-tAF\t", "-c", sql]
    r = subprocess.run(args, capture_output=True, text=True, encoding="utf-8")
    if r.returncode != 0:
        raise RuntimeError(f"psql {target['container']} fallo: {r.stderr.strip()[:500]}")
    return [line.split("\t") for line in r.stdout.strip().splitlines() if line.strip()]


def psql_write(sql: str) -> None:
    r = subprocess.run(["docker", "exec", "-i", EVAL["container"], "psql", "-U", EVAL["user"],
                        "-d", EVAL["db"], "-v", "ON_ERROR_STOP=1", "-q"],
                       input=sql, capture_output=True, text=True, encoding="utf-8")
    if r.returncode != 0:
        raise RuntimeError(f"escritura en eval fallo: {r.stderr.strip()[:800]}")


def copy_corpus() -> None:
    """Copia chunks curados (contenido + metadatos) del origen al entorno aislado."""
    counts = psql(SOURCE, f"""
        SELECT count(DISTINCT d.id), count(*)
        FROM documents d
        JOIN (SELECT metadata->>'documentId' AS doc_id, count(*) AS n
              FROM vector_store GROUP BY 1) c ON c.doc_id = d.id::text
        JOIN vector_store v ON v.metadata->>'documentId' = d.id::text
        WHERE {CURATED_FILTER};
    """)
    docs, chunks = int(counts[0][0]), int(counts[0][1])
    print(f"==> Corpus curado en el origen: {docs} documentos / {chunks} chunks")
    if docs != EXPECTED_DOCS or chunks != EXPECTED_CHUNKS:
        sys.exit(f"ABORTA: se esperaban {EXPECTED_DOCS} docs / {EXPECTED_CHUNKS} chunks. "
                 f"El corpus ha cambiado; revisa antes de medir.")

    rows = psql(SOURCE, f"""
        SELECT v.id::text, replace(replace(v.content, E'\\t', ' '), E'\\n', ' '),
               v.metadata::text, d.name
        FROM documents d
        JOIN (SELECT metadata->>'documentId' AS doc_id, count(*) AS n
              FROM vector_store GROUP BY 1) c ON c.doc_id = d.id::text
        JOIN vector_store v ON v.metadata->>'documentId' = d.id::text
        WHERE {CURATED_FILTER}
        ORDER BY v.id;
    """)
    print(f"    leidos {len(rows)} chunks (solo lectura)")

    psql_write("""
        CREATE EXTENSION IF NOT EXISTS vector;
        CREATE EXTENSION IF NOT EXISTS unaccent;
        DROP TEXT SEARCH CONFIGURATION IF EXISTS spanish_unaccent CASCADE;
        CREATE TEXT SEARCH CONFIGURATION spanish_unaccent ( COPY = spanish );
        ALTER TEXT SEARCH CONFIGURATION spanish_unaccent
            ALTER MAPPING FOR hword, hword_part, word WITH unaccent, spanish_stem;

        DROP TABLE IF EXISTS corpus_chunk CASCADE;
        CREATE TABLE corpus_chunk (
            id          uuid PRIMARY KEY,
            content     text NOT NULL,
            metadata    jsonb NOT NULL,
            doc_name    text NOT NULL,
            doc_slug    text NOT NULL,
            content_tsv tsvector GENERATED ALWAYS AS (
                setweight(to_tsvector('spanish_unaccent', coalesce(content, '')), 'A') ||
                setweight(to_tsvector('spanish_unaccent', coalesce(metadata ->> 'section', '')), 'B')
            ) STORED
        );
        CREATE INDEX corpus_chunk_tsv_gin ON corpus_chunk USING gin (content_tsv);
    """)

    batch, buf = [], []
    for cid, content, meta, name in rows:
        slug = name.strip().lower().replace(" ", "-")
        esc = lambda s: s.replace("'", "''")  # noqa: E731
        buf.append(f"('{cid}','{esc(content)}','{esc(meta)}','{esc(name)}','{esc(slug)}')")
        if len(buf) >= 200:
            batch.append(buf)
            buf = []
    if buf:
        batch.append(buf)
    for i, group in enumerate(batch, 1):
        psql_write("INSERT INTO corpus_chunk (id, content, metadata, doc_name, doc_slug) VALUES "
                   + ",".join(group) + ";")
        print(f"    insertado lote {i}/{len(batch)}")

    check = psql(EVAL, "SELECT count(*), count(DISTINCT doc_slug) FROM corpus_chunk;", False)
    print(f"==> Entorno aislado: {check[0][0]} chunks / {check[0][1]} documentos")
    if int(check[0][0]) != EXPECTED_CHUNKS:
        sys.exit("ABORTA: la copia no cuadra con el origen.")


def embed_batch(model: str, texts: list[str], prefix: str) -> list[list[float]]:
    body = json.dumps({"model": model, "input": [prefix + t for t in texts]}).encode()
    req = urllib.request.Request(OLLAMA_EMBED, data=body,
                                 headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=1800) as r:
        return json.load(r)["embeddings"]


def table_for(model: str) -> str:
    return "emb_" + model.replace(":", "_").replace("-", "_").replace(".", "_")


def ingest_model(model: str, batch_size: int) -> None:
    dims = MODEL_DIMS[model]
    _, doc_prefix = MODEL_PREFIXES[model]
    table = table_for(model)
    print(f"==> Embebiendo con {model} (dims={dims}, prefijo doc={doc_prefix!r}) -> {table}")

    probe = embed_batch(model, ["prueba"], doc_prefix)
    if len(probe[0]) != dims:
        sys.exit(f"ABORTA: {model} devolvio {len(probe[0])} dimensiones, se esperaban {dims}.")

    psql_write(f"""
        DROP TABLE IF EXISTS {table} CASCADE;
        CREATE TABLE {table} (
            chunk_id  uuid PRIMARY KEY REFERENCES corpus_chunk(id),
            embedding vector({dims}) NOT NULL
        );
    """)

    rows = psql(EVAL, "SELECT id::text, content FROM corpus_chunk ORDER BY id;", False)
    total = len(rows)
    done, t0 = 0, time.perf_counter()
    for start in range(0, total, batch_size):
        group = rows[start:start + batch_size]
        vecs = embed_batch(model, [c for _, c in group], doc_prefix)
        if len(vecs) != len(group):
            sys.exit(f"ABORTA: Ollama devolvio {len(vecs)} vectores para {len(group)} chunks.")
        values = ",".join(f"('{cid}','[{','.join(f'{x:.7f}' for x in v)}]')"
                          for (cid, _), v in zip(group, vecs))
        psql_write(f"INSERT INTO {table} (chunk_id, embedding) VALUES {values};")
        done += len(group)
        el = time.perf_counter() - t0
        print(f"    {done}/{total}  {done/el:.2f} chunks/s  ETA {(total-done)/(done/el)/60:.1f} min")

    el = time.perf_counter() - t0
    check = psql(EVAL, f"SELECT count(*) FROM {table};", False)
    print(f"==> {model}: {check[0][0]} embeddings en {el/60:.1f} min ({total/el:.2f} chunks/s)")
    if int(check[0][0]) != total:
        sys.exit("ABORTA: faltan embeddings.")

    # Indice HNSW coseno, igual que V3__vector_store.sql en produccion.
    psql_write(f"CREATE INDEX {table}_hnsw ON {table} USING hnsw (embedding vector_cosine_ops);")
    print(f"    indice HNSW creado sobre {table}")


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--copy-corpus", action="store_true")
    ap.add_argument("--model", choices=sorted(MODEL_DIMS))
    ap.add_argument("--batch-size", type=int, default=32)
    a = ap.parse_args()
    if a.copy_corpus:
        copy_corpus()
    if a.model:
        ingest_model(a.model, a.batch_size)
    if not a.copy_corpus and not a.model:
        ap.error("indica --copy-corpus y/o --model")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
