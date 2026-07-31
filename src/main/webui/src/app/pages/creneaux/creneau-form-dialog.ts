import { Component, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { ReferenceCrudService } from '../../core/reference-crud.service';
import { ReferenceDataStore } from '../../core/reference-data.store';
import { SolverJobService } from '../../core/solver-job.service';
import { slugify } from '../../core/slug';
import { Creneau, GroupeCreneau } from '../../core/models';

/** Sentinel `mat-select` value that reveals the "new group" name field. */
const NOUVEAU_GROUPE = '__nouveau__';
const GROUPE_DEFAUT_ID = 'DEFAUT';

interface CreneauDraft {
  id: string;
  jour: number;
  date: string;
  heureDebut: string;
  heureFin: string;
  standsOuvertsIds: string[];
  groupeId: string;
  nouveauGroupeNom: string;
}

export interface CreneauFormData {
  creneau: Creneau | null;
}

/** Add/edit dialog for a timeslot: festival day, date, hours and planning group. Per-stand overrides stay on the page's checklist. */
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
  templateUrl: './creneau-form-dialog.html'
})
export class CreneauFormDialog {
  protected readonly jobs = inject(SolverJobService);
  /** Editing is disabled while a solve/analysis runs, to avoid corrupting the data it reads. */
  protected readonly editingLocked = computed(() => this.jobs.solverBusy());

  protected readonly store = inject(ReferenceDataStore);
  protected readonly dialogRef = inject<MatDialogRef<CreneauFormDialog, boolean>>(MatDialogRef);
  private readonly data = inject<CreneauFormData>(MAT_DIALOG_DATA);
  private readonly crud = inject(ReferenceCrudService);

  protected readonly nouveauGroupeValue = NOUVEAU_GROUPE;
  protected readonly editingId = signal<string | null>(this.data.creneau?.id ?? null);
  /** New créneaux default to the currently active group, not a fixed one. */
  private readonly activeGroupeId =
    this.store.groupesCreneaux().find((groupe) => groupe.actif)?.id ?? GROUPE_DEFAUT_ID;
  protected readonly draft = signal<CreneauDraft>(toDraft(this.data.creneau, this.activeGroupeId));
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
    let groupeId = draft.groupeId;
    if (groupeId === NOUVEAU_GROUPE) {
      const id = await this.creerGroupe(draft.nouveauGroupeNom.trim());
      if (!id) {
        return;
      }
      groupeId = id;
    }
    const creneau: Creneau = {
      id: draft.id.trim(),
      jour: Number(draft.jour),
      date: draft.date,
      heureDebut: draft.heureDebut,
      heureFin: draft.heureFin,
      standsOuvertsIds: draft.standsOuvertsIds,
      groupe: { id: groupeId, nom: '', actif: false }
    };
    if (await this.crud.save('creneaux', creneau, this.editingId(), $localize`:@@creneaux.entityLabel:Créneau`)) {
      this.dialogRef.close(true);
    }
  }

  /** Creates the group typed in the "new group" field, deriving its id from the name. Returns the new id, or null on failure. */
  private async creerGroupe(nom: string): Promise<string | null> {
    if (!nom) {
      return null;
    }
    const id = slugify(
      nom,
      this.store.groupesCreneaux().map((groupe) => groupe.id)
    );
    const groupe: GroupeCreneau = { id, nom, actif: false };
    const created = await this.crud.save(
      'groupes-creneaux',
      groupe,
      null,
      $localize`:@@groupesCreneaux.entityLabel:Groupe de créneaux`
    );
    return created ? groupe.id : null;
  }
}

function toDraft(creneau: Creneau | null, activeGroupeId: string): CreneauDraft {
  if (!creneau) {
    return {
      id: '',
      jour: 1,
      date: '',
      heureDebut: '',
      heureFin: '',
      standsOuvertsIds: [],
      groupeId: activeGroupeId,
      nouveauGroupeNom: ''
    };
  }
  return {
    id: creneau.id,
    jour: creneau.jour,
    date: creneau.date ?? '',
    heureDebut: creneau.heureDebut ?? '',
    heureFin: creneau.heureFin ?? '',
    standsOuvertsIds: [...(creneau.standsOuvertsIds ?? [])],
    groupeId: creneau.groupe?.id ?? activeGroupeId,
    nouveauGroupeNom: ''
  };
}
