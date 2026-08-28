// Non-blocking user feedback: Material snack bars, plus desktop notifications
// when the browser tab is in the background and the user granted the permission.
//
// Every call also appends to a persisted log (localStorage) so warnings that
// would otherwise vanish with the snack bar — post-solve feasibility issues,
// failed constraints, solver job status, CRUD errors — stay reviewable from
// the Notifications page.

import { Injectable, computed, inject, signal } from '@angular/core';
import { MatSnackBar } from '@angular/material/snack-bar';
import { FeasibilityReport } from './models';
import { editionScopedKey } from './edition-courante';
import { hardScoreNegativeMessage } from '../shared/feasibility-messages';

const SNACK_TIMEOUT_MS = 9000;
const STORAGE_KEY_BASE = 'planning-equipes.notifications';

/**
 * One log per edition: a warning about 2026's data has no business showing up
 * while looking at 2025 (docs/decisions/0001-cloisonnement-par-edition.md §6). Resolved lazily rather than
 * once at module scope — the key must follow the edition the page was loaded
 * with, and switching edition reloads the page anyway.
 */
function storageKey(): string {
  return editionScopedKey(STORAGE_KEY_BASE);
}
/** Caps the persisted log so localStorage cannot grow unbounded over a long session. */
const MAX_NOTIFICATIONS = 200;

export type ToastVariant = 'info' | 'success' | 'warning' | 'error';

/** The three severities surfaced on the Notifications page. */
export type NotificationSeverity = 'info' | 'warning' | 'alert';

export interface NotifyOptions {
  title: string;
  message?: string;
  variant?: ToastVariant;
  /** 0 keeps the snack bar open until the user dismisses it. */
  timeout?: number;
  desktop?: boolean;
  /** Logs the notification without popping a snack bar (e.g. a banner already shows it inline). */
  silent?: boolean;
}

export interface AppNotification {
  id: string;
  severity: NotificationSeverity;
  title: string;
  message: string;
  timestamp: number;
  read: boolean;
}

function severityOf(variant: ToastVariant): NotificationSeverity {
  if (variant === 'warning') {
    return 'warning';
  }
  if (variant === 'error') {
    return 'alert';
  }
  return 'info';
}

function loadPersisted(): AppNotification[] {
  try {
    const raw = localStorage.getItem(storageKey());
    return raw ? (JSON.parse(raw) as AppNotification[]) : [];
  } catch {
    return []; // corrupted or unavailable storage: start from an empty log
  }
}

@Injectable({ providedIn: 'root' })
export class NotificationService {
  private readonly snackBar = inject(MatSnackBar);

  /** Newest first. */
  readonly notifications = signal<AppNotification[]>(loadPersisted());
  readonly unreadCount = computed(() => this.notifications().filter((notification) => !notification.read).length);

  notify({
    title,
    message = '',
    variant = 'info',
    timeout = SNACK_TIMEOUT_MS,
    desktop = false,
    silent = false
  }: NotifyOptions): void {
    if (!silent) {
      this.snackBar.open(message ? `${title} — ${message}` : title, $localize`:@@notification.close:Fermer`, {
        duration: timeout > 0 ? timeout : undefined,
        panelClass: `snack-${variant}`,
        horizontalPosition: 'right',
        verticalPosition: 'bottom'
      });
    }
    this.push(severityOf(variant), title, message);
    if (desktop) {
      this.showDesktopNotification(title, message);
    }
  }

  /** Appends an entry straight to the Notifications page, e.g. from the debug test actions. */
  push(severity: NotificationSeverity, title: string, message = ''): void {
    const entry: AppNotification = {
      id: crypto.randomUUID(),
      severity,
      title,
      message,
      timestamp: Date.now(),
      read: false
    };
    this.notifications.update((list) => [entry, ...list].slice(0, MAX_NOTIFICATIONS));
    this.persist();
  }

  /**
   * Logs a post-solve feasibility issue to the Notifications page, so
   * it stays reviewable even for whoever isn't looking at the Solveur or
   * Contraintes page when the background job completes — both already show
   * the same wording inline via `app-feasibility-banner`, so this is always
   * `silent` to avoid a duplicate toast on top of that persisted banner.
   * No-op when the result is actually feasible.
   */
  notifyFeasibility(faisabilite: FeasibilityReport | null, hardScore: number | null): void {
    if (faisabilite && !faisabilite.feasible) {
      this.notify({
        title: $localize`:@@feasibility.notification.notFeasible:Planning non totalement réalisable`,
        message: this.feasibilityMessage(faisabilite),
        variant: 'warning',
        silent: true
      });
      return;
    }
    if (hardScore !== null && hardScore < 0) {
      this.notify({
        title: $localize`:@@feasibility.notification.hardScoreNegative:Planning non totalement réalisable`,
        message: hardScoreNegativeMessage(hardScore),
        variant: 'error',
        silent: true
      });
    }
  }

  /**
   * The server-built summary, followed by how many causes back it and by the
   * worst one — the log entry must stand on its own, away from the banner that
   * lists them all.
   */
  private feasibilityMessage(faisabilite: FeasibilityReport): string {
    const causes = faisabilite.causes ?? [];
    if (faisabilite.totalCauses <= 0 || causes.length === 0) {
      return faisabilite.message;
    }
    const total = faisabilite.totalCauses;
    const premiere = causes[0].message;
    const detail = $localize`:@@feasibility.notification.causes:${total}:count: cause(s) identifiée(s), la plus grave : ${premiere}:cause:`;
    return `${faisabilite.message} ${detail}`;
  }

  markRead(id: string): void {
    this.notifications.update((list) =>
      list.map((notification) => (notification.id === id ? { ...notification, read: true } : notification))
    );
    this.persist();
  }

  markAllRead(): void {
    this.notifications.update((list) =>
      list.map((notification) => (notification.read ? notification : { ...notification, read: true }))
    );
    this.persist();
  }

  clear(): void {
    this.notifications.set([]);
    this.persist();
  }

  /** Asks once, from a user gesture, so a long job can notify a backgrounded tab. */
  requestDesktopPermission(): void {
    if (!('Notification' in window) || Notification.permission !== 'default') {
      return;
    }
    Notification.requestPermission().catch(() => {
      /* permission prompts can be blocked; snack bars remain the fallback */
    });
  }

  private showDesktopNotification(title: string, message: string): void {
    if (!('Notification' in window) || Notification.permission !== 'granted') {
      return;
    }
    if (document.visibilityState === 'visible') {
      return;
    }
    try {
      new Notification(title, { body: message });
    } catch {
      /* some browsers require a service worker; the snack bar already covered it */
    }
  }

  private persist(): void {
    try {
      localStorage.setItem(storageKey(), JSON.stringify(this.notifications()));
    } catch {
      /* storage full or unavailable: the in-memory log still works for this session */
    }
  }
}
