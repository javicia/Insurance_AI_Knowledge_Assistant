import { HttpInterceptorFn } from '@angular/common/http';
import { inject } from '@angular/core';
import { tap } from 'rxjs';

import { TRACE_ID_HEADER } from '../config/api.config';
import { TraceContextService } from '../services/trace-context.service';

/**
 * Captures the `X-Trace-Id` response header (echoed by TraceIdFilter on every request, brief
 * FASE 15 section 6) into {@link TraceContextService}, so a technical-details panel can offer a
 * trace id even for a response whose body could not be parsed as JSON.
 */
export const traceInterceptor: HttpInterceptorFn = (req, next) => {
  const traceContext = inject(TraceContextService);

  return next(req).pipe(
    tap((event) => {
      if ('headers' in event) {
        traceContext.record(event.headers.get(TRACE_ID_HEADER));
      }
    }),
  );
};
