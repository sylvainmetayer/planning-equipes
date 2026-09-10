import { ChangeDetectionStrategy, Component } from '@angular/core';
import { RouterOutlet } from '@angular/router';

/**
 * Bare application root: a single router outlet. The chrome lives in the
 * layouts the routes pick — `shell/admin-shell` for the admin app (toolbar,
 * navigation drawer, solver monitor), a minimal standalone layout for /login
 * and the espace animateur (issue #165), which must render without any admin
 * navigation or polling.
 */
@Component({
  selector: 'app-root',
  imports: [RouterOutlet],
  template: '<router-outlet />',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class App {}
