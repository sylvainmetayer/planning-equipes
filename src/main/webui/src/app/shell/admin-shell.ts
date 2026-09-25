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
  viewChild,
} from '@angular/core';
import { takeUntilDestroyed, toSignal } from '@angular/core/rxjs-interop';
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
import { AdminApi } from '../core/api/admin-api';
import {
  LOCAL_DRAFT_STORAGE,
  SESSION_DRAFT_STORAGE,
  purgeExpiredDrafts,
  purgeAllDrafts,
} from '../core/brouillon-formulaire';
import { EditionStore } from '../core/edition.store';
import { SESSION_END_BUS, announceLogout, onLogoutElsewhere } from '../core/session-end';
import { AppLocale, getStoredLocale, setStoredLocaleAndReload } from '../core/locale';
import {
  defaultNavStorage,
  readCollapsedGroups,
  toggleCollapsedGroup,
  writeCollapsedGroups,
} from '../core/nav-collapse';
import { KeyboardShortcutsService } from '../core/keyboard-shortcuts.service';
import { NotificationService } from '../core/notification.service';
import { PlanningResolutionStore } from '../core/planning-resolution.store';
import { ThemeService } from '../core/theme.service';
import { NavModeService } from '../core/nav-mode.service';
import { ThemePreference } from '../core/theme-preference';
import { injectAppConfig } from '../core/app-config';
import { SolverJobService } from '../core/solver-job.service';
import { BrandLogo } from '../shared/brand-logo';
import { DataStaleIndicator } from '../shared/data-stale-indicator';
import { DateMockIndicator } from '../shared/date-mock-indicator';
import { ScrollHint } from '../shared/scroll-hint';
import { SolverRunningIndicator } from '../shared/solver-running-indicator';
import { UpdateAvailableIndicator } from '../shared/update-available-indicator';
import { EditionActuelleBar } from '../shared/edition-actuelle-bar';
import { VersionFooter } from '../shared/version-footer';
import { BRANDING } from '../core/branding';
import { MascotDialog } from './mascot-dialog';
import { NavGroup, buildNavGroups, visibleNavGroups } from './nav-groups';
import { NewWindowLink } from '../shared/new-window-link';
import { PageFocusService } from '../core/page-focus.service';

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
    NewWindowLink,
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
    UpdateAvailableIndicator,
    EditionActuelleBar,
    VersionFooter,
  ],
  templateUrl: './admin-shell.html',
  styleUrl: './admin-shell.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class AdminShell {
  private readonly destroyRef = inject(DestroyRef);
  /**
   * Built once, from the server's own answer: what the backend says it is,
   * not what this bundle was built as.
   */
  protected readonly navGroups = buildNavGroups(injectAppConfig().devMode);
  /**
   * The path on screen, without its query string: what decides whether an
   * entry hidden by the simple menu is shown anyway, because the reader is
   * on it. Updated on every navigation, like the announcement below.
   */
  private readonly cheminCourant = signal(pathOf(inject(Router).url));
  /** What the drawer lists: see `visibleNavGroups`. */
  protected readonly visibleGroups = computed(() =>
    visibleNavGroups(this.navGroups, this.navMode.mode(), this.cheminCourant()),
  );
  protected readonly jobs = inject(SolverJobService);
  private readonly branding = inject(BRANDING);
  protected readonly resolution = inject(PlanningResolutionStore);
  protected readonly editions = inject(EditionStore);
  protected readonly notifications = inject(NotificationService);
  protected readonly theme = inject(ThemeService);
  protected readonly navMode = inject(NavModeService);
  protected readonly locale: AppLocale = getStoredLocale();

  private readonly router = inject(Router);
  private readonly dialog = inject(MatDialog);
  private readonly shortcuts = inject(KeyboardShortcutsService);
  private readonly announcer = inject(LiveAnnouncer);
  private readonly pageFocus = inject(PageFocusService);
  private readonly snackBar = inject(MatSnackBar);
  /** Where the long forms keep their drafts: purged of the expired ones here, of all of them at logout. */
  private readonly draftStorages = [inject(LOCAL_DRAFT_STORAGE), inject(SESSION_DRAFT_STORAGE)];
  private readonly sessionEnd = inject(SESSION_END_BUS);

  /** True while at least one unread notification is severity 'alert': overrides the badge count with a warning glyph. */
  protected readonly hasUnreadAlert = computed(() =>
    this.notifications
      .notifications()
      .some((notification) => notification.severity === 'alert' && !notification.read),
  );
  protected readonly notificationBadgeContent = computed(() =>
    this.hasUnreadAlert() ? '⚠' : String(this.notifications.unreadCount()),
  );
  protected readonly notificationBadgeDescription = computed(() =>
    this.hasUnreadAlert()
      ? $localize`:@@nav.notificationsBadge.alert:Alerte non lue`
      : $localize`:@@nav.notificationsBadge.count:${this.notifications.unreadCount()}:count: notification(s) non lue(s)`,
  );
  /** The link's name: « Notifications », and what the badge shows when it shows anything. */
  protected readonly notificationsLabel = computed(() => {
    const nom = $localize`:@@shell.notifications:Notifications`;
    return this.notifications.unreadCount() === 0
      ? nom
      : `${nom} — ${this.notificationBadgeDescription()}`;
  });

  private readonly handset = toSignal(
    inject(BreakpointObserver)
      .observe([Breakpoints.Handset, Breakpoints.TabletPortrait])
      .pipe(map((state) => state.matches)),
    { initialValue: false },
  );

  /** The drawer overlays the content on small screens, docks on large ones. */
  protected readonly drawerMode = computed<'over' | 'side'>(() =>
    this.handset() ? 'over' : 'side',
  );
  /**
   * The drawer follows the viewport, but stays user-controllable afterwards:
   * `linkedSignal` is exactly that — derived until written, reset by the next
   * breakpoint change. An `effect` writing this signal would do the same for
   * one more scheduling round-trip and an untraceable write.
   */
  protected readonly drawerOpen = linkedSignal(() => !this.handset());

  constructor() {
    const destroyRef = this.destroyRef;
    // A draft is a net against an accident, not a workspace: past a day it
    // goes, whichever edition and form it belongs to (docs/rgpd.md §7).
    this.draftStorages.forEach((storage) => purgeExpiredDrafts(storage));
    // A logout in another tab reaches this one's sessionStorage only through
    // this tab: its fiche animateur draft goes too (docs/rgpd.md §7).
    const sessionDrafts = this.draftStorages[1];
    destroyRef.onDestroy(onLogoutElsewhere(() => purgeAllDrafts(sessionDrafts), this.sessionEnd));
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
        takeUntilDestroyed(),
      )
      .subscribe((event) => {
        this.cheminCourant.set(pathOf((event as NavigationEnd).urlAfterRedirects));
        queueMicrotask(() => this.annoncerNavigation());
      });
    // Global keyboard shortcuts (issue #314), armed for the admin session only:
    // /login and the espace animateur render outside this shell and have
    // neither a palette nor any of these destinations.
    this.shortcuts.start();
    destroyRef.onDestroy(() => this.shortcuts.stop());
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
    const sequence = [
      'ArrowUp',
      'ArrowUp',
      'ArrowDown',
      'ArrowDown',
      'ArrowLeft',
      'ArrowRight',
      'ArrowLeft',
      'ArrowRight',
      'b',
      'a',
    ];
    let position = 0;
    const onKey = (event: KeyboardEvent): void => {
      const target = event.target as HTMLElement | null;
      if (target && ['INPUT', 'TEXTAREA', 'SELECT'].includes(target.tagName)) {
        return;
      }
      const touche = event.key.length === 1 ? event.key.toLowerCase() : event.key;
      if (touche === sequence[position]) {
        position++;
      } else {
        position = touche === sequence[0] ? 1 : 0;
      }
      if (position === sequence.length) {
        position = 0;
        this.dialog.open(MascotDialog, { autoFocus: false });
      }
    };
    document.addEventListener('keydown', onKey);
    // Held as a field: `inject()` only works in the constructor, and this
    // listener is registered from a method that happens to be called there.
    this.destroyRef.onDestroy(() => document.removeEventListener('keydown', onKey));
  }

  protected toggleDrawer(): void {
    this.drawerOpen.update((open) => !open);
  }

  /** Folded navigation groups, remembered across visits (see `core/nav-collapse`). */
  private readonly navStorage = defaultNavStorage();
  protected readonly collapsedGroups = signal<ReadonlySet<string>>(
    readCollapsedGroups(
      this.navStorage,
      this.navGroups.map((group) => group.id),
    ),
  );

  protected isCollapsed(group: NavGroup): boolean {
    return this.collapsedGroups().has(group.id);
  }

  protected toggleGroup(group: NavGroup): void {
    const next = toggleCollapsedGroup(this.collapsedGroups(), group.id);
    this.collapsedGroups.set(next);
    writeCollapsedGroups(this.navStorage, next);
  }

  protected readonly allCollapsed = computed(
    () => this.collapsedGroups().size >= this.navGroups.length,
  );

  /** One control for every group: folding them one by one is one click per group. */
  protected toggleAllGroups(): void {
    const next = this.allCollapsed()
      ? new Set<string>()
      : new Set(this.navGroups.map((group) => group.id));
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
    this.pageFocus.arriveOn(this.contenu()?.nativeElement);
  }

  /** Skip link: `href="#contenu"` alone would move the caret but not the focus. */
  protected focusContenu(event: Event): void {
    this.pageFocus.skipTo(event, this.contenu()?.nativeElement);
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

  /* ------------------------- Colour scheme (issue #317) ------------------------- */

  /**
   * The button shows what is *painted*, never what was chosen: a user staring
   * at a dark screen expects a moon there, including under `system`. That is
   * the whole reason `ThemeService` listens to the media query — at sunset the
   * machine flips, `color-scheme: light dark` repaints natively, and this icon
   * follows instead of freezing on the scheme of an hour ago.
   *
   * <p>What `system` adds is a marker, not another icon: `.theme-auto-dot` in
   * `admin-shell.css` pins a dot on the corner, so "dark because I asked" and
   * "dark because it is 9 pm" do not look identical. The accessible name
   * spells the difference out.</p>
   */
  protected readonly themeIcon = computed(() =>
    this.theme.scheme() === 'dark' ? 'dark_mode' : 'light_mode',
  );

  /**
   * The accessible name carries the current state *and* what activating will
   * do: the control cycles through three values, so "switch theme" alone would
   * leave a screen-reader user unable to tell where they are.
   */
  protected readonly themeLabel = computed(() => this.themeLabelFor(this.theme.preference()));

  private themeLabelFor(preference: ThemePreference): string {
    switch (preference) {
      case 'system':
        return $localize`:@@shell.theme.system:Thème automatique, suit le système. Activer le thème clair.`;
      case 'light':
        return $localize`:@@shell.theme.light:Thème clair. Activer le thème sombre.`;
      case 'dark':
        return $localize`:@@shell.theme.dark:Thème sombre. Revenir au thème automatique.`;
    }
  }

  /**
   * Cycles system → light → dark. The new state is also announced: the
   * button's own name changes, and a name that changes under the focus is not
   * re-read by assistive technology.
   */
  protected toggleTheme(): void {
    this.announcer.announce(this.themeAnnouncementFor(this.theme.cycle()), 'polite');
  }

  private themeAnnouncementFor(preference: ThemePreference): string {
    switch (preference) {
      case 'system':
        return $localize`:@@shell.theme.announce.system:Thème automatique, suit le système.`;
      case 'light':
        return $localize`:@@shell.theme.announce.light:Thème clair.`;
      case 'dark':
        return $localize`:@@shell.theme.announce.dark:Thème sombre.`;
    }
  }

  /**
   * The accessible name carries the current state *and* what activating will
   * do, like the theme button: the control has two states, and "menu" alone
   * would leave a screen-reader user unable to tell which one they are in.
   */
  protected readonly navModeLabel = computed(() =>
    this.navMode.mode() === 'avance'
      ? $localize`:@@shell.navMode.avance:Menu avancé, tous les écrans. Revenir au menu simple.`
      : $localize`:@@shell.navMode.simple:Menu simple, sans les écrans de diagnostic. Afficher le menu avancé.`,
  );

  /** Switches simple ↔ avancé, and announces the state the name no longer re-reads. */
  protected toggleNavMode(): void {
    const next = this.navMode.toggle();
    this.announcer.announce(
      next === 'avance'
        ? $localize`:@@shell.navMode.announce.avance:Menu avancé.`
        : $localize`:@@shell.navMode.announce.simple:Menu simple.`,
      'polite',
    );
  }

  private readonly adminApi = inject(AdminApi);

  /**
   * Drops the admin session cookie, then hard-navigates to /login: a reload
   * (rather than a router navigation) also resets every store this shell
   * preloaded, so nothing keeps polling behind the login page.
   */
  protected async logout(): Promise<void> {
    // Before anything that could fail: on a shared régie computer, the next
    // person must not be offered the previous one's unsaved entries.
    this.draftStorages.forEach((storage) => purgeAllDrafts(storage));
    announceLogout(this.sessionEnd);
    try {
      await this.adminApi.logout();
    } finally {
      window.location.assign('/login');
    }
  }
}

/** The path of a router URL, without query string or fragment. */
function pathOf(url: string): string {
  const fin = url.search(/[?#]/);
  return fin === -1 ? url : url.slice(0, fin);
}
