// Non-blocking user feedback: Material snack bars, plus desktop notifications
// when the browser tab is in the background and the user granted the permission.

import { Injectable, inject } from '@angular/core';
import { MatSnackBar } from '@angular/material/snack-bar';

const SNACK_TIMEOUT_MS = 9000;

export type ToastVariant = 'info' | 'success' | 'error';

export interface NotifyOptions {
  title: string;
  message?: string;
  variant?: ToastVariant;
  /** 0 keeps the snack bar open until the user dismisses it. */
  timeout?: number;
  desktop?: boolean;
}

@Injectable({ providedIn: 'root' })
export class NotificationService {
  private readonly snackBar = inject(MatSnackBar);

  notify({ title, message = '', variant = 'info', timeout = SNACK_TIMEOUT_MS, desktop = false }: NotifyOptions): void {
    this.snackBar.open(message ? `${title} — ${message}` : title, 'Fermer', {
      duration: timeout > 0 ? timeout : undefined,
      panelClass: `snack-${variant}`,
      horizontalPosition: 'right',
      verticalPosition: 'bottom'
    });
    if (desktop) {
      this.showDesktopNotification(title, message);
    }
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
}
