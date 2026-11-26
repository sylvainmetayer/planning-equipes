import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { parseDateKey } from '../../core/date-utils';
import { intlLocale } from '../../core/locale';
import { ValidationPanel } from './validation-panel';

export interface RelectureDialogData {
  /** The day to read, `AAAA-MM-JJ`. */
  jour: string;
  /** Whether the page narrows what it shows: the reading still covers the whole day. */
  filtre: boolean;
}

/**
 * The state of the day, from the relecture bar: the prerequisites, the
 * three-valued control and the comment, in a dialog that closes on the state
 * it records and hands the page its sentence.
 */
@Component({
  selector: 'app-relecture-dialog',
  imports: [MatButtonModule, MatDialogModule, ValidationPanel],
  template: `
    <h2 mat-dialog-title>{{ titre }}</h2>
    <mat-dialog-content>
      <app-validation-panel [jour]="data.jour" [filtre]="data.filtre" [entete]="false"
                            (reported)="dialogRef.close($event)" />
    </mat-dialog-content>
    <mat-dialog-actions align="end">
      <button matButton type="button" (click)="dialogRef.close()" i18n="@@common.close">Fermer</button>
    </mat-dialog-actions>
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class RelectureDialog {
  protected readonly data = inject<RelectureDialogData>(MAT_DIALOG_DATA);
  protected readonly dialogRef = inject<MatDialogRef<RelectureDialog, string>>(MatDialogRef);

  private readonly jourLabel = parseDateKey(this.data.jour).toLocaleDateString(intlLocale(), {
    weekday: 'long',
    day: 'numeric',
    month: 'long',
  });
  protected readonly titre = $localize`:@@journee.relecture.dialog.titre:Relecture du ${this.jourLabel}:jour:`;
}
