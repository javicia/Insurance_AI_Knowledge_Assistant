// Local-dev default (ng serve, proxy.conf.json forwards /api to the backend on :8080) - the
// production container overwrites this exact file at startup via docker-entrypoint.sh, generating
// it from environment variables (FASE 16 frontend/backend separation, FASE 18 OIDC).
// PUBLIC_API_BASE_URL empty string means "same-origin relative path", which is what
// proxy.conf.json and the docker-compose gateway route both rely on. PUBLIC_OIDC_ISSUER/
// PUBLIC_OIDC_CLIENT_ID point at the same Keycloak realm the backend validates tokens against, so
// `ng serve` logs in against the real local Keycloak exactly like the packaged container does.
window.__env = {
  PUBLIC_API_BASE_URL: '',
  PUBLIC_OIDC_ISSUER: 'http://localhost:8180/realms/insurance-ai',
  PUBLIC_OIDC_CLIENT_ID: 'insurance-ai-frontend'
};
