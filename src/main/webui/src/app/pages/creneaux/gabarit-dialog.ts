import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatTooltipModule } from '@angular/material/tooltip';
import { GenerateurCreneauxApi } from '../../core/api/generateur-creneaux-api';
import { injectGelReferentiel } from '../../core/gel-referentiel.store';
import { ReferenceCrudService } from '../../core/reference-crud.service';
import { SolverJobService } from '../../core/solver-job.service';
import { Gabarit } from '../../core/models';
import { ErreurVacations, formatVacations, libelleVacation, parseVacations } from './gabarits';

export interface GabaritDialogData {
  gabarit: Gabarit | null;
}

/**
 * « Appliquer sans mémoriser »: the day's timeslots, as the series dialog
 * reads them, to be laid on dates without keeping a template — what « Créer
 * une série » used to do from a button of its own.
 */
export interface OneOffSeries {
  fenetres: string;
}

/** What the dialog closes with: the template written, a series to lay, or nothing. */
export type GabaritDialogResult = Gabarit | OneOffSeries | null;

export function isOneOffSeries(result: GabaritDialogResult | undefined): result is OneOffSeries {
  return !!result && 'fenetres' in result;
}

/**
 * One day template: its name, and its vacations on a line — the way the
 * series dialog already reads a day. Nothing is written to the grid here: a
 * template changes nothing until the calendar is applied.
 */
@Component({
  selector: 'app-gabarit-dialog',
  imports: [
    FormsModule,
    MatDialogModule,
    MatFormFieldModule,
    MatInputModule,
    MatButtonModule,
    MatIconModule,
    MatTooltipModule,
  ],
  templateUrl: './gabarit-dialog.html',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class GabaritDialog {
  protected readonly editingLocked = inject(SolverJobService).editingLocked;
  private readonly gel = injectGelReferentiel();
  /**
   * « Appliquer sans mémoriser » writes timeslots, which a CRENEAUX freeze
   * refuses (ADR 0052); the template itself is not the grid and stays open.
   */
  protected readonly creneauxFrozen = computed(() => this.gel.isFrozen('CRENEAUX'));
  protected readonly dialogRef =
    inject<MatDialogRef<GabaritDialog, GabaritDialogResult>>(MatDialogRef);
  private readonly data = inject<GabaritDialogData>(MAT_DIALOG_DATA);
  private readonly api = inject(GenerateurCreneauxApi);
  private readonly crud = inject(ReferenceCrudService);

  protected readonly editingId = this.data.gabarit?.id ?? null;
  protected readonly nom = signal(this.data.gabarit?.nom ?? '');
  protected readonly ligne = signal(
    this.data.gabarit ? formatVacations(this.data.gabarit.vacations) : '',
  );
  protected readonly enregistrement = signal(false);

  protected readonly formTitle = this.editingId
    ? $localize`:@@gabarits.form.editTitle:Modifier le gabarit`
    : $localize`:@@gabarits.form.newTitle:Nouveau gabarit`;

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

  /** The timeslots are enough to lay them once: a name is only needed to keep them. */
  protected readonly oneOffImpossible = computed(
    () => this.saisie().erreur !== null || this.ligne().trim() === '',
  );

  /**
   * The day's timeslots laid on dates by the series dialog, the template kept
   * nowhere. A meal relay does not travel: a series writes plain timeslots.
   */
  protected applyWithoutKeeping(): void {
    const vacations = this.saisie().vacations;
    if (this.oneOffImpossible() || this.creneauxFrozen() || !vacations) {
      return;
    }
    this.dialogRef.close({
      fenetres: vacations.map((vacation) => libelleVacation(vacation).replace('–', '-')).join(', '),
    });
  }

  protected async save(): Promise<void> {
    const vacations = this.saisie().vacations;
    if (this.invalide() || !vacations || this.enregistrement()) {
      return;
    }
    const gabarit: Gabarit = {
      nom: this.nom().trim(),
      vacations,
      modifieLe: this.data.gabarit?.modifieLe ?? null,
    };
    this.enregistrement.set(true);
    try {
      const ecrit =
        this.editingId != null
          ? await this.api.update(this.editingId, gabarit)
          : await this.api.create(gabarit);
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
        return $localize`:@@gabarits.form.error.vide:Indiquez les vacations de la journée, par exemple « 09:00-12:00, 12:00-13:00 R, 13:00-14:00 R, 14:00-20:00 ».`;
      case 'FORME':
        return $localize`:@@gabarits.form.error.forme:« ${morceau}:morceau: » n'est pas une vacation : écrivez « début-fin », et un R après pour un relais repas.`;
      case 'HEURE':
        return $localize`:@@gabarits.form.error.heure:« ${morceau}:morceau: » : une heure s'écrit 09:00 ou 9h30.`;
      case 'DOUBLON':
        return $localize`:@@gabarits.form.error.doublon:« ${morceau}:morceau: » est déjà dans la journée.`;
    }
  }
}
