import { BreakpointObserver, Breakpoints } from '@angular/cdk/layout';
import { ChangeDetectionStrategy, Component, DestroyRef, computed, inject, linkedSignal } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { MatBadgeModule } from '@angular/material/badge';
import { MatButtonModule } from '@angular/material/button';
import { MatDividerModule } from '@angular/material/divider';
import { MatIconModule } from '@angular/material/icon';
import { MatListModule } from '@angular/material/list';
import { MatSidenavModule } from '@angular/material/sidenav';
import { MatToolbarModule } from '@angular/material/toolbar';
import { RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { map } from 'rxjs';
import { AppLocale, getStoredLocale, setStoredLocaleAndReload } from './core/locale';
import { NotificationService } from './core/notification.service';
import { PlanningResolutionStore } from './core/planning-resolution.store';
import { SolverJobService } from './core/solver-job.service';
import { DataStaleIndicator } from './shared/data-stale-indicator';
import { GroupeMismatchBanner } from './shared/groupe-mismatch-banner';
import { JobMonitor } from './shared/job-monitor';

interface NavLink {
  path: string;
  label: string;
  icon: string;
  /**
   * True for a real external link (target="_blank"), rendered as a plain
   * <a href> instead of an Angular routerLink. `/db` resolves relative to
   * whatever host serves this app; routing it to pgAdmin is a deployment-side
   * reverse-proxy concern, not something this repo builds (docker-compose
   * only exposes a dev-only pgAdmin on :5050, unrelated to this link).
   */
  external?: boolean;
  /** Shows the unread notification count as a mat-badge on this link only. */
  badge?: 'notifications';
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
      {
        path: '/notifications',
        label: $localize`:@@nav.link.notifications:Notifications`,
        icon: 'notifications',
        badge: 'notifications'
      },
      { path: '/problemes', label: $localize`:@@nav.link.problemes:Problèmes`, icon: 'report_problem' },
      { path: '/constraints', label: $localize`:@@nav.link.constraints:Contraintes`, icon: 'fact_check' },
      {
        path: '/data-setup',
        label: $localize`:@@nav.link.dataSetup:Données`,
        icon: 'storage'
      }
    ]
  },
  {
    title: $localize`:@@nav.group.referenceData:Données de référence`,
    links: [
      { path: '/stands', label: $localize`:@@nav.link.stands:Stands`, icon: 'storefront' },
      { path: '/emplacements', label: $localize`:@@nav.link.emplacements:Emplacements`, icon: 'place' },
      { path: '/animateurs', label: $localize`:@@nav.link.animateurs:Animateurs`, icon: 'groups' },
      { path: '/creneaux', label: $localize`:@@nav.link.creneaux:Créneaux`, icon: 'schedule' },
      { path: '/decoupage', label: $localize`:@@nav.link.decoupage:Découpage`, icon: 'content_cut' },
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
      { path: '/hours', label: $localize`:@@nav.link.hours:Heures`, icon: 'schedule' },
      {
        path: '/staffing',
        label: $localize`:@@nav.link.staffing:Besoin en animateurs`,
        icon: 'engineering'
      }
    ]
  },
  {
    title: $localize`:@@nav.group.tools:Outils`,
    links: [
      {
        path: '/db',
        label: $localize`:@@nav.link.db:Base de données (pgAdmin)`,
        icon: 'storage',
        external: true
      },
      { path: '/debug', label: $localize`:@@nav.link.debug:Débogage`, icon: 'bug_report' }
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
    MatBadgeModule,
    MatDividerModule,
    JobMonitor,
    DataStaleIndicator,
    GroupeMismatchBanner
  ],
  templateUrl: './app.html',
  styleUrl: './app.css',
  changeDetection: ChangeDetectionStrategy.OnPush
})
export class App {
  protected readonly navGroups = buildNavGroups();
  protected readonly jobs = inject(SolverJobService);
  protected readonly resolution = inject(PlanningResolutionStore);
  protected readonly notifications = inject(NotificationService);
  protected readonly locale: AppLocale = getStoredLocale();

  /** True while at least one unread notification is severity 'alert': overrides the badge count with a warning glyph. */
  protected readonly hasUnreadAlert = computed(() =>
    this.notifications.notifications().some((notification) => notification.severity === 'alert' && !notification.read)
  );
  protected readonly notificationBadgeContent = computed(() =>
    this.hasUnreadAlert() ? '⚠' : String(this.notifications.unreadCount())
  );
  protected readonly notificationBadgeDescription = computed(() =>
    this.hasUnreadAlert()
      ? $localize`:@@nav.notificationsBadge.alert:Alerte non lue`
      : $localize`:@@nav.notificationsBadge.count:${this.notifications.unreadCount()}:count: notification(s) non lue(s)`
  );

  private readonly handset = toSignal(
    inject(BreakpointObserver)
      .observe([Breakpoints.Handset, Breakpoints.TabletPortrait])
      .pipe(map((state) => state.matches)),
    { initialValue: false }
  );

  /** The drawer overlays the content on small screens, docks on large ones. */
  protected readonly drawerMode = computed<'over' | 'side'>(() => (this.handset() ? 'over' : 'side'));
  /**
   * The drawer follows the viewport, but stays user-controllable afterwards:
   * `linkedSignal` is exactly that — derived until written, reset by the next
   * breakpoint change. An `effect` writing this signal would do the same for
   * one more scheduling round-trip and an untraceable write.
   */
  protected readonly drawerOpen = linkedSignal(() => !this.handset());

  constructor() {
    // Starts polling the server-side solver lock for the whole session.
    this.jobs.start();
    // Loaded once here (not per-page) so the mismatch banner is correct on
    // every screen, including ones that never touch ReferenceDataStore (e.g.
    // the calendars). Refreshed after every solve, wherever it was started.
    void this.resolution.reload();
    inject(DestroyRef).onDestroy(this.jobs.onResult('SOLVE', () => void this.resolution.reload()));
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
