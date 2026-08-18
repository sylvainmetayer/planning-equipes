import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { firstValueFrom } from 'rxjs';
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
import { correspondAuFiltre } from '../../core/text-filter';
import { Emplacement } from '../../core/models';
import { BulkActionsBar } from '../../shared/bulk-actions-bar';
import { DetailData, DetailDialog } from '../../shared/detail-dialog';
import { TableFilter } from '../../shared/table-filter';
import { EmplacementBulkEditData, EmplacementBulkEditDialog } from './emplacement-bulk-edit-dialog';
import { buildEmplacementDetail } from './emplacement-detail';
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
    BulkActionsBar,
    TableFilter
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

  /** Quick filter of the table: id, name and coordinates. */
  protected readonly filtre = signal('');
  protected readonly emplacementsFiltres = computed(() =>
    this.store
      .emplacements()
      .filter((emplacement) =>
        correspondAuFiltre(this.filtre(), [
          emplacement.id,
          emplacement.nom,
          emplacement.latitude,
          emplacement.longitude
        ])
      )
  );

  /** Keyed on the filtered rows, so "tout sélectionner" follows what the table shows. */
  protected readonly selection = new TableSelection<string>(
    computed(() => this.emplacementsFiltres().map((emplacement) => emplacement.id))
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

  /**
   * Read-only detail of one row, with an "Modifier" button handing over to the
   * usual form dialog — locked, there as here, while a solve is running.
   */
  protected async consult(emplacement: Emplacement): Promise<void> {
    const data: DetailData = {
      title: emplacement.nom || emplacement.id,
      subtitle: emplacement.id,
      sections: buildEmplacementDetail(emplacement, this.store.stands())
    };
    const result = await firstValueFrom(
      this.dialog.open(DetailDialog, { data, width: '40rem', maxWidth: '95vw' }).afterClosed()
    );
    if (result === 'edit') {
      this.edit(emplacement);
    }
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
