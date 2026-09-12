// The shell glue, which had no test at all: the polling lifecycle it owns, the
// navigation announcement, the drawer's `linkedSignal`, the folded-group glue
// on top of `nav-collapse`, the notification badge and the easter egg.
//
// `nav-collapse.ts` (5 tests) covers the pure set arithmetic; what is pinned
// here is the shell that calls it. The component is created — the template
// renders, since a `viewChild` and a `LiveAnnouncer` are involved — but nothing
// is asserted on the DOM.

import { LiveAnnouncer } from '@angular/cdk/a11y';
import { BreakpointObserver } from '@angular/cdk/layout';
import { provideZonelessChangeDetection, Signal, WritableSignal } from '@angular/core';
import { BRANDING, BRANDING_NEUTRE } from '../core/branding';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { MatDialog } from '@angular/material/dialog';
import { MatSnackBar } from '@angular/material/snack-bar';
import { Title } from '@angular/platform-browser';
import { NavigationEnd, Router, provideRouter } from '@angular/router';
import { Subject } from 'rxjs';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiService } from '../core/api.service';
import { AdminApi } from '../core/api/admin-api';
import { EditionStore } from '../core/edition.store';
import { NotificationService } from '../core/notification.service';
import { PlanningResolutionStore } from '../core/planning-resolution.store';
import { SolverJobService } from '../core/solver-job.service';
import { AdminShell } from './admin-shell';

const NAV_STORAGE_KEY = 'planning-equipes.nav.collapsedGroups';
const THEME_STORAGE_KEY = 'planning-equipes.theme';
const NAV_MODE_STORAGE_KEY = 'planning-equipes.nav.mode';

interface NavGroupShape {
  id: string;
  title: string;
  links: { path: string; label: string; avance?: boolean }[];
}

/** Reaches the protected members the template binds to. */
type ShellInternals = {
  navGroups: NavGroupShape[];
  visibleGroups: Signal<NavGroupShape[]>;
  navModeLabel: Signal<string>;
  toggleNavMode: () => void;
  drawerMode: Signal<'over' | 'side'>;
  drawerOpen: WritableSignal<boolean>;
  collapsedGroups: Signal<ReadonlySet<string>>;
  allCollapsed: Signal<boolean>;
  hasUnreadAlert: Signal<boolean>;
  notificationBadgeContent: Signal<string>;
  notificationBadgeDescription: Signal<string>;
  toggleDrawer: () => void;
  isCollapsed: (group: NavGroupShape) => boolean;
  toggleGroup: (group: NavGroupShape) => void;
  toggleAllGroups: () => void;
  onNavigate: () => void;
  themeIcon: Signal<string>;
  themeLabel: Signal<string>;
  toggleTheme: () => void;
  focusContenu: (event: Event) => void;
  logout: () => Promise<void>;
};

describe('AdminShell', () => {
  const handset = new Subject<{ matches: boolean }>();
  const breakpoints = { observe: vi.fn(() => handset.asObservable()) };
  const jobs = {
    start: vi.fn(),
    stop: vi.fn(),
    onResult: vi.fn(),
    file: () => [],
    activeJob: () => null,
  };
  const announcer = { announce: vi.fn() };
  // `openDialogs` too: the global shortcuts (issue #314) ask MatDialog whether
  // something is already open before reacting to a single key press.
  const dialog = { open: vi.fn(), openDialogs: [] as unknown[] };
  const snackBar = { dismiss: vi.fn() };
  const api = { get: vi.fn() };
  const adminApi = { logout: vi.fn() };
  /**
   * Handlers the shell registers, by job type. Keyed rather than collapsed into
   * one: the shell used to subscribe to `SOLVE` alone, and a type-blind double
   * could not see it — the banner then stayed up for good after an incremental
   * solve, which is what shipped.
   */
  const onResultByType = new Map<string, () => void>();
  const unregisterResult = vi.fn();

  let fixture: ComponentFixture<AdminShell>;
  // The two stores the shell preloads are the real ones behind a mocked
  // `ApiService`: the shell renders `EditionActuelleBar` and
  // `DataStaleIndicator`, which read them, and a hand-written double would have
  // to be kept in step with both templates.
  let resolutionReload: ReturnType<typeof vi.spyOn>;
  let editionsReload: ReturnType<typeof vi.spyOn>;

  beforeEach(() => {
    // jsdom implements no ResizeObserver, and the shell renders `ScrollHint`,
    // which uses one. Same stub as `scroll-hint.spec.ts`.
    vi.stubGlobal(
      'ResizeObserver',
      class {
        observe = vi.fn();
        // `unobserve` too: Angular Material calls it when a `mat-form-field`
        // is destroyed. A stub missing it does not fail here — it fails in
        // whichever spec runs next in the same worker, because `stubGlobal`
        // outlives the file that called it.
        unobserve = vi.fn();
        disconnect = vi.fn();
      },
    );
    localStorage.removeItem(NAV_STORAGE_KEY);
    localStorage.removeItem(THEME_STORAGE_KEY);
    localStorage.removeItem(NAV_MODE_STORAGE_KEY);
    document.documentElement.style.colorScheme = '';
    for (const stub of [
      breakpoints.observe,
      jobs.start,
      jobs.stop,
      jobs.onResult,
      announcer.announce,
      dialog.open,
      snackBar.dismiss,
      adminApi.logout,
      api.get,
      unregisterResult,
    ]) {
      stub.mockClear();
    }
    onResultByType.clear();
    jobs.onResult.mockImplementation((type: string, handler: () => void) => {
      onResultByType.set(type, handler);
      return unregisterResult;
    });
    adminApi.logout.mockResolvedValue(undefined);
    api.get.mockResolvedValue({});
    TestBed.configureTestingModule({
      providers: [
        provideZonelessChangeDetection(),
        provideRouter([]),
        { provide: BreakpointObserver, useValue: breakpoints },
        { provide: SolverJobService, useValue: jobs },
        { provide: LiveAnnouncer, useValue: announcer },
        { provide: MatDialog, useValue: dialog },
        { provide: MatSnackBar, useValue: snackBar },
        { provide: ApiService, useValue: api },
        { provide: AdminApi, useValue: adminApi },
        // A mascot is configured by default here: the Konami easter egg only
        // exists on a deployment that has one, and most of these tests are
        // about the sequence, not about the brand.
        { provide: BRANDING, useValue: { ...BRANDING_NEUTRE, mascotUrl: 'mascotte.png' } },
      ],
    });
    // Spied before the shell is built: it preloads them in its constructor.
    resolutionReload = vi
      .spyOn(TestBed.inject(PlanningResolutionStore), 'reload')
      .mockResolvedValue(undefined);
    editionsReload = vi.spyOn(TestBed.inject(EditionStore), 'reload').mockResolvedValue(undefined);
  });

  // The global stub outlives this file otherwise, and the next spec in the
  // same worker inherits a ResizeObserver that is not one.
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  afterEach(() => {
    localStorage.removeItem(NAV_STORAGE_KEY);
    localStorage.removeItem(THEME_STORAGE_KEY);
    localStorage.removeItem(NAV_MODE_STORAGE_KEY);
    document.documentElement.style.colorScheme = '';
  });

  function createShell(): ShellInternals {
    fixture = TestBed.createComponent(AdminShell);
    TestBed.tick();
    return fixture.componentInstance as unknown as ShellInternals;
  }

  describe('the polling lifecycle it owns', () => {
    // The job service is `providedIn: 'root'`, so it outlives this shell: a
    // session expiring (401 → /login destroys the shell) would otherwise leave
    // the loop polling forever, each tick redirecting again.
    it('starts the solver polling for the session', () => {
      createShell();

      expect(jobs.start).toHaveBeenCalledOnce();
      expect(jobs.stop).not.toHaveBeenCalled();
    });

    it('stops it with the shell, not with the page', () => {
      createShell();

      fixture.destroy();

      expect(jobs.stop).toHaveBeenCalledOnce();
    });

    // Loaded here rather than per-page so the mismatch banner and the
    // "Édition actuelle" strip are correct on every screen, including the ones
    // that never touch the reference stores.
    it('preloads the resolution state and the editions once, in the shell', () => {
      createShell();

      expect(resolutionReload).toHaveBeenCalledOnce();
      expect(editionsReload).toHaveBeenCalledOnce();
    });

    it('re-reads the resolution state after a full solve, wherever it was started', () => {
      createShell();
      resolutionReload.mockClear();

      onResultByType.get('SOLVE')?.();

      expect(resolutionReload).toHaveBeenCalledOnce();
    });

    /**
     * An incremental replan writes `planning_resolution` exactly like a full
     * solve, so it clears the "reference data changed since the last solve"
     * banner just the same. The shell only listened for `SOLVE`, so a targeted
     * replan left that banner up until the next referential write or a page
     * reload — telling the operator their fresh plan was stale.
     */
    it('re-reads it after an incremental replan too, which also rewrites the resolution', () => {
      createShell();
      resolutionReload.mockClear();

      onResultByType.get('SOLVE_INCREMENTAL')?.();

      expect(resolutionReload).toHaveBeenCalledOnce();
    });

    it('unregisters both solve handlers with the shell', () => {
      createShell();

      fixture.destroy();

      expect(unregisterResult).toHaveBeenCalledTimes(2);
    });
  });

  describe('announcing a navigation', () => {
    // A router navigation swaps the content but leaves the focus on the link
    // that was clicked: without this a keyboard user tabs back through the
    // whole drawer and a screen reader announces nothing at all.
    async function navigate(): Promise<void> {
      const router = TestBed.inject(Router);
      (router.events as Subject<unknown>).next(new NavigationEnd(1, '/stands', '/stands'));
      // The announcement is queued in a microtask, so the router `title` is
      // already applied when it reads `Title.getTitle()`.
      await Promise.resolve();
      await Promise.resolve();
    }

    it('announces the page name, stripped of the application suffix', async () => {
      TestBed.inject(Title).setTitle('Stands — Planning Équipes');
      createShell();

      await navigate();

      expect(announcer.announce).toHaveBeenCalledExactlyOnceWith('Stands', 'polite');
    });

    it('announces nothing when the page carries no title', async () => {
      TestBed.inject(Title).setTitle('— Planning Équipes');
      createShell();

      await navigate();

      expect(announcer.announce).not.toHaveBeenCalled();
    });

    it('announces nothing before any navigation has happened', () => {
      TestBed.inject(Title).setTitle('Stands — Planning Équipes');
      createShell();

      expect(announcer.announce).not.toHaveBeenCalled();
    });

    it('stops announcing once the shell is destroyed', async () => {
      TestBed.inject(Title).setTitle('Stands — Planning Équipes');
      createShell();

      fixture.destroy();
      await navigate();

      expect(announcer.announce).not.toHaveBeenCalled();
    });
  });

  describe('the drawer', () => {
    it('docks on a large screen and overlays on a small one', () => {
      const shell = createShell();

      expect(shell.drawerMode()).toBe('side');
      expect(shell.drawerOpen()).toBe(true);

      handset.next({ matches: true });
      TestBed.tick();

      expect(shell.drawerMode()).toBe('over');
      expect(shell.drawerOpen()).toBe(false);
    });

    // `linkedSignal`, not an `effect` writing a signal: derived until written,
    // and reset by the next breakpoint change.
    it('stays user-controllable, and is reset by the next breakpoint change', () => {
      const shell = createShell();

      shell.toggleDrawer();
      expect(shell.drawerOpen()).toBe(false);

      handset.next({ matches: true });
      TestBed.tick();
      expect(shell.drawerOpen()).toBe(false);

      handset.next({ matches: false });
      TestBed.tick();
      expect(shell.drawerOpen()).toBe(true);
    });

    /*
     * Material lays a snackbar out full width on a handset and renders it in
     * the CDK overlay, a top-layer popover: it then sits over the last links of
     * the open drawer and eats the taps meant for them — the menu opens, the
     * link does nothing, the page stays put. Opening the menu clears it; the
     * message itself is kept on the Notifications page.
     */
    it('clears the notification snackbar when it opens over the page', () => {
      const shell = createShell();
      handset.next({ matches: true });
      TestBed.tick();
      expect(snackBar.dismiss).not.toHaveBeenCalled();

      shell.toggleDrawer();
      TestBed.tick();

      expect(shell.drawerOpen()).toBe(true);
      expect(snackBar.dismiss).toHaveBeenCalled();
    });

    // A docked drawer takes its own room instead of overlaying the page: the
    // snackbar covers nothing, and dismissing it would drop a message the user
    // never had the chance to read.
    it('leaves the snackbar alone while the drawer is docked', () => {
      const shell = createShell();

      expect(shell.drawerOpen()).toBe(true);
      expect(snackBar.dismiss).not.toHaveBeenCalled();
    });

    it('closes itself after navigating on a small screen', () => {
      const shell = createShell();
      handset.next({ matches: true });
      TestBed.tick();
      shell.drawerOpen.set(true);

      shell.onNavigate();

      expect(shell.drawerOpen()).toBe(false);
    });

    it('stays open after navigating on a large screen', () => {
      const shell = createShell();

      shell.onNavigate();

      expect(shell.drawerOpen()).toBe(true);
    });
  });

  describe('the folded navigation groups', () => {
    it('starts with every group open', () => {
      const shell = createShell();

      expect(shell.collapsedGroups().size).toBe(0);
      expect(shell.allCollapsed()).toBe(false);
      expect(shell.isCollapsed(shell.navGroups[0])).toBe(false);
    });

    it('folds and unfolds one group, and remembers it across visits', () => {
      const shell = createShell();
      const group = shell.navGroups[0];

      shell.toggleGroup(group);
      expect(shell.isCollapsed(group)).toBe(true);
      expect(JSON.parse(localStorage.getItem(NAV_STORAGE_KEY) ?? '[]')).toEqual([group.id]);

      shell.toggleGroup(group);
      expect(shell.isCollapsed(group)).toBe(false);
      expect(JSON.parse(localStorage.getItem(NAV_STORAGE_KEY) ?? '[]')).toEqual([]);
    });

    // Stored by stable group id, not by title: the titles are translated, and
    // a language switch must not silently unfold everything.
    it('reads back the folded groups written by a previous visit', () => {
      localStorage.setItem(NAV_STORAGE_KEY, JSON.stringify(['reference-data']));

      const shell = createShell();

      expect(shell.collapsedGroups()).toEqual(new Set(['reference-data']));
    });

    it('folds every group at once, and unfolds them all on the second press', () => {
      const shell = createShell();

      shell.toggleAllGroups();
      expect(shell.allCollapsed()).toBe(true);
      expect(shell.collapsedGroups().size).toBe(shell.navGroups.length);

      shell.toggleAllGroups();
      expect(shell.allCollapsed()).toBe(false);
      expect(shell.collapsedGroups().size).toBe(0);
    });

    // One group still open means the control must still read "fold them all",
    // however many of the others are already folded.
    it('is not "all folded" while a single group is still open', () => {
      const shell = createShell();
      for (const group of shell.navGroups.slice(0, -1)) {
        shell.toggleGroup(group);
      }

      expect(shell.allCollapsed()).toBe(false);

      shell.toggleAllGroups();

      expect(shell.allCollapsed()).toBe(true);
      expect(shell.collapsedGroups().size).toBe(shell.navGroups.length);
    });

    it('persists the fold-all as well', () => {
      const shell = createShell();

      shell.toggleAllGroups();

      expect(JSON.parse(localStorage.getItem(NAV_STORAGE_KEY) ?? '[]')).toHaveLength(
        shell.navGroups.length,
      );
    });
  });

  describe('the simple / advanced menu', () => {
    const paths = (groups: NavGroupShape[]): string[] =>
      groups.flatMap((group) => group.links.map((link) => link.path));

    it('hides the expert entries by default, and nothing else', () => {
      const shell = createShell();

      const hidden = shell.navGroups
        .flatMap((group) => group.links)
        .filter((link) => link.avance)
        .map((link) => link.path);
      // The exact set, not a sample: what the simple menu hides is the whole
      // point of the mode, so adding a screen has to be a deliberate edit here
      // rather than something a loose assertion waves through.
      expect([...hidden].sort()).toEqual([
        '/banc-de-touche',
        '/carte-jour',
        '/comparateur',
        '/constraints',
        '/debug',
        '/equite',
        '/fragilite',
        '/graphe',
        '/heatmap',
        '/historique',
        '/instantanes',
        '/kpi',
        '/mcp-client',
        '/pauses',
        '/rail-jour',
        '/repos',
        '/timeline',
      ]);
      const visible = paths(shell.visibleGroups());
      for (const path of hidden) {
        expect(visible).not.toContain(path);
      }
      expect(visible.length + hidden.length).toBe(paths(shell.navGroups).length);
      expect(shell.navModeLabel()).toContain('Menu simple');
    });

    it('lists everything once switched to advanced, and remembers it across visits', () => {
      const shell = createShell();

      shell.toggleNavMode();

      expect(paths(shell.visibleGroups())).toEqual(paths(shell.navGroups));
      expect(localStorage.getItem(NAV_MODE_STORAGE_KEY)).toBe('avance');
      expect(announcer.announce).toHaveBeenCalledWith('Menu avancé.', 'polite');
      expect(shell.navModeLabel()).toContain('Menu avancé');
    });

    it('starts advanced when the previous visit chose so', () => {
      localStorage.setItem(NAV_MODE_STORAGE_KEY, 'avance');

      const shell = createShell();

      expect(paths(shell.visibleGroups())).toEqual(paths(shell.navGroups));
    });

    // A link of the help or of the palette to a hidden screen still lands
    // there; the drawer then shows where the reader is, and only that.
    it('shows a hidden entry for the time of the visit when its route is on screen', () => {
      const shell = createShell();
      const router = TestBed.inject(Router);

      (router.events as Subject<unknown>).next(
        new NavigationEnd(1, '/debug?focus=date-du-jour', '/debug?focus=date-du-jour'),
      );

      const visible = paths(shell.visibleGroups());
      expect(visible).toContain('/debug');
      expect(visible).not.toContain('/mcp-client');

      (router.events as Subject<unknown>).next(new NavigationEnd(2, '/stands', '/stands'));

      expect(paths(shell.visibleGroups())).not.toContain('/debug');
    });

    // Folding is about groups, whatever the mode lists: the count stays the
    // count of groups, or "tout replier" would flip depending on the mode.
    it('keeps folding every group, hidden entries or not', () => {
      const shell = createShell();

      shell.toggleAllGroups();

      expect(shell.allCollapsed()).toBe(true);
      expect(shell.visibleGroups().length).toBeGreaterThan(0);
    });
  });

  describe('the notification badge', () => {
    function pushAlert(read = false): void {
      const notifications = TestBed.inject(NotificationService);
      notifications.push('alert', 'Résolution impossible');
      if (read) {
        notifications.markAllRead();
      }
    }

    it('shows the unread count when nothing is an alert', () => {
      const shell = createShell();
      TestBed.inject(NotificationService).push('info', 'Résolution terminée');

      expect(shell.hasUnreadAlert()).toBe(false);
      expect(shell.notificationBadgeContent()).toBe('1');
      expect(shell.notificationBadgeDescription()).toContain('1');
    });

    // The count is replaced, not merely coloured: a warning glyph is what makes
    // an alert readable at a glance in the drawer.
    it('replaces the count with a warning glyph on an unread alert', () => {
      const shell = createShell();
      pushAlert();

      expect(shell.hasUnreadAlert()).toBe(true);
      expect(shell.notificationBadgeContent()).toBe('⚠');
      expect(shell.notificationBadgeDescription()).toContain('Alerte');
    });

    it('goes back to the count once the alert has been read', () => {
      const shell = createShell();
      pushAlert(true);

      expect(shell.hasUnreadAlert()).toBe(false);
      expect(shell.notificationBadgeContent()).toBe('0');
    });
  });

  describe('the Konami easter egg', () => {
    const SEQUENCE = [
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

    // `bubbles: true` matters: the listener sits on `document`, so an event
    // dispatched on a field without bubbling would never reach it — and the
    // "form fields are ignored" test below would pass without any guard.
    function press(key: string, target?: HTMLElement): void {
      const event = new KeyboardEvent('keydown', { key, bubbles: true });
      (target ?? document).dispatchEvent(event);
    }

    it('summons the mascot on the full sequence', () => {
      createShell();

      SEQUENCE.forEach((key) => press(key));

      expect(dialog.open).toHaveBeenCalledOnce();
    });

    // A deployment with no mascot has nothing to show, and an empty dialog is
    // worse than none: the listener is never even registered.
    it('summons nothing when the deployment configured no mascot', () => {
      TestBed.resetTestingModule();
      TestBed.configureTestingModule({
        providers: [
          provideZonelessChangeDetection(),
          provideRouter([]),
          { provide: BreakpointObserver, useValue: breakpoints },
          { provide: SolverJobService, useValue: jobs },
          { provide: LiveAnnouncer, useValue: announcer },
          { provide: MatDialog, useValue: dialog },
          { provide: MatSnackBar, useValue: snackBar },
          { provide: ApiService, useValue: api },
          { provide: AdminApi, useValue: adminApi },
          { provide: BRANDING, useValue: BRANDING_NEUTRE },
        ],
      });
      vi.spyOn(TestBed.inject(PlanningResolutionStore), 'reload').mockResolvedValue(undefined);
      vi.spyOn(TestBed.inject(EditionStore), 'reload').mockResolvedValue(undefined);
      createShell();

      SEQUENCE.forEach((key) => press(key));

      expect(dialog.open).not.toHaveBeenCalled();
    });

    it('summons nothing on a partial sequence', () => {
      createShell();

      SEQUENCE.slice(0, -1).forEach((key) => press(key));

      expect(dialog.open).not.toHaveBeenCalled();
    });

    it('restarts from the first key rather than giving up on a wrong one', () => {
      createShell();

      press('ArrowDown');
      SEQUENCE.forEach((key) => press(key));

      expect(dialog.open).toHaveBeenCalledOnce();
    });

    // The subtle case, and the one a naive `position = 0` gets wrong: the key
    // that breaks the sequence is itself its first key, so it must count as a
    // restart rather than be thrown away.
    it('counts a wrong key that opens the sequence as the new first key', () => {
      createShell();

      press('ArrowUp');
      press('ArrowUp');
      SEQUENCE.forEach((key) => press(key));

      expect(dialog.open).toHaveBeenCalledOnce();
    });

    it('accepts the final keys whatever their case', () => {
      createShell();

      SEQUENCE.slice(0, -2).forEach((key) => press(key));
      press('B');
      press('A');

      expect(dialog.open).toHaveBeenCalledOnce();
    });

    // Nothing is buffered and form fields are ignored: typing "…ba" in a text
    // input must not pop a mascot over the form.
    it('ignores keystrokes aimed at a form field', () => {
      createShell();
      const input = document.createElement('input');
      document.body.appendChild(input);

      SEQUENCE.forEach((key) => press(key, input));

      expect(dialog.open).not.toHaveBeenCalled();
      input.remove();
    });

    it('stops listening once the shell is destroyed', () => {
      createShell();
      fixture.destroy();

      SEQUENCE.forEach((key) => press(key));

      expect(dialog.open).not.toHaveBeenCalled();
    });
  });

  describe('the navigation itself', () => {
    it('lists every group with a stable id and at least one link', () => {
      const shell = createShell();

      expect(shell.navGroups.length).toBeGreaterThan(0);
      for (const group of shell.navGroups) {
        expect(group.id).not.toBe('');
        expect(group.links.length).toBeGreaterThan(0);
      }
    });

    it('gives every group a distinct id, or folding one would fold another', () => {
      const shell = createShell();

      const ids = shell.navGroups.map((group) => group.id);
      expect(new Set(ids).size).toBe(ids.length);
    });

    it('routes every internal link to a distinct path', () => {
      const shell = createShell();

      const paths = shell.navGroups.flatMap((group) => group.links.map((link) => link.path));
      expect(new Set(paths).size).toBe(paths.length);
    });
  });

  // The resolution logic lives in `theme-preference.ts` and `theme.service.ts`;
  // what is pinned here is the toolbar button on top of them — a control with
  // three states, whose whole accessibility rests on saying which one it is in.
  describe('the colour-scheme button', () => {
    /** The `auto` marker: the only thing separating "dark chosen" from "dark resolved". */
    function marqueurAuto(): Element | null {
      return fixture.nativeElement.querySelector('.theme-auto-dot');
    }

    /** A `matchMedia` this test drives, standing in for the machine's setting. */
    function machine(prefereSombre: boolean) {
      const listeners = new Set<(event: MediaQueryListEvent) => void>();
      const media = {
        matches: prefereSombre,
        addEventListener: (_type: string, listener: (event: MediaQueryListEvent) => void) =>
          listeners.add(listener),
        removeEventListener: (_type: string, listener: (event: MediaQueryListEvent) => void) =>
          listeners.delete(listener),
        emit(matches: boolean) {
          media.matches = matches;
          for (const listener of listeners) {
            listener({ matches } as MediaQueryListEvent);
          }
        },
      };
      vi.stubGlobal('matchMedia', () => media);
      return media;
    }

    // The icon names the scheme actually painted, in all three states: jsdom
    // asks for no dark, so `système` resolves to light and shows the same moon
    // as an explicit light — the marker below is what separates them.
    it('cycles système → clair → sombre and says so', () => {
      const shell = createShell();

      expect(shell.themeIcon()).toBe('light_mode');
      expect(shell.themeLabel()).toContain('automatique');
      expect(marqueurAuto()).not.toBeNull();

      shell.toggleTheme();
      TestBed.tick();
      expect(shell.themeIcon()).toBe('light_mode');
      expect(shell.themeLabel()).toContain('clair');
      expect(marqueurAuto()).toBeNull();

      shell.toggleTheme();
      TestBed.tick();
      expect(shell.themeIcon()).toBe('dark_mode');
      expect(shell.themeLabel()).toContain('sombre');
      expect(localStorage.getItem(THEME_STORAGE_KEY)).toBe('dark');
      expect(marqueurAuto()).toBeNull();

      shell.toggleTheme();
      TestBed.tick();
      expect(shell.themeIcon()).toBe('light_mode');
      expect(marqueurAuto()).not.toBeNull();
    });

    // Why `ThemeService` listens to the media query at all: under `système`,
    // sunset flips the machine and `color-scheme: light dark` repaints on its
    // own. An icon frozen on the scheme of an hour ago would then show a sun
    // over a dark screen.
    it('follows the machine turning dark while staying on système', () => {
      const media = machine(false);
      const shell = createShell();

      expect(shell.themeIcon()).toBe('light_mode');

      media.emit(true);
      TestBed.tick();

      expect(shell.themeIcon()).toBe('dark_mode');
      expect(shell.themeLabel()).toContain('automatique');
      expect(marqueurAuto()).not.toBeNull();
    });

    // An explicit choice outranks the machine, so the icon must not move.
    it('ignores the machine once a scheme has been chosen', () => {
      const media = machine(false);
      const shell = createShell();
      shell.toggleTheme();
      TestBed.tick();

      media.emit(true);
      TestBed.tick();

      expect(shell.themeIcon()).toBe('light_mode');
      expect(marqueurAuto()).toBeNull();
    });

    // The button's own accessible name changes under the focus, and a name
    // that changes under the focus is not re-read: without the announcement a
    // screen-reader user presses the button and hears nothing at all.
    it('announces the new scheme', () => {
      const shell = createShell();

      shell.toggleTheme();

      expect(announcer.announce).toHaveBeenCalledWith(expect.stringContaining('clair'), 'polite');
    });
  });

  describe('logging out', () => {
    // A reload rather than a router navigation: it also resets every store the
    // shell preloaded, so nothing keeps polling behind the login page.
    it('drops the session cookie, then leaves for /login', async () => {
      const assign = vi.fn();
      vi.spyOn(window, 'location', 'get').mockReturnValue({ assign } as unknown as Location);
      const shell = createShell();

      await shell.logout();

      expect(adminApi.logout).toHaveBeenCalledOnce();
      expect(assign).toHaveBeenCalledExactlyOnceWith('/login');
      vi.restoreAllMocks();
    });

    // Leaving the user stuck on a shell whose session is already gone would be
    // worse than leaving anyway.
    it('leaves even when the server refuses the logout', async () => {
      const assign = vi.fn();
      vi.spyOn(window, 'location', 'get').mockReturnValue({ assign } as unknown as Location);
      adminApi.logout.mockRejectedValue(new Error('Serveur indisponible.'));
      const shell = createShell();

      await expect(shell.logout()).rejects.toThrow();

      expect(assign).toHaveBeenCalledExactlyOnceWith('/login');
      vi.restoreAllMocks();
    });
  });
});
