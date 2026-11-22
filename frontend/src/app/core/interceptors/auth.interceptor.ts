import { HttpInterceptorFn } from '@angular/common/http';
import { inject } from '@angular/core';

import { AuthService } from '../auth/auth.service';
import { API_BASE_URL } from '../config/api.config';

/**
 * FASE 18: attaches `Authorization: Bearer <access_token>` to every request this app makes to its
 * own API (the gateway, via `PUBLIC_API_BASE_URL`) - never to third-party requests (e.g. the
 * Google Fonts stylesheet links in index.html are plain `<link>` tags, never routed through
 * `HttpClient`, so this interceptor never sees them anyway, but the explicit `startsWith` guard
 * below is the real safety net if that ever changes).
 */
export const authInterceptor: HttpInterceptorFn = (request, next) => {
  const authService = inject(AuthService);

  if (!request.url.startsWith(API_BASE_URL)) {
    return next(request);
  }

  const token = authService.getAccessToken();
  if (!token) {
    return next(request);
  }

  return next(request.clone({ setHeaders: { Authorization: `Bearer ${token}` } }));
};
