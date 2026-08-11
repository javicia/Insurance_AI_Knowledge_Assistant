# syntax=docker/dockerfile:1
#
# Backend-only image (FASE 16 - frontend/backend separation, brief section 47: "ESTO ES
# OBLIGATORIO. Frontend y backend son productos separados."). This image contains no Node/npm and
# no Angular build stage - the SPA is a fully independent deployable, see frontend/Dockerfile and
# docs/adr/ADR-014-FRONTEND-BACKEND-SEPARATION.md. Superseded the single-container FASE 15 image
# (ADR-013), which is now historical context only.

########################################
# Stage 1 - build the Spring Boot backend
########################################
FROM eclipse-temurin:25-jdk-noble AS backend-build
WORKDIR /app

# Dependencies resolve from the manifest before any source is copied in, maximizing layer cache
# reuse across rebuilds that don't touch dependencies.
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN ./mvnw -B dependency:go-offline

COPY src/ src/

# Backend correctness (286 tests, ArchUnit, Testcontainers-backed integration tests) is verified
# independently via `./mvnw clean verify` in development/CI before this image is ever built -
# re-running the full Testcontainers-backed suite inside this build stage would require
# Docker-in-Docker, which this image intentionally does not assume. Skipping tests at this
# specific packaging step is a standard, documented build-time optimization, not a weakening of
# the actual test suite - that verification already happened, and is re-run independently as part
# of this same delivery.
RUN ./mvnw -B clean package -DskipTests

########################################
# Stage 2 - minimal runtime
########################################
FROM eclipse-temurin:25-jre-noble AS runtime

# curl is the healthcheck's only added dependency (check the real Actuator health endpoint, not an
# external network target) - not shipped in the -jre base image by default, installed here
# deliberately rather than avoided at the cost of a weaker healthcheck.
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*

RUN useradd --system --create-home --home-dir /home/insurance-ai --shell /usr/sbin/nologin insurance-ai

WORKDIR /app
COPY --from=backend-build /app/target/insurance-knowledge-assistant-*.jar app.jar
RUN chown insurance-ai:insurance-ai app.jar

USER insurance-ai

EXPOSE 8080

# JVM tuning is deliberately not hardcoded - JAVA_OPTS is empty by default (modern JDKs are
# already container-memory-aware) and can be overridden per deployment without rebuilding.
ENV JAVA_OPTS=""

HEALTHCHECK --interval=15s --timeout=5s --start-period=90s --retries=5 \
    CMD curl --fail --silent http://localhost:8080/actuator/health | grep -q '"status":"UP"' || exit 1

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
