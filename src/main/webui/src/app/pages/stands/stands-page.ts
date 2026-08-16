import { ChangeDetectionStrategy, Component, computed, inject } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MatDialog } from '@angular/material/dialog';
import { MatIconModule } from '@angular/material/icon';
import { MatTableModule } from '@angular/material/table';
import { MatTooltipModule } from '@angular/material/tooltip';
import { labelStandsPluriel } from '../../core/entity-labels';
import { ProblemesStore } from '../../core/problemes.store';
import { ReferenceCrudService } from '../../core/reference-crud.service';
import { ReferenceDataStore } from '../../core/reference-data.store';
import { SolverJobService } from '../../core/solver-job.service';
import { TableSelection } from '../../core/table-selection';
import { Stand } from '../../core/models';
import { BulkActionsBar } from '../../shared/bulk-actions-bar';
import { StandBulkEditData, StandBulkEditDialog } from './stand-bulk-edit-dialog';
import { StandFormData, StandFormDialog } from './stand-form-dialog';

/**
 * Stands CRUD: identity, staffing bounds, adults-only flag and typologies.
 *
 * Rows named by a feasibility cause carry an alert icon whose tooltip is the
 * cause's own message, so a stand nobody can staff is visible where it is
 * edited, not only on the Problèmes page.
 *
 * Rows are multi-selectable, for a bulk delete or a bulk edit of the fields
 * stands share (emplacement, typologies, effectif, indicateurs).
 */
@Component({
  selector: 'app-stands-page',
  imports: [
    MatCardModule,
    MatButtonModule,
    MatCheckboxModule,
    MatIconModule,
    MatTableModule,
    MatTooltipModule,
    BulkActionsBar
  ],
  templateUrl: './stands-page.html',
  changeDetection: ChangeDetectionStrategy.OnPush
})
export class StandsPage {
  protected readonly columns = ['select', 'id', 'nom', 'effectif', 'typologies', 'emplacement', 'fermetures', 'ouvertures', 'actions'];
  protected readonly store = inject(ReferenceDataStore);
  protected readonly jobs = inject(SolverJobService);
  /** Editing is disabled while a solve/analysis runs, to avoid corrupting the data it reads. */
  protected readonly editingLocked = computed(() => this.jobs.solverBusy());

  protected readonly selection = new TableSelection<string>(
    computed(() => this.store.stands().map((stand) => stand.id))
  );

  /** Holds `causeParStandId`: a memoised map, so each row only does a lookup. */
  protected readonly problemes = inject(ProblemesStore);

  private readonly crud = inject(ReferenceCrudService);
  private readonly dialog = inject(MatDialog);

  constructor() {
    void this.crud.reload();
    void this.problemes.reloadFeasibility();
  }

  protected typologiesLabel(stand: Stand): string {
    return (stand.typologiesProposees ?? []).join(', ') || '—';
  }

  protected effectifSuffix(stand: Stand): string {
    const majeurs = stand.reserveMajeurs ? $localize`:@@stands.suffix.majeurs: · majeurs` : '';
    const premium = stand.premium ? $localize`:@@stands.suffix.premium: · premium` : '';
    const epuisant = stand.niveauEffort === 'EPUISANT' ? $localize`:@@stands.suffix.epuisant: · épuisant` : '';
    return `${majeurs}${premium}${epuisant}`;
  }

  protected emplacementLabel(stand: Stand): string {
    return stand.emplacement?.nom || '—';
  }

  protected openCreate(): void {
    this.openDialog(null);
  }

  protected edit(stand: Stand): void {
    this.openDialog(stand);
  }

  private openDialog(stand: Stand | null): void {
    this.dialog.open<StandFormDialog, StandFormData, boolean>(StandFormDialog, {
      data: { stand },
      width: '40rem',
      maxWidth: '95vw',
      autoFocus: 'first-tabbable'
    });
  }

  protected async remove(stand: Stand): Promise<void> {
    await this.crud.remove('stands', stand.id, $localize`:@@stands.entityLabel:Stand`);
  }

  protected async removeSelection(): Promise<void> {
    await this.crud.removeMany('stands', this.selection.selectedIds(), labelStandsPluriel());
  }

  protected editSelection(): void {
    const selectionnes = new Set(this.selection.selectedIds());
    this.dialog.open<StandBulkEditDialog, StandBulkEditData, boolean>(StandBulkEditDialog, {
      data: { stands: this.store.stands().filter((stand) => selectionnes.has(stand.id)) },
      width: '48rem',
      maxWidth: '95vw',
      autoFocus: 'first-tabbable'
    });
  }
}
