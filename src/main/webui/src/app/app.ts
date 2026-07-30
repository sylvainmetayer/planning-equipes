import { Component, computed, effect, inject, signal } from '@angular/core';
import { BreakpointObserver, Breakpoints } from '@angular/cdk/layout';
import { toSignal } from '@angular/core/rxjs-interop';
import { MatButtonModule } from '@angular/material/button';
import { MatDividerModule } from '@angular/material/divider';
import { MatIconModule } from '@angular/material/icon';
import { MatListModule } from '@angular/material/list';
import { MatSidenavModule } from '@angular/material/sidenav';
import { MatToolbarModule } from '@angular/material/toolbar';
import { map } from 'rxjs';
import { RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { SolverJobService } from './core/solver-job.service';
import { JobMonitor } from './shared/job-monitor';

interface NavLink {
  path: string;
  label: string;
  icon: string;
}

interface NavGroup {
  title: string;
  links: NavLink[];
}

/** One entry per route: the navigation mirrors the page split exactly. */
const NAV_GROUPS: NavGroup[] = [
  {
    title: 'Planning',
    links: [
      { path: '/solver', label: 'Solver', icon: 'play_circle' },
      { path: '/data-setup', label: 'Data setup', icon: 'database' },
      { path: '/data-transfer', label: 'Data transfer', icon: 'swap_vert' }
    ]
  },
  {
    title: 'Reference data',
    links: [
      { path: '/stands', label: 'Stands', icon: 'storefront' },
      { path: '/animateurs', label: 'Animateurs', icon: 'groups' },
      { path: '/creneaux', label: 'Créneaux', icon: 'schedule' },
      { path: '/typologies', label: 'Typologies', icon: 'category' },
      { path: '/ad-hoc-constraints', label: 'Ad hoc constraints', icon: 'rule' }
    ]
  },
  {
    title: 'Views',
    links: [
      { path: '/calendar', label: 'Assignment calendar', icon: 'calendar_month' },
      { path: '/day-calendar', label: 'Day calendar', icon: 'view_day' },
      { path: '/constraints', label: 'Constraints', icon: 'fact_check' },
      { path: '/hours', label: 'Hours', icon: 'schedule' }
    ]
  }
];

/**
 * Application shell: Material toolbar, navigation drawer listing every page,
 * and the solver monitor fed by the server-side job state.
 */
@Component({
  selector: 'app-root',
  imports: [
    RouterOutlet,
    RouterLink,
    RouterLinkActive,
    MatToolbarModule,
    MatSidenavModule,
    MatListModule,
    MatIconModule,
    MatButtonModule,
    MatDividerModule,
    JobMonitor
  ],
  templateUrl: './app.html',
  styleUrl: './app.css'
})
export class App {
  protected readonly navGroups = NAV_GROUPS;
  protected readonly jobs = inject(SolverJobService);

  private readonly handset = toSignal(
    inject(BreakpointObserver)
      .observe([Breakpoints.Handset, Breakpoints.TabletPortrait])
      .pipe(map((state) => state.matches)),
    { initialValue: false }
  );

  /** The drawer overlays the content on small screens, docks on large ones. */
  protected readonly drawerMode = computed<'over' | 'side'>(() => (this.handset() ? 'over' : 'side'));
  protected readonly drawerOpen = signal(true);

  constructor() {
    // Starts polling the server-side solver lock for the whole session.
    this.jobs.start();
    // The drawer follows the viewport, but stays user-controllable afterwards.
    effect(() => this.drawerOpen.set(!this.handset()));
  }

  protected toggleDrawer(): void {
    this.drawerOpen.update((open) => !open);
  }

  /** Closes the overlay drawer after navigating on a small screen. */
  protected onNavigate(): void {
    if (this.handset()) {
      this.drawerOpen.set(false);
    }
  }
}
