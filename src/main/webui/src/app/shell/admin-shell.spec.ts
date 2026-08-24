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
import { Title } from '@angular/platform-browser';
import { NavigationEnd, Router, provideRouter } from '@angular/router';
import { Subject } from 'rxjs';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiService } from '../core/api.service';
import { EditionStore } from '../core/edition.store';
import { NotificationService } from '../core/notification.service';
import { PlanningResolutionStore } from '../core/planning-resolution.store';
import { SolverJobService } from '../core/solver-job.service';
import { AdminShell } from './admin-shell';

const NAV_STORAGE_KEY = 'planning-equipes.nav.collapsedGroups';

interface NavGroupShape {
  id: string;
  title: string;
  links: { path: string; label: string }[];
}

/** Reaches the protected members the template binds to. */
type ShellInternals = {
  navGroups: NavGroupShape[];
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
  focusContenu: (event: Event) => void;
  logout: () => Promise<void>;
};

describe('AdminShell', () => {
  const handset = new Subject<{ matches: boolean }>();
  const breakpoints = { observe: vi.fn(() => handset.asObservable()) };
  const jobs = { start: vi.fn(), stop: vi.fn(), onResult: vi.fn(), file: () => [], activeJob: () => null };
  const announcer = { announce: vi.fn() };
  const dialog = { open: vi.fn() };
  const api = { post: vi.fn(), get: vi.fn() };
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
      }
    );
    localStorage.removeItem(NAV_STORAGE_KEY);
    for (const stub of [
      breakpoints.observe,
      jobs.start,
      jobs.stop,
      jobs.onResult,
      announcer.announce,
      dialog.open,
      api.post,
      api.get,
      unregisterResult
    ]) {
      stub.mockClear();
    }
    onResultByType.clear();
    jobs.onResult.mockImplementation((type: string, handler: () => void) => {
      onResultByType.set(type, handler);
      return unregisterResult;
    });
    api.post.mockResolvedValue(undefined);
    api.get.mockResolvedValue({});
    TestBed.configureTestingModule({
      providers: [
        provideZonelessChangeDetection(),
        provideRouter([]),
        { provide: BreakpointObserver, useValue: breakpoints },
        { provide: SolverJobService, useValue: jobs },
        { provide: LiveAnnouncer, useValue: announcer },
        { provide: MatDialog, useValue: dialog },
        { provide: ApiService, useValue: api },
        // A mascot is configured by default here: the Konami easter egg only
        // exists on a deployment that has one, and most of these tests are
        // about the sequence, not about the brand.
        { provide: BRANDING, useValue: { ...BRANDING_NEUTRE, mascotUrl: 'mascotte.png' } }
      ]
    });
    // Spied before the shell is built: it preloads them in its constructor.
    resolutionReload = vi.spyOn(TestBed.inject(PlanningResolutionStore), 'reload').mockResolvedValue(undefined);
    editionsReload = vi.spyOn(TestBed.inject(EditionStore), 'reload').mockResolvedValue(undefined);
  });

  // The global stub outlives this file otherwise, and the next spec in the
  // same worker inherits a ResizeObserver that is not one.
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  afterEach(() => {
    localStorage.removeItem(NAV_STORAGE_KEY);
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

      expect(JSON.parse(localStorage.getItem(NAV_STORAGE_KEY) ?? '[]')).toHaveLength(shell.navGroups.length);
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
      'a'
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
          { provide: ApiService, useValue: api },
          { provide: BRANDING, useValue: BRANDING_NEUTRE }
        ]
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

  describe('logging out', () => {
    // A reload rather than a router navigation: it also resets every store the
    // shell preloaded, so nothing keeps polling behind the login page.
    it('drops the session cookie, then leaves for /login', async () => {
      const assign = vi.fn();
      vi.spyOn(window, 'location', 'get').mockReturnValue({ assign } as unknown as Location);
      const shell = createShell();

      await shell.logout();

      expect(api.post).toHaveBeenCalledExactlyOnceWith('/api/auth/logout', null);
      expect(assign).toHaveBeenCalledExactlyOnceWith('/login');
      vi.restoreAllMocks();
    });

    // Leaving the user stuck on a shell whose session is already gone would be
    // worse than leaving anyway.
    it('leaves even when the server refuses the logout', async () => {
      const assign = vi.fn();
      vi.spyOn(window, 'location', 'get').mockReturnValue({ assign } as unknown as Location);
      api.post.mockRejectedValue(new Error('Serveur indisponible.'));
      const shell = createShell();

      await expect(shell.logout()).rejects.toThrow();

      expect(assign).toHaveBeenCalledExactlyOnceWith('/login');
      vi.restoreAllMocks();
    });
  });
});
