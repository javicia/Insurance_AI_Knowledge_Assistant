# syntax=docker/dockerfile:1
#
# Independent frontend image (FASE 16 - frontend/backend separation, brief section 47: "ESTO ES
# OBLIGATORIO... NO: COPY frontend/dist backend/static; NO: Maven ejecutando npm; SI: Frontend
# Docker image / Backend Docker image"). This image contains no Java/Maven - it is a static Angular
# bundle served by nginx, configured at container startup with the one thing it is allowed to know:
# PUBLIC_API_BASE_URL. See docs/adr/ADR-014-FRONTEND-BACKEND-SEPARATION.md.

########################################
# Stage 1 - build the Angular SPA
########################################
FROM node:22-slim AS build
WORKDIR /app

# Copy only the manifest/lockfile first so `npm ci` is cached across rebuilds that don't touch
# dependencies - this build never depends on a developer having already run `npm run build`
# locally.
COPY package.json package-lock.json ./
RUN npm ci

COPY . .
RUN npm run build

########################################
# Stage 2 - minimal static runtime
########################################
# nginx-unprivileged, not plain nginx: the entire process (master included, not just workers)
# runs as a non-root user and listens on 8080 by default - genuinely non-root end to end, not a
# hand-patched permission workaround (see FASE 39 CONTAINER_HARDENING.md).
FROM nginxinc/nginx-unprivileged:1.27-alpine AS runtime

USER root
RUN apk add --no-cache curl

COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY docker-entrypoint.sh /docker-entrypoint.d/40-inject-runtime-config.sh
RUN chmod +x /docker-entrypoint.d/40-inject-runtime-config.sh

COPY --from=build --chown=nginx:nginx /app/dist/frontend/browser/ /usr/share/nginx/html/

USER nginx

EXPOSE 8080

HEALTHCHECK --interval=15s --timeout=5s --start-period=10s --retries=5 \
    CMD curl --fail --silent http://localhost:8080/ || exit 1
