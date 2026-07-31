import { Component, computed, inject } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MatDialog } from '@angular/material/dialog';
import { MatExpansionModule } from '@angular/material/expansion';
import { MatIconModule } from '@angular/material/icon';
import { MatTooltipModule } from '@angular/material/tooltip';
import { ReferenceCrudService } from '../../core/reference-crud.service';
import { ReferenceDataStore } from '../../core/reference-data.store';
import { SolverJobService } from '../../core/solver-job.service';
import { Creneau } from '../../core/models';
import { CreneauFormData, CreneauFormDialog } from './creneau-form-dialog';

/** Timeslots CRUD: festival day, date and hours of every schedulable slot. */
@Component({
  selector: 'app-creneaux-page',
  imports: [
    MatCardModule,
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
  protected readonly jobs = inject(SolverJobService);
  /** Editing is disabled while a solve/analysis runs, to avoid corrupting the data it reads. */
  protected readonly editingLocked = computed(() => this.jobs.solverBusy());

  private readonly crud = inject(ReferenceCrudService);
  private readonly dialog = inject(MatDialog);

  constructor() {
    void this.crud.reload();
  }

  protected openCreate(): void {
    this.openDialog(null);
  }

  protected edit(creneau: Creneau): void {
    this.openDialog(creneau);
  }

  private openDialog(creneau: Creneau | null): void {
    this.dialog.open<CreneauFormDialog, CreneauFormData, boolean>(CreneauFormDialog, {
      data: { creneau },
      width: '36rem',
      maxWidth: '95vw',
      autoFocus: 'first-tabbable'
    });
  }

  protected async remove(creneau: Creneau): Promise<void> {
    await this.crud.remove('creneaux', creneau.id, 'Timeslot');
  }

  /** Empty (or full) list means "every stand is open" — the default. */
  protected isStandOuvert(creneau: Creneau, standId: string): boolean {
    const ids = creneau.standsOuvertsIds ?? [];
    return ids.length === 0 || ids.includes(standId);
  }

  protected standsOuvertsLabel(creneau: Creneau): string {
    const ids = creneau.standsOuvertsIds ?? [];
    if (ids.length === 0) {
      return 'All stands open';
    }
    const closed = this.store.stands().length - ids.length;
    return closed > 0 ? `${closed} stand${closed > 1 ? 's' : ''} closed` : 'All stands open';
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
    await this.crud.save('creneaux', { ...creneau, standsOuvertsIds }, creneau.id, 'Timeslot');
  }
}
