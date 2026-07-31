import { Component, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { ReferenceCrudService } from '../../core/reference-crud.service';
import { SolverJobService } from '../../core/solver-job.service';
import { Creneau } from '../../core/models';

interface CreneauDraft {
  id: string;
  jour: number;
  date: string;
  heureDebut: string;
  heureFin: string;
  standsOuvertsIds: string[];
}

export interface CreneauFormData {
  creneau: Creneau | null;
}

/** Add/edit dialog for a timeslot: festival day, date and hours. Per-stand overrides stay on the page's checklist. */
@Component({
  selector: 'app-creneau-form-dialog',
  imports: [FormsModule, MatDialogModule, MatFormFieldModule, MatInputModule, MatButtonModule, MatIconModule],
  templateUrl: './creneau-form-dialog.html'
})
export class CreneauFormDialog {
  protected readonly jobs = inject(SolverJobService);
  /** Editing is disabled while a solve/analysis runs, to avoid corrupting the data it reads. */
  protected readonly editingLocked = computed(() => this.jobs.solverBusy());

  protected readonly dialogRef = inject<MatDialogRef<CreneauFormDialog, boolean>>(MatDialogRef);
  private readonly data = inject<CreneauFormData>(MAT_DIALOG_DATA);
  private readonly crud = inject(ReferenceCrudService);

  protected readonly editingId = signal<string | null>(this.data.creneau?.id ?? null);
  protected readonly draft = signal<CreneauDraft>(toDraft(this.data.creneau));
  protected readonly formTitle = computed(() => {
    const id = this.editingId();
    return id
      ? $localize`:@@creneaux.form.editTitle:Modifier le créneau ${id}:id:`
      : $localize`:@@creneaux.form.newTitle:Nouveau créneau`;
  });
  protected readonly submitLabel = computed(() =>
    this.editingId()
      ? $localize`:@@creneaux.submit.edit:Modifier le créneau`
      : $localize`:@@creneaux.submit.create:Créer le créneau`
  );

  protected patch(patch: Partial<CreneauDraft>): void {
    this.draft.update((draft) => ({ ...draft, ...patch }));
  }

  protected async save(): Promise<void> {
    const draft = this.draft();
    const creneau: Creneau = {
      id: draft.id.trim(),
      jour: Number(draft.jour),
      date: draft.date,
      heureDebut: draft.heureDebut,
      heureFin: draft.heureFin,
      standsOuvertsIds: draft.standsOuvertsIds
    };
    if (await this.crud.save('creneaux', creneau, this.editingId(), $localize`:@@creneaux.entityLabel:Créneau`)) {
      this.dialogRef.close(true);
    }
  }
}

function toDraft(creneau: Creneau | null): CreneauDraft {
  if (!creneau) {
    return { id: '', jour: 1, date: '', heureDebut: '', heureFin: '', standsOuvertsIds: [] };
  }
  return {
    id: creneau.id,
    jour: creneau.jour,
    date: creneau.date ?? '',
    heureDebut: creneau.heureDebut ?? '',
    heureFin: creneau.heureFin ?? '',
    standsOuvertsIds: [...(creneau.standsOuvertsIds ?? [])]
  };
}
