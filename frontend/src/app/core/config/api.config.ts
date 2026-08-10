/**
 * Same-origin API base (brief FASE 15 section 40) - never a hardcoded host/port. In both `ng
 * serve` (via proxy.conf.json, forwarding /api and /actuator to the backend) and the packaged
 * single-container production build (Spring Boot serving both the SPA and /api/**), a relative
 * path resolves correctly with no environment-specific branching needed.
 */
export const API_BASE_URL = '/api';
export const ACTUATOR_BASE_URL = '/actuator';

/** Must match com.rag.springai.insuranceai.infrastructure.observability.TraceIdFilter exactly. */
export const TRACE_ID_HEADER = 'X-Trace-Id';
