import {
  ChangeDetectionStrategy,
  Component,
  OnInit,
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
import { ActivatedRoute, RouterLink } from '@angular/router';
import { labelTypologiesPluriel } from '../../core/entity-labels';
import { ReferenceDataStore } from '../../core/reference-data.store';
import { ReferenceTablePage } from '../../core/reference-table-page';
import { LigneTypologie, TypologieItem } from '../../core/models';
import { PlanningApi } from '../../core/api/planning-api';
import { NO_SORT, keepViewInQueryParams } from '../../core/view-query-params';
import { BulkActionsBar } from '../../shared/bulk-actions-bar';
import { EmptyState } from '../../shared/empty-state';
import { FilterChip, FilterChips } from '../../shared/filter-chips';
import { RowMenu } from '../../shared/row-menu';
import { RowWarning } from '../../shared/row-warning';
import { ImportedRowsFilter } from '../../shared/imported-rows-filter';
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
import { ImportButton } from '../../shared/import-button';

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

/** Where a state ranks when its column is sorted: what needs doing first. */
const RANG_ETAT: Record<EtatTypologie, number> = {
  ORPHELINE: 0,
  FRAGILE: 1,
  SANS_COMPETENT_INUTILISEE: 2,
  INUTILISEE: 3,
  NORMALE: 4,
};

/** What the plan made of a typologie, once one is computed. */
export interface EffetPlan {
  postes: number;
  heures: number;
  sansCompetence: number;
}

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
    RouterLink,
    BulkActionsBar,
    EmptyState,
    FilterChips,
    RowMenu,
    RowWarning,
    TableFilter,
    GelNotice,
  ],
  templateUrl: './typologies-page.html',
  styleUrl: './typologies-page.css',
  encapsulation: ViewEncapsulation.None,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class TypologiesPage extends ReferenceTablePage<TypologieItem> implements OnInit {
  private readonly gel = injectGelReferentiel();
  /** Creating or deleting a typologie or an emplacement is what a TYPOLOGIES_EMPLACEMENTS freeze refuses (ADR 0052). */
  protected readonly creationLocked = computed(
    () => this.editingLocked() || this.gel.isFrozen('TYPOLOGIES_EMPLACEMENTS'),
  );

  /** The columns after a solve: what the plan made of each typologie. */
  private readonly colonnesPlan = ['postes', 'heures', 'sansCompetence'];

  protected readonly columns = computed(() => [
    'select',
    'id',
    'code',
    'label',
    'ninja',
    'competents',
    'souhaits',
    'stands',
    ...(this.effetsPlan() === null ? [] : this.colonnesPlan),
    'etat',
    'actions',
  ]);

  private readonly planningApi = inject(PlanningApi);

  /** Typologie id → what the persisted plan made of it; `null` while no plan holds a seat. */
  protected readonly effetsPlan = signal<ReadonlyMap<string, EffetPlan> | null>(null);

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

  /** « Seulement les typologies à traiter » is ticked whenever a state filter holds. */
  protected readonly aTraiterSeulement = computed(() => this.etats().length > 0);

  /** The state filter, as the chip above the table. */
  protected readonly chips = computed<FilterChip[]>(() =>
    this.etats().length === 0
      ? []
      : [
          {
            key: 'etat',
            label: $localize`:@@typologies.chip.etat:État : ${this.etats().map(libelleEtat).join(', ')}:etats:`,
          },
        ],
  );

  protected readonly viewChanged = computed(
    () =>
      this.etats().length > 0 ||
      this.filtre().trim() !== '' ||
      (this.sort().active !== '' && this.sort().direction !== ''),
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
      sortValues: {
        id: (typologie) => typologie.id,
        code: (typologie) => typologie.code,
        label: (typologie) => typologie.label || typologie.id,
        ninja: (typologie) => Boolean(typologie.ninja),
        competents: (typologie) => this.usage(typologie).competents,
        souhaits: (typologie) => this.usage(typologie).souhaits,
        stands: (typologie) => this.usage(typologie).stands,
        etat: (typologie) => RANG_ETAT[this.usage(typologie).etat],
        postes: (typologie) => this.effetsPlan()?.get(typologie.id)?.postes,
        heures: (typologie) => this.effetsPlan()?.get(typologie.id)?.heures,
        sansCompetence: (typologie) => this.effetsPlan()?.get(typologie.id)?.sansCompetence,
      },
      export: {
        name: 'typologies',
        columns: () => [
          { title: $localize`:@@common.id:Id`, value: (typologie) => typologie.id },
          {
            title: $localize`:@@referentiel.field.code:Code`,
            value: (typologie) => typologie.code,
          },
          {
            title: $localize`:@@typologies.field.label:Libellé`,
            value: (typologie) => typologie.label,
          },
          {
            title: $localize`:@@typologies.field.ninja:Typologie ninja`,
            value: (typologie) => (typologie.ninja ? $localize`:@@common.oui:Oui` : ''),
          },
          {
            title: $localize`:@@typologies.column.competents:Compétents`,
            value: (typologie) => this.usage(typologie).competents,
          },
          {
            title: $localize`:@@typologies.column.souhaits:Souhaits`,
            value: (typologie) => this.usage(typologie).souhaits,
          },
          {
            title: $localize`:@@typologies.column.stands:Stands`,
            value: (typologie) => this.usage(typologie).stands,
          },
          ...(this.effetsPlan() === null
            ? []
            : [
                {
                  title: $localize`:@@typologies.column.postes:Postes`,
                  value: (typologie: TypologieItem) => this.effetsPlan()?.get(typologie.id)?.postes,
                },
                {
                  title: $localize`:@@typologies.column.heures:Heures`,
                  value: (typologie: TypologieItem) => this.effetsPlan()?.get(typologie.id)?.heures,
                },
                {
                  title: $localize`:@@typologies.column.sansCompetence:Affectés sans la compétence`,
                  value: (typologie: TypologieItem) =>
                    this.effetsPlan()?.get(typologie.id)?.sansCompetence,
                },
              ]),
          {
            title: $localize`:@@typologies.column.etat:État`,
            value: (typologie) => libelleEtat(this.usage(typologie).etat),
          },
        ],
      },
      paste: () => [
        {
          key: 'code',
          title: $localize`:@@referentiel.field.code:Code`,
          read: (typologie) => typologie.code ?? '',
          write: (typologie, text) => ({ ...typologie, code: text }),
        },
        {
          key: 'label',
          title: $localize`:@@typologies.field.label:Libellé`,
          read: (typologie) => typologie.label,
          write: (typologie, text) => ({ ...typologie, label: text }),
        },
      ],
      duplicate: (typologie, dialog: MatDialog) => {
        dialog.open<TypologieFormDialog, TypologieFormData, boolean>(TypologieFormDialog, {
          data: { typologie: null, modele: typologie },
          width: '40rem',
          maxWidth: '95vw',
          autoFocus: 'first-tabbable',
        });
      },
    });
    const params = inject(ActivatedRoute).snapshot.queryParamMap;
    this.etats.set(readEtatsParam(params.get('etat')));
    keepViewInQueryParams(() => ({
      etat: etatsQueryParam(this.etats()),
    }));
  }

  ngOnInit(): void {
    void this.loadPlanEffects();
  }

  /**
   * What the persisted plan made of each typologie — the seats and hours held
   * on it, and the people sat there without the competence. Read once; a
   * page that fails to read it simply shows no such columns, like an edition
   * never solved.
   */
  private async loadPlanEffects(): Promise<void> {
    try {
      const rapport = await this.planningApi.typologiesReport();
      this.effetsPlan.set(planEffects(rapport.typologies));
    } catch {
      this.effetsPlan.set(null);
    }
  }

  /**
   * « Typologie ninja », ticked in the table: the single holder changes, the
   * server demoting the previous one in the same write. Unticked, nobody is.
   */
  protected async setNinja(typologie: TypologieItem, ninja: boolean): Promise<void> {
    const label = $localize`:@@typologies.entityLabel:Typologie`;
    await this.crud.save('typologies', { ...typologie, ninja }, typologie.id, label, {
      text: typologie.label,
    });
  }

  protected usage(typologie: TypologieItem): UsageTypologie {
    return this.usages().get(typologie.id) ?? usageVide(typologie.id);
  }

  /** The state filter — the quick filter already ran, the sort comes after. */
  protected override refine(lignes: readonly TypologieItem[]): readonly TypologieItem[] {
    const etats = this.etats();
    return etats.length === 0
      ? lignes
      : lignes.filter((ligne) => etats.includes(this.usage(ligne).etat));
  }

  protected removeChip(): void {
    this.etats.set([]);
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
    this.filtre.set('');
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

  protected ninjaLabel(typologie: TypologieItem): string {
    return $localize`:@@typologies.ninja.cocher:Typologie ninja : ${typologie.label || typologie.id}:typologie:`;
  }

  protected ninjaHint(): string {
    return $localize`:@@typologies.field.ninjaHint:Un animateur qui possède cette typologie sait s'adapter : il peut être affecté à n'importe quel stand.`;
  }

  protected grilleLabel(typologie: TypologieItem): string {
    return $localize`:@@typologies.grille.lien:Saisir les appréciations de ${typologie.label || typologie.id}:typologie: dans la grille`;
  }

  protected competentsHint(usage: UsageTypologie): string {
    return repartitionCompetents(usage);
  }
}

/** Typologie id → seats, hours and people sat without the competence; `null` when no seat is held. */
export function planEffects(
  lignes: readonly LigneTypologie[],
): ReadonlyMap<string, EffetPlan> | null {
  if (!lignes.some((ligne) => ligne.postes > 0)) {
    return null;
  }
  return new Map(
    lignes.map((ligne) => [
      ligne.typologie,
      {
        postes: ligne.postes,
        heures: Math.round(ligne.heures * 10) / 10,
        sansCompetence: ligne.affectesSansCompetence.length,
      },
    ]),
  );
}
