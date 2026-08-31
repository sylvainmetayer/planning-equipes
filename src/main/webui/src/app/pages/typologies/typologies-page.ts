import { LiveAnnouncer } from '@angular/cdk/a11y';
import { ChangeDetectionStrategy, Component, ElementRef, computed, inject, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MatDialog } from '@angular/material/dialog';
import { MatIconModule } from '@angular/material/icon';
import { MatTableModule } from '@angular/material/table';
import { MatTooltipModule } from '@angular/material/tooltip';
import { RouterLink } from '@angular/router';
import { labelTypologiesPluriel } from '../../core/entity-labels';
import { ReferenceCrudService } from '../../core/reference-crud.service';
import { ReferenceDataStore } from '../../core/reference-data.store';
import { SolverJobService } from '../../core/solver-job.service';
import { TableNavigation } from '../../core/table-navigation';
import { TableSelection } from '../../core/table-selection';
import { correspondAuFiltre } from '../../core/text-filter';
import { TypologieItem } from '../../core/models';
import { BulkActionsBar } from '../../shared/bulk-actions-bar';
import { DetailData, DetailDialog } from '../../shared/detail-dialog';
import { TableFilter } from '../../shared/table-filter';
import { buildTypologieDetail } from './typologie-detail';
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
    MatCardModule,
    MatButtonModule,
    MatCheckboxModule,
    MatIconModule,
    MatTableModule,
    MatTooltipModule,
    RouterLink,
    BulkActionsBar,
    TableFilter
  ],
  templateUrl: './typologies-page.html',
  changeDetection: ChangeDetectionStrategy.OnPush
})
export class TypologiesPage {
  protected readonly columns = ['select', 'id', 'label', 'ninja', 'actions'];
  protected readonly store = inject(ReferenceDataStore);
  protected readonly jobs = inject(SolverJobService);
  /** Editing is disabled while a solve/analysis runs, to avoid corrupting the data it reads. */
  protected readonly editingLocked = this.jobs.editingLocked;

  /** Quick filter of the table: id and label, the two things a typologie is looked up by. */
  protected readonly filtre = signal('');
  /**
   * Who references a typologie, counted before it can be deleted. Removing one
   * silently strips it from every stand and animateur that named it.
   */
  protected usages(typologieId: string): string {
    const stands = this.store.stands().filter((stand) => stand.typologiesProposees?.includes(typologieId)).length;
    const animateurs = this.store
      .animateurs()
      .filter((animateur) => Object.keys(animateur.competences ?? {}).includes(typologieId)).length;
    if (stands === 0 && animateurs === 0) {
      return $localize`:@@typologies.usages.none:Aucun stand ni animateur ne la référence.`;
    }
    return $localize`:@@typologies.usages:${stands}:stands: stand(s) et ${animateurs}:animateurs: animateur(s) la référencent.`;
  }

  protected readonly typologiesFiltrees = computed(() =>
    this.store.typologies().filter((typologie) => correspondAuFiltre(this.filtre(), [typologie.id, typologie.label]))
  );

  /** Keyed on the filtered rows, so "tout sélectionner" follows what the table shows. */
  protected readonly selection = new TableSelection<string>(
    computed(() => this.typologiesFiltrees().map((typologie) => typologie.id))
  );

  private readonly hote = inject<ElementRef<HTMLElement>>(ElementRef);

  /**
   * Roving tabindex over the rows: the arrows move the focus, Entrée opens the
   * detail, Espace ticks the row. `core/table-navigation.ts` holds the whole
   * mechanism, shared with the other reference-data tables.
   */
  protected readonly navigation = new TableNavigation({
    rows: this.typologiesFiltrees,
    id: (typologie: TypologieItem) => typologie.id,
    host: () => this.hote.nativeElement,
    selection: this.selection,
    open: (typologie: TypologieItem) => {
      void this.consult(typologie);
      return true;
    },
    announcer: inject(LiveAnnouncer)
  });

  private readonly crud = inject(ReferenceCrudService);
  private readonly dialog = inject(MatDialog);

  constructor() {
    void this.crud.reload();
  }

  /**
   * Read-only detail of one row, with an "Modifier" button handing over to the
   * usual form dialog — locked, there as here, while a solve is running.
   */
  protected async consult(typologie: TypologieItem): Promise<void> {
    const data: DetailData = {
      title: typologie.label || typologie.id,
      subtitle: typologie.id,
      sections: buildTypologieDetail(typologie, this.store.stands(), this.store.animateurs())
    };
    const result = await firstValueFrom(
      this.dialog.open(DetailDialog, { data, width: '40rem', maxWidth: '95vw' }).afterClosed()
    );
    if (result === 'edit') {
      this.edit(typologie);
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
    await this.crud.remove(
      'typologies',
      typologie.id,
      $localize`:@@typologies.entityLabel:Typologie`,
      this.usages(typologie.id)
    );
  }

  protected async removeSelection(): Promise<void> {
    await this.crud.removeMany('typologies', this.selection.selectedIds(), labelTypologiesPluriel());
  }
}
