import { Component, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatExpansionModule } from '@angular/material/expansion';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatTableModule } from '@angular/material/table';
import { MatTooltipModule } from '@angular/material/tooltip';
import { ReferenceCrudService } from '../../core/reference-crud.service';
import { ReferenceDataStore } from '../../core/reference-data.store';
import { SolverJobService } from '../../core/solver-job.service';
import { Emplacement } from '../../core/models';

interface EmplacementDraft {
  id: string;
  nom: string;
  latitude: number | null;
  longitude: number | null;
}

/** Emplacements CRUD: named, GPS-located places a stand can be tied to. */
@Component({
  selector: 'app-emplacements-page',
  imports: [
    FormsModule,
    MatCardModule,
    MatFormFieldModule,
    MatInputModule,
    MatExpansionModule,
    MatButtonModule,
    MatIconModule,
    MatTableModule,
    MatTooltipModule
  ],
  templateUrl: './emplacements-page.html'
})
export class EmplacementsPage {
  protected readonly columns = ['id', 'nom', 'coordonnees', 'actions'];
  protected readonly store = inject(ReferenceDataStore);
  protected readonly draft = signal<EmplacementDraft>(emptyDraft());
  protected readonly editingId = signal<string | null>(null);
  protected readonly formTitle = computed(() =>
    this.editingId() ? `Edit location ${this.editingId()}` : 'New location'
  );
  protected readonly jobs = inject(SolverJobService);
  /** Editing is disabled while a solve/analysis runs, to avoid corrupting the data it reads. */
  protected readonly editingLocked = computed(() => this.jobs.solverBusy());

  private readonly crud = inject(ReferenceCrudService);

  constructor() {
    void this.crud.reload();
  }

  protected patch(patch: Partial<EmplacementDraft>): void {
    this.draft.update((draft) => ({ ...draft, ...patch }));
  }

  protected coordonneesLabel(emplacement: Emplacement): string {
    if (emplacement.latitude == null || emplacement.longitude == null) {
      return '—';
    }
    return `${emplacement.latitude.toFixed(5)}, ${emplacement.longitude.toFixed(5)}`;
  }

  protected async save(): Promise<void> {
    const draft = this.draft();
    const emplacement: Emplacement = {
      id: draft.id.trim(),
      nom: draft.nom.trim(),
      latitude: draft.latitude === null || draft.latitude === undefined || `${draft.latitude}` === ''
        ? null
        : Number(draft.latitude),
      longitude: draft.longitude === null || draft.longitude === undefined || `${draft.longitude}` === ''
        ? null
        : Number(draft.longitude)
    };
    if (await this.crud.save('emplacements', emplacement, this.editingId(), 'Location')) {
      this.cancel();
    }
  }

  protected edit(emplacement: Emplacement): void {
    this.draft.set({
      id: emplacement.id,
      nom: emplacement.nom ?? '',
      latitude: emplacement.latitude,
      longitude: emplacement.longitude
    });
    this.editingId.set(emplacement.id);
  }

  protected cancel(): void {
    this.draft.set(emptyDraft());
    this.editingId.set(null);
  }

  protected async remove(emplacement: Emplacement): Promise<void> {
    if (await this.crud.remove('emplacements', emplacement.id, 'Location') && this.editingId() === emplacement.id) {
      this.cancel();
    }
  }
}

function emptyDraft(): EmplacementDraft {
  return {
    id: '',
    nom: '',
    latitude: null,
    longitude: null
  };
}
