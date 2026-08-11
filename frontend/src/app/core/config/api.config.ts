/**
 * FASE 16 (frontend/backend separation, brief section 47): the frontend is now an independent
 * deployable that knows only `PUBLIC_API_BASE_URL` - never a database URL, Kafka URL, LLM
 * credential, or internal service URL. That value is injected at container startup (never baked
 * into the build, see `docker-entrypoint.sh` and `public/env.js`) as `window.__env
 * .PUBLIC_API_BASE_URL` and read here once at module load. An empty string (the local-dev default
 * in `public/env.js`, and `env.js` being entirely absent under `ng serve`) resolves to a
 * same-origin relative path, which is what `proxy.conf.json` relies on for the local development
 * loop - the fallback is not a special case, it is what an empty configured base URL always means.
 */
declare global {
  interface Window {
    __env?: {
      PUBLIC_API_BASE_URL?: string;
      /** FASE 18 - see oidc.config.ts for how these two are used. */
      PUBLIC_OIDC_ISSUER?: string;
      PUBLIC_OIDC_CLIENT_ID?: string;
    };
  }
}

const configuredBaseUrl = typeof window !== 'undefined' ? window.__env?.PUBLIC_API_BASE_URL ?? '' : '';

export const API_BASE_URL = `${configuredBaseUrl}/api`;
export const ACTUATOR_BASE_URL = `${configuredBaseUrl}/actuator`;

/** Must match com.rag.springai.insuranceai.infrastructure.observability.TraceIdFilter exactly. */
export const TRACE_ID_HEADER = 'X-Trace-Id';
