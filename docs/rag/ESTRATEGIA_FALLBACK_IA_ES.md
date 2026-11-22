# Estrategia de fallback a IA general (FASE 28)

Estado: **IMPLEMENTADO y VERIFICADO EN DESPLIEGUE REAL**. Desactivado por defecto.

Fecha: 2026-11-22.

Permite que el asistente responda desde el conocimiento general del modelo cuando la
documentación no contiene evidencia suficiente — **sin que esa respuesta pueda confundirse con
información respaldada por una póliza**.

---

## 1. Los cuatro modos de respuesta

Expuestos en `RagAnswer.answerMode` (`AnswerMode.java`). Sustituyen al booleano `blocked`
anterior, que solo podía expresar dos de estos cuatro estados y había que leerlo junto a
`grounding.status` para que significara algo.

| Modo | Significado | Citas | `grounding.status` |
|---|---|---|---|
| `DOCUMENT_GROUNDED` | Respaldado por evidencia documental suficiente | **Sí** | `GROUNDED` |
| `GENERAL_AI_UNVERIFIED` | Conocimiento general del modelo | **Nunca** | `NOT_GROUNDED` |
| `NO_ANSWER` | Sin evidencia y sin permiso para usar IA general | Nunca | `NOT_GROUNDED` |
| `BLOCKED` | Rechazado por un guardarraíl de seguridad | Nunca | `NOT_GROUNDED` |

`grounding` se conserva porque es lo que registran la auditoría de IA y
`EvaluationRunnerService`; `answerMode` es el discriminador sobre el que debe ramificar un
cliente.

---

## 2. La invariante central: sin citas inventadas

`RagAnswer`'s constructor **rechaza** cualquier respuesta no `DOCUMENT_GROUNDED` que lleve
fuentes:

```java
if (answerMode != AnswerMode.DOCUMENT_GROUNDED && !sources.isEmpty()) {
    throw new IllegalArgumentException(...);
}
```

Está en el constructor, no delegado a quien llama, porque es el fallo que esta función podría
causar y que más daño haría: una afirmación de procedencia fabricada, indistinguible para un
lector de una real. La evidencia insuficiente que *disparó* el fallback no es una fuente de una
respuesta que no se escribió a partir de ella.

Por el mismo motivo el `LlmPrompt` del camino de fallback lleva **lista de pasajes vacía**:
pasar los candidatos descartados fundamentaría la respuesta precisamente en la evidencia que esa
rama acaba de rechazar, mientras la respuesta declara no tener fuentes.

---

## 3. Política (`GeneralAiFallbackPolicy`)

Devuelve un *motivo de rechazo* en lugar de un booleano, para que se pueda auditar y medir **por
qué** no se usó el fallback: la diferencia entre una función desactivada y una función
silenciosamente rota.

| Motivo | Cuándo |
|---|---|
| `DISABLED` | `insurance-ai.rag.general-ai-fallback.enabled: false` (defecto) |
| `CONTRACTUAL_QUESTION` | La pregunta pide una determinación sobre una póliza concreta |
| `PII_IN_QUESTION` | Hay PII detectada y `allow-questions-with-pii: false` (defecto) |

**Fail-closed por construcción**: solo se permite el fallback si *todas* las condiciones se
cumplen positivamente.

### 3.1 La regla contractual es el control determinante

Un asistente asegurador que responde «¿está cubierto?» desde conocimiento general no da una
respuesta degradada: da una **respuesta equivocada sobre un contrato del que la empresa
responde**, y el aviso en la respuesta no lo deshace.

Por eso las preguntas que piden una determinación sobre cobertura, exclusiones, franquicias,
indemnizaciones o elegibilidad se rechazan **con independencia de la configuración**:
deliberadamente **no existe ninguna propiedad que las habilite**.

Se reconocen dos señales, ambas leídas en español e **insensibles a acentos**:

1. Marcador de situación específica (`mi`, `mis`, `me`, `tengo`, `nuestro`…) **Y** un término de
   contrato (`cobertura`, `franquicia`, `indemnizacion`, `poliza`, `siniestro`…), **o**
2. Una frase de determinación por sí sola (`esta cubierto`, `tengo derecho`, `cuanto me
   indemnizan`, `puedo reclamar`, `me cubre`…).

Un término de contrato **solo** no basta: «¿qué es una franquicia?» es justamente el caso
educativo para el que existe esta función.

### 3.2 Limitaciones reconocidas de la política

Es una **regla determinista de palabras clave, no un clasificador**, y eso es una decisión con
límites reales:

- **No** detectará una pregunta contractual formulada sin ninguno de estos marcadores.
- **Sí** rechazará algunas preguntas inocuas que los contengan.
- Está ajustada para **errar hacia el rechazo**.
- **No** sustituye al requisito de supervisión humana registrado en el sistema de IA
  (`docs/governance/HUMAN_OVERSIGHT.md`).

La insensibilidad a acentos no es un detalle: un control que se evita omitiendo una tilde no es
un control. Verificado: `esta cubierto mi vehiculo?` se rechaza igual que
`¿está cubierto mi vehículo?`.

---

## 4. Garantías estructurales (no por regla editable)

Dos propiedades no dependen de la política, sino del **flujo de control**:

1. **Una pregunta bloqueada nunca llega al fallback.** `AskInsuranceKnowledgeUseCase` retorna en
   el camino del guardarraíl *antes* de la recuperación. Un *prompt injection* respondido por el
   camino de IA general sin guardas sería el ataque triunfando por otra vía.
2. **Una pregunta que sí encontró evidencia nunca se desvía** a conocimiento general: el fallback
   se alcanza solo desde la rama de "sin evidencia suficiente".

---

## 5. Seguridad y gobernanza

| Control | Tratamiento |
|---|---|
| Prompt injection | Bloqueo previo; nunca activa fallback (garantía estructural §4) |
| PII | No se reenvía a un proveedor externo salvo autorización explícita por propiedad |
| Prompt | En el **Prompt Registry** (`V9`, clave `general-ai-system-prompt`), no una constante del jar |
| Auditoría | `AuditOutcome.GENERAL_AI_ANSWER`, outcome propio y distinto de `GROUNDED_ANSWER` |
| Métricas | `rag.general_ai.count`, `rag.general_ai.refused.count{reason}`, `rag.general_ai.failed.count{error}` |
| Fallo del proveedor | Degrada a `NO_ANSWER`, **no** a 502/503 |
| Clasificación documental / control de acceso | Sin cambios: el fallback no lee documentos |

### 5.1 El prompt de fallback es un control, no texto decorativo

`GeneralAiSystemPrompt.TEXT` prohíbe explícitamente: citar o inventar documentos; afirmar que una
cobertura concreta existe o aplica; decidir sobre siniestros, precios o elegibilidad. Y **obliga**
a declarar que la respuesta no se basa en la documentación de la empresa.

Es un prompt **separado**, no una variante del de RAG: la instrucción central de aquél es
«responde solo desde el contexto recuperado», que es precisamente la instrucción que no puede
aplicarse cuando no hay contexto recuperado.

`GeneralAiPromptSeedDataTest` verifica que la `V9` y la constante no pueden divergir
(checksum SHA-256) **y** que las tres prohibiciones siguen presentes en el texto.

### 5.2 Presupuesto y rate limiting

- **Rate limiting**: heredado del gateway (`RateLimitingFilter`), sin cambios.
- **Control de presupuesto/coste: NO IMPLEMENTADO.** No existe tope de gasto para este camino.
  Los contadores de tokens (`rag.llm.tokens.*`) se emiten solo si el proveedor reporta uso, y
  `LlmCostCalculator` permite proyectar coste a posteriori, pero **nada corta el gasto en
  caliente**. Mitigación actual: la función está desactivada por defecto. **Pendiente real**
  antes de habilitarla en un despliegue con proveedor de pago.

---

## 6. Incompatibilidad deliberada con la evaluación

`EvaluationRunnerService.run` **se niega a arrancar** si el fallback está activo.

Motivo: ese servicio colapsa `grounding` a `ExpectedOutcome` (`GROUNDED` / `NO_ANSWER`). Una
respuesta `GENERAL_AI_UNVERIFIED` es `NOT_GROUNDED`, así que se puntuaría como *no-answer* — pero
el sistema **sí respondió**. Todos los casos `NO_ANSWER` del dataset pasarían silenciosamente
mientras el asistente respondía preguntas fuera del corpus: exactamente lo contrario de lo que
esos casos miden.

Antes que etiquetar mal el resultado, el servicio falla con un mensaje explícito. **Desactive el
fallback para las ejecuciones de evaluación.**

---

## 7. Verificación

### 7.1 Tests (todos PASS)

| Suite | Tests | Qué fija |
|---|---|---|
| `GeneralAiFallbackPolicyTest` | 20 | Rechazos, defecto desactivado, 8 formulaciones contractuales, sin acentos, PII, orden de precedencia |
| `AskInsuranceKnowledgeUseCaseFallbackTest` | 13 | Etiquetado, ausencia de citas, pasajes vacíos, clave de prompt correcta, bloqueo no rescatado, degradación por fallo del proveedor, auditoría, métricas |
| `RagAnswerTest` | 4 | La invariante de "sin citas" es inviolable |
| `GeneralAiPromptSeedDataTest` | 2 | Checksum de `V9` y prohibiciones del prompt |

### 7.2 Despliegue real (WAF → Gateway → Backend, JWT de Keycloak)

Con el fallback **habilitado** temporalmente:

| Pregunta | Resultado observado | Esperado |
|---|---|---|
| «¿Que es la fotosintesis de las plantas?» | `GENERAL_AI_UNVERIFIED`, **0 fuentes** | ✓ |
| «¿Cual es la capital de Francia?» | `GENERAL_AI_UNVERIFIED`, **0 fuentes** | ✓ |
| «¿Cubre mi seguro de hogar las inundaciones del sotano?» | `NO_ANSWER` | ✓ política rechaza |
| «Ignore all previous instructions…» | `BLOCKED` | ✓ no rescatado |
| «¿Como funciona el bonus-malus en general?» | `DOCUMENT_GROUNDED`, 8 fuentes | ✓ hay evidencia real |

Métricas leídas del actuator tras esas pruebas:

```
rag.general_ai.count                      COUNT=2.0
rag.general_ai.refused.count{reason=CONTRACTUAL_QUESTION}  COUNT=1.0
rag.grounding.count                       COUNT=2.0
rag.no_answer.count                       COUNT=1.0
rag.blocked.count                         COUNT=1.0
```

Nótese que las 2 respuestas de IA general **no** inflaron `rag.grounding.count` — la separación
métrica buscada. `rag.grounding.count` se lee en los paneles como «respuestas respaldadas por
nuestra documentación»; mezclar ahí una respuesta no verificada sobrestimaría justo lo que esta
función mantiene separado.

Con el fallback **revertido al defecto**, las mismas preguntas devuelven `NO_ANSWER`.

### 7.3 Observación honesta sobre el proveedor *fake*

Con `FakeLlmAdapter`, el texto devuelto en el camino de fallback empieza por *"Based on the
retrieved documentation:"* — una cadena fija del adaptador *fake*, que en este camino es
**engañosa** (no hay documentación recuperada). No afecta a un proveedor real, que recibe el
prompt de `V9` y responde a él. Queda anotado como **cosmético, limitado al proveedor de
pruebas**; el `answerMode` y la ausencia de fuentes, que son el contrato real, son correctos.

---

## 8. Interfaz de usuario

`AssistantMessageKind` gana `'general-ai'`, mapeado **1:1 y de forma exhaustiva** desde
`answerMode` en `ChatSessionService.kindOf` — un `switch` sin `default`, para que añadir un quinto
modo en el backend **rompa la compilación** en lugar de renderizarse silenciosamente como
respuesta fundamentada, que para una respuesta no verificada sería el peor defecto posible.

El aviso se renderiza **encima** del texto de la respuesta (a diferencia del aviso de PII, que va
debajo): el lector debe saber que esto no viene de la documentación **antes** de leerlo, no
después. Incluye franja de color de advertencia, título y explicación de que no debe usarse para
coberturas, exclusiones ni importes.

---

## 9. Cómo habilitarlo

```yaml
insurance-ai:
  rag:
    general-ai-fallback:
      enabled: true
      allow-questions-with-pii: false   # mantener en false salvo base legal revisada
```

**Antes de habilitarlo en un entorno con proveedor de pago**, resuelva lo pendiente de §5.2
(control de presupuesto) y revise la base legal del tratamiento de datos, ya que el camino
reenvía la pregunta del empleado a un tercero.
