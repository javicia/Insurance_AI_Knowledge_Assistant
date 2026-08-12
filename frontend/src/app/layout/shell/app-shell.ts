import { BreakpointObserver, Breakpoints } from '@angular/cdk/layout';
import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { MatSidenavModule } from '@angular/material/sidenav';
import { RouterOutlet } from '@angular/router';
import { map } from 'rxjs';

import { Header } from '../header/header';
import { Sidebar } from '../sidebar/sidebar';

@Component({
  selector: 'app-shell',
  imports: [RouterOutlet, MatSidenavModule, Header, Sidebar],
  templateUrl: './app-shell.html',
  styleUrl: './app-shell.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class AppShell {
  private readonly breakpointObserver = inject(BreakpointObserver);

  protected readonly isHandset = toSignal(
    this.breakpointObserver.observe(Breakpoints.Handset).pipe(map((result) => result.matches)),
    { initialValue: false },
  );

  /**
   * Only consulted on handset (the template hard-codes `opened` to true on larger viewports,
   * where the drawer is a permanent `side` drawer), so this is specifically "is the mobile
   * overlay drawer open".
   *
   * FASE 26: this was `signal(true)`, which meant a phone-sized browser loaded the application
   * with the navigation drawer already covering the entire screen - the user had to dismiss it
   * before seeing any content. Found by driving a real 390x844 browser; the unit suite never
   * renders at a handset breakpoint, and the drawer is correct on desktop either way.
   */
  protected readonly sidenavOpened = signal(false);

  protected toggleSidenav(): void {
    this.sidenavOpened.set(!this.sidenavOpened());
  }

  protected closeOnMobileNavigate(): void {
    if (this.isHandset()) {
      this.sidenavOpened.set(false);
    }
  }
}
