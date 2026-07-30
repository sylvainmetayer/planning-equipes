import { Component, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatExpansionModule } from '@angular/material/expansion';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { MatTableModule } from '@angular/material/table';
import { MatTooltipModule } from '@angular/material/tooltip';
import { ReferenceCrudService } from '../../core/reference-crud.service';
import { ReferenceDataStore } from '../../core/reference-data.store';
import { SolverJobService } from '../../core/solver-job.service';
import { ContrainteAdHoc, TypeContrainteAdHoc } from '../../core/models';

const CONTRAINTE_TYPES: { value: TypeContrainteAdHoc; label: string }[] = [
  { value: 'INDISPONIBILITE_FORCEE', label: 'Forced unavailability' },
  { value: 'INCOMPATIBILITE', label: 'Incompatibility' },
  { value: 'AFFECTATION_FORCEE', label: 'Forced assignment' }
];

interface ContrainteDraft {
  id: string;
  type: TypeContrainteAdHoc;
  creneauId: string;
  standId: string;
  raison: string;
  animateurIds: string[];
}

/**
 * Ad hoc constraints CRUD. They are evaluated as hard constraints by the
 * solver. The backend only exposes POST (create or overwrite by id) and
 * DELETE, so an edit is always saved as a creation.
 */
@Component({
  selector: 'app-ad-hoc-constraints-page',
  imports: [
    FormsModule,
    MatCardModule,
    MatFormFieldModule,
    MatInputModule,
    MatSelectModule,
    MatExpansionModule,
    MatButtonModule,
    MatIconModule,
    MatTableModule,
    MatTooltipModule
  ],
  templateUrl: './ad-hoc-constraints-page.html'
})
export class AdHocConstraintsPage {
  protected readonly types = CONTRAINTE_TYPES;
  protected readonly columns = ['id', 'type', 'animateurs', 'portee', 'raison', 'actions'];

  protected readonly store = inject(ReferenceDataStore);
  protected readonly draft = signal<ContrainteDraft>(emptyDraft());
  protected readonly editingId = signal<string | null>(null);
  protected readonly formTitle = computed(() =>
    this.editingId() ? `Edit constraint ${this.editingId()}` : 'New ad hoc constraint'
  );
  protected readonly jobs = inject(SolverJobService);
  /** Editing is disabled while a solve/analysis runs, to avoid corrupting the data it reads. */
  protected readonly editingLocked = computed(() => this.jobs.solverBusy());

  private readonly crud = inject(ReferenceCrudService);

  constructor() {
    void this.crud.reload();
  }

  protected patch(patch: Partial<ContrainteDraft>): void {
    this.draft.update((draft) => ({ ...draft, ...patch }));
  }

  protected animateursLabel(contrainte: ContrainteAdHoc): string {
    const ids = (contrainte.animateursConcernes ?? []).map((animateur) => animateur.id);
    return ids.length ? ids.join(', ') : '—';
  }

  protected porteeLabel(contrainte: ContrainteAdHoc): string {
    const scope = [
      contrainte.creneau ? `slot ${contrainte.creneau.id}` : '',
      contrainte.stand ? `stand ${contrainte.stand.id}` : ''
    ]
      .filter(Boolean)
      .join(' · ');
    return scope || '—';
  }

  protected async save(): Promise<void> {
    const draft = this.draft();
    const contrainte: ContrainteAdHoc = {
      id: draft.id.trim(),
      type: draft.type,
      animateursConcernes: draft.animateurIds.map((id) => ({ id })),
      creneau: draft.creneauId ? { id: draft.creneauId } : null,
      stand: draft.standId ? { id: draft.standId } : null,
      raison: draft.raison.trim(),
      creeParUtilisateurId: 'ui'
    };
    if (await this.crud.save('contraintes-ad-hoc', contrainte, null, 'Constraint')) {
      this.cancel();
    }
  }

  protected edit(contrainte: ContrainteAdHoc): void {
    this.draft.set({
      id: contrainte.id,
      type: contrainte.type,
      creneauId: contrainte.creneau?.id ?? '',
      standId: contrainte.stand?.id ?? '',
      raison: contrainte.raison ?? '',
      animateurIds: (contrainte.animateursConcernes ?? []).map((animateur) => animateur.id)
    });
    this.editingId.set(contrainte.id);
  }

  protected cancel(): void {
    this.draft.set(emptyDraft());
    this.editingId.set(null);
  }

  protected async remove(contrainte: ContrainteAdHoc): Promise<void> {
    if (
      (await this.crud.remove('contraintes-ad-hoc', contrainte.id, 'Constraint')) &&
      this.editingId() === contrainte.id
    ) {
      this.cancel();
    }
  }
}

function emptyDraft(): ContrainteDraft {
  return {
    id: '',
    type: CONTRAINTE_TYPES[0].value,
    creneauId: '',
    standId: '',
    raison: '',
    animateurIds: []
  };
}
