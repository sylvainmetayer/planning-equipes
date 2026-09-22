import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { ReferenceCrudService } from '../../core/reference-crud.service';
import { SolverJobService } from '../../core/solver-job.service';
import { Emplacement } from '../../core/models';
import { MapPicker, MapPosition } from '../../shared/map-picker';
import { StatusMessage } from '../../shared/status-message';

interface EmplacementDraft {
  /** Optional business code, the only handle a CSV or a scenario has on this row (ADR 0049). */
  code: string;
  nom: string;
  latitude: number | null;
  longitude: number | null;
  /** The store's `modifieLe` at opening, sent back as the write's precondition (issue #362). */
  modifieLe: string | null;
}

export interface EmplacementFormData {
  emplacement: Emplacement | null;
}

/** Add/edit dialog for an emplacement: identity plus GPS coordinates, set via the map picker or typed directly. */
@Component({
  selector: 'app-emplacement-form-dialog',
  imports: [
    FormsModule,
    MatDialogModule,
    MatFormFieldModule,
    MatInputModule,
    MatButtonModule,
    MatIconModule,
    MapPicker,
    StatusMessage,
  ],
  templateUrl: './emplacement-form-dialog.html',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class EmplacementFormDialog {
  protected readonly jobs = inject(SolverJobService);
  /** Editing is disabled while a solve/analysis runs, to avoid corrupting the data it reads. */
  protected readonly editingLocked = this.jobs.editingLocked;

  protected readonly dialogRef = inject<MatDialogRef<EmplacementFormDialog, boolean>>(MatDialogRef);
  private readonly data = inject<EmplacementFormData>(MAT_DIALOG_DATA);
  private readonly crud = inject(ReferenceCrudService);

  protected readonly editingId = signal<number | null>(this.data.emplacement?.id ?? null);
  protected readonly draft = signal<EmplacementDraft>(toDraft(this.data.emplacement));
  protected readonly formTitle = computed(() => {
    const nom = this.data.emplacement?.nom;
    return this.editingId()
      ? $localize`:@@emplacements.form.editTitle:Modifier l'emplacement ${nom ?? ''}:nom:`
      : $localize`:@@emplacements.form.newTitle:Nouvel emplacement`;
  });
  protected readonly submitLabel = computed(() =>
    this.editingId()
      ? $localize`:@@emplacements.submit.edit:Modifier l'emplacement`
      : $localize`:@@emplacements.submit.create:Créer l'emplacement`,
  );

  protected patch(patch: Partial<EmplacementDraft>): void {
    this.draft.update((draft) => ({ ...draft, ...patch }));
  }

  /** Says out loud what a click on the map just wrote into the two fields. */
  protected readonly messagePosition = signal('');

  protected onPositionChange(position: MapPosition): void {
    this.patch({ latitude: position.latitude, longitude: position.longitude });
    this.messagePosition.set(
      $localize`:@@emplacements.position.set:Position choisie : ${position.latitude.toFixed(5)}:latitude:, ${position.longitude.toFixed(5)}:longitude:`,
    );
  }

  protected async save(): Promise<void> {
    const draft = this.draft();
    const code = draft.code.trim();
    const emplacement: Emplacement = {
      // The server mints the id on a creation and keeps it on an edit; nothing
      // here ever decides it (ADR 0049, D1).
      id: this.editingId() as number,
      code: code === '' ? null : code,
      nom: draft.nom.trim(),
      latitude:
        draft.latitude === null || draft.latitude === undefined || `${draft.latitude}` === ''
          ? null
          : Number(draft.latitude),
      longitude:
        draft.longitude === null || draft.longitude === undefined || `${draft.longitude}` === ''
          ? null
          : Number(draft.longitude),
      modifieLe: draft.modifieLe,
    };
    if (
      await this.crud.save(
        'emplacements',
        emplacement,
        this.editingId(),
        $localize`:@@emplacements.entityLabel:Emplacement`,
        { requireId: false },
      )
    ) {
      this.dialogRef.close(true);
    }
  }
}

function toDraft(emplacement: Emplacement | null): EmplacementDraft {
  if (!emplacement) {
    return { code: '', nom: '', latitude: null, longitude: null, modifieLe: null };
  }
  return {
    code: emplacement.code ?? '',
    nom: emplacement.nom ?? '',
    latitude: emplacement.latitude,
    longitude: emplacement.longitude,
    modifieLe: emplacement.modifieLe ?? null,
  };
}
