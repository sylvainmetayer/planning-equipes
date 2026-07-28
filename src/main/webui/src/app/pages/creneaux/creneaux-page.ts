import { Component, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatTableModule } from '@angular/material/table';
import { MatTooltipModule } from '@angular/material/tooltip';
import { ReferenceCrudService } from '../../core/reference-crud.service';
import { ReferenceDataStore } from '../../core/reference-data.store';
import { Creneau } from '../../core/models';

interface CreneauDraft {
  id: string;
  jour: number;
  date: string;
  heureDebut: string;
  heureFin: string;
}

/** Timeslots CRUD: festival day, date and hours of every schedulable slot. */
@Component({
  selector: 'app-creneaux-page',
  imports: [
    FormsModule,
    MatCardModule,
    MatFormFieldModule,
    MatInputModule,
    MatButtonModule,
    MatIconModule,
    MatTableModule,
    MatTooltipModule
  ],
  templateUrl: './creneaux-page.html'
})
export class CreneauxPage {
  protected readonly columns = ['id', 'jour', 'date', 'heures', 'actions'];
  protected readonly store = inject(ReferenceDataStore);
  protected readonly draft = signal<CreneauDraft>(emptyDraft());
  protected readonly editingId = signal<string | null>(null);
  protected readonly formTitle = computed(() =>
    this.editingId() ? `Edit timeslot ${this.editingId()}` : 'New timeslot'
  );

  private readonly crud = inject(ReferenceCrudService);

  constructor() {
    void this.crud.reload();
  }

  protected patch(patch: Partial<CreneauDraft>): void {
    this.draft.update((draft) => ({ ...draft, ...patch }));
  }

  protected async save(): Promise<void> {
    const draft = this.draft();
    const creneau: Creneau = {
      id: draft.id.trim(),
      jour: Number(draft.jour),
      date: draft.date,
      heureDebut: draft.heureDebut,
      heureFin: draft.heureFin
    };
    if (await this.crud.save('creneaux', creneau, this.editingId(), 'Timeslot')) {
      this.cancel();
    }
  }

  protected edit(creneau: Creneau): void {
    this.draft.set({
      id: creneau.id,
      jour: creneau.jour,
      date: creneau.date ?? '',
      heureDebut: creneau.heureDebut ?? '',
      heureFin: creneau.heureFin ?? ''
    });
    this.editingId.set(creneau.id);
  }

  protected cancel(): void {
    this.draft.set(emptyDraft());
    this.editingId.set(null);
  }

  protected async remove(creneau: Creneau): Promise<void> {
    if (await this.crud.remove('creneaux', creneau.id, 'Timeslot') && this.editingId() === creneau.id) {
      this.cancel();
    }
  }
}

function emptyDraft(): CreneauDraft {
  return { id: '', jour: 1, date: '', heureDebut: '', heureFin: '' };
}
