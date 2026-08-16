import { ChangeDetectionStrategy, Component, computed, inject } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MatDialog } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatSelectModule } from '@angular/material/select';
import { MatTableModule } from '@angular/material/table';
import { MatTooltipModule } from '@angular/material/tooltip';
import { labelTypologiesPluriel } from '../../core/entity-labels';
import { ReferenceCrudService } from '../../core/reference-crud.service';
import { ReferenceDataStore } from '../../core/reference-data.store';
import { SolverJobService } from '../../core/solver-job.service';
import { TableSelection } from '../../core/table-selection';
import { TypologieItem } from '../../core/models';
import { BulkActionsBar } from '../../shared/bulk-actions-bar';
import { TypologieFormData, TypologieFormDialog } from './typologie-form-dialog';

/**
 * Typologies CRUD: the game families a stand can propose and an animator master.
 *
 * Rows are multi-selectable for a bulk delete. There is no bulk edit here: a
 * typologie only carries its own label, and the ninja flag is single-holder by
 * construction.
 */
@Component({
  selector: 'app-typologies-page',
  imports: [
    FormsModule,
    MatCardModule,
    MatButtonModule,
    MatCheckboxModule,
    MatFormFieldModule,
    MatIconModule,
    MatSelectModule,
    MatTableModule,
    MatTooltipModule,
    BulkActionsBar
  ],
  templateUrl: './typologies-page.html',
  changeDetection: ChangeDetectionStrategy.OnPush
})
export class TypologiesPage {
  protected readonly columns = ['select', 'id', 'label', 'ninja', 'actions'];
  protected readonly store = inject(ReferenceDataStore);
  protected readonly jobs = inject(SolverJobService);
  /** Editing is disabled while a solve/analysis runs, to avoid corrupting the data it reads. */
  protected readonly editingLocked = computed(() => this.jobs.solverBusy());

  protected readonly selection = new TableSelection<string>(
    computed(() => this.store.typologies().map((typologie) => typologie.id))
  );

  private readonly crud = inject(ReferenceCrudService);
  private readonly dialog = inject(MatDialog);

  constructor() {
    void this.crud.reload();
  }

  /** Id of the typologie currently flagged ninja — at most one, `null` when none. */
  protected readonly typologieNinjaId = computed(
    () => this.store.typologies().find((typologie) => typologie.ninja)?.id ?? null
  );

  /**
   * Promotes `id` as the single ninja typologie, or clears the flag altogether
   * when `id` is `null`. Only the newly selected typologie is sent: the server
   * demotes the previous holder in the same transaction (a partial unique index
   * makes two ninjas impossible anyway).
   */
  protected async setNinja(id: string | null): Promise<void> {
    const label = $localize`:@@typologies.entityLabel:Typologie`;
    const courante = this.store.typologies().find((typologie) => typologie.ninja) ?? null;
    if ((courante?.id ?? null) === id) {
      return;
    }
    if (id === null) {
      if (courante) {
        await this.crud.save('typologies', { ...courante, ninja: false }, courante.id, label);
      }
      return;
    }
    const cible = this.store.typologies().find((typologie) => typologie.id === id);
    if (cible) {
      await this.crud.save('typologies', { ...cible, ninja: true }, cible.id, label);
    }
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

  protected async removeSelection(): Promise<void> {
    await this.crud.removeMany('typologies', this.selection.selectedIds(), labelTypologiesPluriel());
  }
}
