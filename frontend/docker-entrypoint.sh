#!/bin/sh
# Runs as one of nginx's own /docker-entrypoint.d/ startup hooks (FASE 16 separation, FASE 18
# OIDC): generates env.js from environment variables so the same built image is deployable against
# any environment's gateway/Keycloak URLs without a rebuild - see core/config/api.config.ts,
# core/config/oidc.config.ts, and docs/adr/ADR-014-FRONTEND-BACKEND-SEPARATION.md. Defaults match
# the local-dev values already committed in public/env.js.
set -eu

: "${PUBLIC_API_BASE_URL:=}"
: "${PUBLIC_OIDC_ISSUER:=http://localhost:8180/realms/insurance-ai}"
: "${PUBLIC_OIDC_CLIENT_ID:=insurance-ai-frontend}"

cat > /usr/share/nginx/html/env.js <<EOF
window.__env = {
  PUBLIC_API_BASE_URL: '${PUBLIC_API_BASE_URL}',
  PUBLIC_OIDC_ISSUER: '${PUBLIC_OIDC_ISSUER}',
  PUBLIC_OIDC_CLIENT_ID: '${PUBLIC_OIDC_CLIENT_ID}'
};
EOF

# FASE 32: Content-Security-Policy, generated here (not hardcoded in nginx.conf) because
# connect-src must reference this deployment's real PUBLIC_API_BASE_URL - an empty value (local
# dev / same-origin) needs no extra entry beyond 'self'; a configured absolute URL (the WAF/
# gateway address in Docker) is appended explicitly. style-src/font-src allow Google Fonts (the
# only third-party origin this app ever loads, see index.html) and 'unsafe-inline' styles
# (Angular Material's runtime style injection) - documented as a real, evaluated exception, not
# an oversight. script-src stays 'self' only: this is an AOT-compiled Angular production build,
# which needs neither 'unsafe-eval' nor 'unsafe-inline' for scripts.
#
# FASE 26 incident (found by the first real-browser E2E run): a CSP source expression whose path
# does NOT end in "/" matches that ONE exact URL and nothing below it (CSP Level 3, "path part
# match"). `PUBLIC_OIDC_ISSUER` is an issuer *base* (…/realms/insurance-ai) and every URL the app
# actually calls is underneath it (…/realms/insurance-ai/.well-known/openid-configuration, the
# token endpoint, …), so listing it verbatim allowed literally none of them. The browser blocked
# the OIDC discovery request, the login flow could never start, and the deployed app was
# unusable - while nginx, the container healthcheck, and every curl-based API check stayed green,
# because none of them execute JavaScript or enforce CSP. A trailing slash is therefore required,
# and appended here if the configured value lacks one.
connect_src_extra=""
if [ -n "${PUBLIC_API_BASE_URL}" ]; then
    connect_src_extra=" ${PUBLIC_API_BASE_URL}"
fi
if [ -n "${PUBLIC_OIDC_ISSUER}" ]; then
    case "${PUBLIC_OIDC_ISSUER}" in
        */) oidc_connect_src="${PUBLIC_OIDC_ISSUER}" ;;
        *)  oidc_connect_src="${PUBLIC_OIDC_ISSUER}/" ;;
    esac
    connect_src_extra="${connect_src_extra} ${oidc_connect_src}"
fi

cat > /etc/nginx/conf.d/csp-header.conf <<EOF
add_header Content-Security-Policy "default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline' https://fonts.googleapis.com; font-src 'self' https://fonts.gstatic.com; img-src 'self' data:; connect-src 'self'${connect_src_extra}; frame-ancestors 'none'; base-uri 'self'; form-action 'self'" always;
EOF
