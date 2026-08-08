import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { MatTooltipModule } from '@angular/material/tooltip';
import { ReferenceCrudService } from '../../core/reference-crud.service';
import { ReferenceDataStore } from '../../core/reference-data.store';
import { SolverJobService } from '../../core/solver-job.service';
import { IndisponibiliteStand, OuvertureStand, Stand } from '../../core/models';

interface StandDraft {
  id: string;
  nom: string;
  effectifMin: number;
  effectifMax: number;
  reserveMajeurs: boolean;
  premium: boolean;
  typologiesProposees: string[];
  emplacementId: string | null;
  indisponibilites: IndisponibiliteStand[];
  ouvertures: OuvertureStand[];
}

export interface StandFormData {
  stand: Stand | null;
}

/** Add/edit dialog for a stand: identity, staffing bounds, adults-only flag and typologies. */
@Component({
  selector: 'app-stand-form-dialog',
  imports: [
    FormsModule,
    MatDialogModule,
    MatFormFieldModule,
    MatInputModule,
    MatSelectModule,
    MatCheckboxModule,
    MatButtonModule,
    MatIconModule,
    MatTooltipModule
  ],
  templateUrl: './stand-form-dialog.html',
  changeDetection: ChangeDetectionStrategy.OnPush
})
export class StandFormDialog {
  protected readonly store = inject(ReferenceDataStore);
  protected readonly jobs = inject(SolverJobService);
  /** Editing is disabled while a solve/analysis runs, to avoid corrupting the data it reads. */
  protected readonly editingLocked = computed(() => this.jobs.solverBusy());

  protected readonly dialogRef = inject<MatDialogRef<StandFormDialog, boolean>>(MatDialogRef);
  private readonly data = inject<StandFormData>(MAT_DIALOG_DATA);
  private readonly crud = inject(ReferenceCrudService);

  protected readonly editingId = signal<string | null>(this.data.stand?.id ?? null);
  protected readonly draft = signal<StandDraft>(toDraft(this.data.stand));
  protected readonly effectifInvalid = computed(() => {
    const draft = this.draft();
    return Number(draft.effectifMax) < Number(draft.effectifMin);
  });
  protected readonly formTitle = computed(() => {
    const id = this.editingId();
    return id ? $localize`:@@stands.form.editTitle:Modifier le stand ${id}:id:` : $localize`:@@stands.form.newTitle:Nouveau stand`;
  });
  protected readonly submitLabel = computed(() =>
    this.editingId() ? $localize`:@@stands.submit.edit:Modifier le stand` : $localize`:@@stands.submit.create:Créer le stand`
  );

  /** Any closure whose end time isn't strictly after its start time — the backend rejects these outright. */
  protected readonly indisponibiliteInvalide = computed(() =>
    this.draft().indisponibilites.some(
      (indispo) => !indispo.date || !indispo.heureDebut || !indispo.heureFin || indispo.heureFin <= indispo.heureDebut
    )
  );

  /** Any opening whose end time isn't strictly after its start time — the backend rejects these outright. */
  protected readonly ouvertureInvalide = computed(() =>
    this.draft().ouvertures.some(
      (ouverture) =>
        !ouverture.date || !ouverture.heureDebut || !ouverture.heureFin || ouverture.heureFin <= ouverture.heureDebut
    )
  );

  /** A day can't carry both a closure and an opening — the backend rejects this outright. */
  protected readonly conflitOuvertureFermeture = computed(() => {
    const draft = this.draft();
    const joursFermeture = new Set(draft.indisponibilites.map((indispo) => indispo.date).filter(Boolean));
    return draft.ouvertures.some((ouverture) => ouverture.date && joursFermeture.has(ouverture.date));
  });

  protected patch(patch: Partial<StandDraft>): void {
    this.draft.update((draft) => ({ ...draft, ...patch }));
  }

  protected ajouterIndisponibilite(): void {
    this.draft.update((draft) => ({
      ...draft,
      indisponibilites: [...draft.indisponibilites, { id: null, date: '', heureDebut: '', heureFin: '', motif: null }]
    }));
  }

  protected patchIndisponibilite(index: number, patch: Partial<IndisponibiliteStand>): void {
    this.draft.update((draft) => ({
      ...draft,
      indisponibilites: draft.indisponibilites.map((indispo, i) => (i === index ? { ...indispo, ...patch } : indispo))
    }));
  }

  protected retirerIndisponibilite(index: number): void {
    this.draft.update((draft) => ({
      ...draft,
      indisponibilites: draft.indisponibilites.filter((_, i) => i !== index)
    }));
  }

  protected ajouterOuverture(): void {
    this.draft.update((draft) => ({
      ...draft,
      ouvertures: [...draft.ouvertures, { id: null, date: '', heureDebut: '', heureFin: '', motif: null }]
    }));
  }

  protected patchOuverture(index: number, patch: Partial<OuvertureStand>): void {
    this.draft.update((draft) => ({
      ...draft,
      ouvertures: draft.ouvertures.map((ouverture, i) => (i === index ? { ...ouverture, ...patch } : ouverture))
    }));
  }

  protected retirerOuverture(index: number): void {
    this.draft.update((draft) => ({
      ...draft,
      ouvertures: draft.ouvertures.filter((_, i) => i !== index)
    }));
  }

  protected async save(): Promise<void> {
    if (
      this.effectifInvalid() ||
      this.indisponibiliteInvalide() ||
      this.ouvertureInvalide() ||
      this.conflitOuvertureFermeture()
    ) {
      return;
    }
    const draft = this.draft();
    const stand: Stand = {
      id: draft.id.trim(),
      nom: draft.nom.trim(),
      typologiesProposees: draft.typologiesProposees,
      effectifMin: Number(draft.effectifMin) || 0,
      effectifMax: Number(draft.effectifMax) || 0,
      reserveMajeurs: draft.reserveMajeurs,
      premium: draft.premium,
      emplacement: draft.emplacementId
        ? (this.store.emplacements().find((e) => e.id === draft.emplacementId) ?? null)
        : null,
      indisponibilites: draft.indisponibilites,
      ouvertures: draft.ouvertures
    };
    if (await this.crud.save('stands', stand, this.editingId(), $localize`:@@stands.entityLabel:Stand`)) {
      this.dialogRef.close(true);
    }
  }
}

function toDraft(stand: Stand | null): StandDraft {
  if (!stand) {
    return {
      id: '',
      nom: '',
      effectifMin: 1,
      effectifMax: 1,
      reserveMajeurs: false,
      premium: false,
      typologiesProposees: [],
      emplacementId: null,
      indisponibilites: [],
      ouvertures: []
    };
  }
  return {
    id: stand.id,
    nom: stand.nom ?? '',
    effectifMin: stand.effectifMin,
    effectifMax: stand.effectifMax,
    reserveMajeurs: Boolean(stand.reserveMajeurs),
    premium: Boolean(stand.premium),
    typologiesProposees: [...(stand.typologiesProposees ?? [])],
    emplacementId: stand.emplacement?.id ?? null,
    indisponibilites: (stand.indisponibilites ?? []).map((indispo) => ({ ...indispo })),
    ouvertures: (stand.ouvertures ?? []).map((ouverture) => ({ ...ouverture }))
  };
}
