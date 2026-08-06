// Material replacement for window.confirm: a dialog, plus a service exposing it
// as an awaitable boolean so pages keep their linear async flow.

import { ChangeDetectionStrategy, Component, Injectable, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
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
  danger?: boolean;
  /** When set, a free-text field is shown and its content is returned instead of `true`. */
  reasonLabel?: string;
}

/** `null` when the dialog was cancelled, the (possibly empty) reason otherwise. */
export type ConfirmResult = boolean | string | null;

@Component({
  selector: 'app-confirm-dialog',
  imports: [FormsModule, MatDialogModule, MatButtonModule, MatFormFieldModule, MatInputModule],
  template: `
    <h2 mat-dialog-title>{{ data.title }}</h2>
    <mat-dialog-content>
      <p>{{ data.message }}</p>
      @if (data.reasonLabel) {
        <mat-form-field appearance="outline" class="confirm-dialog-reason">
          <mat-label>{{ data.reasonLabel }}</mat-label>
          <textarea matInput rows="3" name="motif" [ngModel]="reason()" (ngModelChange)="reason.set($event)"></textarea>
        </mat-form-field>
      }
    </mat-dialog-content>
    <mat-dialog-actions align="end">
      <button matButton (click)="dialogRef.close(null)" i18n="@@confirmDialog.cancel">Annuler</button>
      <button matButton="filled" [color]="data.danger ? 'warn' : 'primary'"
              [disabled]="!!data.reasonLabel && reason().trim().length === 0"
              (click)="dialogRef.close(data.reasonLabel ? reason().trim() : true)">
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
  protected readonly reason = signal('');
}

@Injectable({ providedIn: 'root' })
export class ConfirmService {
  private readonly dialog = inject(MatDialog);

  async ask(data: ConfirmData): Promise<boolean> {
    return (await this.open(data)) === true;
  }

  /**
   * Same dialog with a mandatory free-text field. Resolves to the reason typed
   * by the user, or `null` if they cancelled — used to record *why* a legal
   * constraint is being disabled (constat C2 de l'audit de conformité RH).
   */
  async askWithReason(data: ConfirmData & { reasonLabel: string }): Promise<string | null> {
    const result = await this.open(data);
    return typeof result === 'string' ? result : null;
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
