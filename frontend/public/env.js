// Local-dev default (ng serve, proxy.conf.json forwards /api to the backend on :8080) - the
// production container overwrites this exact file at startup via docker-entrypoint.sh, generating
// it from the PUBLIC_API_BASE_URL environment variable (FASE 16 frontend/backend separation).
// Empty string means "same-origin relative path", which is what proxy.conf.json and the
// docker-compose gateway route both rely on.
window.__env = {
  PUBLIC_API_BASE_URL: ''
};
