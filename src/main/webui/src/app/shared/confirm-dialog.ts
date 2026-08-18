// Material replacement for window.confirm: a dialog, plus a service exposing it
// as an awaitable boolean so pages keep their linear async flow.

import { ChangeDetectionStrategy, Component, Injectable, inject } from '@angular/core';
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
  danger?: boolean;
}

/** `null` when the dialog was cancelled. */
export type ConfirmResult = boolean | null;

@Component({
  selector: 'app-confirm-dialog',
  imports: [MatDialogModule, MatButtonModule],
  template: `
    <h2 mat-dialog-title>{{ data.title }}</h2>
    <mat-dialog-content>
      <p>{{ data.message }}</p>
    </mat-dialog-content>
    <mat-dialog-actions align="end">
      <button matButton (click)="dialogRef.close(null)" i18n="@@confirmDialog.cancel">Annuler</button>
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
}

@Injectable({ providedIn: 'root' })
export class ConfirmService {
  private readonly dialog = inject(MatDialog);

  async ask(data: ConfirmData): Promise<boolean> {
    return (await this.open(data)) === true;
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
