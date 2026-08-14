# Metodología de benchmarking

Este documento explica **cómo** se mide, para que cualquiera pueda cuestionar los números del
informe final sabiendo exactamente qué representan y qué no.

## 1. Qué se mide y qué no

Cada ejecución registra en el propio fichero de resultados el proveedor LLM en uso y el umbral
semántico configurado, leídos **del contenedor en marcha**, no del fichero de configuración. Un
resultado no puede así atribuirse por error a una configuración distinta de la que lo produjo.

**No se mide** (y se declara como `NO MEDIDA` en el informe, con su motivo):

- Latencia real de un LLM comercial, tokens y coste observado: requiere credenciales de un
  proveedor real, que no están configuradas en este entorno.
- Desglose interno por etapa (guardarraíles / recuperación / reranking / ensamblado de prompt /
  LLM / auditoría) medido desde fuera: el API expone una única latencia HTTP. El desglose existe
  en los spans de OpenTelemetry y en las métricas `rag.retrieval.latency` / `rag.llm.latency`, pero
  el arnés no las agrega todavía.

## 2. Herramienta de carga: por qué un arnés propio

Se inspeccionó el repositorio antes de decidir: **no había** k6, Gatling, JMeter, Locust ni
Artillery, y ninguno está instalado en la máquina. Introducir uno significaba una instalación de
red no necesariamente reproducible aquí, o arrastrar una cadena JVM/Node completa.

Lo que el proyecto realmente necesita medir es HTTP autenticado contra un API con rate limiting,
con refresco de token, muestreo de recursos de Docker y resultados que casen con el dataset de
evaluación existente. Eso son unos cientos de líneas de Python de biblioteca estándar
(`benchmarks/lib/harness.py`).

**Limitación honesta**: el arnés usa un **modelo cerrado** — N trabajadores concurrentes emitiendo
peticiones una tras otra. No reproduce la fidelidad de llegadas de un modelo abierto (tasa de
llegada independiente de la latencia del servicio) ni la generación distribuida. Es adecuado para
medir latencia de servicio a una concurrencia fija, que es lo que el informe afirma; no lo es para
simular tráfico real de producción.

## 3. Percentiles

Se usa el percentil por **rango más cercano** (nearest-rank), no interpolación. Con los tamaños de
muestra de estas ejecuciones (30–60 peticiones por fase) la interpolación inventaría valores que
nunca se observaron; el rango más cercano siempre devuelve una medición real.

Consecuencia directa que hay que tener presente: con 60 muestras, **p99 es esencialmente el máximo
observado**. No se le debe dar el significado estadístico de un p99 calculado sobre decenas de
miles de peticiones.

## 4. Rate limiting

El gateway limita por identidad: 60/min en `/api/chat`, 10/min en `/api/documents`, 5/min en
`/api/evaluation`. El arnés **pausa** para respetarlo. De no hacerlo, mediría el limitador en lugar
del pipeline RAG y reportaría una tasa de error fabricada.

Se decidió respetar el límite en vez de desactivarlo: desactivar una medida de seguridad para
obtener mejores números está explícitamente prohibido en este proyecto.

Esto impone un límite real al benchmark: **no se puede medir el throughput máximo del backend a
través del gateway**, porque el limitador corta antes. Las cifras de throughput describen el
sistema *con su rate limiting activo*, que es la configuración real de producción.

## 5. Calentamiento, frío y caliente

- **Calentamiento**: 10 peticiones antes de cada fase. Una JVM recién arrancada, un pool de
  conexiones vacío y cachés frías producen las primeras latencias más altas.
- **Frío**: se reinicia el contenedor del backend, se espera a que el healthcheck lo declare sano
  y se mide **la primera petición**.
- **Caliente**: 20 peticiones después de esa primera.

Los dos resultados se guardan por separado y nunca se promedian juntos.

## 6. Recursos

`docker stats --no-stream` muestreado cada 2 s durante cada fase, para los diez contenedores del
proyecto. Es la misma fuente que vería un operador. Se registran media y **pico** por contenedor:
el pico es el que informa el dimensionamiento.

Precisión limitada: cada muestra es un proceso `docker stats` nuevo, de ahí el intervalo en
segundos. Un pico de CPU más corto que el intervalo puede pasar desapercibido.

## 7. Deduplicación en el benchmark de ingesta

El backend deduplica documentos por **hash de contenido**. Una primera versión de este benchmark
resubía los PDF del corpus y reportaba «34 ms hasta EMBEDDED»: no estaba midiendo ingesta, sino una
búsqueda que devolvía el documento ya existente.

El benchmark genera ahora un PDF nuevo por ejecución, con un marcador único en el texto, forzando
un ciclo real de extracción → chunking → embedding → persistencia. Se documenta el fallo porque
produjo un número plausible y completamente falso.

**Granularidad**: `GET /api/documents/{id}` comparte el presupuesto de 10/min, así que el sondeo se
hace cada 8 s. Los tiempos «hasta EMBEDDED» son por tanto una **cota superior** con resolución de
8 s, no una medida fina. La medida precisa está en los spans de Kafka en Jaeger.

## 8. Regresiones

`benchmarks/summarize.py compare baseline.json candidato.json` calcula:

```
diff % = (candidato - baseline) / baseline * 100
```

y etiqueta `IMPROVED` / `REGRESSED` / `UNCHANGED` contra una **banda de ruido del ±15 %**.

No se emite PASS/FAIL: este proyecto no tiene un SLA de rendimiento acordado, así que un veredicto
sería inventado. La banda se imprime en la salida para que el lector pueda discrepar de ella.

La banda no es arbitraria: en la comparación de topologías de este mismo proyecto, fases idénticas
difirieron en ese orden de magnitud **sin ningún cambio de código**.

## 9. Qué invalida una ejecución

- Otros contenedores compitiendo por CPU/E-S en la misma máquina (aquí los hay: otro proyecto
  ocupa el puerto 8080 y mantiene sus propios contenedores en marcha).
- Cambiar el proveedor o el umbral entre ejecuciones y compararlas igualmente.
- Ejecutar sin corpus ingerido: la recuperación no encontraría nada y la latencia parecería mejor.
- Confundir resultados del proveedor `fake` con los de un modelo real.
