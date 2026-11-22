#!/usr/bin/env bash
# FASE 31 - Arranca un backend AISLADO con embeddings locales bge-m3.
#
# Todo por linea de comandos: no se edita application.yaml, de modo que el despliegue principal
# conserva su configuracion y este proceso no puede heredar nada por error.
export JAVA_HOME="D:/Javier/Java/.jdks/jdk-25.0.4+7"
cd "D:/Javier/Proyectos/rag_springai_prueba/backend" || exit 1

"$JAVA_HOME/bin/java" -jar target/insurance-knowledge-assistant-0.0.1-SNAPSHOT.jar \
  --server.port=8091 \
  --spring.http.clients.read-timeout=120s \
  --spring.datasource.url=jdbc:postgresql://localhost:5435/fase31_eval \
  --spring.datasource.username=fase31_eval \
  --spring.datasource.password=fase31_eval_local_only \
  --insurance-ai.ai.provider=fake \
  --insurance-ai.ai.embedding-provider=ollama \
  --insurance-ai.ai.embedding-model=bge-m3:latest \
  --spring.ai.model.embedding=ollama \
  --spring.ai.ollama.base-url=http://localhost:11434 \
  --spring.ai.ollama.embedding.options.model=bge-m3:latest \
  --insurance-ai.rag.vector-table=vector_store_bge_m3 \
  --insurance-ai.rag.semantic.similarity-threshold=0.65 \
  --insurance-ai.rag.general-ai-fallback.enabled=false \
  --spring.kafka.consumer.group-id=fase31-eval-processor \
  --spring.kafka.bootstrap-servers=localhost:9094 \
  --spring.security.oauth2.resourceserver.jwt.jwk-set-uri=http://localhost:8180/realms/insurance-ai/protocol/openid-connect/certs \
  --insurance-ai.security.oauth2.issuer=http://localhost:8180/realms/insurance-ai \
  --management.otlp.tracing.export.enabled=false \
  --logging.level.root=WARN \
  --logging.level.com.rag.springai=INFO \
  --logging.level.org.flywaydb=INFO
