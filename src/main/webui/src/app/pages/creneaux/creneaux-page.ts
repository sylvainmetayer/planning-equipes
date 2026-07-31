import { Component, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MatExpansionModule } from '@angular/material/expansion';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatTooltipModule } from '@angular/material/tooltip';
import { ReferenceCrudService } from '../../core/reference-crud.service';
import { ReferenceDataStore } from '../../core/reference-data.store';
import { SolverJobService } from '../../core/solver-job.service';
import { Creneau } from '../../core/models';

interface CreneauDraft {
  id: string;
  jour: number;
  date: string;
  heureDebut: string;
  heureFin: string;
  standsOuvertsIds: string[];
}

/** Timeslots CRUD: festival day, date and hours of every schedulable slot. */
@Component({
  selector: 'app-creneaux-page',
  imports: [
    FormsModule,
    MatCardModule,
    MatFormFieldModule,
    MatInputModule,
    MatCheckboxModule,
    MatExpansionModule,
    MatButtonModule,
    MatIconModule,
    MatTooltipModule
  ],
  templateUrl: './creneaux-page.html'
})
export class CreneauxPage {
  protected readonly store = inject(ReferenceDataStore);
  protected readonly draft = signal<CreneauDraft>(emptyDraft());
  protected readonly editingId = signal<string | null>(null);
  protected readonly formTitle = computed(() => {
    const id = this.editingId();
    return id
      ? $localize`:@@creneaux.form.editTitle:Modifier le créneau ${id}:id:`
      : $localize`:@@creneaux.form.newTitle:Nouveau créneau`;
  });
  protected readonly submitLabel = computed(() =>
    this.editingId()
      ? $localize`:@@creneaux.submit.edit:Modifier le créneau`
      : $localize`:@@creneaux.submit.create:Créer le créneau`
  );
  protected readonly jobs = inject(SolverJobService);
  /** Editing is disabled while a solve/analysis runs, to avoid corrupting the data it reads. */
  protected readonly editingLocked = computed(() => this.jobs.solverBusy());

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
      heureFin: draft.heureFin,
      standsOuvertsIds: draft.standsOuvertsIds
    };
    if (await this.crud.save('creneaux', creneau, this.editingId(), $localize`:@@creneaux.entityLabel:Créneau`)) {
      this.cancel();
    }
  }

  protected edit(creneau: Creneau): void {
    this.draft.set({
      id: creneau.id,
      jour: creneau.jour,
      date: creneau.date ?? '',
      heureDebut: creneau.heureDebut ?? '',
      heureFin: creneau.heureFin ?? '',
      standsOuvertsIds: [...(creneau.standsOuvertsIds ?? [])]
    });
    this.editingId.set(creneau.id);
  }

  protected cancel(): void {
    this.draft.set(emptyDraft());
    this.editingId.set(null);
  }

  protected async remove(creneau: Creneau): Promise<void> {
    if (await this.crud.remove('creneaux', creneau.id, $localize`:@@creneaux.entityLabel:Créneau`) && this.editingId() === creneau.id) {
      this.cancel();
    }
  }

  /** Empty (or full) list means "every stand is open" — the default. */
  protected isStandOuvert(creneau: Creneau, standId: string): boolean {
    const ids = creneau.standsOuvertsIds ?? [];
    return ids.length === 0 || ids.includes(standId);
  }

  protected standsOuvertsLabel(creneau: Creneau): string {
    const ids = creneau.standsOuvertsIds ?? [];
    if (ids.length === 0) {
      return $localize`:@@creneaux.allOpen:Tous les stands sont ouverts`;
    }
    const closed = this.store.stands().length - ids.length;
    if (closed <= 0) {
      return $localize`:@@creneaux.allOpen:Tous les stands sont ouverts`;
    }
    return closed === 1
      ? $localize`:@@creneaux.closedOne:${closed}:count: stand fermé`
      : $localize`:@@creneaux.closedMany:${closed}:count: stands fermés`;
  }

  /** Toggling saves immediately: this checklist edits persisted state directly, not the draft form. */
  protected async toggleStandOuvert(creneau: Creneau, standId: string, checked: boolean): Promise<void> {
    if (this.editingLocked()) {
      return;
    }
    const allIds = this.store.stands().map((stand) => stand.id);
    const current = creneau.standsOuvertsIds && creneau.standsOuvertsIds.length > 0 ? creneau.standsOuvertsIds : allIds;
    const next = checked ? Array.from(new Set([...current, standId])) : current.filter((id) => id !== standId);
    const standsOuvertsIds = next.length >= allIds.length ? [] : next;
    await this.crud.save('creneaux', { ...creneau, standsOuvertsIds }, creneau.id, $localize`:@@creneaux.entityLabel:Créneau`);
  }
}

function emptyDraft(): CreneauDraft {
  return { id: '', jour: 1, date: '', heureDebut: '', heureFin: '', standsOuvertsIds: [] };
}
