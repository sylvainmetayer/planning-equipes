import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MatDialog } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { MatTableModule } from '@angular/material/table';
import { MatTooltipModule } from '@angular/material/tooltip';
import { labelCreneauxPluriel } from '../../core/entity-labels';
import { PlanningResolutionStore } from '../../core/planning-resolution.store';
import { ProblemesStore } from '../../core/problemes.store';
import { ReferenceCrudService } from '../../core/reference-crud.service';
import { ReferenceDataStore } from '../../core/reference-data.store';
import { SolverJobService } from '../../core/solver-job.service';
import { TableSelection } from '../../core/table-selection';
import { slugify } from '../../core/slug';
import { CauseInfaisabilite, Creneau, GroupeCreneau } from '../../core/models';
import { BulkActionsBar } from '../../shared/bulk-actions-bar';
import { CreneauBulkEditData, CreneauBulkEditDialog } from './creneau-bulk-edit-dialog';
import { CreneauFormData, CreneauFormDialog } from './creneau-form-dialog';

/**
 * Timeslots CRUD: festival day, date and hours of every schedulable slot,
 * organized in switchable "groupes de créneaux" (alternate plannings).
 *
 * Slots are multi-selectable, for a bulk delete or to move a whole batch to
 * another group / realign its hours.
 */
@Component({
  selector: 'app-creneaux-page',
  imports: [
    FormsModule,
    MatCardModule,
    MatCheckboxModule,
    MatFormFieldModule,
    MatInputModule,
    MatSelectModule,
    MatTableModule,
    MatButtonModule,
    MatIconModule,
    MatTooltipModule,
    BulkActionsBar
  ],
  templateUrl: './creneaux-page.html',
  changeDetection: ChangeDetectionStrategy.OnPush
})
export class CreneauxPage {
  protected readonly store = inject(ReferenceDataStore);
  protected readonly jobs = inject(SolverJobService);
  /** Editing is disabled while a solve/analysis runs, to avoid corrupting the data it reads. */
  protected readonly editingLocked = computed(() => this.jobs.solverBusy());

  private readonly problemes = inject(ProblemesStore);
  private readonly crud = inject(ReferenceCrudService);
  private readonly dialog = inject(MatDialog);
  private readonly resolution = inject(PlanningResolutionStore);

  protected readonly nouveauGroupeNom = signal('');
  /** Id of the group currently being activated/created, to disable its row while the request is in flight. */
  protected readonly groupeEnCours = signal<string | null>(null);

  /** `null` = every group shown. */
  protected readonly filtreGroupeId = signal<string | null>(null);

  protected readonly columns = ['select', 'jour', 'date', 'horaires', 'groupe', 'actions'];

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

  /** Keyed on the displayed slots, so the group filter also narrows "tout sélectionner". */
  protected readonly selection = new TableSelection<number>(
    computed(() => this.creneauxAffiches().map((creneau) => creneau.id))
  );

  /**
   * Créneau id → the feasibility cause naming it, re-keyed on the numeric id so
   * a row is a plain map lookup. The report carries `creneauId` as a string
   * while `Creneau.id` is a number, hence the `String(...)` normalisation here
   * rather than in every template.
   */
  protected readonly causeParCreneau = computed<Map<number, CauseInfaisabilite>>(() => {
    const parId = this.problemes.causeParCreneauId();
    const index = new Map<number, CauseInfaisabilite>();
    if (parId.size === 0) {
      return index;
    }
    for (const creneau of this.store.creneaux()) {
      const cause = parId.get(String(creneau.id));
      if (cause) {
        index.set(creneau.id, cause);
      }
    }
    return index;
  });

  constructor() {
    void this.crud.reload();
    void this.problemes.reloadFeasibility();
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

  protected async removeSelection(): Promise<void> {
    await this.crud.removeMany('creneaux', this.selection.selectedIds(), labelCreneauxPluriel());
  }

  protected editSelection(): void {
    const selectionnes = new Set(this.selection.selectedIds());
    this.dialog.open<CreneauBulkEditDialog, CreneauBulkEditData, boolean>(CreneauBulkEditDialog, {
      data: { creneaux: this.store.creneaux().filter((creneau) => selectionnes.has(creneau.id)) },
      width: '40rem',
      maxWidth: '95vw',
      autoFocus: 'first-tabbable'
    });
  }

  /** Activates a timeslot group; checking one implicitly deactivates every other one, so an already-active row is a no-op. */
  protected async activerGroupe(groupe: GroupeCreneau): Promise<void> {
    if (groupe.actif || this.editingLocked() || this.groupeEnCours()) {
      return;
    }
    this.groupeEnCours.set(groupe.id);
    try {
      await this.store.activerGroupeCreneau(groupe.id);
      // The mismatch banner compares against the active group: refresh it
      // right away instead of waiting for the next solve.
      void this.resolution.reload();
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
