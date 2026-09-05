import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { MatTooltipModule } from '@angular/material/tooltip';
import { ModeBooleen, ModeListe } from '../../core/bulk-edit';
import { labelStandsPluriel } from '../../core/entity-labels';
import { HoraireDraft } from './stand-draft';
import { HoraireReglesEditor } from './horaire-regles-editor';
import { effectifDepuisSaisie, premiereErreurHoraire } from './stand-horaires';
import { ReferenceCrudService } from '../../core/reference-crud.service';
import { ReferenceDataStore } from '../../core/reference-data.store';
import { SolverJobService } from '../../core/solver-job.service';
import { NiveauEffort, Stand } from '../../core/models';
import {
  ModeEmplacement,
  ModeHoraires,
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
 * changes is written.
 *
 * Recurring horaires are in scope — they are exactly the kind of thing a whole
 * set of stands shares ("open from 14:00 to closing, every day" covers thirty of
 * them on the reference event). Dated exceptions stay out: those are
 * per-stand, per-day data by nature.
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
    MatIconModule,
    MatTooltipModule,
    HoraireReglesEditor
  ],
  templateUrl: './stand-bulk-edit-dialog.html',
  changeDetection: ChangeDetectionStrategy.OnPush
})
export class StandBulkEditDialog {
  protected readonly store = inject(ReferenceDataStore);
  protected readonly jobs = inject(SolverJobService);
  /** Editing is disabled while a solve/analysis runs, to avoid corrupting the data it reads. */
  protected readonly editingLocked = this.jobs.editingLocked;

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
  protected readonly modesHoraires: { value: ModeHoraires; label: string }[] = [
    { value: 'INCHANGE', label: $localize`:@@bulk.mode.inchange:Ne pas modifier` },
    { value: 'AJOUTER', label: $localize`:@@bulk.mode.ajouter:Ajouter` },
    { value: 'REMPLACER', label: $localize`:@@bulk.mode.remplacer:Remplacer` },
    { value: 'EFFACER', label: $localize`:@@bulk.mode.effacer:Effacer` }
  ];
  /** First problem among the rules being applied, or `null` — same check as the single-stand form. */
  protected readonly erreurHoraires = computed(() => {
    const patch = this.patch().horaires;
    if (patch.mode === 'INCHANGE' || patch.mode === 'EFFACER') {
      return null;
    }
    return premiereErreurHoraire(patch.horaires);
  });

  protected updateModeHoraires(mode: ModeHoraires): void {
    this.update({ horaires: { ...this.patch().horaires, mode } });
  }

  /** The rules as the shared editor hands them back — the whole list, every time. */
  protected remplacerHoraires(horaires: HoraireDraft[]): void {
    this.update({ horaires: { ...this.patch().horaires, horaires } });
  }

  protected readonly formTitle = $localize`:@@stands.bulk.title:Modifier ${this.data.stands.length}:count: stands`;

  protected update(patch: Partial<StandBulkPatch>): void {
    this.patch.update((courant) => ({ ...courant, ...patch }));
  }

  /** An emptied number field means "ne pas modifier", not zero. */
  protected updateEffectif(champ: 'effectifMin' | 'effectifMax', valeur: unknown): void {
    this.update({ [champ]: effectifDepuisSaisie(valeur) } as Partial<StandBulkPatch>);
  }

  protected async save(): Promise<void> {
    if (this.rienAModifier() || this.standsInvalides().length > 0 || this.enCours() || this.erreurHoraires()) {
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
