import { Component, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { MatTableModule } from '@angular/material/table';
import { MatTooltipModule } from '@angular/material/tooltip';
import { ReferenceCrudService } from '../../core/reference-crud.service';
import { ReferenceDataStore } from '../../core/reference-data.store';
import { Stand } from '../../core/models';

interface StandDraft {
  id: string;
  nom: string;
  effectifMin: number;
  effectifMax: number;
  reserveMajeurs: boolean;
  typologiesProposees: string[];
}

/** Stands CRUD: identity, staffing bounds, adults-only flag and typologies. */
@Component({
  selector: 'app-stands-page',
  imports: [
    FormsModule,
    MatCardModule,
    MatFormFieldModule,
    MatInputModule,
    MatSelectModule,
    MatCheckboxModule,
    MatButtonModule,
    MatIconModule,
    MatTableModule,
    MatTooltipModule
  ],
  templateUrl: './stands-page.html'
})
export class StandsPage {
  protected readonly columns = ['id', 'nom', 'effectif', 'typologies', 'actions'];
  protected readonly store = inject(ReferenceDataStore);
  protected readonly draft = signal<StandDraft>(emptyDraft());
  protected readonly editingId = signal<string | null>(null);
  protected readonly formTitle = computed(() =>
    this.editingId() ? `Edit stand ${this.editingId()}` : 'New stand'
  );

  private readonly crud = inject(ReferenceCrudService);

  constructor() {
    void this.crud.reload();
  }

  protected patch(patch: Partial<StandDraft>): void {
    this.draft.update((draft) => ({ ...draft, ...patch }));
  }

  protected typologiesLabel(stand: Stand): string {
    return (stand.typologiesProposees ?? []).join(', ') || '—';
  }

  protected async save(): Promise<void> {
    const draft = this.draft();
    const stand: Stand = {
      id: draft.id.trim(),
      nom: draft.nom.trim(),
      typologiesProposees: draft.typologiesProposees,
      effectifMin: Number(draft.effectifMin) || 0,
      effectifMax: Number(draft.effectifMax) || 0,
      reserveMajeurs: draft.reserveMajeurs
    };
    if (await this.crud.save('stands', stand, this.editingId(), 'Stand')) {
      this.cancel();
    }
  }

  protected edit(stand: Stand): void {
    this.draft.set({
      id: stand.id,
      nom: stand.nom ?? '',
      effectifMin: stand.effectifMin,
      effectifMax: stand.effectifMax,
      reserveMajeurs: Boolean(stand.reserveMajeurs),
      typologiesProposees: [...(stand.typologiesProposees ?? [])]
    });
    this.editingId.set(stand.id);
  }

  protected cancel(): void {
    this.draft.set(emptyDraft());
    this.editingId.set(null);
  }

  protected async remove(stand: Stand): Promise<void> {
    if (await this.crud.remove('stands', stand.id, 'Stand') && this.editingId() === stand.id) {
      this.cancel();
    }
  }
}

function emptyDraft(): StandDraft {
  return { id: '', nom: '', effectifMin: 1, effectifMax: 1, reserveMajeurs: false, typologiesProposees: [] };
}
