// What a block pasted from a spreadsheet would change, shown before anything
// is written (the referential tables' Ctrl+V): every cell with its value
// before and after, and every value left out with its reason. « Appliquer »
// is the one gesture that saves; closing the dialog writes nothing.

import { ChangeDetectionStrategy, Component, Injectable, inject } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import {
  MAT_DIALOG_DATA,
  MatDialog,
  MatDialogModule,
  MatDialogRef,
} from '@angular/material/dialog';
import { firstValueFrom } from 'rxjs';

/** One line of the preview: a cell that changes, or a value refused. */
export interface PastePreviewLine {
  row: string;
  column: string;
  before: string;
  after: string;
}

export interface PastePreviewData {
  changes: readonly PastePreviewLine[];
  refusals: readonly { row: string; column: string; value: string; reason: string }[];
  /** Pasted lines no row takes. */
  unplaced: number;
}

@Component({
  selector: 'app-paste-preview-dialog',
  imports: [MatDialogModule, MatButtonModule],
  template: `
    <h2 mat-dialog-title i18n="@@paste.title">Coller depuis un tableur</h2>
    <mat-dialog-content>
      @if (data.changes.length === 0) {
        <p i18n="@@paste.none">Rien à changer : les cases collées disent déjà ce que dit le tableau.</p>
      } @else {
        <p i18n="@@paste.summary">{{ data.changes.length }} case(s) seront modifiées :</p>
        <div class="table-wrapper paste-preview">
          <table class="paste-preview-table">
            <caption class="visually-hidden" i18n="@@paste.caption">Cases modifiées par le collage : ligne, colonne, valeur actuelle et nouvelle valeur</caption>
            <thead>
              <tr>
                <th scope="col" i18n="@@paste.column.row">Ligne</th>
                <th scope="col" i18n="@@paste.column.column">Colonne</th>
                <th scope="col" i18n="@@paste.column.before">Avant</th>
                <th scope="col" i18n="@@paste.column.after">Après</th>
              </tr>
            </thead>
            <tbody>
              @for (change of data.changes; track $index) {
                <tr>
                  <th scope="row">{{ change.row }}</th>
                  <td>{{ change.column }}</td>
                  <td class="paste-before">{{ change.before || '—' }}</td>
                  <td class="paste-after">{{ change.after || '—' }}</td>
                </tr>
              }
            </tbody>
          </table>
        </div>
      }
      @if (data.refusals.length > 0) {
        <p class="paste-refusals-title" i18n="@@paste.refusals">{{ data.refusals.length }} valeur(s) laissée(s) de côté :</p>
        <ul class="paste-refusals">
          @for (refusal of data.refusals; track $index) {
            <li>{{ refusal.row }} · {{ refusal.column }} « {{ refusal.value }} » : {{ refusal.reason }}</li>
          }
        </ul>
      }
      @if (data.unplaced > 0) {
        <p i18n="@@paste.unplaced">{{ data.unplaced }} ligne(s) collée(s) sans ligne du tableau pour les recevoir.</p>
      }
    </mat-dialog-content>
    <mat-dialog-actions align="end">
      <button matButton type="button" (click)="dialogRef.close(false)" i18n="@@confirmDialog.cancel">Annuler</button>
      <button matButton="filled" type="button" [disabled]="data.changes.length === 0" (click)="dialogRef.close(true)">
        <ng-container i18n="@@paste.apply">Appliquer ({{ data.changes.length }})</ng-container>
      </button>
    </mat-dialog-actions>
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class PastePreviewDialog {
  protected readonly dialogRef = inject<MatDialogRef<PastePreviewDialog, boolean>>(MatDialogRef);
  protected readonly data = inject<PastePreviewData>(MAT_DIALOG_DATA);
}

/** The preview as an awaitable answer: `true` only on « Appliquer ». */
@Injectable({ providedIn: 'root' })
export class PastePreviewService {
  private readonly dialog = inject(MatDialog);

  async confirm(data: PastePreviewData): Promise<boolean> {
    const result = await firstValueFrom(
      this.dialog
        .open<PastePreviewDialog, PastePreviewData, boolean>(PastePreviewDialog, {
          data,
          width: '44rem',
          maxWidth: '95vw',
        })
        .afterClosed(),
    );
    return result === true;
  }
}
