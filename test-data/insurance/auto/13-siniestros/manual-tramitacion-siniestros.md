# Manual Interno de Tramitación de Siniestros de Automóvil

**Compañía**: IBERIA SEGUROS FICTICIA, S.A.

**Código de documento**: SIN-MAN-02

**Versión**: 2.0

**Fecha de entrada en vigor**: 1 de enero de 2026

**Clasificación**: Uso interno. Dirección de Siniestros. Distribución restringida a personal tramitador y mandos intermedios.

**Documentos relacionados**: SIN-PROC-01, CG-AUTO-V2, IND-GUIA-01, IND-EXCL-02, REC-PROC-01

DOCUMENTACIÓN FICTICIA PARA PRUEBAS FUNCIONALES. NO REPRESENTA CONDICIONES REALES DE NINGUNA ASEGURADORA.

## 1. Finalidad del manual

Este manual establece las instrucciones operativas de obligado cumplimiento para el personal del Departamento de Siniestros de Automóvil. Su objeto es homogeneizar criterios de tramitación, garantizar la trazabilidad de las decisiones y asegurar el cumplimiento de los acuerdos de nivel de servicio comprometidos con la Dirección General. Toda desviación respecto de lo aquí previsto debe justificarse en el campo de observaciones del expediente y quedará sujeta a auditoría interna.

El manual no sustituye al condicionado contractual. Ante cualquier conflicto entre lo dispuesto en este documento y las Condiciones Generales CG-AUTO-V2 o las condiciones particulares de la póliza, prevalece siempre el clausulado contractual en lo que resulte más favorable al asegurado.

## 2. Circuito interno de tramitación

### 2.1 Recepción y apertura

La Unidad de Primera Notificación (UPN) recibe el aviso por cualquiera de los cuatro canales habilitados y realiza tres verificaciones automáticas: vigencia de la póliza en la fecha del siniestro, situación de recibos y coincidencia entre el conductor declarado y el conductor implicado. Superadas las verificaciones, el sistema asigna número de expediente EXP-2026-000000 y estado T00.

### 2.2 Clasificación y encolado

El motor de reglas clasifica el expediente en una de las cuatro colas de trabajo: Daños Materiales Simples, Daños Materiales Complejos, Lesiones Corporales, y Robo e Incendio. La clasificación tiene en cuenta el importe estimado, la existencia de terceros implicados, la presencia de heridos y la antigüedad del vehículo. Un expediente mal clasificado debe reencolarse por el tramitador en las primeras 24 horas.

### 2.3 Asignación al tramitador

La asignación es automática por carga de trabajo y especialidad. Ningún tramitador puede autoasignarse un expediente en el que concurra conflicto de interés, entendiendo por tal el parentesco hasta segundo grado con cualquier implicado, la titularidad del vehículo o la vinculación con el taller reparador. La declaración de conflicto se realiza en el propio expediente y provoca reasignación inmediata.

### 2.4 Instrucción y resolución

El tramitador instruye el expediente: solicita documentación, ordena peritación, valora la cobertura frente a la tabla IND-EXCL-02, cuantifica el daño y propone resolución. La resolución puede ser de aceptación total, aceptación parcial, rechazo o traslado a la Unidad de Fraude. Toda resolución de rechazo exige motivación expresa con cita del artículo del condicionado aplicable.

## 3. Niveles de autorización por importe

### 3.1 Escala de facultades

La facultad de autorizar pagos e indemnizaciones se distribuye conforme a la siguiente escala. El importe de referencia es el total del expediente, incluidos honorarios periciales y gastos de asistencia, antes de deducir franquicia.

| Nivel | Perfil autorizador | Importe máximo | Doble firma |
|---|---|---|---|
| N1 | Tramitador junior | Hasta 1.500 € | No |
| N2 | Tramitador senior | Hasta 6.000 € | No |
| N3 | Jefe de equipo | Hasta 20.000 € | No |
| N4 | Responsable de área | Hasta 60.000 € | Sí |
| N5 | Dirección de Siniestros | Hasta 250.000 € | Sí |
| N6 | Comité de Siniestros Graves | Sin límite | Sí |

### 3.2 Reglas de aplicación de la escala

Está expresamente prohibido el fraccionamiento artificial de un mismo siniestro en varios pagos con el fin de mantenerse dentro del nivel de facultades propio. La detección de fraccionamiento se considera incidencia grave a efectos disciplinarios. Cuando un expediente supere el umbral del nivel asignado, el tramitador debe elevarlo mediante la acción *Solicitar autorización superior*, que congela el plazo interno hasta la respuesta.

### 3.3 Supuestos de elevación obligatoria

Con independencia del importe, se elevan siempre a nivel N4 o superior: los siniestros con fallecidos o con lesiones de pronóstico grave, los que impliquen posible responsabilidad de la propia compañía por defecto de asesoramiento, los que afecten a flotas de más de 25 vehículos y aquellos en que exista una reclamación previa registrada en REC-PROC-01.

## 4. Criterios de asignación de perito

### 4.1 Peritación telemática

Se resuelven por peritación telemática, sin desplazamiento, los expedientes que cumplan simultáneamente estas condiciones: importe estimado inferior a 1.500 €, daños visibles y localizados en elementos de carrocería exteriores, reportaje fotográfico completo conforme al apartado 5 de SIN-PROC-01 y ausencia de terceros lesionados. El plazo objetivo de valoración telemática es de 48 horas.

### 4.2 Peritación presencial ordinaria

Requieren inspección física los expedientes de importe estimado entre 1.500 € y 20.000 €, los que presenten daños mecánicos no visibles, los que afecten a la estructura o a los sistemas de seguridad pasiva, los de vehículos con más de 12 años de antigüedad y todos los de vehículos eléctricos o híbridos con posible afectación de la batería de tracción.

### 4.3 Peritación especializada

Se asigna perito especialista en los siguientes supuestos: incendio con origen no determinado, robo con sospecha de simulación, vehículos de valor superior a 60.000 €, vehículos históricos o con valor de afección, daños con posible afectación de la batería de alto voltaje y siniestros con más de tres vehículos implicados. El especialista dispone de un plazo ampliado de 12 días hábiles para emitir informe.

### 4.4 Rotación e independencia

El sistema aplica rotación automática entre los gabinetes concertados de cada zona para evitar concentración. Ningún gabinete puede recibir más del 35 % de los expedientes de una misma provincia en un trimestre natural. Los gabinetes con una tasa de reapertura superior al 8 % entran en revisión de calidad.

## 5. Indicadores de fraude

### 5.1 Indicadores relativos a la póliza

Son señales de alerta: la contratación de la póliza en los 30 días anteriores al siniestro, la ampliación de coberturas en los 15 días previos, la existencia de tres o más siniestros declarados en los últimos 12 meses, el impago de recibos regularizado inmediatamente después del siniestro y la modificación reciente del conductor declarado.

### 5.2 Indicadores relativos al siniestro

Se consideran indicadores: la ausencia de testigos en accidentes ocurridos en vía urbana con tráfico habitual, la declaración tardía próxima al límite de 7 días naturales sin causa justificada, la incompatibilidad entre la mecánica descrita y la morfología de los daños, la presencia de daños preexistentes no declarados y la coincidencia de implicados en siniestros anteriores.

### 5.3 Indicadores relativos a los intervinientes

Alertan sobre posible fraude: la reiteración del mismo taller, gabinete o clínica en expedientes no relacionados, la aportación de facturas sin desglose de mano de obra y recambios, la insistencia en el cobro en metálico o en cuenta de tercero, y la negativa reiterada a facilitar la inspección física del vehículo.

### 5.4 Procedimiento ante sospecha

El tramitador que aprecie dos o más indicadores debe trasladar el expediente a la Unidad de Investigación de Fraude mediante el código T71, sin comunicar al asegurado la existencia de la investigación ni formular imputación alguna. La Unidad dispone de 20 días hábiles para emitir informe. Durante ese periodo el expediente permanece en estado suspendido a efectos de SLA, pero se mantienen las obligaciones de información al asegurado sobre el estado general del trámite.

## 6. Escalado

### 6.1 Escalado funcional

El escalado funcional procede cuando el tramitador carece de facultades o de criterio consolidado. Se realiza al jefe de equipo, que debe pronunciarse en 48 horas hábiles. Si el jefe de equipo no resuelve, el expediente escala automáticamente al responsable de área al tercer día hábil.

### 6.2 Escalado por reclamación

La entrada de una reclamación formal genera de oficio la marca REC en el expediente y su escalado al jefe de equipo, que debe emitir informe interno en 5 días hábiles para el Servicio de Atención al Cliente. La existencia de reclamación no paraliza la tramitación ordinaria.

### 6.3 Escalado por criticidad mediática o judicial

Los expedientes con repercusión en medios, con requerimiento judicial o con intervención de asociaciones de consumidores se comunican en el mismo día a la Dirección de Siniestros y a la Asesoría Jurídica, mediante el código T85. La comunicación externa queda reservada al Departamento de Comunicación.

## 7. Acuerdos de nivel de servicio internos

### 7.1 Plazos objetivo

Los SLA internos son más exigentes que los plazos contractuales y se miden en días hábiles. Su cumplimiento se evalúa mensualmente por expediente y por equipo.

| Hito | SLA objetivo | Cumplimiento mínimo |
|---|---|---|
| Apertura desde el aviso | 24 horas | 98 % |
| Designación de perito | 3 días | 95 % |
| Primer contacto con el asegurado | 2 días | 97 % |
| Emisión de informe pericial | 7 días | 90 % |
| Resolución de cobertura | 12 días | 92 % |
| Orden de pago | 15 días | 93 % |

### 7.2 Medición y consecuencias

El cuadro de mando de siniestros publica los resultados el quinto día hábil de cada mes. Un equipo que incumpla dos indicadores durante tres meses consecutivos entra en plan de refuerzo, con revisión semanal de cartera y apoyo de un tramitador senior. Los incumplimientos individuales reiterados se tratan en la evaluación de desempeño.

### 7.3 Exclusiones del cómputo

No computan a efectos de SLA los periodos de espera imputables al asegurado, los de investigación de fraude bajo código T71, los de suspensión judicial y los derivados de causas de fuerza mayor declaradas por la Dirección de Operaciones, como episodios meteorológicos que generen siniestralidad masiva.

## 8. Códigos de tramitación

### 8.1 Códigos de estado

Los códigos de estado describen la situación del expediente y son de uso obligatorio en cada transición. T00 apertura registrada. T10 pendiente de documentación del asegurado. T20 perito designado. T30 informe pericial recibido. T40 cobertura aceptada. T45 cobertura aceptada parcialmente. T50 reparación autorizada. T60 orden de pago emitida. T70 rechazo motivado. T71 traslado a investigación de fraude. T80 recobro frente a tercero iniciado. T85 expediente crítico. T90 cierre administrativo. T95 reapertura.

### 8.2 Códigos de motivo de rechazo

Cada rechazo exige un código de motivo: R01 siniestro anterior a la vigencia. R02 impago de prima. R03 cobertura no contratada. R04 exclusión general aplicable. R05 conductor no autorizado. R06 falta de acreditación del daño. R07 daño preexistente. R08 dolo del asegurado. R09 uso del vehículo distinto del declarado. R10 incumplimiento sustancial del deber de declaración.

### 8.3 Códigos de cierre

Los códigos de cierre alimentan la estadística actuarial: C01 pago íntegro. C02 pago parcial con conformidad. C03 desistimiento del asegurado. C04 rechazo firme. C05 recobro íntegro obtenido. C06 caducidad por inactividad. C07 traslado a vía judicial. La calidad de la codificación de cierre es objeto de auditoría trimestral por parte de la Unidad de Control Interno.
