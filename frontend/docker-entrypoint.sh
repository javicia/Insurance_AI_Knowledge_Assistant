#!/bin/sh
# Runs as one of nginx's own /docker-entrypoint.d/ startup hooks (FASE 16 separation): generates
# env.js from the PUBLIC_API_BASE_URL environment variable so the same built image is deployable
# against any environment's gateway URL without a rebuild - see core/config/api.config.ts and
# docs/adr/ADR-014-FRONTEND-BACKEND-SEPARATION.md. Defaults to an empty string (same-origin
# relative path) when unset, matching the local-dev default already committed in public/env.js.
set -eu

: "${PUBLIC_API_BASE_URL:=}"

cat > /usr/share/nginx/html/env.js <<EOF
window.__env = {
  PUBLIC_API_BASE_URL: '${PUBLIC_API_BASE_URL}'
};
EOF
