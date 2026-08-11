import { Injectable, computed, signal } from '@angular/core';
import { OAuthService } from 'angular-oauth2-oidc';

import { OIDC_CONFIG } from '../config/oidc.config';

/**
 * FASE 18: wraps `OAuthService` (Authorization Code Flow + PKCE, brief: "no implicit flow", "no
 * password grant", "no implementes OAuth2 manual") - this class never parses or validates a JWT
 * signature itself; `angular-oauth2-oidc` does that against Keycloak's real JWKS. Role/authority
 * information read here (`roles`) is exposed purely for UX (hiding nav items a user has no access
 * to) - the backend/gateway always re-checks authorization independently on every request, per the
 * brief's explicit instruction that the frontend must never be trusted for real authorization
 * decisions.
 */
@Injectable({ providedIn: 'root' })
export class AuthService {
  private readonly authenticated = signal(false);
  private readonly claims = signal<Record<string, unknown> | null>(null);

  readonly isAuthenticated = computed(() => this.authenticated());
  readonly username = computed(() => (this.claims()?.['preferred_username'] as string | undefined) ?? null);
  readonly roles = computed<ReadonlySet<string>>(() => new Set(this.realmRoles()));

  constructor(private readonly oauthService: OAuthService) {
    this.oauthService.configure(OIDC_CONFIG);
    this.oauthService.events.subscribe(() => this.refreshState());
  }

  /**
   * Loads Keycloak's OIDC discovery document and completes an in-flight Authorization Code
   * callback if the browser just returned from Keycloak's login page - called once from an
   * `APP_INITIALIZER` (see `app.config.ts`) so routing/guards never race a not-yet-configured
   * `OAuthService`.
   */
  async initialize(): Promise<void> {
    await this.oauthService.loadDiscoveryDocumentAndTryLogin();
    if (this.oauthService.hasValidAccessToken()) {
      this.oauthService.setupAutomaticSilentRefresh();
    }
    this.refreshState();
  }

  login(): void {
    this.oauthService.initCodeFlow();
  }

  logout(): void {
    this.oauthService.logOut();
  }

  getAccessToken(): string | null {
    return this.oauthService.hasValidAccessToken() ? this.oauthService.getAccessToken() : null;
  }

  hasAnyRole(...roles: string[]): boolean {
    const granted = this.roles();
    return roles.some((role) => granted.has(role));
  }

  private refreshState(): void {
    const valid = this.oauthService.hasValidAccessToken();
    this.authenticated.set(valid);
    this.claims.set(valid ? this.decodeAccessTokenClaims() : null);
  }

  /**
   * Decodes the access token's payload for UX purposes only (see class Javadoc-equivalent
   * comment above) - not signature verification, which `angular-oauth2-oidc` already performed
   * against Keycloak's real JWKS before this token was ever accepted as valid.
   */
  private decodeAccessTokenClaims(): Record<string, unknown> | null {
    const token = this.oauthService.getAccessToken();
    if (!token) {
      return null;
    }
    const payload = token.split('.')[1];
    if (!payload) {
      return null;
    }
    try {
      const normalized = payload.replace(/-/g, '+').replace(/_/g, '/');
      return JSON.parse(atob(normalized)) as Record<string, unknown>;
    } catch {
      return null;
    }
  }

  private realmRoles(): string[] {
    const realmAccess = this.claims()?.['realm_access'] as { roles?: string[] } | undefined;
    return realmAccess?.roles ?? [];
  }
}
