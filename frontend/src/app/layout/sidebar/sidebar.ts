import { ChangeDetectionStrategy, Component, inject, output } from '@angular/core';
import { MatIconModule } from '@angular/material/icon';
import { MatListModule } from '@angular/material/list';
import { RouterLink, RouterLinkActive } from '@angular/router';

import { SystemStatusService } from '../../core/services/system-status.service';

interface NavItem {
  readonly path: string;
  readonly label: string;
  readonly icon: string;
}

const NAV_ITEMS: readonly NavItem[] = [
  { path: '/assistant', label: 'AI Assistant', icon: 'forum' },
  { path: '/documents', label: 'Documents', icon: 'description' },
  { path: '/governance', label: 'Governance', icon: 'verified_user' },
  { path: '/audit', label: 'Audit', icon: 'fact_check' },
  { path: '/evaluation', label: 'Evaluation', icon: 'insights' },
];

@Component({
  selector: 'app-sidebar',
  imports: [RouterLink, RouterLinkActive, MatIconModule, MatListModule],
  templateUrl: './sidebar.html',
  styleUrl: './sidebar.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class Sidebar {
  protected readonly systemStatus = inject(SystemStatusService);
  protected readonly navItems = NAV_ITEMS;

  readonly navigated = output<void>();
}
