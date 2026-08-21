import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
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
import { ContrainteAdHoc, TypeContrainteAdHoc } from '../../core/models';

const CONTRAINTE_TYPE_VALUES: TypeContrainteAdHoc[] = ['INDISPONIBILITE_FORCEE', 'INCOMPATIBILITE', 'AFFECTATION_FORCEE', 'AFFINITE'];

/** Called lazily (never at module scope, see `app.ts`'s `buildNavGroups`). */
function contrainteTypeLabel(value: TypeContrainteAdHoc): string {
  switch (value) {
    case 'INDISPONIBILITE_FORCEE':
      return $localize`:@@adHoc.type.indisponibiliteForcee:Indisponibilité forcée`;
    case 'INCOMPATIBILITE':
      return $localize`:@@adHoc.type.incompatibilite:Incompatibilité`;
    case 'AFFECTATION_FORCEE':
      return $localize`:@@adHoc.type.affectationForcee:Affectation forcée`;
    case 'AFFINITE':
      return $localize`:@@adHoc.type.affinite:Affinité (paire à privilégier)`;
  }
}

function contrainteTypes(): { value: TypeContrainteAdHoc; label: string }[] {
  return CONTRAINTE_TYPE_VALUES.map((value) => ({ value, label: contrainteTypeLabel(value) }));
}

interface ContrainteDraft {
  id: string;
  type: TypeContrainteAdHoc;
  creneauId: number | '';
  standId: string;
  raison: string;
  animateurIds: string[];
}

export interface AdHocConstraintFormData {
  contrainte: ContrainteAdHoc | null;
}

/**
 * Add/edit dialog for an ad hoc constraint. The backend only exposes POST
 * (create or overwrite by id) and DELETE, so editing always re-saves under
 * the same id — `editingId` here only drives the dialog title and the
 * read-only id field, never a PUT-vs-POST branch.
 */
@Component({
  selector: 'app-ad-hoc-constraint-form-dialog',
  imports: [FormsModule, MatDialogModule, MatFormFieldModule, MatInputModule, MatSelectModule, MatButtonModule, MatIconModule],
  templateUrl: './ad-hoc-constraint-form-dialog.html',
  changeDetection: ChangeDetectionStrategy.OnPush
})
export class AdHocConstraintFormDialog {
  protected readonly types = contrainteTypes();
  protected readonly store = inject(ReferenceDataStore);
  protected readonly jobs = inject(SolverJobService);
  /** Editing is disabled while a solve/analysis runs, to avoid corrupting the data it reads. */
  protected readonly editingLocked = this.jobs.editingLocked;

  protected readonly dialogRef = inject<MatDialogRef<AdHocConstraintFormDialog, boolean>>(MatDialogRef);
  private readonly data = inject<AdHocConstraintFormData>(MAT_DIALOG_DATA);
  private readonly crud = inject(ReferenceCrudService);

  protected readonly editingId = signal<string | null>(this.data.contrainte?.id ?? null);
  protected readonly draft = signal<ContrainteDraft>(toDraft(this.data.contrainte));
  protected readonly formTitle = computed(() => {
    const id = this.editingId();
    return id
      ? $localize`:@@adHoc.form.editTitle:Modifier la contrainte ${id}:id:`
      : $localize`:@@adHoc.form.newTitle:Nouvelle contrainte ad hoc`;
  });

  protected patch(patch: Partial<ContrainteDraft>): void {
    this.draft.update((draft) => ({ ...draft, ...patch }));
  }

  protected async save(): Promise<void> {
    const draft = this.draft();
    const contrainte: ContrainteAdHoc = {
      id: draft.id.trim(),
      type: draft.type,
      animateursConcernes: draft.animateurIds.map((id) => ({ id })),
      creneau: draft.creneauId !== '' ? { id: draft.creneauId } : null,
      stand: draft.standId ? { id: draft.standId } : null,
      raison: draft.raison.trim(),
      creeParUtilisateurId: 'ui'
    };
    // Always POST (create-or-overwrite): the backend has no PUT for this resource.
    if (await this.crud.save('contraintes-ad-hoc', contrainte, null, $localize`:@@adHoc.entityLabel:Contrainte`)) {
      this.dialogRef.close(true);
    }
  }
}

function toDraft(contrainte: ContrainteAdHoc | null): ContrainteDraft {
  if (!contrainte) {
    return { id: '', type: CONTRAINTE_TYPE_VALUES[0], creneauId: '', standId: '', raison: '', animateurIds: [] };
  }
  return {
    id: contrainte.id,
    type: contrainte.type,
    creneauId: contrainte.creneau?.id ?? '',
    standId: contrainte.stand?.id ?? '',
    raison: contrainte.raison ?? '',
    animateurIds: (contrainte.animateursConcernes ?? []).map((animateur) => animateur.id)
  };
}
