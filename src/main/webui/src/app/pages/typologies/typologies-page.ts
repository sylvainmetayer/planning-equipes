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
import { TypologieItem } from '../../core/models';
import { TypologieFormData, TypologieFormDialog } from './typologie-form-dialog';

/** Typologies CRUD: the game families a stand can propose and an animator master. */
@Component({
  selector: 'app-typologies-page',
  imports: [MatCardModule, MatButtonModule, MatIconModule, MatTableModule, MatTooltipModule],
  templateUrl: './typologies-page.html',
  changeDetection: ChangeDetectionStrategy.OnPush
})
export class TypologiesPage {
  protected readonly columns = ['id', 'label', 'actions'];
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

  protected edit(typologie: TypologieItem): void {
    this.openDialog(typologie);
  }

  private openDialog(typologie: TypologieItem | null): void {
    this.dialog.open<TypologieFormDialog, TypologieFormData, boolean>(TypologieFormDialog, {
      data: { typologie },
      width: '40rem',
      maxWidth: '95vw',
      autoFocus: 'first-tabbable'
    });
  }

  protected async remove(typologie: TypologieItem): Promise<void> {
    await this.crud.remove('typologies', typologie.id, $localize`:@@typologies.entityLabel:Typologie`);
  }
}
