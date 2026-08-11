import { provideHttpClient, withInterceptors } from '@angular/common/http';
import {
  ApplicationConfig,
  inject,
  provideAppInitializer,
  provideBrowserGlobalErrorListeners,
  provideZoneChangeDetection,
} from '@angular/core';
import { MAT_ICON_DEFAULT_OPTIONS } from '@angular/material/icon';
import { provideAnimationsAsync } from '@angular/platform-browser/animations/async';
import { provideRouter, withComponentInputBinding } from '@angular/router';
import { provideOAuthClient } from 'angular-oauth2-oidc';

import { routes } from './app.routes';
import { AuthService } from './core/auth/auth.service';
import { authInterceptor } from './core/interceptors/auth.interceptor';
import { errorInterceptor } from './core/interceptors/error.interceptor';
import { traceInterceptor } from './core/interceptors/trace.interceptor';

export const appConfig: ApplicationConfig = {
  providers: [
    provideBrowserGlobalErrorListeners(),
    provideZoneChangeDetection({ eventCoalescing: true }),
    provideRouter(routes, withComponentInputBinding()),
    // authInterceptor must run before errorInterceptor so an outgoing request already carries its
    // Bearer token by the time errorInterceptor's catchError block would otherwise see it fail.
    provideHttpClient(withInterceptors([traceInterceptor, authInterceptor, errorInterceptor])),
    provideAnimationsAsync(),
    provideOAuthClient(),
    // FASE 18: completes any in-flight Authorization Code callback and loads Keycloak's
    // discovery document before the router activates the first route - authGuard would otherwise
    // race an OAuthService that has not finished configuring itself yet.
    provideAppInitializer(() => inject(AuthService).initialize()),
    { provide: MAT_ICON_DEFAULT_OPTIONS, useValue: { fontSet: 'material-symbols-rounded' } },
  ],
};
