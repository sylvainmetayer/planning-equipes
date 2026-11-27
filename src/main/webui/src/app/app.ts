import { ChangeDetectionStrategy, Component } from '@angular/core';
import { RouterOutlet } from '@angular/router';
import { injectAppConfig } from './core/app-config';

/**
 * Bare application root: a single router outlet. The chrome lives in the
 * layouts the routes pick — `shell/admin-shell` for the admin app (toolbar,
 * navigation drawer, solver monitor), a minimal standalone layout for /login
 * and the espace animateur (issue #165), which must render without any admin
 * navigation or polling.
 *
 * <p>The one thing above every layout is the demo banner (`DEMO_INSTANCE`, ADR
 * 0130): on a public demonstration every page, the login page included, says
 * its data are fictitious and wiped every half hour.</p>
 */
@Component({
  selector: 'app-root',
  imports: [RouterOutlet],
  template: `@if (demo) {
      <p class="demo-banner" role="note" i18n="@@demo.bandeau">Démonstration : données fictives, remise à zéro toutes les 30 minutes (à :00 et :30). N'y saisissez rien de réel ; les e-mails ne partent pas.</p>
    }
    <router-outlet />`,
  styles: `
    .demo-banner {
      margin: 0;
      padding: 0.5rem 1rem;
      text-align: center;
      font: var(--mat-sys-body-medium);
      background: var(--mat-sys-tertiary-container);
      color: var(--mat-sys-on-tertiary-container);
    }
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class App {
  protected readonly demo = injectAppConfig().demoInstance === true;
}
