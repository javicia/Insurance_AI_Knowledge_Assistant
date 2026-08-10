import { Injectable, signal } from '@angular/core';

/**
 * Holds the most recently observed `X-Trace-Id` (brief FASE 15 section 6) so any part of the UI
 * (e.g. a generic error panel with no parsed response body) can still offer a trace id for
 * support/debugging. Updated by {@link traceInterceptor} on every response. Deliberately not
 * shown permanently in the main UI - only inside a collapsible technical-details panel.
 */
@Injectable({ providedIn: 'root' })
export class TraceContextService {
  private readonly lastTraceIdSignal = signal<string | null>(null);
  readonly lastTraceId = this.lastTraceIdSignal.asReadonly();

  record(traceId: string | null): void {
    if (traceId) {
      this.lastTraceIdSignal.set(traceId);
    }
  }
}
