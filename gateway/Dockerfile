# syntax=docker/dockerfile:1
#
# Independent API Gateway image (FASE 16/19) - its own Maven module, its own artifact, no
# dependency on backend/ or frontend/ source. See
# docs/adr/ADR-014-FRONTEND-BACKEND-SEPARATION.md.

########################################
# Stage 1 - build
########################################
FROM eclipse-temurin:25-jdk-noble AS build
WORKDIR /app

COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
# See backend/Dockerfile's identical comment - a BuildKit cache mount for ~/.m2 turns "pom.xml
# changed" back into "download the delta", not "re-download the whole dependency tree".
RUN --mount=type=cache,target=/root/.m2,sharing=locked ./mvnw -B dependency:go-offline

COPY src/ src/
RUN --mount=type=cache,target=/root/.m2,sharing=locked ./mvnw -B clean package -DskipTests

########################################
# Stage 2 - minimal runtime
########################################
FROM eclipse-temurin:25-jre-noble AS runtime

RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*

RUN useradd --system --create-home --home-dir /home/insurance-ai-gateway --shell /usr/sbin/nologin insurance-ai-gateway

WORKDIR /app
COPY --from=build /app/target/insurance-knowledge-assistant-gateway-*.jar app.jar
RUN chown insurance-ai-gateway:insurance-ai-gateway app.jar

USER insurance-ai-gateway

EXPOSE 8082

ENV JAVA_OPTS=""

HEALTHCHECK --interval=15s --timeout=5s --start-period=30s --retries=5 \
    CMD curl --fail --silent http://localhost:8082/actuator/health | grep -q '"status":"UP"' || exit 1

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
