import { inject } from '@angular/core';
import { CanActivateFn } from '@angular/router';

import { AuthService } from './auth.service';

/**
 * FASE 18: UX-only gate - redirects an unauthenticated browser straight to Keycloak's login page
 * instead of rendering a route that would immediately fail every API call with 401. The backend/
 * gateway remain the real authorization boundary (brief: "El frontend solamente adapta UX");
 * removing this guard entirely would not open any capability the backend doesn't already protect.
 */
export const authGuard: CanActivateFn = () => {
  const authService = inject(AuthService);

  if (authService.isAuthenticated()) {
    return true;
  }

  authService.login();
  return false;
};
