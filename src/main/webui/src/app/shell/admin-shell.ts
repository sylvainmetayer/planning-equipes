import { LiveAnnouncer } from '@angular/cdk/a11y';
import { BreakpointObserver, Breakpoints } from '@angular/cdk/layout';
import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  ElementRef,
  computed,
  effect,
  inject,
  linkedSignal,
  signal,
  viewChild
} from '@angular/core';
import { takeUntilDestroyed, toSignal } from '@angular/core/rxjs-interop';
import { Title } from '@angular/platform-browser';
import { MatBadgeModule } from '@angular/material/badge';
import { MatButtonModule } from '@angular/material/button';
import { MatDividerModule } from '@angular/material/divider';
import { MatIconModule } from '@angular/material/icon';
import { MatListModule } from '@angular/material/list';
import { MatSidenavModule } from '@angular/material/sidenav';
import { MatSnackBar } from '@angular/material/snack-bar';
import { MatToolbarModule } from '@angular/material/toolbar';
import { MatDialog } from '@angular/material/dialog';
import { NavigationEnd, Router, RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { filter, map } from 'rxjs';
import { ApiService } from '../core/api.service';
import { EditionStore } from '../core/edition.store';
import { AppLocale, getStoredLocale, setStoredLocaleAndReload } from '../core/locale';
import {
  defaultNavStorage,
  readCollapsedGroups,
  toggleCollapsedGroup,
  writeCollapsedGroups
} from '../core/nav-collapse';
import { NotificationService } from '../core/notification.service';
import { PlanningResolutionStore } from '../core/planning-resolution.store';
import { APP_CONFIG } from '../core/app-config';
import { SolverJobService } from '../core/solver-job.service';
import { BrandLogo } from '../shared/brand-logo';
import { DataStaleIndicator } from '../shared/data-stale-indicator';
import { DateMockIndicator } from '../shared/date-mock-indicator';
import { ScrollHint } from '../shared/scroll-hint';
import { SolverRunningIndicator } from '../shared/solver-running-indicator';
import { EditionActuelleBar } from '../shared/edition-actuelle-bar';
import { BRANDING } from '../core/branding';
import { MascotDialog } from './mascot-dialog';

interface NavLink {
  path: string;
  label: string;
  icon: string;
  /** Shows the unread notification count as a mat-badge on this link only. */
  badge?: 'notifications';
  /**
   * Served by the backend rather than by the Angular router (the Quarkus Dev
   * UI): rendered as a plain anchor opening a new tab, since routing to it
   * would only produce a client-side 404.
   */
  externe?: boolean;
}

interface NavGroup {
  /** Stable across languages: what the collapsed state is stored under. */
  id: string;
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
function buildNavGroups(devMode: boolean): NavGroup[] {
  return [
  {
    id: 'planning',
    title: $localize`:@@nav.group.planning:Planning`,
    links: [
      { path: '/', label: $localize`:@@nav.link.solver:Solveur`, icon: 'play_circle' },
      { path: '/jour-j', label: $localize`:@@nav.link.jourJ:Mode jour J`, icon: 'emergency' },
      {
        path: '/notifications',
        label: $localize`:@@nav.link.notifications:Notifications`,
        icon: 'notifications',
        badge: 'notifications'
      },
      { path: '/problemes', label: $localize`:@@nav.link.problemes:Problèmes`, icon: 'report_problem' },
      { path: '/echanges', label: $localize`:@@nav.link.echanges:Échanges`, icon: 'swap_horiz' },
      { path: '/constraints', label: $localize`:@@nav.link.constraints:Contraintes`, icon: 'fact_check' },
      {
        path: '/ad-hoc-constraints',
        label: $localize`:@@nav.link.adHocConstraints:Ajustements manuels`,
        icon: 'rule'
      },
      { path: '/instantanes', label: $localize`:@@nav.link.snapshots:Instantanés`, icon: 'history' },
      { path: '/aide', label: $localize`:@@nav.link.aide:Aide`, icon: 'help_outline' },
      { path: '/editions', label: $localize`:@@nav.link.editions:Éditions`, icon: 'layers' }
    ]
  },
  {
    id: 'decision-support',
    title: $localize`:@@nav.group.decisionSupport:Aide à la décision`,
    links: [
      {
        path: '/ouvertures',
        label: $localize`:@@nav.link.ouvertures:Ouvertures des stands`,
        icon: 'storefront'
      },
      {
        path: '/staffing',
        label: $localize`:@@nav.link.staffing:Besoin en animateurs`,
        icon: 'engineering'
      }
    ]
  },
  {
    id: 'reference-data',
    title: $localize`:@@nav.group.referenceData:Données de référence`,
    links: [
      { path: '/stands', label: $localize`:@@nav.link.stands:Stands`, icon: 'storefront' },
      { path: '/emplacements', label: $localize`:@@nav.link.emplacements:Emplacements`, icon: 'place' },
      { path: '/animateurs', label: $localize`:@@nav.link.animateurs:Animateurs`, icon: 'groups' },
      { path: '/creneaux', label: $localize`:@@nav.link.creneaux:Créneaux`, icon: 'schedule' },
      { path: '/typologies', label: $localize`:@@nav.link.typologies:Typologies`, icon: 'category' }
    ]
  },
  {
    id: 'views',
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
        path: '/heatmap',
        label: $localize`:@@nav.link.heatmap:Heatmap de charge`,
        icon: 'grid_view'
      },
      {
        path: '/timeline',
        label: $localize`:@@nav.link.timeline:Timeline animateur`,
        icon: 'timeline'
      },
      { path: '/graphe', label: $localize`:@@nav.link.graphe:Graphe`, icon: 'hub' },
      { path: '/kpi', label: $localize`:@@nav.link.kpi:Autopsie du planning`, icon: 'query_stats' },
      {
        path: '/comparateur',
        label: $localize`:@@nav.link.comparateur:Comparateur A/B`,
        icon: 'compare_arrows'
      }
    ]
  },
  {
    id: 'tools',
    title: $localize`:@@nav.group.tools:Outils`,
    links: [
      { path: '/parametres', label: $localize`:@@nav.link.parametres:Paramètres`, icon: 'settings' },
      { path: '/mcp-client', label: $localize`:@@nav.link.mcp:MCP`, icon: 'smart_toy' },
      { path: '/debug', label: $localize`:@@nav.link.debug:Débogage`, icon: 'bug_report' },
      {
        path: '/mentions-legales',
        label: $localize`:@@nav.link.mentionsLegales:Mentions légales`,
        icon: 'gavel'
      },
      {
        path: '/politique-confidentialite',
        label: $localize`:@@nav.link.confidentialite:Politique de confidentialité`,
        icon: 'privacy_tip'
      },
      {
        path: '/conditions-utilisation',
        label: $localize`:@@nav.link.cgu:Conditions d'utilisation`,
        icon: 'handshake'
      },
      // Dev mode only: in a packaged application the Dev UI does not exist,
      // and the entry would lead nowhere.
      ...(devMode
        ? [
            {
              path: '/q/dev-ui',
              label: $localize`:@@nav.link.devUi:Quarkus Dev UI`,
              icon: 'developer_mode',
              externe: true
            }
          ]
        : [])
    ]
  },
  {
    // Pages backed by features that are not finished yet: each one shows an
    // `app-work-in-progress-banner` telling the user so.
    id: 'work-in-progress',
    title: $localize`:@@nav.group.workInProgress:En cours de développement`,
    links: [
      {
        path: '/verrouillages',
        label: $localize`:@@nav.link.verrouillages:Verrouillages`,
        icon: 'lock'
      },
      {
        path: '/fragilite',
        label: $localize`:@@nav.link.fragilite:Fragilité du planning`,
        icon: 'personal_injury'
      }
    ]
  }
  ];
}

/**
 * Admin shell: Material toolbar, navigation drawer listing every admin page,
 * and the solver monitor fed by the server-side job state. Everything under it
 * sits behind the admin session (issue #165) — the routes outside this shell
 * (/login, /animateur/:jeton) render without any of this chrome, so its
 * polling and preloading only ever run for a logged-in admin.
 */
@Component({
  selector: 'app-admin-shell',
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
    BrandLogo,
    DataStaleIndicator,
    DateMockIndicator,
    ScrollHint,
    SolverRunningIndicator,
    EditionActuelleBar
  ],
  templateUrl: './admin-shell.html',
  styleUrl: './admin-shell.css',
  changeDetection: ChangeDetectionStrategy.OnPush
})
export class AdminShell {
  /**
   * Built once, from the server's own answer: what the backend says it is,
   * not what this bundle was built as.
   */
  protected readonly navGroups = buildNavGroups(inject(APP_CONFIG, { optional: true })?.devMode ?? false);
  protected readonly jobs = inject(SolverJobService);
  private readonly branding = inject(BRANDING);
  protected readonly resolution = inject(PlanningResolutionStore);
  protected readonly editions = inject(EditionStore);
  protected readonly notifications = inject(NotificationService);
  protected readonly locale: AppLocale = getStoredLocale();

  private readonly router = inject(Router);
  private readonly dialog = inject(MatDialog);
  private readonly title = inject(Title);
  private readonly announcer = inject(LiveAnnouncer);
  private readonly snackBar = inject(MatSnackBar);

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
    const destroyRef = inject(DestroyRef);
    // Starts polling the server-side solver lock for the whole session, and
    // stops it with this shell: the service is `providedIn: 'root'`, so a
    // session expiring (401 -> /login destroys this shell) would otherwise
    // leave the loop polling forever, each tick redirecting again.
    this.jobs.start();
    destroyRef.onDestroy(() => this.jobs.stop());
    // Loaded once here (not per-page) so the mismatch banner is correct on
    // every screen, including ones that never touch ReferenceDataStore (e.g.
    // the calendars). Refreshed after every solve, wherever it was started.
    void this.resolution.reload();
    // Same reasoning for the "Édition actuelle" strip: it sits in the shell, so
    // the list of editions is loaded here rather than by any single page.
    void this.editions.reload();
    // Both kinds of solve, not just the full one: an incremental replan rewrites
    // `planning_resolution` exactly the same way, so it clears the "reference
    // data changed since the last solve" banner just the same. Listening for
    // `SOLVE` alone left that banner up after a targeted replan until the next
    // referential write or a page reload — telling the operator their fresh
    // plan was stale.
    for (const type of ['SOLVE', 'SOLVE_INCREMENTAL'] as const) {
      destroyRef.onDestroy(this.jobs.onResult(type, () => void this.resolution.reload()));
    }
    // Router `title` is applied on NavigationEnd too; subscribing after it in
    // the same microtask order means `Title.getTitle()` already holds the new
    // page's title when the announcement is built.
    this.router.events
      .pipe(
        filter((event) => event instanceof NavigationEnd),
        takeUntilDestroyed()
      )
      .subscribe(() => queueMicrotask(() => this.annoncerNavigation()));
    this.ecouterKonami();
    this.ecarterLeBandeauDuMenu();
  }

  /**
   * Dismisses the notification snackbar whenever the overlay drawer opens.
   *
   * <p>On a handset Material ignores {@code horizontalPosition} and lays the
   * snackbar out full width ({@code .mat-mdc-snack-bar-handset}), inside the
   * CDK overlay — which is a top-layer popover, so no z-index puts the drawer
   * back in front of it. A snackbar standing at the bottom of the screen
   * therefore covers the last links of the navigation drawer and swallows the
   * taps aimed at them: the menu opens, the user taps « Stands », and the page
   * simply does not change.</p>
   *
   * <p>It bit the Solveur page first because that is where a notification is
   * most likely to be on screen at the very moment the menu is opened — the
   * job service reports the solver's state as the page loads ("résolution déjà
   * en cours", a failed job, which is even shown with no timeout at all).
   * Reaching another page by its URL left the snackbar time to expire, which
   * is why the same menu worked from there.</p>
   *
   * <p>Nothing is lost by dismissing it: {@code NotificationService.notify}
   * also files every message on the Notifications page, badge included.</p>
   */
  private ecarterLeBandeauDuMenu(): void {
    effect(() => {
      if (this.handset() && this.drawerOpen()) {
        this.snackBar.dismiss();
      }
    });
  }

  /**
   * ↑↑↓↓←→←→BA summons the deployment's mascot. Pure easter egg: the
   * listener only tracks the sequence position (no buffering of anything
   * typed) and ignores keystrokes aimed at form fields.
   *
   * <p>An instance that configured no mascot registers no listener at all:
   * there is nothing to show, and an empty dialog is worse than none.</p>
   */
  private ecouterKonami(): void {
    if (!this.branding.mascotUrl) {
      return;
    }
    const sequence = ['ArrowUp', 'ArrowUp', 'ArrowDown', 'ArrowDown',
      'ArrowLeft', 'ArrowRight', 'ArrowLeft', 'ArrowRight', 'b', 'a'];
    let position = 0;
    const onKey = (event: KeyboardEvent): void => {
      const cible = event.target as HTMLElement | null;
      if (cible && ['INPUT', 'TEXTAREA', 'SELECT'].includes(cible.tagName)) {
        return;
      }
      const touche = event.key.length === 1 ? event.key.toLowerCase() : event.key;
      position = touche === sequence[position] ? position + 1 : touche === sequence[0] ? 1 : 0;
      if (position === sequence.length) {
        position = 0;
        this.dialog.open(MascotDialog, { autoFocus: false });
      }
    };
    document.addEventListener('keydown', onKey);
    inject(DestroyRef).onDestroy(() => document.removeEventListener('keydown', onKey));
  }

  protected toggleDrawer(): void {
    this.drawerOpen.update((open) => !open);
  }

  /** Folded navigation groups, remembered across visits (see `core/nav-collapse`). */
  private readonly navStorage = defaultNavStorage();
  protected readonly collapsedGroups = signal<ReadonlySet<string>>(readCollapsedGroups(this.navStorage));

  protected isCollapsed(group: NavGroup): boolean {
    return this.collapsedGroups().has(group.id);
  }

  protected toggleGroup(group: NavGroup): void {
    const next = toggleCollapsedGroup(this.collapsedGroups(), group.id);
    this.collapsedGroups.set(next);
    writeCollapsedGroups(this.navStorage, next);
  }

  protected readonly allCollapsed = computed(() => this.collapsedGroups().size >= this.navGroups.length);

  /** One control for the five groups: folding them one by one is five clicks. */
  protected toggleAllGroups(): void {
    const next = this.allCollapsed() ? new Set<string>() : new Set(this.navGroups.map((group) => group.id));
    this.collapsedGroups.set(next);
    writeCollapsedGroups(this.navStorage, next);
  }

  /**
   * Moves the focus into the page content, and announces which page it is.
   *
   * A router navigation swaps the content but leaves the focus on the link
   * that was clicked: a keyboard user tabs back through the whole drawer, and
   * a screen reader announces nothing at all. Focusing the `<main>` (which is
   * `tabindex="-1"`, so focusable by script but not by tab) puts the caret at
   * the top of the new page, and the announcer speaks its title — the same
   * title the browser puts in the tab.
   */
  private readonly contenu = viewChild<ElementRef<HTMLElement>>('contenu');

  private annoncerNavigation(): void {
    this.contenu()?.nativeElement.focus({ preventScroll: true });
    const titre = this.title.getTitle().split('—')[0].trim();
    if (titre) {
      this.announcer.announce(titre, 'polite');
    }
  }

  /** Skip link: `href="#contenu"` alone would move the caret but not the focus. */
  protected focusContenu(event: Event): void {
    event.preventDefault();
    this.contenu()?.nativeElement.focus();
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

  private readonly api = inject(ApiService);

  /**
   * Drops the admin session cookie, then hard-navigates to /login: a reload
   * (rather than a router navigation) also resets every store this shell
   * preloaded, so nothing keeps polling behind the login page.
   */
  protected async logout(): Promise<void> {
    try {
      await this.api.post('/api/auth/logout', null);
    } finally {
      window.location.assign('/login');
    }
  }
}
