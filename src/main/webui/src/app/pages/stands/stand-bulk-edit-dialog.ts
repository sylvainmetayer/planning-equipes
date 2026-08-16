import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { ModeBooleen, ModeListe } from '../../core/bulk-edit';
import { labelStandsPluriel } from '../../core/entity-labels';
import { ReferenceCrudService } from '../../core/reference-crud.service';
import { ReferenceDataStore } from '../../core/reference-data.store';
import { SolverJobService } from '../../core/solver-job.service';
import { NiveauEffort, Stand } from '../../core/models';
import {
  ModeEmplacement,
  StandBulkPatch,
  appliquerPatchStand,
  patchStandEstVide,
  patchStandVide,
  standsAvecEffectifInvalide
} from './stand-bulk-edit';

export interface StandBulkEditData {
  stands: Stand[];
}

/**
 * Bulk edit of the selected stands: emplacement (the GPS-located place),
 * typologies proposées, staffing bounds and the premium/majeurs/effort flags.
 * Every field defaults to "ne pas modifier", so only what the user explicitly
 * changes is written. Closures and openings stay out: they are per-stand data.
 */
@Component({
  selector: 'app-stand-bulk-edit-dialog',
  imports: [
    FormsModule,
    MatDialogModule,
    MatFormFieldModule,
    MatInputModule,
    MatSelectModule,
    MatButtonModule,
    MatIconModule
  ],
  templateUrl: './stand-bulk-edit-dialog.html',
  changeDetection: ChangeDetectionStrategy.OnPush
})
export class StandBulkEditDialog {
  protected readonly store = inject(ReferenceDataStore);
  protected readonly jobs = inject(SolverJobService);
  /** Editing is disabled while a solve/analysis runs, to avoid corrupting the data it reads. */
  protected readonly editingLocked = computed(() => this.jobs.solverBusy());

  protected readonly dialogRef = inject<MatDialogRef<StandBulkEditDialog, boolean>>(MatDialogRef);
  private readonly data = inject<StandBulkEditData>(MAT_DIALOG_DATA);
  private readonly crud = inject(ReferenceCrudService);

  protected readonly patch = signal<StandBulkPatch>(patchStandVide());
  protected readonly rienAModifier = computed(() => patchStandEstVide(this.patch()));
  protected readonly enCours = signal(false);

  /** Stands the patch would leave with `effectifMax < effectifMin`: the batch is blocked as a whole. */
  protected readonly standsInvalides = computed(() =>
    standsAvecEffectifInvalide(this.data.stands, this.patch(), this.store.emplacements())
  );
  protected readonly effectifInvalideMessage = computed(() => {
    const noms = this.standsInvalides()
      .map((stand) => stand.nom || stand.id)
      .join(', ');
    return $localize`:@@stands.bulk.effectifInvalide:Effectif maximum inférieur au minimum pour : ${noms}:stands:`;
  });

  protected readonly modesBooleen: { value: ModeBooleen; label: string }[] = [
    { value: 'INCHANGE', label: $localize`:@@bulk.mode.inchange:Ne pas modifier` },
    { value: 'OUI', label: $localize`:@@common.oui:Oui` },
    { value: 'NON', label: $localize`:@@common.non:Non` }
  ];
  protected readonly modesListe: { value: ModeListe; label: string }[] = [
    { value: 'AUCUN', label: $localize`:@@bulk.mode.inchange:Ne pas modifier` },
    { value: 'AJOUTER', label: $localize`:@@bulk.mode.ajouter:Ajouter` },
    { value: 'RETIRER', label: $localize`:@@bulk.mode.retirer:Retirer` },
    { value: 'REMPLACER', label: $localize`:@@bulk.mode.remplacer:Remplacer` }
  ];
  protected readonly modesEmplacement: { value: ModeEmplacement; label: string }[] = [
    { value: 'INCHANGE', label: $localize`:@@bulk.mode.inchange:Ne pas modifier` },
    { value: 'DEFINIR', label: $localize`:@@bulk.mode.definir:Définir` },
    { value: 'EFFACER', label: $localize`:@@bulk.mode.effacer:Effacer` }
  ];
  protected readonly niveauxEffort: { value: 'INCHANGE' | NiveauEffort; label: string }[] = [
    { value: 'INCHANGE', label: $localize`:@@bulk.mode.inchange:Ne pas modifier` },
    { value: 'NORMAL', label: 'NORMAL' },
    { value: 'EPUISANT', label: 'EPUISANT' }
  ];

  protected readonly formTitle = $localize`:@@stands.bulk.title:Modifier ${this.data.stands.length}:count: stands`;

  protected update(patch: Partial<StandBulkPatch>): void {
    this.patch.update((courant) => ({ ...courant, ...patch }));
  }

  /** An emptied number field means "ne pas modifier", not zero. */
  protected updateEffectif(champ: 'effectifMin' | 'effectifMax', valeur: unknown): void {
    const nombre = valeur === '' || valeur === null || valeur === undefined ? null : Number(valeur);
    this.update({ [champ]: nombre === null || Number.isNaN(nombre) ? null : nombre } as Partial<StandBulkPatch>);
  }

  protected async save(): Promise<void> {
    if (this.rienAModifier() || this.standsInvalides().length > 0 || this.enCours()) {
      return;
    }
    const patch = this.patch();
    const emplacements = this.store.emplacements();
    const payloads = this.data.stands.map((stand) => appliquerPatchStand(stand, patch, emplacements));
    this.enCours.set(true);
    try {
      if ((await this.crud.saveMany('stands', payloads, labelStandsPluriel())) > 0) {
        this.dialogRef.close(true);
      }
    } finally {
      this.enCours.set(false);
    }
  }
}
