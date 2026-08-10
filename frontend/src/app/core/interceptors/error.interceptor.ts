import { HttpErrorResponse, HttpInterceptorFn } from '@angular/common/http';
import { inject } from '@angular/core';
import { catchError, throwError } from 'rxjs';

import { TRACE_ID_HEADER } from '../config/api.config';
import type { ApiError, ErrorResponse } from '../models/api-error.model';
import { TraceContextService } from '../services/trace-context.service';

function isErrorResponseShape(body: unknown): body is ErrorResponse {
  if (typeof body !== 'object' || body === null) {
    return false;
  }
  const candidate = body as Record<string, unknown>;
  return typeof candidate['code'] === 'string' && typeof candidate['message'] === 'string';
}

/**
 * User-facing fallback messages for HTTP statuses whose body did not match {@link ErrorResponse}
 * (brief FASE 15 section 25) - covers both genuine transport failures (status 0, a proxy/network
 * error with no JSON body at all) and any endpoint whose failure bypasses GlobalExceptionHandler
 * (e.g. a malformed query parameter rejected by Spring's own default error handling, which
 * returns a differently-shaped body). GlobalExceptionHandler-produced errors (422/502/503/500)
 * always carry a real `message` and take the {@link isErrorResponseShape} branch instead - these
 * are only ever seen for the cases the backend's own error contract does not cover.
 */
const FALLBACK_MESSAGES: Readonly<Record<number, string>> = {
  0: 'Unable to reach the server. Check your connection and try again.',
  400: 'The request could not be understood by the server.',
  401: 'Authentication is required for this action.',
  403: 'You do not have permission to perform this action.',
  404: 'The requested resource was not found.',
  408: 'The request timed out. Please try again.',
  409: 'The request could not be completed due to a conflict.',
  422: 'The request could not be processed.',
  429: 'Too many requests. Please wait a moment and try again.',
  500: 'Something went wrong on our end.',
  502: 'The upstream AI provider rejected the request.',
  503: 'The service is temporarily unavailable. Please try again shortly.',
  504: 'The upstream service took too long to respond.',
};

/**
 * Normalizes every failed HTTP call into an {@link ApiError} (brief FASE 15 section 25) - never
 * a raw stack trace, Java exception name, or SQL error reaches a component, whether the failure
 * came from GlobalExceptionHandler's real {@link ErrorResponse} contract or a transport-level
 * failure that never reached the backend at all.
 */
export const errorInterceptor: HttpInterceptorFn = (req, next) => {
  const traceContext = inject(TraceContextService);

  return next(req).pipe(
    catchError((error: unknown) => {
      if (!(error instanceof HttpErrorResponse)) {
        return throwError(() => error);
      }

      const headerTraceId = error.headers.get(TRACE_ID_HEADER);
      const body: unknown = error.error;

      const apiError: ApiError = isErrorResponseShape(body)
        ? { status: error.status, code: body.code, message: body.message, traceId: body.traceId }
        : {
            status: error.status,
            code: error.status === 0 ? 'NETWORK_ERROR' : `HTTP_${error.status}`,
            message: FALLBACK_MESSAGES[error.status] ?? 'Something went wrong.',
            traceId: headerTraceId,
          };

      traceContext.record(apiError.traceId);
      return throwError(() => apiError);
    }),
  );
};
