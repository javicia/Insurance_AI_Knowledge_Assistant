import { ChangeDetectionStrategy, Component, input } from '@angular/core';

/**
 * A single, honest loading message with a tasteful animated indicator (brief FASE 15 section 16).
 *
 * The backend's `POST /api/chat` is a single synchronous call with no streaming/staged progress
 * events - a multi-step "Searching… → Reviewing… → Generating…" sequence would imply the UI
 * knows which backend stage is currently executing, which it does not. Per the brief's own
 * explicit fallback rule ("no fingir streaming"), this shows one generic, accurate message
 * instead of fabricating stage transitions.
 */
@Component({
  selector: 'app-loading-indicator',
  templateUrl: './loading-indicator.html',
  styleUrl: './loading-indicator.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class LoadingIndicator {
  readonly message = input('Searching corporate knowledge and preparing a grounded answer…');
}
