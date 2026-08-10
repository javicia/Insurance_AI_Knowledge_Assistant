/** Mirrors adapters.inbound.rest.ErrorResponse exactly - never a stack trace or SQL. */
export interface ErrorResponse {
  readonly code: string;
  readonly message: string;
  readonly traceId: string;
}

/**
 * Normalized client-side representation of any failed HTTP call, built by
 * core/interceptors/error.interceptor.ts from either a real {@link ErrorResponse} body or a
 * transport-level failure (network down, non-JSON body, CORS) - so every feature service and
 * component can rely on one shape, never inspect a raw HttpErrorResponse.
 */
export interface ApiError {
  readonly status: number;
  readonly code: string;
  readonly message: string;
  readonly traceId: string | null;
}
