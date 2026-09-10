import { ChangeDetectionStrategy, Component } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MatDialog } from '@angular/material/dialog';
import { MatIconModule } from '@angular/material/icon';
import { MatTableModule } from '@angular/material/table';
import { MatTooltipModule } from '@angular/material/tooltip';
import { RouterLink } from '@angular/router';
import { labelTypologiesPluriel } from '../../core/entity-labels';
import { ReferenceDataStore } from '../../core/reference-data.store';
import { ReferenceTablePage } from '../../core/reference-table-page';
import { TypologieItem } from '../../core/models';
import { BulkActionsBar } from '../../shared/bulk-actions-bar';
import { TableFilter } from '../../shared/table-filter';
import { buildTypologieDetail } from './typologie-detail';
import { TypologieFormData, TypologieFormDialog } from './typologie-form-dialog';

/**
 * Who references a typologie, counted before it can be deleted. Removing one
 * silently strips it from every stand and animateur that named it.
 */
function usagesTypologie(typologieId: string, store: ReferenceDataStore): string {
  const stands = store
    .stands()
    .filter((stand) => stand.typologiesProposees?.includes(typologieId)).length;
  const animateurs = store
    .animateurs()
    .filter((animateur) => Object.keys(animateur.competences ?? {}).includes(typologieId)).length;
  if (stands === 0 && animateurs === 0) {
    return $localize`:@@typologies.usages.none:Aucun stand ni animateur ne la référence.`;
  }
  return $localize`:@@typologies.usages:${stands}:stands: stand(s) et ${animateurs}:animateurs: animateur(s) la référencent.`;
}

/**
 * Typologies CRUD: the game families a stand can propose and an animator master.
 *
 * Rows are multi-selectable for a bulk delete. There is no bulk edit here: a
 * typologie only carries its own label, and the ninja flag is single-holder by
 * construction.
 *
 * Everything shared with the other referential tables is in
 * {@link ReferenceTablePage}; what is below is what this one does differently.
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
    TableFilter,
  ],
  templateUrl: './typologies-page.html',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class TypologiesPage extends ReferenceTablePage<TypologieItem> {
  protected readonly columns = ['select', 'id', 'label', 'ninja', 'actions'];

  /** The template names the rows after the entity, as the other pages do. */
  protected readonly typologiesFiltrees = this.lignesFiltrees;

  constructor() {
    super({
      rows: (store) => store.typologies(),
      id: (typologie) => typologie.id,
      champsFiltre: (typologie) => [typologie.id, typologie.label],
      detail: (typologie, store) => ({
        title: typologie.label || typologie.id,
        subtitle: typologie.id,
        sections: buildTypologieDetail(typologie, store.stands(), store.animateurs()),
      }),
      formulaire: (typologie, dialog: MatDialog) => {
        dialog.open<TypologieFormDialog, TypologieFormData, boolean>(TypologieFormDialog, {
          data: { typologie },
          width: '40rem',
          maxWidth: '95vw',
          autoFocus: 'first-tabbable',
        });
      },
      ressource: 'typologies',
      libelle: () => $localize`:@@typologies.entityLabel:Typologie`,
      libellePluriel: labelTypologiesPluriel,
      usages: (typologie, store) => usagesTypologie(typologie.id, store),
    });
  }
}
