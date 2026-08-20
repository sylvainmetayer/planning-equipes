import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { labelCreneauxPluriel } from '../../core/entity-labels';
import { ReferenceCrudService } from '../../core/reference-crud.service';
import { ReferenceDataStore } from '../../core/reference-data.store';
import { SolverJobService } from '../../core/solver-job.service';
import { Creneau } from '../../core/models';
import {
  CreneauBulkPatch,
  appliquerPatchCreneau,
  creneauxAvecHorairesInvalides,
  patchCreneauEstVide,
  patchCreneauVide
} from './creneau-bulk-edit';

export interface CreneauBulkEditData {
  creneaux: Creneau[];
}

/**
 * Bulk edit of the selected créneaux: shift their hours
 * (the main use — building an alternate planning from an existing one), and/or
 * realign their hours. Day and date stay per-slot.
 */
@Component({
  selector: 'app-creneau-bulk-edit-dialog',
  imports: [
    FormsModule,
    MatDialogModule,
    MatFormFieldModule,
    MatInputModule,
    MatSelectModule,
    MatButtonModule,
    MatIconModule
  ],
  templateUrl: './creneau-bulk-edit-dialog.html',
  changeDetection: ChangeDetectionStrategy.OnPush
})
export class CreneauBulkEditDialog {
  protected readonly store = inject(ReferenceDataStore);
  protected readonly jobs = inject(SolverJobService);
  /** Editing is disabled while a solve/analysis runs, to avoid corrupting the data it reads. */
  protected readonly editingLocked = computed(() => this.jobs.editingLocked());

  protected readonly dialogRef = inject<MatDialogRef<CreneauBulkEditDialog, boolean>>(MatDialogRef);
  private readonly data = inject<CreneauBulkEditData>(MAT_DIALOG_DATA);
  private readonly crud = inject(ReferenceCrudService);

  protected readonly patch = signal<CreneauBulkPatch>(patchCreneauVide());
  protected readonly rienAModifier = computed(() => patchCreneauEstVide(this.patch()));
  protected readonly enCours = signal(false);

  /** Slots the patch would leave ending before they start: the batch is blocked as a whole. */
  protected readonly creneauxInvalides = computed(() =>
    creneauxAvecHorairesInvalides(this.data.creneaux, this.patch())
  );

  protected readonly formTitle = $localize`:@@creneaux.bulk.title:Modifier ${this.data.creneaux.length}:count: créneaux`;

  protected update(patch: Partial<CreneauBulkPatch>): void {
    this.patch.update((courant) => ({ ...courant, ...patch }));
  }

  protected async save(): Promise<void> {
    if (this.rienAModifier() || this.creneauxInvalides().length > 0 || this.enCours()) {
      return;
    }
    const patch = this.patch();
    const payloads = this.data.creneaux.map((creneau) => appliquerPatchCreneau(creneau, patch));
    this.enCours.set(true);
    try {
      if ((await this.crud.saveMany('creneaux', payloads, labelCreneauxPluriel())) > 0) {
        this.dialogRef.close(true);
      }
    } finally {
      this.enCours.set(false);
    }
  }
}
