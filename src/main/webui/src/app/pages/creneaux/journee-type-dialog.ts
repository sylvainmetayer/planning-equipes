import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { JourneesTypesApi } from '../../core/api/journees-types-api';
import { ReferenceCrudService } from '../../core/reference-crud.service';
import { SolverJobService } from '../../core/solver-job.service';
import { JourneeType } from '../../core/models';
import {
  ErreurVacations,
  formatVacations,
  libelleVacation,
  parseVacations,
} from './journees-types';

export interface JourneeTypeDialogData {
  journeeType: JourneeType | null;
}

/**
 * One day template: its name, and its vacations on a line — the way the
 * series dialog already reads a day. Nothing is written to the grid here: a
 * template changes nothing until the calendar is applied.
 */
@Component({
  selector: 'app-journee-type-dialog',
  imports: [
    FormsModule,
    MatDialogModule,
    MatFormFieldModule,
    MatInputModule,
    MatButtonModule,
    MatIconModule,
  ],
  templateUrl: './journee-type-dialog.html',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class JourneeTypeDialog {
  protected readonly editingLocked = inject(SolverJobService).editingLocked;
  protected readonly dialogRef =
    inject<MatDialogRef<JourneeTypeDialog, JourneeType | null>>(MatDialogRef);
  private readonly data = inject<JourneeTypeDialogData>(MAT_DIALOG_DATA);
  private readonly api = inject(JourneesTypesApi);
  private readonly crud = inject(ReferenceCrudService);

  protected readonly editingId = this.data.journeeType?.id ?? null;
  protected readonly nom = signal(this.data.journeeType?.nom ?? '');
  protected readonly ligne = signal(
    this.data.journeeType ? formatVacations(this.data.journeeType.vacations) : '',
  );
  protected readonly enregistrement = signal(false);

  protected readonly formTitle = this.editingId
    ? $localize`:@@journeesTypes.form.editTitle:Modifier la journée type`
    : $localize`:@@journeesTypes.form.newTitle:Nouvelle journée type`;

  protected readonly saisie = computed(() => parseVacations(this.ligne()));
  protected readonly erreur = computed(() => {
    const saisie = this.saisie();
    return saisie.erreur === null ? null : this.message(saisie.erreur, saisie.morceau);
  });
  /** The line read back as chips, so a typo shows before anything is saved. */
  protected readonly apercu = computed(() => this.saisie().vacations ?? []);
  protected readonly invalide = computed(
    () => this.nom().trim() === '' || this.saisie().erreur !== null || this.ligne().trim() === '',
  );

  protected readonly libelleVacation = libelleVacation;

  protected async save(): Promise<void> {
    const vacations = this.saisie().vacations;
    if (this.invalide() || !vacations || this.enregistrement()) {
      return;
    }
    const journeeType: JourneeType = {
      nom: this.nom().trim(),
      vacations,
      modifieLe: this.data.journeeType?.modifieLe ?? null,
    };
    this.enregistrement.set(true);
    try {
      const ecrit =
        this.editingId != null
          ? await this.api.update(this.editingId, journeeType)
          : await this.api.create(journeeType);
      this.dialogRef.close(ecrit);
    } catch (error) {
      this.crud.reportError(error);
    } finally {
      this.enregistrement.set(false);
    }
  }

  private message(erreur: ErreurVacations, morceau: string): string {
    switch (erreur) {
      case 'VIDE':
        return $localize`:@@journeesTypes.form.error.vide:Indiquez les vacations de la journée, par exemple « 09:00-12:00, 12:00-13:00 R, 13:00-14:00 R, 14:00-20:00 ».`;
      case 'FORME':
        return $localize`:@@journeesTypes.form.error.forme:« ${morceau}:morceau: » n'est pas une vacation : écrivez « début-fin », et un R après pour un relais repas.`;
      case 'HEURE':
        return $localize`:@@journeesTypes.form.error.heure:« ${morceau}:morceau: » : une heure s'écrit 09:00 ou 9h30.`;
      case 'DOUBLON':
        return $localize`:@@journeesTypes.form.error.doublon:« ${morceau}:morceau: » est déjà dans la journée.`;
    }
  }
}
