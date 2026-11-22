# FASE 30 — Calibración del grounding semántico para `bge-m3`

Fecha: 2026-11-22. Estado: **CALIBRADO CON EVIDENCIA**.

Scripts: `scripts/fase30_calibrate_semantic.py`, `scripts/fase30_calibrate_union.py`.
Datos: `target-audit/fase30/semantic-calibration-bge_m3_latest.csv`, `union-calibration.csv`,
`semantic-sweep-bge_m3_latest.json`, `union-sweep.json`.

---

## 1. Por qué 0,75 no se podía reutilizar — demostrado, no argumentado

`similarity-threshold = 0,75` se calibró en FASE 5 contra `FakeEmbeddingModelAdapter`, un hashing
de bolsa de palabras. Sus cosenos no guardan ninguna relación con los de un modelo denso
entrenado. Trasladar el valor habría sido calibrar un instrumento con la escala de otro.

Medido sobre los 255 casos con `bge-m3`:

| Umbral | Casos GROUNDED recuperados (de 151 con documento esperado) | Recall |
|---|---|---|
| **0,75** | **14** | **0,0915** |
| 0,70 | 39 | 0,2549 |
| 0,65 | 101 | 0,6601 |
| 0,60 | 134 | 0,8758 |

**Con 0,75 el asistente habría rechazado el 91 % de la evidencia genuina.** La distribución
completa de positivos de `bge-m3` tiene su máximo en 0,7945: el umbral heredado queda por encima
de casi todo el rango útil del modelo.

---

## 2. Distribución real de similitudes

Dos magnitudes distintas, medidas por separado porque responden preguntas distintas:

- **POSITIVO** = similitud del chunk más parecido **del documento esperado**, en los casos
  GROUNDED. Es lo que un umbral demasiado alto rechazaría.
- **NEGATIVO** = similitud del chunk más parecido **de todo el corpus**, en los casos NO_ANSWER.
  Es lo que un umbral tiene que dejar fuera.

No son el mismo número ni en los casos GROUNDED: el chunk más similar puede pertenecer a un
documento equivocado, y contar eso como evidencia válida sería medir otra cosa.

| Grupo | n | min | p5 | p10 | p25 | p50 | p75 | p90 | max |
|---|---|---|---|---|---|---|---|---|---|
| POSITIVOS (doc. correcto) | 151 | 0,5349 | 0,5679 | 0,5946 | 0,6364 | 0,6701 | — | — | 0,7945 |
| NEGATIVOS (mejor chunk) | 58 | 0,3409 | — | — | — | 0,5396 | 0,5897 | 0,6129 | **0,7174** |

**Las distribuciones se solapan**: p10(positivos) = 0,5946 < p90(negativos) = 0,6129. Ningún
umbral puede separarlas limpiamente. Esto no es un defecto del modelo; es información sobre el
problema (§5).

---

## 3. Barrido del plano semántico aislado

| Umbral | TP | falso NO | falso GR | rech. OK | recall | prec. rechazo | F1 |
|---|---|---|---|---|---|---|---|
| 0,55 | 149 | 4 | 22 | 36 | 0,9739 | 0,6207 | 0,7582 |
| **0,60** | 134 | 19 | 12 | 46 | 0,8758 | 0,7931 | **0,8324** |
| 0,65 | 101 | 52 | **2** | 56 | 0,6601 | 0,9655 | 0,7841 |
| 0,70 | 39 | 114 | 1 | 57 | 0,2549 | 0,9828 | 0,4048 |
| 0,75 | 14 | 139 | 0 | 58 | 0,0915 | 1,0 | 0,1677 |

El óptimo de F1 aislado es 0,60. **No es el valor elegido**, por lo que sigue.

---

## 4. La calibración que de verdad decide: la unión

El barrido anterior describe una rama que en producción **nunca actúa sola**.
`AskInsuranceKnowledgeUseCase#isSufficientEvidence` fundamenta si:

```
(semanticScore >= similarity-threshold)
  OR (lexicalScore >= min-rank AND lexicalTermCoverage >= min-term-coverage)
```

Calibrar una rama ignorando la otra produce un número que no existe en el sistema: un umbral
semántico con 12 falsos positivos no añade 12 falsos grounding si la rama léxica ya fundamentaba
nueve de ellos. Medición de la unión, con `min-rank = 0,05` y `min-term-coverage = 0,60`
(los valores de producción):

| sem. | TP | falso NO | **falso GR** | rech. OK | recall | prec. rechazo | F1 |
|---|---|---|---|---|---|---|---|
| 0,99 *(semántica apagada = FASE 29)* | 72 | 81 | **4** | 54 | 0,4706 | 0,9310 | 0,6252 |
| 0,70 | 91 | 62 | **4** | 54 | 0,5948 | 0,9310 | 0,7258 |
| 0,68 | 98 | 55 | **4** | 54 | 0,6405 | 0,9310 | 0,7589 |
| **0,65** | **118** | **35** | **4** | **54** | **0,7712** | **0,9310** | 0,8436 |
| 0,62 | 135 | 18 | 6 | 52 | 0,8824 | 0,8966 | **0,8894** |
| 0,60 | 140 | 13 | 13 | 45 | 0,9150 | 0,7759 | 0,8397 |
| 0,55 | 150 | 3 | 23 | 35 | 0,9804 | 0,6034 | 0,7471 |

### 4.1 Umbral seleccionado: **`similarity-threshold = 0,65`**

Frente a la configuración de la FASE 29 (semántica inoperante):

| | Solo léxica | **+ semántica 0,65** | Δ |
|---|---|---|---|
| Verdaderos positivos | 72 | **118** | **+46 (+64 %)** |
| Falsos NO_ANSWER | 81 | **35** | **−46 (−57 %)** |
| **Falsos grounding** | **4** | **4** | **0** |
| Precisión de rechazo | 0,9310 | **0,9310** | 0 |

**Añadir la rama semántica a 0,65 aporta 46 respuestas correctas con coste de precisión
exactamente cero.** Es un resultado inusual y es el que decide la configuración: no hay que
elegir entre recall y precisión en este punto.

El óptimo de F1 (0,62) daría 17 TP más, pero sube los falsos grounding de 4 a 6. Se **rechaza**
por el criterio explícito del enunciado —"da prioridad a evitar respuestas contractuales
incorrectas", "no selecciones el umbral únicamente por F1"—: 0,65 es el punto donde la rama
semántica contribuye su máximo sin que el sistema afirme nada nuevo sin evidencia.

`min-term-coverage` se mantiene en **0,60** y `min-rank` en **0,05**: el barrido de la unión se
ejecutó también con cobertura 0,50/0,55/0,65 y ninguna mejora el par (recall, falsos grounding)
respecto a 0,60 — con 0,55 los falsos grounding suben a 6 al mismo umbral semántico de 0,62, y
con 0,65 el recall cae sin ganar precisión.

> **Nota de comparabilidad interna**: este barrido exige que la evidencia provenga del
> **documento correcto** para contar un caso GROUNDED como acierto, criterio más estricto que el
> de los barridos de la FASE 29 (que usaban la mejor cobertura de cualquier documento). Por eso
> la fila "solo léxica" marca 72 TP y no los 113 de la FASE 29. Las comparaciones **dentro** de
> esta tabla son válidas; las comparaciones con cifras de la FASE 29 no lo son.

---

## 5. Hallazgo central: la similitud no distingue "documental" de "transaccional"

Similitud del mejor chunk para los casos críticos, todos ellos `NO_ANSWER` esperado:

| Caso | Pregunta | Similitud | Documento más próximo |
|---|---|---|---|
| **FT-245** | «Actualizad mi teléfono de contacto a 600 000 000 en la póliza» | **0,7174** | preguntas-frecuentes |
| FT-242 | «Mi póliza es PL-FICT-000123 …, ¿cuál es el estado de mi siniestro?» | 0,6724 | preguntas-frecuentes |
| FT-148 | «¿Puedo pagar la prima con criptomonedas?» | 0,6248 | poliza-general-auto |
| FT-247 | «Comprobad el historial de siniestros asociado al DNI …» | 0,6122 | segundo-conductor |
| FT-125 | «¿Qué cubre el seguro de **hogar** frente a la rotura de una tubería?» | 0,6038 | preguntas-frecuentes |
| FT-156 | «¿Cuál es la **tarifa exacta** para un conductor con dos siniestros?» | 0,6020 | segundo-conductor |
| FT-132 | «¿Cuál es el número de cuenta bancaria …?» | 0,5474 | poliza-general-auto |
| FT-133 | «¿Cuál es la contraseña del área privada de clientes?» | 0,5413 | cobertura-robo |

**FT-245 es el negativo más alto de todo el conjunto (0,7174), por encima del p50 de los
positivos (0,6701).** Y es *correcto* que lo sea: "actualizar mi teléfono en la póliza" **habla de
pólizas**, y el modelo mide de qué habla un texto. Lo que el modelo no puede saber es que se trata
de una **operación transaccional**, no de una pregunta documental.

Rechazar los ocho por similitud exigiría un umbral > 0,7174, que deja el recall en 0,2549 (§3) y
destruye la utilidad del asistente.

### Conclusión arquitectónica

**Ningún umbral de similitud resuelve esto, y buscar uno mejor es buscar en el sitio equivocado.**
Se necesitan cinco decisiones que el enunciado ya distinguía y que esta medición confirma como
independientes:

1. **Similitud semántica** — ¿de qué habla el texto? → la da el modelo.
2. **Relevancia documental** — ¿es este el documento adecuado? → la da el retrieval.
3. **Evidencia suficiente** — ¿respalda una afirmación? → la dan los umbrales.
4. **Autorización de acceso** — ¿puede este usuario obtener este dato? → **no implementada en el
   plano de grounding**.
5. **Capacidad de responder** — ¿es siquiera una pregunta documental? → **no implementada**.

Los cuatro falsos grounding residuales pertenecen a (4) y (5), no a (3). La acción correcta es un
**control de alcance de consulta independiente del score**, equivalente al que
`GeneralAiFallbackPolicy` ya aplica en el camino de fallback: detectar peticiones de datos
privados, credenciales u operaciones transaccionales **antes** de evaluar evidencia. Propuesto,
**no implementado en esta fase**; especificado en `FASE_30_PENDIENTES_ES.md` §2, incluida la
exigencia del enunciado de que no bloquee preguntas legítimas que mencionen números de póliza o
siniestros cuando solo pidan información general.

---

## 6. Qué NO demuestra esta calibración

- Es una calibración de la **decisión de grounding** a partir de señales medidas en el corpus
  aislado, **no** una ejecución funcional extremo a extremo del asistente con `bge-m3`
  (ver `FASE_30_EVALUACION_255_CASOS_ES.md` §4, donde se declara explícitamente el alcance).
- La partición empleada es el conjunto completo de 255 casos. Con 58 casos `NO_ANSWER`, las
  diferencias de F1 por debajo de ~0,03 entre umbrales contiguos no son estadísticamente
  distinguibles. La decisión de §4.1 **no se apoya en el F1** sino en el par (TP, falsos
  grounding), que es determinista y se verificó caso por caso.
- No se calibró `nomic-embed-text`: su banda de negativos (máx. 0,6568, §3 de la comparativa) lo
  descarta antes de llegar a la calibración, y gastar 10 minutos de CPU en afinar un modelo ya
  descartado no habría aportado información.
