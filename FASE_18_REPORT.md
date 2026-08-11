# FASE 18 — Frontend OIDC (Authorization Code Flow + PKCE)

**Fecha**: 2026-08-11
**Estado**: Cerrada. Implementada y verificada con 61/61 tests frontend (dos ejecuciones
consecutivas estables) y build de producción limpio.

## 1. Qué se ha implementado

1. `angular-oauth2-oidc@22.0.2` (única librería OIDC usada - "no implementes OAuth2 manual").
2. `core/config/oidc.config.ts`: Authorization Code Flow con PKCE (`responseType: 'code'`, PKCE
   habilitado por defecto por la librería, coincidiendo con `pkce.code.challenge.method: S256` del
   cliente Keycloak `insurance-ai-frontend`) - nunca implicit flow, nunca password grant desde el
   navegador. `issuer`/`clientId` son configuración runtime (`window.__env`), igual que
   `PUBLIC_API_BASE_URL` desde FASE 16 - nunca horneados en el build.
3. `core/auth/auth.service.ts`: envuelve `OAuthService`, expone signals (`isAuthenticated`,
   `username`, `roles`) - el decodificado de claims del access token es explícitamente solo para
   UX (nunca para autorización real, documentado en el propio código).
4. `core/auth/auth.guard.ts`: `CanActivateFn` que redirige a login si no autenticado - UX, no
   seguridad real (el backend/gateway ya la aplican de forma independiente).
5. `core/interceptors/auth.interceptor.ts`: añade `Authorization: Bearer` solo a peticiones hacia
   `API_BASE_URL` (el gateway) - nunca a peticiones fuera de la API propia.
6. `core/interceptors/error.interceptor.ts` actualizado: un 401 en una sesión que se creía
   autenticada dispara re-login automático (sesión expirada/revocada en uso).
7. `app.config.ts`: `provideOAuthClient()` + `provideAppInitializer()` completando cualquier
   callback de Authorization Code en curso y cargando el discovery document antes de que el router
   active la primera ruta.
8. `app.routes.ts`: `authGuard` en las 5 rutas de features.
9. UX de identidad: menú de usuario en el header (nombre + cerrar sesión).
10. `frontend/proxy.conf.json`/`public/env.js`/`docker-entrypoint.sh` actualizados con
    `PUBLIC_OIDC_ISSUER`/`PUBLIC_OIDC_CLIENT_ID` apuntando al mismo Keycloak que valida el backend.

## 2. Archivos principales

`frontend/src/app/core/config/oidc.config.ts`, `core/auth/{auth.service,auth.guard}.ts`,
`core/auth/testing/auth-service-stub.ts`, `core/interceptors/auth.interceptor.ts`,
`core/interceptors/error.interceptor.ts` (modificado), `app.config.ts`, `app.routes.ts`,
`layout/header/{header.ts,header.html,header.scss}`, `public/env.js`, `docker-entrypoint.sh`.

## 3. Decisiones arquitectónicas

- **`angular-oauth2-oidc`, no una implementación manual** - la única librería OIDC/OAuth2 madura
  y mantenida para Angular con soporte de Code Flow + PKCE de primera clase.
- **Decodificación de claims client-side es solo UX, nunca autorización real** - documentado
  explícitamente en `auth.service.ts`: el backend/gateway vuelven a comprobar todo de forma
  independiente en cada petición, tal como exige el brief.
- **`useSilentRefresh: false`** - un cliente SPA público no tiene secreto para autenticar un
  iframe de refresh silencioso frente a problemas de cookies de terceros; el refresh_token grant
  (que la librería ya usa internamente para `setupAutomaticSilentRefresh()` en Code Flow) es más
  robusto para este caso.
- **`PUBLIC_OIDC_ISSUER`/`PUBLIC_OIDC_CLIENT_ID` como configuración runtime**, exactamente el
  mismo patrón que `PUBLIC_API_BASE_URL` de FASE 16 - una sola imagen Docker desplegable contra
  cualquier realm de Keycloak sin rebuild.

## 4. Seguridad

- El frontend nunca conoce ni usa un client secret (cliente público con PKCE).
- El frontend nunca implementa validación de firma JWT - solo decodifica claims para UX, la
  validación real ocurre exclusivamente en el backend (FASE 17).
- El interceptor de autenticación solo añade el Bearer token a peticiones hacia la propia API,
  nunca a peticiones de terceros (Google Fonts, etc. - que además nunca pasan por `HttpClient`).

## 5. Tests - resultado real

- `auth.service.spec.ts` (7 tests): estado inicial, `login()`/`logout()` delegan correctamente,
  estado autenticado/username/roles se derivan del token tras un evento `token_received`,
  `getAccessToken()` nunca devuelve un token que `OAuthService` no considera válido,
  `initialize()` configura el refresh automático solo cuando hay sesión válida.
- `auth.guard.spec.ts` (2 tests): permite navegación si autenticado, redirige y bloquea si no.
- `auth.interceptor.spec.ts` (3 tests): añade el header solo a peticiones de la API propia, nunca
  sin token, nunca fuera de la API.
- `error.interceptor.spec.ts` (+2 tests nuevos): re-autentica en 401 de sesión previamente
  autenticada, no lo hace para una sesión ya anónima.
- **Suite completa: 61/61 PASS, dos ejecuciones consecutivas estables** (47 preexistentes + 14
  nuevos, ningún test debilitado ni eliminado).
- `npm run build`: limpio, sin warnings, bundle inicial 327.61 kB (dentro del presupuesto de 500
  kB/1 MB configurado en `angular.json`).

## 6. Problemas encontrados

1. **3 specs existentes rotos al introducir `authInterceptor`/cambios en `error.interceptor`**:
   `error.interceptor.spec.ts`, `chat-session.service.spec.ts`, `assistant-page.spec.ts`
   registraban `errorInterceptor` en su propio `TestBed` sin proveer `AuthService`, y
   `AuthService` ahora depende de `OAuthService` (no disponible en esos módulos de test) - causa
   raíz identificada por inspección (no asumida), corregida creando `authServiceStub()` reutilizable
   e inyectándolo en los 4 specs afectados (incluyendo `app.spec.ts`, que renderiza `Header`).
2. **Tests de re-autenticación en 401 fallaban con "Cannot override provider when the test module
   has already been instantiated"** - causa raíz: intentaban `TestBed.overrideProvider` después de
   que el `beforeEach` compartido ya hubiera instanciado el módulo. Corregido configurando un
   `TestBed` local completo dentro de cada uno de esos dos tests en vez de reutilizar el compartido.
3. **Error de tipos real en `auth.service.spec.ts`**: `Observable<unknown>` no asignable a
   `Observable<OAuthEvent>` para el stub de `OAuthService.events` - corregido tipando el `Subject`
   de prueba explícitamente como `Subject<OAuthEvent>`. Detectado por el propio build de Vitest
   (`ng test` compila specs con su tsconfig específico), no por `tsc --noEmit` con el tsconfig de
   la app, que excluye los specs - una discrepancia real entre ambos comandos de verificación,
   documentada aquí para no repetir la confusión.

## 7-8. Causa raíz / Correcciones

Ver punto 6.

## 9. Limitaciones

- Sin verificación de expiración de sesión mediante Playwright contra Keycloak real todavía -
  cubierto por FASE 27/35 (Playwright E2E), no por esta fase.
- El menú de usuario en el header es la única superficie de UX de identidad añadida - no se ha
  construido una página de perfil ni gestión de cuenta (fuera de alcance del PoC).

## 10. Evidencias

`npm test -- --watch=false`: 61/61, dos ejecuciones consecutivas. `npm run build`: limpio.
`npx tsc --noEmit -p tsconfig.app.json`: sin errores.

## 11. Siguiente fase

FASE 19 — Gateway hardening (routing completo, propagación de principal, sanitización de headers,
CORS estricto, límites de tamaño, manejo consistente de errores). Continuando automáticamente.
