import { ChangeDetectionStrategy, Component } from '@angular/core';
import { MatIconModule } from '@angular/material/icon';

/**
 * The "blocked by AI security policy" state (brief FASE 15 section 14) - never exposes the
 * regex/rule that matched, the system prompt, or any other implementation detail, only a plain,
 * non-technical explanation.
 */
@Component({
  selector: 'app-security-banner',
  imports: [MatIconModule],
  templateUrl: './security-banner.html',
  styleUrl: './security-banner.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class SecurityBanner {}
