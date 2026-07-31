// Material replacement for window.confirm: a dialog, plus a service exposing it
// as an awaitable boolean so pages keep their linear async flow.

import { Component, Injectable, inject } from '@angular/core';
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

@Component({
  selector: 'app-confirm-dialog',
  imports: [MatDialogModule, MatButtonModule],
  template: `
    <h2 mat-dialog-title>{{ data.title }}</h2>
    <mat-dialog-content>{{ data.message }}</mat-dialog-content>
    <mat-dialog-actions align="end">
      <button matButton (click)="dialogRef.close(false)">Annuler</button>
      <button matButton="filled" [color]="data.danger ? 'warn' : 'primary'" (click)="dialogRef.close(true)">
        {{ data.confirmLabel ?? 'Confirmer' }}
      </button>
    </mat-dialog-actions>
  `
})
export class ConfirmDialog {
  protected readonly dialogRef = inject<MatDialogRef<ConfirmDialog, boolean>>(MatDialogRef);
  protected readonly data = inject<ConfirmData>(MAT_DIALOG_DATA);
}

@Injectable({ providedIn: 'root' })
export class ConfirmService {
  private readonly dialog = inject(MatDialog);

  async ask(data: ConfirmData): Promise<boolean> {
    const dialogRef = this.dialog.open<ConfirmDialog, ConfirmData, boolean>(ConfirmDialog, {
      data,
      width: '32rem',
      autoFocus: 'dialog'
    });
    return (await firstValueFrom(dialogRef.afterClosed())) === true;
  }
}
