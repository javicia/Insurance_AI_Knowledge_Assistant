import { AuthConfig } from 'angular-oauth2-oidc';

/**
 * FASE 18: like `PUBLIC_API_BASE_URL` (see api.config.ts), the OIDC issuer/client id are runtime
 * facts injected at container startup, never baked into the build - the same image is deployable
 * against any environment's Keycloak realm without a rebuild. `public/env.js` (local dev default)
 * points at the same Keycloak the backend validates tokens against
 * (`http://localhost:8180/realms/insurance-ai`) so `ng serve` behaves identically to the packaged
 * container.
 */
const configuredIssuer = typeof window !== 'undefined' ? window.__env?.PUBLIC_OIDC_ISSUER ?? '' : '';
const configuredClientId = typeof window !== 'undefined' ? window.__env?.PUBLIC_OIDC_CLIENT_ID ?? '' : '';

/**
 * Authorization Code Flow with PKCE (brief FASE 18: "no implicit flow", "no password grant") -
 * `angular-oauth2-oidc` enables PKCE by default for the code flow, matching the
 * `insurance-ai-frontend` Keycloak client's own `pkce.code.challenge.method: S256` requirement
 * (see `infra/keycloak/realm-export.json`). `responseType: 'code'` is the only flow configured -
 * there is no implicit-flow fallback to accidentally use.
 */
export const OIDC_CONFIG: AuthConfig = {
  issuer: configuredIssuer,
  clientId: configuredClientId,
  responseType: 'code',
  scope: 'openid profile email',
  redirectUri: typeof window !== 'undefined' ? window.location.origin + '/' : '',
  // A public SPA client has no secret to authenticate a silent-refresh iframe with, and Keycloak
  // already issues a refresh token for this client - refresh_token grant, not silent iframe
  // refresh, is what setupAutomaticSilentRefresh() actually uses under the hood in this library
  // for code-flow clients, avoiding third-party-cookie issues silent iframe refresh has.
  useSilentRefresh: false,
  timeoutFactor: 0.75,
  sessionChecksEnabled: false,
  showDebugInformation: false,
};
