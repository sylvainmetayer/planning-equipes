import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { labelEmplacementsPluriel } from '../../core/entity-labels';
import { ReferenceCrudService } from '../../core/reference-crud.service';
import { SolverJobService } from '../../core/solver-job.service';
import { Emplacement } from '../../core/models';
import { MapPicker, MapPosition } from '../../shared/map-picker';
import {
  EmplacementBulkPatch,
  ModeCoordonnees,
  appliquerPatchEmplacement,
  patchEmplacementEstVide,
  patchEmplacementVide
} from './emplacement-bulk-edit';

export interface EmplacementBulkEditData {
  emplacements: Emplacement[];
}

/**
 * Bulk edit of the selected emplacements: the GPS point, typed or picked on the
 * map — for the places that share one (several rooms of the same building), or
 * to clear coordinates imported wrong. Names stay per-place.
 */
@Component({
  selector: 'app-emplacement-bulk-edit-dialog',
  imports: [
    FormsModule,
    MatDialogModule,
    MatFormFieldModule,
    MatInputModule,
    MatSelectModule,
    MatButtonModule,
    MatIconModule,
    MapPicker
  ],
  templateUrl: './emplacement-bulk-edit-dialog.html',
  changeDetection: ChangeDetectionStrategy.OnPush
})
export class EmplacementBulkEditDialog {
  protected readonly jobs = inject(SolverJobService);
  /** Editing is disabled while a solve/analysis runs, to avoid corrupting the data it reads. */
  protected readonly editingLocked = computed(() => this.jobs.solverBusy());

  protected readonly dialogRef = inject<MatDialogRef<EmplacementBulkEditDialog, boolean>>(MatDialogRef);
  private readonly data = inject<EmplacementBulkEditData>(MAT_DIALOG_DATA);
  private readonly crud = inject(ReferenceCrudService);

  protected readonly patch = signal<EmplacementBulkPatch>(patchEmplacementVide());
  protected readonly rienAModifier = computed(() => patchEmplacementEstVide(this.patch()));
  protected readonly enCours = signal(false);

  protected readonly modesCoordonnees: { value: ModeCoordonnees; label: string }[] = [
    { value: 'INCHANGE', label: $localize`:@@bulk.mode.inchange:Ne pas modifier` },
    { value: 'DEFINIR', label: $localize`:@@bulk.mode.definir:Définir` },
    { value: 'EFFACER', label: $localize`:@@bulk.mode.effacer:Effacer` }
  ];

  protected readonly formTitle = $localize`:@@emplacements.bulk.title:Modifier ${this.data.emplacements.length}:count: emplacements`;

  protected updateCoordonnees(patch: Partial<EmplacementBulkPatch['coordonnees']>): void {
    this.patch.update((courant) => ({ coordonnees: { ...courant.coordonnees, ...patch } }));
  }

  /** An emptied number field means "pas de valeur", not zero (a valid latitude). */
  protected updateNombre(champ: 'latitude' | 'longitude', valeur: unknown): void {
    const nombre = valeur === '' || valeur === null || valeur === undefined ? null : Number(valeur);
    this.updateCoordonnees({ [champ]: nombre === null || Number.isNaN(nombre) ? null : nombre });
  }

  protected onPositionChange(position: MapPosition): void {
    this.updateCoordonnees({ mode: 'DEFINIR', latitude: position.latitude, longitude: position.longitude });
  }

  protected async save(): Promise<void> {
    if (this.rienAModifier() || this.enCours()) {
      return;
    }
    const patch = this.patch();
    const payloads = this.data.emplacements.map((emplacement) => appliquerPatchEmplacement(emplacement, patch));
    this.enCours.set(true);
    try {
      if ((await this.crud.saveMany('emplacements', payloads, labelEmplacementsPluriel())) > 0) {
        this.dialogRef.close(true);
      }
    } finally {
      this.enCours.set(false);
    }
  }
}
