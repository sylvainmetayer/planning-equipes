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
import { MatSortModule, Sort } from '@angular/material/sort';
import { MatTableModule } from '@angular/material/table';
import { MatTooltipModule } from '@angular/material/tooltip';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { labelTypologiesPluriel } from '../../core/entity-labels';
import { ReferenceDataStore } from '../../core/reference-data.store';
import { ReferenceTablePage } from '../../core/reference-table-page';
import { TypologieItem } from '../../core/models';
import {
  NO_SORT,
  keepViewInQueryParams,
  readSort,
  sortQueryParams,
} from '../../core/view-query-params';
import { BulkActionsBar } from '../../shared/bulk-actions-bar';
import { TableFilter } from '../../shared/table-filter';
import { buildTypologieDetail } from './typologie-detail';
import { TypologieFormData, TypologieFormDialog } from './typologie-form-dialog';
import {
  ETATS_A_TRAITER,
  EtatTypologie,
  UsageTypologie,
  etatsQueryParam,
  explicationEtat,
  iconeEtat,
  libelleEtat,
  readEtatsParam,
  repartitionCompetents,
  usagesTypologies,
} from './usage-typologies';
import { injectGelReferentiel } from '../../core/gel-referentiel.store';
import { GelNotice } from '../../shared/gel-notice';

/**
 * Who references a typologie, counted before it can be deleted. Removing one
 * silently strips it from every stand and animateur that named it.
 */
function referencesTypologie(typologieId: string, store: ReferenceDataStore): string {
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

/** The columns a click on the header sorts by. */
type ColonneTriable = 'id' | 'label' | 'competents' | 'souhaits' | 'stands';

/** A typologie the usage map does not know yet reads as nothing at all. */
function usageVide(typologieId: string): UsageTypologie {
  return {
    typologieId,
    competents: 0,
    referents: 0,
    autonomes: 0,
    debutants: 0,
    polyvalents: 0,
    souhaits: 0,
    stands: 0,
    etat: 'NORMALE',
  };
}

/**
 * Typologies CRUD: the game families a stand can propose and an animator master.
 *
 * Rows are multi-selectable for a bulk delete. There is no bulk edit here: a
 * typologie only carries its own label, and the ninja flag is single-holder by
 * construction.
 *
 * Beside the referential row, what each typologie is worth to the planning:
 * how many hold it, wish for it and propose it, and a badge when that is
 * worth acting upon — a typologie a stand proposes and nobody masters is
 * otherwise only discovered at the Diagnostic, after a solve.
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
    MatMenuModule,
    MatSortModule,
    MatTableModule,
    MatTooltipModule,
    RouterLink,
    BulkActionsBar,
    TableFilter,
    GelNotice,
  ],
  templateUrl: './typologies-page.html',
  styleUrl: './typologies-page.css',
  encapsulation: ViewEncapsulation.None,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class TypologiesPage extends ReferenceTablePage<TypologieItem> {
  private readonly gel = injectGelReferentiel();
  /** Creating or deleting a typologie or an emplacement is what a TYPOLOGIES_EMPLACEMENTS freeze refuses (ADR 0052). */
  protected readonly creationLocked = computed(
    () => this.editingLocked() || this.gel.isFrozen('TYPOLOGIES_EMPLACEMENTS'),
  );

  protected readonly columns = [
    'select',
    'id',
    'code',
    'label',
    'ninja',
    'competents',
    'souhaits',
    'stands',
    'etat',
    'actions',
  ];

  /** The template names the rows after the entity, as the other pages do. */
  protected readonly typologiesFiltrees = this.lignesFiltrees;

  /**
   * What each typologie is worth to the planning, computed on the store the
   * page already holds — the same definition the « État de l'édition » counts
   * server-side.
   */
  protected readonly usages = computed(
    () =>
      new Map(
        usagesTypologies(this.store.typologies(), this.store.stands(), this.store.animateurs()).map(
          (usage) => [usage.typologieId, usage],
        ),
      ),
  );

  /** How many typologies a stand proposes and nobody holds, for the banner. */
  protected readonly orphelines = computed(
    () => [...this.usages().values()].filter((usage) => usage.etat === 'ORPHELINE').length,
  );

  /** `?etat=orpheline,fragile`: the states the table is narrowed to; empty = every row. */
  protected readonly etats = signal<EtatTypologie[]>([]);

  protected readonly sort = signal<Sort>(NO_SORT);

  /** « Seulement les typologies à traiter » is ticked whenever a state filter holds. */
  protected readonly aTraiterSeulement = computed(() => this.etats().length > 0);

  protected readonly viewChanged = computed(
    () => this.etats().length > 0 || (this.sort().active !== '' && this.sort().direction !== ''),
  );

  constructor() {
    super({
      rows: (store) => store.typologies(),
      id: (typologie) => typologie.id,
      champsFiltre: (typologie) => [typologie.id, typologie.code, typologie.label],
      detail: (typologie, store) => ({
        title: typologie.label || typologie.id,
        subtitle: typologie.id,
        sections: buildTypologieDetail(
          typologie,
          store.stands(),
          store.animateurs(),
          store.typologies(),
        ),
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
      name: (typologie) => typologie.label,
      libellePluriel: labelTypologiesPluriel,
      usages: (typologie, store) => referencesTypologie(typologie.id, store),
    });
    const params = inject(ActivatedRoute).snapshot.queryParamMap;
    this.etats.set(readEtatsParam(params.get('etat')));
    this.sort.set(readSort(params));
    keepViewInQueryParams(() => ({
      etat: etatsQueryParam(this.etats()),
      ...sortQueryParams(this.sort()),
    }));
  }

  protected usage(typologie: TypologieItem): UsageTypologie {
    return this.usages().get(typologie.id) ?? usageVide(typologie.id);
  }

  /** The state filter, then the sort — the quick filter already ran. */
  protected override refine(lignes: readonly TypologieItem[]): readonly TypologieItem[] {
    const etats = this.etats();
    const retenues =
      etats.length === 0
        ? lignes
        : lignes.filter((ligne) => etats.includes(this.usage(ligne).etat));
    const { active, direction } = this.sort();
    if (!active || direction === '') {
      return retenues;
    }
    const signe = direction === 'asc' ? 1 : -1;
    return [...retenues].sort((gauche, droite) => signe * this.compare(active, gauche, droite));
  }

  private compare(colonne: string, gauche: TypologieItem, droite: TypologieItem): number {
    switch (colonne as ColonneTriable) {
      case 'id':
        return gauche.id.localeCompare(droite.id);
      case 'label':
        return (gauche.label || gauche.id).localeCompare(droite.label || droite.id);
      case 'competents':
      case 'souhaits':
      case 'stands': {
        const key = colonne as 'competents' | 'souhaits' | 'stands';
        return this.usage(gauche)[key] - this.usage(droite)[key];
      }
      default:
        return 0;
    }
  }

  protected toggleATraiter(checked: boolean): void {
    this.etats.set(checked ? [...ETATS_A_TRAITER] : []);
  }

  /** The banner's link: the orphans alone. */
  protected showOrphelines(): void {
    this.etats.set(['ORPHELINE']);
  }

  protected resetView(): void {
    this.etats.set([]);
    this.sort.set(NO_SORT);
  }

  protected etatLabel(etat: EtatTypologie): string {
    return libelleEtat(etat);
  }

  protected etatIcon(etat: EtatTypologie): string {
    return iconeEtat(etat);
  }

  protected etatHint(usage: UsageTypologie): string {
    return explicationEtat(usage);
  }

  protected competentsHint(usage: UsageTypologie): string {
    return repartitionCompetents(usage);
  }
}
