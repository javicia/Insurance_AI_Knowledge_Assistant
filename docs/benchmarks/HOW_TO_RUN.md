# Cómo ejecutar los benchmarks

## 1. Requisitos

- Docker Desktop en marcha.
- Python 3.11+ con `pyyaml` (`python -m pip install pyyaml`).
- El stack desplegado y sano.

No hacen falta credenciales de OpenAI/Anthropic para las mediciones de infraestructura y pipeline.
Sí hacen falta para medir latencia real de LLM, tokens y coste observado (§9).

## 2. Preparación

```bash
cd D:/Javier/Proyectos/rag_springai_prueba

# El corpus español debe estar ingerido: sin él la recuperación no encuentra nada y las
# latencias saldrían artificialmente buenas.
docker compose -f docker-compose.yml -f docker-compose.corpus-es.yml up -d
python scripts/build_test_corpus_pdfs.py --check
bash scripts/ingest_test_corpus.sh
```

### Si el puerto 8080 está ocupado

El backend publica `8080:8080`. Si otra aplicación de tu máquina ya lo tiene, el contenedor entra
en bucle de reinicio con `Bind for 0.0.0.0:8080 failed: port is already allocated` — y, peor,
`curl localhost:8080/actuator/health` responde de **la otra aplicación**, así que parece sano.

```bash
docker ps --format "{{.Names}}\t{{.Ports}}" | grep 8080     # localizar al culpable
docker compose -f docker-compose.yml -f docker-compose.corpus-es.yml \
               -f docker-compose.hostports.yml up -d        # remapea el backend a 18080
```

Si usas el remapeo, ajusta `topologies.backend.base_url` en `benchmarks/config/benchmark.yaml`.

## 3. Comprobación previa

```bash
docker compose ps        # ocho servicios healthy; kafka-ui y otel-collector sin healthcheck
```

## 4. Ejecución

```bash
python benchmarks/run_benchmark.py resources   # recursos en reposo            (~40 s)
python benchmarks/run_benchmark.py quick       # concurrencia 1/5/10           (~5 min)
python benchmarks/run_benchmark.py full        # añade 25/50                   (~12 min)
python benchmarks/run_benchmark.py topology    # WAF vs Gateway vs backend     (~6 min)
python benchmarks/run_benchmark.py coldwarm    # arranque en frío vs caliente  (~3 min)
python benchmarks/run_benchmark.py ingestion   # subida hasta EMBEDDED         (~6 min)
python benchmarks/run_benchmark.py spike       # 1 -> 10 -> 50 -> 100          (~8 min)
python benchmarks/run_benchmark.py sustained   # carga sostenida 5 min         (~7 min)
```

Las pausas de 60 s que verás son deliberadas: respetan el límite de 60 req/min del gateway. Sin
ellas medirías el rate limiter, no el RAG.

## 5. Resultados

```bash
python benchmarks/summarize.py                                   # resumen de la última ejecución
python benchmarks/summarize.py compare A.json B.json             # detección de regresiones
```

Ficheros en `benchmarks/results/`, con marca de tiempo, commit de git, proveedor LLM y umbral
semántico reales de esa ejecución.

## 6. Interpretación

- **p99 con 60 muestras es prácticamente el máximo observado.** No le des el peso estadístico de
  un p99 sobre decenas de miles de peticiones.
- **El throughput es "con rate limiting activo"**, que es la configuración de producción. No es el
  techo del backend.
- **Los tiempos de ingesta son cota superior** (sondeo cada 8 s por el límite de 10/min).
- **Diferencias por debajo del ±15 % no son concluyentes** en esta máquina.

## 7. Coste

`benchmarks/config/benchmark.yaml` define precios **configurables** por millón de tokens. Son
valores de referencia anotados con su fecha; **verifícalos contra la tarifa vigente** antes de
citar ninguna cifra. El informe distingue siempre coste OBSERVADO (tokens reales medidos) de coste
ESTIMADO (proyección sobre un volumen supuesto).

## 8. Proveedor `fake` frente a proveedor real

El stack por defecto usa `FakeLlmAdapter`, que no hace ninguna llamada de red. Sirve para medir el
pipeline, la recuperación, la infraestructura y la latencia interna.

**Los resultados con el proveedor `fake` NO representan el rendimiento real de un LLM.** Para medir
latencia real, tokens, coste y errores 429/5xx del proveedor:

```bash
INSURANCE_AI_PROVIDER=openai OPENAI_API_KEY=sk-... docker compose up -d backend
python benchmarks/run_benchmark.py quick
```

Los resultados de ambos modos **no deben mezclarse jamás** en la misma tabla.

## 9. Resolución de problemas

| Síntoma | Causa probable | Qué hacer |
|---|---|---|
| `Bind for 0.0.0.0:8080 failed` | otra aplicación ocupa 8080 | overlay `docker-compose.hostports.yml` (§2) |
| El backend reinicia en bucle con `UnknownHostException` | DNS de Docker no resuelve el nombre del propio contenedor; Logback falla al configurar el SyslogAppender | ya mitigado con `hostname:` en `docker-compose.yml`; si reaparece, `docker compose down && up` recrea la red |
| `postgres unhealthy` durante minutos tras un apagado brusco | recuperación de PostgreSQL (fsync del directorio de datos) | esperar; se observaron ~5,5 min |
| Todo devuelve 429 | se superó el límite del gateway | esperar 60 s; el arnés ya pausa solo |
| Todo devuelve 401 a mitad de ejecución | token caducado (~5 min) | el arnés lo refresca solo; si persiste, comprobar Keycloak |
| Keycloak tarda >10 min en arrancar | fase de augmentación de Quarkus | es normal en esta máquina; está medido y documentado |
| Latencias muy peores de lo esperado | otros contenedores compitiendo por CPU/E-S | `docker stats`; documentar la contención junto al resultado |
| `NOT_GROUNDED` en todo | corpus no ingerido, o umbral 0.75 con proveedor `fake` | ingerir corpus y usar el overlay `corpus-es` |
