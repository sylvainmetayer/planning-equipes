import {
  ChangeDetectionStrategy,
  Component,
  ViewEncapsulation,
  computed,
  inject,
  signal,
} from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MatDialog } from '@angular/material/dialog';
import { MatIconModule } from '@angular/material/icon';
import { MatMenuModule } from '@angular/material/menu';
import { MatSortModule } from '@angular/material/sort';
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
import { compareNatural } from '../../core/table-sort';
import { currentViewParams, readSort } from '../../core/view-query-params';
import { LieuMarker, LieuMove, LieuxMap } from './lieux-map';
import { PasteColumn } from '../../core/paste-rows';
import { BulkActionsBar } from '../../shared/bulk-actions-bar';
import { EmptyState } from '../../shared/empty-state';
import { RowMenu } from '../../shared/row-menu';
import { RowWarning } from '../../shared/row-warning';
import { ImportedRowsFilter } from '../../shared/imported-rows-filter';
import { TableFilter } from '../../shared/table-filter';
import { EmplacementBulkEditData, EmplacementBulkEditDialog } from './emplacement-bulk-edit-dialog';
import { buildEmplacementDetail } from './emplacement-detail';
import { EmplacementFormData, EmplacementFormDialog } from './emplacement-form-dialog';
import { injectGelReferentiel } from '../../core/gel-referentiel.store';
import { GelNotice } from '../../shared/gel-notice';
import { ImportButton } from '../../shared/import-button';

/** Mirrors `QualiteConstraints.DISTANCE_ELOIGNEE_METRES` on the server. */
const SEUIL_ELOIGNEMENT_METRES = 300;

/**
 * The « Lieux » tab of the Stands page (`/stands?onglet=lieux`, the former
 * `/emplacements`): named, GPS-located places a stand can be tied to.
 *
 * A map of every located place, its stands in the tooltip, where a marker
 * dragged elsewhere saves the new position at once — no dialog. Below, the
 * table, every column sortable, with the stands standing on each place. Rows
 * are multi-selectable, for a bulk delete or to put several places on the
 * same GPS point at once. Drawn inside the tab's `@defer`: the map pulls
 * Leaflet, which the Stands table's chunk must not carry.
 *
 * Everything shared with the other referential tables is in
 * {@link ReferenceTablePage}; what is below is what this one does differently.
 */
@Component({
  selector: 'app-emplacements-page',
  imports: [
    ImportedRowsFilter,
    ImportButton,
    MatCardModule,
    MatButtonModule,
    MatCheckboxModule,
    MatIconModule,
    MatMenuModule,
    MatSortModule,
    MatTableModule,
    MatTooltipModule,
    BulkActionsBar,
    EmptyState,
    RowMenu,
    RowWarning,
    TableFilter,
    GelNotice,
    LieuxMap,
  ],
  templateUrl: './emplacements-page.html',
  styleUrl: './emplacements-page.css',
  // Global by design (AGENTS.md): loaded with the tab, unscoped like a partial.
  encapsulation: ViewEncapsulation.None,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class EmplacementsPage extends ReferenceTablePage<Emplacement> {
  private readonly gel = injectGelReferentiel();
  /** Creating or deleting a typologie or an emplacement is what a TYPOLOGIES_EMPLACEMENTS freeze refuses (ADR 0052). */
  protected readonly creationLocked = computed(
    () => this.editingLocked() || this.gel.isFrozen('TYPOLOGIES_EMPLACEMENTS'),
  );

  protected readonly columns = [
    'select',
    'id',
    'code',
    'nom',
    'stands',
    'coordonnees',
    'voisin',
    'actions',
  ];

  /** Each place's stands, by name: the column, the map's tooltips and the sort read it. */
  protected readonly standsByLocation = computed(() => {
    const byLocation = new Map<string, string[]>();
    for (const stand of this.store.stands()) {
      const lieu = stand.emplacement?.id;
      if (lieu) {
        byLocation.set(lieu, [...(byLocation.get(lieu) ?? []), stand.nom || stand.id]);
      }
    }
    for (const noms of byLocation.values()) {
      noms.sort(compareNatural);
    }
    return byLocation;
  });

  /** The located places, as the map draws them. */
  protected readonly markers = computed<LieuMarker[]>(() =>
    this.store
      .emplacements()
      .filter((emplacement) => emplacement.latitude != null && emplacement.longitude != null)
      .map((emplacement) => ({
        id: emplacement.id,
        nom: emplacement.nom || emplacement.id,
        latitude: emplacement.latitude!,
        longitude: emplacement.longitude!,
        stands: this.standsByLocation().get(emplacement.id) ?? [],
      })),
  );
  /** Places the map cannot draw: no coordinates yet. */
  protected readonly unlocated = computed(
    () => this.store.emplacements().length - this.markers().length,
  );

  /** The template names the rows after the entity, as the other pages do. */
  protected readonly emplacementsFiltres = this.lignesFiltrees;

  constructor() {
    // The address bar as it is now, not the router's snapshot: this tab is
    // created and destroyed as the reader moves between the page's tabs.
    const params = currentViewParams();
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
      sortValues: {
        id: (emplacement) => emplacement.id,
        code: (emplacement) => emplacement.code,
        nom: (emplacement) => emplacement.nom,
        stands: (emplacement) => this.standsByLocation().get(emplacement.id)?.length ?? 0,
        coordonnees: (emplacement) => emplacement.latitude,
        voisin: (emplacement) => this.voisins().get(emplacement.id)?.metres,
      },
      export: {
        name: 'emplacements',
        columns: () => [
          { title: $localize`:@@common.id:Id`, value: (emplacement) => emplacement.id },
          {
            title: $localize`:@@referentiel.field.code:Code`,
            value: (emplacement) => emplacement.code,
          },
          { title: $localize`:@@common.nom:Nom`, value: (emplacement) => emplacement.nom },
          {
            title: $localize`:@@lieux.column.stands:Stands rattachés`,
            value: (emplacement) => this.standsByLocation().get(emplacement.id) ?? [],
          },
          {
            title: $localize`:@@emplacements.field.latitude:Latitude`,
            value: (emplacement) => emplacement.latitude,
          },
          {
            title: $localize`:@@emplacements.field.longitude:Longitude`,
            value: (emplacement) => emplacement.longitude,
          },
          {
            title: $localize`:@@lieux.column.voisin:Lieu le plus proche`,
            value: (emplacement) => this.voisinLePlusProche(emplacement),
          },
        ],
      },
      paste: () => [
        {
          key: 'code',
          title: $localize`:@@referentiel.field.code:Code`,
          read: (emplacement) => emplacement.code ?? '',
          write: (emplacement, text) => ({ ...emplacement, code: text }),
        },
        {
          key: 'nom',
          title: $localize`:@@common.nom:Nom`,
          read: (emplacement) => emplacement.nom,
          write: (emplacement, text) => ({ ...emplacement, nom: text }),
        },
        coordinateColumn('latitude', $localize`:@@emplacements.field.latitude:Latitude`, 90),
        coordinateColumn('longitude', $localize`:@@emplacements.field.longitude:Longitude`, 180),
      ],
      duplicate: (emplacement, dialog: MatDialog) => {
        dialog.open<EmplacementFormDialog, EmplacementFormData, boolean>(EmplacementFormDialog, {
          data: { emplacement: null, modele: emplacement },
          width: '40rem',
          maxWidth: '95vw',
          autoFocus: 'first-tabbable',
        });
      },
    });
    // A link naming a place (the stand fiche's location) lands filtered on it.
    this.filtre.set(params.get('q') ?? '');
    this.sort.set(readSort(params));
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

  /** Bumped when a dragged marker's position could not be saved: the map lays it back. */
  protected readonly mapRevision = signal(0);

  /** A marker dropped elsewhere: the place's new position, saved at once — no dialog. */
  protected async move(move: LieuMove): Promise<void> {
    const emplacement = this.store.emplacements().find((each) => each.id === move.id);
    if (!emplacement) {
      return;
    }
    const saved = await this.crud.save(
      'emplacements',
      { ...emplacement, latitude: move.latitude, longitude: move.longitude },
      emplacement.id,
      $localize`:@@emplacements.entityLabel:Emplacement`,
      { text: emplacement.nom || emplacement.id },
    );
    if (!saved) {
      // Refused: the marker goes back where the place still is.
      this.mapRevision.update((revision) => revision + 1);
    }
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
  private readonly voisins = computed<Map<string, Voisin>>(() => {
    const emplacements = this.store.emplacements();
    return new Map(
      emplacements.map((emplacement) => [
        emplacement.id,
        voisinLePlusProche(emplacement, emplacements),
      ]),
    );
  });

  protected voisinLePlusProche(emplacement: Emplacement): string {
    return this.voisins().get(emplacement.id)?.libelle ?? '';
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

/** The nearest other emplacement, worded, and how far it is — what its column sorts on. */
interface Voisin {
  libelle: string;
  metres: number | null;
}

function voisinLePlusProche(
  emplacement: Emplacement,
  emplacements: readonly Emplacement[],
): Voisin {
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
    return { libelle: '', metres: null };
  }
  const distance = formatDistance(plusProche.metres);
  const nom = plusProche.nom;
  return {
    metres: plusProche.metres,
    libelle:
      plusProche.metres > SEUIL_ELOIGNEMENT_METRES
        ? $localize`:@@emplacements.voisin.loin:${distance}:distance: de ${nom}:nom: (au-delà du seuil d'éloignement)`
        : $localize`:@@emplacements.voisin:${distance}:distance: de ${nom}:nom:`,
  };
}

/** A pasted latitude or longitude: a decimal number within ±`limite`, a comma read as the point. */
function coordinateColumn(
  key: 'latitude' | 'longitude',
  title: string,
  limite: number,
): PasteColumn<Emplacement> {
  return {
    key,
    title,
    read: (emplacement) => (emplacement[key] === null ? '' : String(emplacement[key])),
    write: (emplacement, text) => {
      const valeur = Number(text.replace(',', '.'));
      if (!Number.isFinite(valeur) || Math.abs(valeur) > limite) {
        return $localize`:@@emplacements.collage.coordonnee:un nombre décimal entre -${limite}:limite: et ${limite}:limite:`;
      }
      return { ...emplacement, [key]: valeur };
    },
  };
}
