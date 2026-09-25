import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MatDialog } from '@angular/material/dialog';
import { MatIconModule } from '@angular/material/icon';
import { MatTableModule } from '@angular/material/table';
import { MatTooltipModule } from '@angular/material/tooltip';
import { labelEmplacementsPluriel } from '../../core/entity-labels';
import { ReferenceTablePage } from '../../core/reference-table-page';
import { ConstraintsApi } from '../../core/api/constraints-api';
import {
  distanceMetres,
  formatDistance,
  WALKING_DEFAULTS,
  WalkingSettings,
} from '../../core/distance';
import { Emplacement } from '../../core/models';
import { BulkActionsBar } from '../../shared/bulk-actions-bar';
import { TableFilter } from '../../shared/table-filter';
import { EmplacementBulkEditData, EmplacementBulkEditDialog } from './emplacement-bulk-edit-dialog';
import { buildEmplacementDetail } from './emplacement-detail';
import { EmplacementFormData, EmplacementFormDialog } from './emplacement-form-dialog';

/** Mirrors `QualiteConstraints.DISTANCE_ELOIGNEE_METRES` on the server. */
const SEUIL_ELOIGNEMENT_METRES = 300;

/**
 * Emplacements CRUD: named, GPS-located places a stand can be tied to.
 *
 * Rows are multi-selectable, for a bulk delete or to put several places on the
 * same GPS point at once.
 *
 * Everything shared with the other referential tables is in
 * {@link ReferenceTablePage}; what is below is what this one does differently.
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
    TableFilter,
  ],
  templateUrl: './emplacements-page.html',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class EmplacementsPage extends ReferenceTablePage<Emplacement> {
  protected readonly columns = ['select', 'id', 'code', 'nom', 'coordonnees', 'voisin', 'actions'];

  /** The template names the rows after the entity, as the other pages do. */
  protected readonly emplacementsFiltres = this.lignesFiltrees;

  constructor() {
    // Read by the detail's walking times: the defaults until the edition's own
    // settings arrive, and for good if they cannot be read.
    const walking = signal<WalkingSettings>(WALKING_DEFAULTS);
    super({
      rows: (store) => store.emplacements(),
      id: (emplacement) => emplacement.id,
      champsFiltre: (emplacement) => [
        emplacement.id,
        emplacement.code,
        emplacement.nom,
        emplacement.latitude,
        emplacement.longitude,
      ],
      detail: (emplacement, store) => ({
        title: emplacement.nom || emplacement.id,
        subtitle: emplacement.id,
        sections: buildEmplacementDetail(
          emplacement,
          store.stands(),
          store.emplacements(),
          walking(),
        ),
      }),
      formulaire: (emplacement, dialog: MatDialog) => {
        dialog.open<EmplacementFormDialog, EmplacementFormData, boolean>(EmplacementFormDialog, {
          data: { emplacement },
          width: '40rem',
          maxWidth: '95vw',
          autoFocus: 'first-tabbable',
        });
      },
      ressource: 'emplacements',
      libelle: () => $localize`:@@emplacements.entityLabel:Emplacement`,
      name: (emplacement) => emplacement.nom,
      libellePluriel: labelEmplacementsPluriel,
    });
    const constraintsApi = inject(ConstraintsApi);
    void (async () => {
      try {
        const settings = await constraintsApi.qualityParameters();
        if (settings) {
          walking.set(settings);
        }
      } catch {
        // The defaults stay: a walking time slightly off beats no walking time.
      }
    })();
  }

  protected coordonneesLabel(emplacement: Emplacement): string {
    if (emplacement.latitude == null || emplacement.longitude == null) {
      return '—';
    }
    return `${emplacement.latitude.toFixed(5)}, ${emplacement.longitude.toFixed(5)}`;
  }

  /**
   * Nearest other emplacement of each one, in metres — computed once per
   * change of the referential. Read from a `matCellDef`, the former per-row
   * scan made every change-detection pass O(N²) over the whole table.
   *
   * Two constraints reason in metres — a change of emplacement beyond 300 m is
   * penalised, and a day spread over too many of them too. Nobody can judge
   * that from two pairs of decimal coordinates, so the table says it.
   */
  private readonly voisins = computed<Map<string, string>>(() => {
    const emplacements = this.store.emplacements();
    return new Map(
      emplacements.map((emplacement) => [
        emplacement.id,
        voisinLePlusProche(emplacement, emplacements),
      ]),
    );
  });

  protected voisinLePlusProche(emplacement: Emplacement): string {
    return this.voisins().get(emplacement.id) ?? '';
  }

  protected editSelection(): void {
    const selectionnes = new Set(this.selection.selectedIds());
    this.dialog.open<EmplacementBulkEditDialog, EmplacementBulkEditData, boolean>(
      EmplacementBulkEditDialog,
      {
        data: {
          emplacements: this.store
            .emplacements()
            .filter((emplacement) => selectionnes.has(emplacement.id)),
        },
        width: '44rem',
        maxWidth: '95vw',
        autoFocus: 'first-tabbable',
      },
    );
  }
}

function voisinLePlusProche(
  emplacement: Emplacement,
  emplacements: readonly Emplacement[],
): string {
  let plusProche: { nom: string; metres: number } | null = null;
  for (const autre of emplacements) {
    if (autre.id === emplacement.id) {
      continue;
    }
    const metres = distanceMetres(emplacement, autre);
    if (metres === null) {
      continue;
    }
    if (!plusProche || metres < plusProche.metres) {
      plusProche = { nom: autre.nom || autre.id, metres };
    }
  }
  if (!plusProche) {
    return '';
  }
  const distance = formatDistance(plusProche.metres);
  const nom = plusProche.nom;
  return plusProche.metres > SEUIL_ELOIGNEMENT_METRES
    ? $localize`:@@emplacements.voisin.loin:${distance}:distance: de ${nom}:nom: (au-delà du seuil d'éloignement)`
    : $localize`:@@emplacements.voisin:${distance}:distance: de ${nom}:nom:`;
}
