import { AuthService } from '../auth.service';

/**
 * FASE 18: a minimal stand-in for `AuthService` used by specs that exercise `authInterceptor`/
 * `errorInterceptor` but are not themselves testing authentication - avoids pulling in the real
 * `angular-oauth2-oidc` `OAuthService` (and therefore a real OIDC discovery-document HTTP call)
 * into unrelated HTTP-contract tests. `AuthService`'s own behavior has its own dedicated spec.
 */
export function authServiceStub(overrides: Partial<AuthService> = {}): Partial<AuthService> {
  return {
    isAuthenticated: (() => false) as unknown as AuthService['isAuthenticated'],
    username: (() => null) as unknown as AuthService['username'],
    roles: (() => new Set()) as unknown as AuthService['roles'],
    getAccessToken: () => null,
    login: () => {},
    logout: () => {},
    hasAnyRole: () => false,
    ...overrides,
  };
}
