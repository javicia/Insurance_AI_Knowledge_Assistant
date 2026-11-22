import { provideHttpClient, withInterceptors } from '@angular/common/http';
import {
  ApplicationConfig,
  inject,
  provideAppInitializer,
  provideBrowserGlobalErrorListeners,
  provideZonelessChangeDetection,
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
    // FASE 26 (found by the first real-browser E2E run, not by any unit test): this was
    // `provideZoneChangeDetection(...)`, which *requires* Zone.js - but `zone.js` is not a
    // dependency of this project and `angular.json` declares no `polyfills` entry, so Zone.js was
    // never bundled. The deployed application therefore threw NG0908 ("Angular requires Zone.js")
    // during bootstrap and rendered a completely blank page in a real browser. Nothing caught it:
    // the unit suite builds its own TestBed, the container healthcheck only proves nginx serves
    // the HTML, and curl-based API checks never execute JavaScript.
    // Zoneless is the correct resolution rather than adding zone.js - this application is already
    // fully signal-based (every component's state is signals/computed), which is exactly the model
    // zoneless change detection is designed for, and it matches how the project was scaffolded
    // (Angular 22 omits Zone.js by default). See docs/frontend/FRONTEND.md.
    provideZonelessChangeDetection(),
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
