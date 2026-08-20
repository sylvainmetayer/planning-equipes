import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { firstValueFrom } from 'rxjs';
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
import { StatusMessage } from '../../shared/status-message';
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
    FormsModule,
    MatCardModule,
    MatButtonModule,
    MatCheckboxModule,
    MatFormFieldModule,
    MatIconModule,
    MatSelectModule,
    MatTableModule,
    MatTooltipModule,
    BulkActionsBar,
    TableFilter
  ,
    StatusMessage],
  templateUrl: './typologies-page.html',
  changeDetection: ChangeDetectionStrategy.OnPush
})
export class TypologiesPage {
  protected readonly columns = ['select', 'id', 'label', 'ninja', 'actions'];
  protected readonly store = inject(ReferenceDataStore);
  protected readonly jobs = inject(SolverJobService);
  /** Editing is disabled while a solve/analysis runs, to avoid corrupting the data it reads. */
  protected readonly editingLocked = computed(() => this.jobs.solverBusy());

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

  private readonly crud = inject(ReferenceCrudService);
  private readonly dialog = inject(MatDialog);

  constructor() {
    void this.crud.reload();
  }

  /**
   * Warning text when no typologie is flagged ninja (and the referential is
   * not simply empty): without it, no animateur is polyvalent — nobody can be
   * seated outside their own competences, and `preserverBufferPolyvalents`
   * (keep one polyvalent free per créneau to absorb last-minute absences)
   * has nothing to protect. A silent degradation worth a visible sentence.
   */
  protected readonly alerteNinjaManquant = computed(() => {
    if (this.store.typologies().length === 0 || this.store.typologies().some((typologie) => typologie.ninja)) {
      return '';
    }
    return $localize`:@@typologies.ninjaManquant:Aucune typologie « ninja » n'est désignée. Sans elle, aucun animateur n'est polyvalent : personne ne peut être affecté en dehors de ses compétences, et la contrainte « préserver un polyvalent libre par créneau » (votre marge de manœuvre en cas d'absence de dernière minute) ne protège plus rien. Choisissez la typologie qui joue ce rôle dans le sélecteur ci-dessus.`;
  });

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
