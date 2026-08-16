import { ChangeDetectionStrategy, Component, computed, inject } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MatDialog } from '@angular/material/dialog';
import { MatIconModule } from '@angular/material/icon';
import { MatTableModule } from '@angular/material/table';
import { MatTooltipModule } from '@angular/material/tooltip';
import { labelEmplacementsPluriel } from '../../core/entity-labels';
import { ReferenceCrudService } from '../../core/reference-crud.service';
import { ReferenceDataStore } from '../../core/reference-data.store';
import { SolverJobService } from '../../core/solver-job.service';
import { TableSelection } from '../../core/table-selection';
import { Emplacement } from '../../core/models';
import { BulkActionsBar } from '../../shared/bulk-actions-bar';
import { EmplacementBulkEditData, EmplacementBulkEditDialog } from './emplacement-bulk-edit-dialog';
import { EmplacementFormData, EmplacementFormDialog } from './emplacement-form-dialog';

/**
 * Emplacements CRUD: named, GPS-located places a stand can be tied to.
 *
 * Rows are multi-selectable, for a bulk delete or to put several places on the
 * same GPS point at once.
 */
@Component({
  selector: 'app-emplacements-page',
  imports: [
    MatCardModule,
    MatButtonModule,
    MatCheckboxModule,
    MatIconModule,
    MatTableModule,
    MatTooltipModule,
    BulkActionsBar
  ],
  templateUrl: './emplacements-page.html',
  changeDetection: ChangeDetectionStrategy.OnPush
})
export class EmplacementsPage {
  protected readonly columns = ['select', 'id', 'nom', 'coordonnees', 'actions'];
  protected readonly store = inject(ReferenceDataStore);
  protected readonly jobs = inject(SolverJobService);
  /** Editing is disabled while a solve/analysis runs, to avoid corrupting the data it reads. */
  protected readonly editingLocked = computed(() => this.jobs.solverBusy());

  protected readonly selection = new TableSelection<string>(
    computed(() => this.store.emplacements().map((emplacement) => emplacement.id))
  );

  private readonly crud = inject(ReferenceCrudService);
  private readonly dialog = inject(MatDialog);

  constructor() {
    void this.crud.reload();
  }

  protected coordonneesLabel(emplacement: Emplacement): string {
    if (emplacement.latitude == null || emplacement.longitude == null) {
      return '—';
    }
    return `${emplacement.latitude.toFixed(5)}, ${emplacement.longitude.toFixed(5)}`;
  }

  protected openCreate(): void {
    this.openDialog(null);
  }

  protected edit(emplacement: Emplacement): void {
    this.openDialog(emplacement);
  }

  private openDialog(emplacement: Emplacement | null): void {
    this.dialog.open<EmplacementFormDialog, EmplacementFormData, boolean>(EmplacementFormDialog, {
      data: { emplacement },
      width: '40rem',
      maxWidth: '95vw',
      autoFocus: 'first-tabbable'
    });
  }

  protected async remove(emplacement: Emplacement): Promise<void> {
    await this.crud.remove('emplacements', emplacement.id, $localize`:@@emplacements.entityLabel:Emplacement`);
  }

  protected async removeSelection(): Promise<void> {
    await this.crud.removeMany('emplacements', this.selection.selectedIds(), labelEmplacementsPluriel());
  }

  protected editSelection(): void {
    const selectionnes = new Set(this.selection.selectedIds());
    this.dialog.open<EmplacementBulkEditDialog, EmplacementBulkEditData, boolean>(EmplacementBulkEditDialog, {
      data: { emplacements: this.store.emplacements().filter((emplacement) => selectionnes.has(emplacement.id)) },
      width: '44rem',
      maxWidth: '95vw',
      autoFocus: 'first-tabbable'
    });
  }
}
