import { HttpClient, provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';

import { authInterceptor } from './auth.interceptor';
import { AuthService } from '../auth/auth.service';

describe('authInterceptor', () => {
  let http: HttpClient;
  let httpMock: HttpTestingController;

  function configure(token: string | null) {
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(withInterceptors([authInterceptor])),
        provideHttpClientTesting(),
        { provide: AuthService, useValue: { getAccessToken: () => token } },
      ],
    });
    http = TestBed.inject(HttpClient);
    httpMock = TestBed.inject(HttpTestingController);
  }

  afterEach(() => httpMock.verify());

  it('attaches a Bearer token to a request targeting the API', () => {
    configure('a-real-access-token');

    http.get('/api/chat').subscribe();

    const request = httpMock.expectOne('/api/chat');
    expect(request.request.headers.get('Authorization')).toBe('Bearer a-real-access-token');
    request.flush({});
  });

  it('does not attach an Authorization header when there is no access token', () => {
    configure(null);

    http.get('/api/chat').subscribe();

    const request = httpMock.expectOne('/api/chat');
    expect(request.request.headers.has('Authorization')).toBe(false);
    request.flush({});
  });

  it('never attaches the token to a request outside the API base URL', () => {
    configure('a-real-access-token');

    http.get('/env.js').subscribe();

    const request = httpMock.expectOne('/env.js');
    expect(request.request.headers.has('Authorization')).toBe(false);
    request.flush({});
  });
});
