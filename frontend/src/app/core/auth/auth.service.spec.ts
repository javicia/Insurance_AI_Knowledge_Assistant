import { TestBed } from '@angular/core/testing';
import { OAuthEvent, OAuthService } from 'angular-oauth2-oidc';
import { Subject } from 'rxjs';
import { vi } from 'vitest';

import { AuthService } from './auth.service';

/** Builds a minimal base64url-encoded JWT with the given payload - no real signature needed, this
 *  test only exercises AuthService's own client-side (UX-only) claim decoding. */
function fakeJwt(payload: Record<string, unknown>): string {
  const base64url = (obj: object) =>
    btoa(JSON.stringify(obj)).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
  return `${base64url({ alg: 'none' })}.${base64url(payload)}.`;
}

describe('AuthService', () => {
  let events: Subject<OAuthEvent>;
  let validToken: boolean;
  let accessToken: string;
  let oauthServiceStub: Partial<OAuthService>;
  let service: AuthService;

  beforeEach(() => {
    events = new Subject();
    validToken = false;
    accessToken = '';

    oauthServiceStub = {
      configure: vi.fn(),
      events: events.asObservable(),
      hasValidAccessToken: () => validToken,
      getAccessToken: () => accessToken,
      loadDiscoveryDocumentAndTryLogin: vi.fn().mockResolvedValue(true),
      setupAutomaticSilentRefresh: vi.fn(),
      initCodeFlow: vi.fn(),
      logOut: vi.fn(),
    };

    TestBed.configureTestingModule({
      providers: [AuthService, { provide: OAuthService, useValue: oauthServiceStub }],
    });
    service = TestBed.inject(AuthService);
  });

  it('starts unauthenticated with no username or roles', () => {
    expect(service.isAuthenticated()).toBe(false);
    expect(service.username()).toBeNull();
    expect(service.roles().size).toBe(0);
  });

  it('login() delegates to OAuthService.initCodeFlow (Authorization Code Flow, no implicit flow)', () => {
    service.login();

    expect(oauthServiceStub.initCodeFlow).toHaveBeenCalledTimes(1);
  });

  it('logout() delegates to OAuthService.logOut', () => {
    service.logout();

    expect(oauthServiceStub.logOut).toHaveBeenCalledTimes(1);
  });

  it('reflects authenticated state, username, and roles once a valid token is present', () => {
    validToken = true;
    accessToken = fakeJwt({
      preferred_username: 'alice.user',
      realm_access: { roles: ['AI_USER', 'offline_access'] },
    });

    events.next({ type: 'token_received' } as OAuthEvent);

    expect(service.isAuthenticated()).toBe(true);
    expect(service.username()).toBe('alice.user');
    expect(service.hasAnyRole('AI_USER')).toBe(true);
    expect(service.hasAnyRole('AI_GOVERNANCE_ADMIN')).toBe(false);
  });

  it('getAccessToken returns null when there is no valid token, even if one is technically stored', () => {
    validToken = false;
    accessToken = fakeJwt({ preferred_username: 'alice.user' });

    expect(service.getAccessToken()).toBeNull();
  });

  it('initialize() loads the discovery document and sets up silent refresh for a valid session', async () => {
    validToken = true;

    await service.initialize();

    expect(oauthServiceStub.loadDiscoveryDocumentAndTryLogin).toHaveBeenCalledTimes(1);
    expect(oauthServiceStub.setupAutomaticSilentRefresh).toHaveBeenCalledTimes(1);
  });

  it('initialize() does not set up silent refresh when there is no valid session', async () => {
    validToken = false;

    await service.initialize();

    expect(oauthServiceStub.setupAutomaticSilentRefresh).not.toHaveBeenCalled();
  });
});
