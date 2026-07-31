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
import { AppLocale, getStoredLocale, setStoredLocaleAndReload } from './core/locale';
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

/**
 * One entry per route: the navigation mirrors the page split exactly.
 *
 * Built lazily (called from the component constructor, not at module scope):
 * $localize resolves translations from whatever `loadTranslations()` has
 * registered at call time, and that only happens once `main.ts` has fetched
 * the English catalog — before `bootstrapApplication()` runs, but after this
 * module has already been imported.
 */
function buildNavGroups(): NavGroup[] {
  return [
  {
    title: $localize`:@@nav.group.planning:Planning`,
    links: [
      { path: '/solver', label: $localize`:@@nav.link.solver:Solveur`, icon: 'play_circle' },
      { path: '/debug', label: $localize`:@@nav.link.debug:Débogage`, icon: 'bug_report' },
      {
        path: '/data-setup',
        label: $localize`:@@nav.link.dataSetup:Configuration des données`,
        icon: 'storage'
      },
      {
        path: '/data-transfer',
        label: $localize`:@@nav.link.dataTransfer:Transfert de données`,
        icon: 'swap_vert'
      }
    ]
  },
  {
    title: $localize`:@@nav.group.referenceData:Données de référence`,
    links: [
      { path: '/stands', label: $localize`:@@nav.link.stands:Stands`, icon: 'storefront' },
      { path: '/animateurs', label: $localize`:@@nav.link.animateurs:Animateurs`, icon: 'groups' },
      { path: '/creneaux', label: $localize`:@@nav.link.creneaux:Créneaux`, icon: 'schedule' },
      { path: '/typologies', label: $localize`:@@nav.link.typologies:Typologies`, icon: 'category' },
      {
        path: '/ad-hoc-constraints',
        label: $localize`:@@nav.link.adHocConstraints:Contraintes ad hoc`,
        icon: 'rule'
      }
    ]
  },
  {
    title: $localize`:@@nav.group.views:Vues`,
    links: [
      {
        path: '/calendar',
        label: $localize`:@@nav.link.calendar:Calendrier des affectations`,
        icon: 'calendar_month'
      },
      {
        path: '/day-calendar',
        label: $localize`:@@nav.link.dayCalendar:Calendrier journalier`,
        icon: 'view_day'
      },
      { path: '/constraints', label: $localize`:@@nav.link.constraints:Contraintes`, icon: 'fact_check' },
      { path: '/hours', label: $localize`:@@nav.link.hours:Heures`, icon: 'schedule' },
      {
        path: '/staffing',
        label: $localize`:@@nav.link.staffing:Besoin en animateurs`,
        icon: 'engineering'
      }
    ]
  }
  ];
}

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
  protected readonly navGroups = buildNavGroups();
  protected readonly jobs = inject(SolverJobService);
  protected readonly locale: AppLocale = getStoredLocale();

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

  /** Language messages resolve once at bootstrap, so switching reloads the page. */
  protected toggleLocale(): void {
    setStoredLocaleAndReload(this.locale === 'fr' ? 'en' : 'fr');
  }
}
