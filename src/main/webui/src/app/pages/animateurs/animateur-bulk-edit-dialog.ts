import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { ModeBooleen, ModeListe } from '../../core/bulk-edit';
import { labelAnimateursPluriel } from '../../core/entity-labels';
import { ReferenceCrudService } from '../../core/reference-crud.service';
import { ReferenceDataStore } from '../../core/reference-data.store';
import { SolverJobService } from '../../core/solver-job.service';
import { Animateur, NiveauCompetence } from '../../core/models';
import {
  AnimateurBulkPatch,
  ModeEntree,
  appliquerPatchAnimateur,
  patchAnimateurEstVide,
  patchAnimateurVide,
} from './animateur-bulk-edit';

const NIVEAUX: NiveauCompetence[] = ['DEBUTANT', 'AUTONOME', 'REFERENT'];

export interface AnimateurBulkEditData {
  animateurs: Animateur[];
}

/**
 * Bulk edit of the selected animateurs: appreciation, souhaits, manager flag and
 * unavailable days. Every field defaults to "ne pas modifier", so only what the
 * user explicitly changes is written — the rest keeps each animateur's own
 * value. Identity and birth date are deliberately absent: they are per-person
 * data, and the minor/adult status derives from the birth date.
 */
@Component({
  selector: 'app-animateur-bulk-edit-dialog',
  imports: [
    FormsModule,
    MatDialogModule,
    MatFormFieldModule,
    MatInputModule,
    MatSelectModule,
    MatButtonModule,
    MatIconModule,
  ],
  templateUrl: './animateur-bulk-edit-dialog.html',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class AnimateurBulkEditDialog {
  protected readonly niveaux = NIVEAUX;
  protected readonly store = inject(ReferenceDataStore);
  protected readonly jobs = inject(SolverJobService);
  /** Editing is disabled while a solve/analysis runs, to avoid corrupting the data it reads. */
  protected readonly editingLocked = this.jobs.editingLocked;

  protected readonly dialogRef =
    inject<MatDialogRef<AnimateurBulkEditDialog, boolean>>(MatDialogRef);
  private readonly data = inject<AnimateurBulkEditData>(MAT_DIALOG_DATA);
  private readonly crud = inject(ReferenceCrudService);

  protected readonly patch = signal<AnimateurBulkPatch>(patchAnimateurVide());
  protected readonly rienAModifier = computed(() => patchAnimateurEstVide(this.patch()));
  protected readonly enCours = signal(false);

  protected readonly modesBooleen: { value: ModeBooleen; label: string }[] = [
    { value: 'INCHANGE', label: $localize`:@@bulk.mode.inchange:Ne pas modifier` },
    { value: 'OUI', label: $localize`:@@common.oui:Oui` },
    { value: 'NON', label: $localize`:@@common.non:Non` },
  ];
  protected readonly modesEntree: { value: ModeEntree; label: string }[] = [
    { value: 'AUCUN', label: $localize`:@@bulk.mode.inchange:Ne pas modifier` },
    { value: 'AJOUTER', label: $localize`:@@bulk.mode.ajouter:Ajouter` },
    { value: 'RETIRER', label: $localize`:@@bulk.mode.retirer:Retirer` },
  ];
  protected readonly modesListe: { value: ModeListe; label: string }[] = [
    ...this.modesEntree,
    { value: 'REMPLACER', label: $localize`:@@bulk.mode.remplacer:Remplacer` },
  ];

  protected readonly formTitle = $localize`:@@animateurs.bulk.title:Modifier ${this.data.animateurs.length}:count: animateurs`;

  protected update(patch: Partial<AnimateurBulkPatch>): void {
    this.patch.update((courant) => ({ ...courant, ...patch }));
  }

  protected async save(): Promise<void> {
    if (this.rienAModifier() || this.enCours()) {
      return;
    }
    const patch = this.patch();
    const payloads = this.data.animateurs.map((animateur) =>
      appliquerPatchAnimateur(animateur, patch),
    );
    this.enCours.set(true);
    try {
      if ((await this.crud.saveMany('animateurs', payloads, labelAnimateursPluriel())) > 0) {
        this.dialogRef.close(true);
      }
    } finally {
      this.enCours.set(false);
    }
  }
}
