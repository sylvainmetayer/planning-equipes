// Material replacement for window.prompt: one required text field, resolved as
// an awaitable string. Sibling of `confirm-dialog`, which answers yes/no.
//
// It stays a plain text field on purpose: comparing what was typed against an
// expected word belongs to the caller — see `confirmation-recopie.ts`, which
// wraps this dialog for the actions that destroy data.

import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import {
  MatDialog,
  MatDialogModule,
  MatDialogRef,
  MAT_DIALOG_DATA,
} from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { firstValueFrom } from 'rxjs';

export interface PromptDialogData {
  title: string;
  label: string;
  confirmLabel?: string;
  /** Prefilled value, e.g. when renaming something. */
  valeurInitiale?: string;
  /**
   * Sentence shown above the field: what is about to happen, and how far it
   * reaches. A form-field label is the wrong place for it.
   */
  message?: string;
  /** Colours the confirm button as a destructive action, like `confirm-dialog`. */
  danger?: boolean;
}

@Component({
  selector: 'app-prompt-dialog',
  imports: [FormsModule, MatDialogModule, MatButtonModule, MatFormFieldModule, MatInputModule],
  template: `
    <h2 mat-dialog-title>{{ data.title }}</h2>
    <mat-dialog-content>
      @if (data.message) {
        <p>{{ data.message }}</p>
      }
      <mat-form-field appearance="outline" class="prompt-dialog-field">
        <mat-label>{{ data.label }}</mat-label>
        <input matInput name="valeur" [ngModel]="valeur()" (ngModelChange)="valeur.set($event)" />
      </mat-form-field>
    </mat-dialog-content>
    <mat-dialog-actions align="end">
      <button matButton (click)="dialogRef.close(null)" i18n="@@promptDialog.cancel">
        Annuler
      </button>
      <button
        matButton="filled"
        [color]="data.danger ? 'warn' : 'primary'"
        [disabled]="valeur().trim().length === 0"
        (click)="dialogRef.close(valeur().trim())"
      >
        {{ data.confirmLabel ?? defaultConfirmLabel }}
      </button>
    </mat-dialog-actions>
  `,
  styles: `
    .prompt-dialog-field {
      width: 100%;
    }
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class PromptDialog {
  protected readonly dialogRef = inject<MatDialogRef<PromptDialog, string | null>>(MatDialogRef);
  protected readonly data = inject<PromptDialogData>(MAT_DIALOG_DATA);
  protected readonly defaultConfirmLabel = $localize`:@@promptDialog.confirm:Valider`;
  protected readonly valeur = signal(this.data.valeurInitiale ?? '');

  /** Opens the dialog and resolves to the trimmed value, or `null` if cancelled. */
  static async ask(dialog: MatDialog, data: PromptDialogData): Promise<string | null> {
    const dialogRef = dialog.open<PromptDialog, PromptDialogData, string | null>(PromptDialog, {
      data,
      width: '28rem',
      autoFocus: 'first-tabbable',
    });
    return (await firstValueFrom(dialogRef.afterClosed())) ?? null;
  }
}
