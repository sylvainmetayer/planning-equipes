import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { ReferenceCrudService } from '../../core/reference-crud.service';
import { SolverJobService } from '../../core/solver-job.service';
import { Creneau } from '../../core/models';

/** Sentinel `mat-select` value that reveals the "new group" name field. */

interface CreneauDraft {
  date: string;
  heureDebut: string;
  heureFin: string;
}

export interface CreneauFormData {
  creneau: Creneau | null;
}

/** Add/edit dialog for a timeslot: event day, date, hours and planning group. Stand availability is edited from the stand itself (see stands page). */
@Component({
  selector: 'app-creneau-form-dialog',
  imports: [
    FormsModule,
    MatDialogModule,
    MatFormFieldModule,
    MatInputModule,
    MatSelectModule,
    MatButtonModule,
    MatIconModule
  ],
  templateUrl: './creneau-form-dialog.html',
  changeDetection: ChangeDetectionStrategy.OnPush
})
export class CreneauFormDialog {
  protected readonly jobs = inject(SolverJobService);
  /** Editing is disabled while a solve/analysis runs, to avoid corrupting the data it reads. */
  protected readonly editingLocked = this.jobs.editingLocked;

  protected readonly dialogRef = inject<MatDialogRef<CreneauFormDialog, boolean>>(MatDialogRef);
  private readonly data = inject<CreneauFormData>(MAT_DIALOG_DATA);
  private readonly crud = inject(ReferenceCrudService);

  protected readonly editingId = signal<number | null>(this.data.creneau?.id ?? null);
  protected readonly draft = signal<CreneauDraft>(toDraft(this.data.creneau));
  protected readonly formTitle = computed(() => {
    const creneau = this.data.creneau;
    return creneau
      ? $localize`:@@creneaux.form.editTitle:Modifier le créneau du ${creneau.date}:date: ${creneau.heureDebut}:heure:`
      : $localize`:@@creneaux.form.newTitle:Nouveau créneau`;
  });
  protected readonly submitLabel = computed(() =>
    this.editingId()
      ? $localize`:@@creneaux.submit.edit:Modifier le créneau`
      : $localize`:@@creneaux.submit.create:Créer le créneau`
  );

  /**
   * What the three `required` attributes were promising and not delivering:
   * `(ngSubmit)` fires whatever the form's validity, so a créneau with empty
   * fields used to leave for the server, whose `NOT NULL` columns rejected it —
   * « Saisie refusée » instead of an error against the field. The stand dialog
   * next door already gates its submit this way.
   *
   * <p>A missing field is the <b>only</b> thing refused here. In particular an
   * end at or before the start is not an error: it is how the domain writes a
   * slot running past midnight ({@code Creneau.getDureeMinutes} counts
   * 20:00→00:00 as 240 minutes, and a stand window dated J+1 is only read by
   * such a slot). Refusing it would make the night slot unwritable from the
   * screen that exists to write slots.
   */
  protected readonly formulaireInvalide = computed(() => {
    const { date, heureDebut, heureFin } = this.draft();
    return !date || !heureDebut || !heureFin;
  });

  /**
   * Says out loud what an end before the start means, so a typo is caught by
   * the person who made it rather than accepted in silence — without refusing
   * the slot, which is legitimate. Only worth saying once both hours are
   * filled: before that they are simply missing.
   */
  protected readonly franchitMinuit = computed(() => {
    const { heureDebut, heureFin } = this.draft();
    return Boolean(heureDebut) && Boolean(heureFin) && heureFin <= heureDebut;
  });

  protected patch(patch: Partial<CreneauDraft>): void {
    this.draft.update((draft) => ({ ...draft, ...patch }));
  }

  protected async save(): Promise<void> {
    // Guarded here and not only on the button: pressing Enter in a field
    // submits the form, and a component that trusts its own template to hold
    // the rule has no rule.
    if (this.formulaireInvalide()) {
      return;
    }
    const draft = this.draft();
    const editingId = this.editingId();
    const creneau: Partial<Creneau> = {
      date: draft.date,
      heureDebut: draft.heureDebut,
      heureFin: draft.heureFin
    };
    if (editingId != null) {
      creneau.id = editingId;
    }
    if (
      await this.crud.save('creneaux', creneau, editingId, $localize`:@@creneaux.entityLabel:Créneau`, {
        requireId: false
      })
    ) {
      this.dialogRef.close(true);
    }
  }

}

function toDraft(creneau: Creneau | null): CreneauDraft {
  if (!creneau) {
    return { date: '', heureDebut: '', heureFin: '' };
  }
  return {
    date: creneau.date ?? '',
    heureDebut: creneau.heureDebut ?? '',
    heureFin: creneau.heureFin ?? ''
  };
}
