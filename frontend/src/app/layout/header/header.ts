import { ChangeDetectionStrategy, Component, inject, output } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatMenuModule } from '@angular/material/menu';
import { MatTooltipModule } from '@angular/material/tooltip';

import { AuthService } from '../../core/auth/auth.service';
import { SystemStatusService } from '../../core/services/system-status.service';

@Component({
  selector: 'app-header',
  imports: [MatButtonModule, MatIconModule, MatMenuModule, MatTooltipModule],
  templateUrl: './header.html',
  styleUrl: './header.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class Header {
  protected readonly systemStatus = inject(SystemStatusService);
  protected readonly auth = inject(AuthService);

  readonly menuToggle = output<void>();

  protected statusLabel(): string {
    switch (this.systemStatus.health()) {
      case 'UP':
        return 'AI System Operational';
      case 'DOWN':
        return 'AI System Unavailable';
      default:
        return 'Checking AI System status…';
    }
  }

  protected logout(): void {
    this.auth.logout();
  }
}
