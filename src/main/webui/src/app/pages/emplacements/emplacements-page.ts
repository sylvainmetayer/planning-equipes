import { ChangeDetectionStrategy, Component, computed, inject } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatDialog } from '@angular/material/dialog';
import { MatIconModule } from '@angular/material/icon';
import { MatTableModule } from '@angular/material/table';
import { MatTooltipModule } from '@angular/material/tooltip';
import { ReferenceCrudService } from '../../core/reference-crud.service';
import { ReferenceDataStore } from '../../core/reference-data.store';
import { SolverJobService } from '../../core/solver-job.service';
import { Emplacement } from '../../core/models';
import { EmplacementFormData, EmplacementFormDialog } from './emplacement-form-dialog';

/** Emplacements CRUD: named, GPS-located places a stand can be tied to. */
@Component({
  selector: 'app-emplacements-page',
  imports: [MatCardModule, MatButtonModule, MatIconModule, MatTableModule, MatTooltipModule],
  templateUrl: './emplacements-page.html',
  changeDetection: ChangeDetectionStrategy.OnPush
})
export class EmplacementsPage {
  protected readonly columns = ['id', 'nom', 'coordonnees', 'actions'];
  protected readonly store = inject(ReferenceDataStore);
  protected readonly jobs = inject(SolverJobService);
  /** Editing is disabled while a solve/analysis runs, to avoid corrupting the data it reads. */
  protected readonly editingLocked = computed(() => this.jobs.solverBusy());

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
}
