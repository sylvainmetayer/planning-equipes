import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import {
  MAT_SNACK_BAR_DATA,
  MatSnackBarAction,
  MatSnackBarActions,
  MatSnackBarLabel,
  MatSnackBarRef,
} from '@angular/material/snack-bar';
import { Router } from '@angular/router';
import { LienNotification } from '../core/notification.service';

/** What the snack bar shows: the sentence, and the screen it offers to open. */
export interface NotificationSnackData {
  message: string;
  lien: LienNotification;
}

/**
 * A snack bar with two actions where Material's plain one has a single
 * action: the link a notification offers (issue #489 — a warning naming a
 * fiche reopens the fiche), and « Fermer », which stays. A bar whose only
 * action navigates cannot be dismissed without leaving the page, and a
 * warning that stays until read (no timeout) must still be dismissable.
 */
@Component({
  selector: 'app-notification-snack',
  imports: [MatButtonModule, MatSnackBarLabel, MatSnackBarActions, MatSnackBarAction],
  template: `
    <span matSnackBarLabel>{{ data.message }}</span>
    <span matSnackBarActions>
      <button matButton matSnackBarAction type="button" (click)="follow()">
        {{ data.lien.libelle }}
      </button>
      <button matButton matSnackBarAction type="button" (click)="ref.dismiss()" i18n="@@notification.close">
        Fermer
      </button>
    </span>
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class NotificationSnack {
  protected readonly data = inject<NotificationSnackData>(MAT_SNACK_BAR_DATA);
  protected readonly ref = inject(MatSnackBarRef<NotificationSnack>);
  /** Optional: a spec rendering no router still shows the bar, the link just goes nowhere. */
  private readonly router = inject(Router, { optional: true });

  /** The link is the action: pressing it goes there, and the bar closes with it. */
  protected follow(): void {
    const { route, queryParams } = this.data.lien;
    this.ref.dismiss();
    void this.router?.navigate([route], { queryParams });
  }
}
