import { BreakpointObserver, Breakpoints } from '@angular/cdk/layout';
import { Component, computed, effect, inject, signal } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { MatButtonModule } from '@angular/material/button';
import { MatDividerModule } from '@angular/material/divider';
import { MatIconModule } from '@angular/material/icon';
import { MatListModule } from '@angular/material/list';
import { MatSidenavModule } from '@angular/material/sidenav';
import { MatToolbarModule } from '@angular/material/toolbar';
import { RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { map } from 'rxjs';
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
      { path: '/solver', label: 'Solveur', icon: 'play_circle' },
      { path: '/debug', label: 'Débogage', icon: 'bug_report' },
      { path: '/data-setup', label: 'Configuration des données', icon: 'storage' },
      { path: '/data-transfer', label: 'Transfert de données', icon: 'swap_vert' }
    ]
  },
  {
    title: 'Données de référence',
    links: [
      { path: '/stands', label: 'Stands', icon: 'storefront' },
      { path: '/animateurs', label: 'Animateurs', icon: 'groups' },
      { path: '/creneaux', label: 'Créneaux', icon: 'schedule' },
      { path: '/typologies', label: 'Typologies', icon: 'category' },
      { path: '/ad-hoc-constraints', label: 'Contraintes ad hoc', icon: 'rule' }
    ]
  },
  {
    title: 'Vues',
    links: [
      { path: '/calendar', label: 'Calendrier des affectations', icon: 'calendar_month' },
      { path: '/day-calendar', label: 'Calendrier journalier', icon: 'view_day' },
      { path: '/constraints', label: 'Contraintes', icon: 'fact_check' },
      { path: '/hours', label: 'Heures', icon: 'schedule' },
      { path: '/staffing', label: 'Besoin en animateurs', icon: 'engineering' }
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
