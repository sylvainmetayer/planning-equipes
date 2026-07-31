import { Component, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MatDialog } from '@angular/material/dialog';
import { MatExpansionModule } from '@angular/material/expansion';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { MatTooltipModule } from '@angular/material/tooltip';
import { ReferenceCrudService } from '../../core/reference-crud.service';
import { ReferenceDataStore } from '../../core/reference-data.store';
import { SolverJobService } from '../../core/solver-job.service';
import { slugify } from '../../core/slug';
import { Creneau, GroupeCreneau } from '../../core/models';
import { CreneauFormData, CreneauFormDialog } from './creneau-form-dialog';

/** Timeslots CRUD: festival day, date and hours of every schedulable slot, organized in switchable "groupes de créneaux" (alternate plannings). */
@Component({
  selector: 'app-creneaux-page',
  imports: [
    FormsModule,
    MatCardModule,
    MatCheckboxModule,
    MatExpansionModule,
    MatFormFieldModule,
    MatInputModule,
    MatSelectModule,
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

  protected readonly nouveauGroupeNom = signal('');
  /** Id of the group currently being activated/created, to disable its row while the request is in flight. */
  protected readonly groupeEnCours = signal<string | null>(null);

  /** `null` = every group shown. */
  protected readonly filtreGroupeId = signal<string | null>(null);

  /**
   * Filtered by the selected group (if any), then sorted by group name so
   * each planning's slots stay together; `store.creneaux()` is already
   * chronological (jour, heureDebut), and the sort below is stable, so slots
   * within a group keep that order.
   */
  protected readonly creneauxAffiches = computed(() => {
    const groupeId = this.filtreGroupeId();
    const creneaux = groupeId ? this.store.creneaux().filter((c) => c.groupe?.id === groupeId) : this.store.creneaux();
    return [...creneaux].sort((a, b) => (a.groupe?.nom ?? a.groupe?.id ?? '').localeCompare(b.groupe?.nom ?? b.groupe?.id ?? ''));
  });

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
    await this.crud.remove('creneaux', creneau.id, $localize`:@@creneaux.entityLabel:Créneau`);
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

  /** Activates a timeslot group; checking one implicitly deactivates every other one, so an already-active row is a no-op. */
  protected async activerGroupe(groupe: GroupeCreneau): Promise<void> {
    if (groupe.actif || this.editingLocked() || this.groupeEnCours()) {
      return;
    }
    this.groupeEnCours.set(groupe.id);
    try {
      await this.store.activerGroupeCreneau(groupe.id);
    } catch (error) {
      this.crud.reportError(error);
    } finally {
      this.groupeEnCours.set(null);
    }
  }

  protected async supprimerGroupe(groupe: GroupeCreneau): Promise<void> {
    await this.crud.remove('groupes-creneaux', groupe.id, $localize`:@@groupesCreneaux.entityLabel:Groupe de créneaux`);
  }

  protected async ajouterGroupe(): Promise<void> {
    const nom = this.nouveauGroupeNom().trim();
    if (!nom) {
      return;
    }
    const id = slugify(
      nom,
      this.store.groupesCreneaux().map((g) => g.id)
    );
    const groupe: GroupeCreneau = { id, nom, actif: false };
    if (
      await this.crud.save(
        'groupes-creneaux',
        groupe,
        null,
        $localize`:@@groupesCreneaux.entityLabel:Groupe de créneaux`
      )
    ) {
      this.nouveauGroupeNom.set('');
    }
  }
}
