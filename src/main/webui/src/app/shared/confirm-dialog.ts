// Material replacement for window.confirm: a dialog, plus a service exposing it
// as an awaitable boolean so pages keep their linear async flow.

import { ChangeDetectionStrategy, Component, Injectable, inject, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import {
  MAT_DIALOG_DATA,
  MatDialog,
  MatDialogModule,
  MatDialogRef
} from '@angular/material/dialog';
import { firstValueFrom } from 'rxjs';

export interface ConfirmData {
  title: string;
  message: string;
  confirmLabel?: string;
  /** Label of the dismiss button — defaults to « Annuler ». */
  cancelLabel?: string;
  danger?: boolean;
  /**
   * A second paragraph the caller is still fetching — typically what the
   * deletion would take with it, counted by the server.
   *
   * A promise rather than a string on purpose: the dialog must open on the
   * click that asked for it. Awaiting the count before opening left a window
   * in which the button was still live and nothing had appeared, so a double
   * click stacked two dialogs and then two deletions, the second failing on a
   * row the first had already removed. It arrives here instead, and a
   * rejection simply leaves the paragraph out — the count informs, it never
   * blocks.
   */
  detail?: Promise<string>;
}

/** `null` when the dialog was cancelled. */
/** `true` confirmed, `false` the cancel button, `null` dismissed (Escape, backdrop). */
export type ConfirmResult = boolean | null;

@Component({
  selector: 'app-confirm-dialog',
  imports: [MatDialogModule, MatButtonModule],
  template: `
    <h2 mat-dialog-title>{{ data.title }}</h2>
    <mat-dialog-content>
      <p>{{ data.message }}</p>
      @if (detail(); as texte) {
        <p>{{ texte }}</p>
      }
    </mat-dialog-content>
    <mat-dialog-actions align="end">
      <!--
        Closes with false, not null: null is what Escape and the backdrop
        produce, and one dialog needs the two apart — the conflict of issue
        #362, whose cancel button ("Recharger") throws away what the user
        typed, so the gesture that means "I did not decide" must not perform
        it. ask() maps both to false, so every other caller is unchanged.
      -->
      <button matButton (click)="dialogRef.close(false)">
        {{ data.cancelLabel ?? defaultCancelLabel }}
      </button>
      <button matButton="filled" [color]="data.danger ? 'warn' : 'primary'" (click)="dialogRef.close(true)">
        {{ data.confirmLabel ?? defaultConfirmLabel }}
      </button>
    </mat-dialog-actions>
  `,
  changeDetection: ChangeDetectionStrategy.OnPush
})
export class ConfirmDialog {
  protected readonly dialogRef = inject<MatDialogRef<ConfirmDialog, ConfirmResult>>(MatDialogRef);
  protected readonly data = inject<ConfirmData>(MAT_DIALOG_DATA);
  protected readonly defaultConfirmLabel = $localize`:@@confirmDialog.confirm:Confirmer`;
  protected readonly defaultCancelLabel = $localize`:@@confirmDialog.cancel:Annuler`;
  /** Empty until {@link ConfirmData.detail} resolves, and after it rejects. */
  protected readonly detail = signal('');

  constructor() {
    void this.data.detail?.then((text) => this.detail.set(text)).catch(() => undefined);
  }
}

@Injectable({ providedIn: 'root' })
export class ConfirmService {
  private readonly dialog = inject(MatDialog);

  async ask(data: ConfirmData): Promise<boolean> {
    return (await this.open(data)) === true;
  }

  /**
   * Same dialog, all three answers kept apart: `true` confirmed, `false` the
   * cancel button, `null` dismissed (Escape, backdrop, the close cross). Use
   * it wherever cancelling and dismissing must not do the same thing — a
   * dialog whose cancel button performs a real action, for one (issue #362:
   * « Recharger » throws away what the user typed, so Escape must not).
   */
  async askThreeWay(data: ConfirmData): Promise<ConfirmResult> {
    return this.open(data);
  }

  private async open(data: ConfirmData): Promise<ConfirmResult> {
    const dialogRef = this.dialog.open<ConfirmDialog, ConfirmData, ConfirmResult>(ConfirmDialog, {
      data,
      width: '32rem',
      autoFocus: 'dialog'
    });
    return (await firstValueFrom(dialogRef.afterClosed())) ?? null;
  }
}
