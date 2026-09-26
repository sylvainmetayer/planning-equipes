import {
  ChangeDetectionStrategy,
  Component,
  computed,
  DestroyRef,
  inject,
  OnInit,
  signal,
  ViewEncapsulation,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MatDialog } from '@angular/material/dialog';
import { MatIconModule } from '@angular/material/icon';
import { MatButtonToggleModule } from '@angular/material/button-toggle';
import { MatMenuModule } from '@angular/material/menu';
import { MatSortModule } from '@angular/material/sort';
import { MatTableModule } from '@angular/material/table';
import { MatTooltipModule } from '@angular/material/tooltip';
import { ActivatedRoute, ParamMap, Router, RouterLink } from '@angular/router';
import { StandsApi } from '../../core/api/stands-api';
import { labelStandsPluriel } from '../../core/entity-labels';
import { NotificationService } from '../../core/notification.service';
import { PlanningStateService } from '../../core/planning-state.service';
import { ProblemesStore } from '../../core/problemes.store';
import { ReferenceTablePage } from '../../core/reference-table-page';
import { PlanningEvenement, RapportOuvertures, Stand } from '../../core/models';
import { typologieLabels } from '../../core/typologie-colors';
import { EmplacementsPage } from '../emplacements/emplacements-page';
import { OngletStands, readOngletStands } from './stands-onglet';
import { BulkActionsBar } from '../../shared/bulk-actions-bar';
import { EmptyState } from '../../shared/empty-state';
import { FilterChip, FilterChips } from '../../shared/filter-chips';
import { RowMenu } from '../../shared/row-menu';
import { RowWarning } from '../../shared/row-warning';
import { PasteColumn } from '../../core/paste-rows';
import {
  NO_SORT,
  keepViewInQueryParams,
  optionalParam,
  readSort,
  sortQueryParams,
} from '../../core/view-query-params';
import { GelNotice } from '../../shared/gel-notice';
import { injectGelReferentiel } from '../../core/gel-referentiel.store';
import { ImportedRowsFilter } from '../../shared/imported-rows-filter';
import { TableFilter } from '../../shared/table-filter';
import { ConfirmService } from '../../shared/confirm-dialog';
import { StandBulkEditData, StandBulkEditDialog } from './stand-bulk-edit-dialog';
import { MAX_STANDS_COMPARES, MIN_STANDS_COMPARES } from '../ouvertures/comparaison-ouvertures';
import { StandCreationDialog } from './stand-creation-dialog';
import { StandFormData, StandFormDialog } from './stand-form-dialog';
import { ImportButton } from '../../shared/import-button';
import {
  StandSortContext,
  coverageRate,
  standCoverage,
  standOpenings,
  standSortValue,
  typologieLabelsOf,
} from './stand-order';

/** The columns of the table that sort, on what {@link standSortValue} reads. */
const SORTED_COLUMNS = [
  'id',
  'code',
  'nom',
  'effectif',
  'typologies',
  'emplacement',
  'ouvert',
  'couverture',
];

/**
 * Stands (`/stands`), two tabs chosen by `?onglet=`: the stands, and
 * `?onglet=lieux` the places they stand on (the former `/emplacements`, map
 * included — drawn in a `@defer`, so Leaflet stays out of this chunk).
 *
 * The table sorts on every column (`?sort=&dir=`); a name opens the stand's
 * fiche, which walks the table in that order, a game category the Typologies
 * screen, a location the Lieux tab. « Ouvert » counts the days a stand opens
 * and the seats it asks for; once a plan is computed, « Couverture » says how
 * many of them are held. « Ajouter » is the guided creation.
 *
 * Rows named by a feasibility cause carry an alert icon whose tooltip is the
 * cause's own message, so a stand nobody can staff is visible where it is
 * edited, not only on the Problèmes page. Rows are multi-selectable, for a
 * bulk delete or a bulk edit of the fields stands share.
 */
@Component({
  selector: 'app-stands-page',
  imports: [
    ImportedRowsFilter,
    ImportButton,
    MatCardModule,
    MatButtonModule,
    MatButtonToggleModule,
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
    EmplacementsPage,
  ],
  templateUrl: './stands-page.html',
  styleUrls: ['../../../styles/horaires-stand.css', './stands-page.css'],
  // Global by design (AGENTS.md): loaded with the route, unscoped like the partial it was.
  encapsulation: ViewEncapsulation.None,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class StandsPage extends ReferenceTablePage<Stand> implements OnInit {
  private readonly gel = injectGelReferentiel();
  /**
   * What a STANDS freeze refuses as a whole (ADR 0052): creating, deleting,
   * compacting the opening hours, and the bulk actions — a bulk edit sets the
   * shared fields a freeze covers. The single edit stays open: a rename or a
   * new location passes, and the form shows the frozen fields read-only.
   */
  protected readonly standsLocked = computed(
    () => this.editingLocked() || this.gel.isFrozen('STANDS'),
  );

  /** The tab on screen — `?onglet=lieux` for the places. */
  protected readonly onglet = signal<OngletStands>('stands');
  /**
   * Set once the reader switches tabs: leaving the stands for the Lieux then
   * clears the table's keys from the URL, which the Lieux table reads on
   * arrival. A link that lands on the Lieux tab keeps its keys for it.
   */
  private tabSwitched = false;

  /** « Couverture » only once a plan holds somebody: before, there is nothing to cover. */
  protected readonly columns = computed(() => [
    'select',
    'id',
    'code',
    'nom',
    'effectif',
    'typologies',
    'emplacement',
    'ouvert',
    ...(this.coverage().size > 0 ? ['couverture'] : []),
    'actions',
  ]);

  /** The table's sort, carried to the fiche so its « précédent / suivant » walks the same order. */
  protected readonly ficheParams = computed(() =>
    Object.fromEntries(
      Object.entries(sortQueryParams(this.sort())).filter(([, value]) => value !== null),
    ),
  );

  /** The template names the rows after the entity, as the other pages do. */
  protected readonly standsFiltres = this.lignesFiltrees;

  /**
   * `?typologie=` and `?emplacement=`: the stands proposing that game
   * category, or standing on that location — where the counts of the
   * Typologies screen and the fiche of a location lead.
   */
  protected readonly typologieFiltre = signal('');
  protected readonly emplacementFiltre = signal('');

  /** The two filters as chips above the table, named rather than by their ids. */
  protected readonly chips = computed<FilterChip[]>(() => {
    const chips: FilterChip[] = [];
    const typologie = this.typologieFiltre();
    if (typologie) {
      const label =
        this.store.typologies().find((each) => each.id === typologie)?.label ?? typologie;
      chips.push({
        key: 'typologie',
        label: $localize`:@@stands.chip.typologie:Typologie : ${label}:typologie:`,
      });
    }
    const emplacement = this.emplacementFiltre();
    if (emplacement) {
      const nom =
        this.store.emplacements().find((each) => each.id === emplacement)?.nom ?? emplacement;
      chips.push({
        key: 'emplacement',
        label: $localize`:@@stands.chip.emplacement:Emplacement : ${nom}:emplacement:`,
      });
    }
    return chips;
  });

  /** True while the compaction round-trip is in flight, to keep it from being fired twice. */
  protected readonly compactageEnCours = signal(false);

  /** Holds `causeParStandId`: a memoised map, so each row only does a lookup. */
  protected readonly problemes = inject(ProblemesStore);

  /** Bounds the form's callback: this page is lazy, and a callback on a dead one writes into nothing. */
  private readonly destroyRef = inject(DestroyRef);
  private readonly standsApi = inject(StandsApi);
  private readonly confirm = inject(ConfirmService);
  private readonly notifications = inject(NotificationService);
  private readonly planningState = inject(PlanningStateService);
  private readonly router = inject(Router);

  /** The openings report: the « Ouvert » column. `null` until it is in, and then the column says nothing. */
  private readonly ouvertures = signal<RapportOuvertures | null>(null);
  /** The persisted plan: the « Couverture » column; `null` when unreadable, and the column is left out. */
  private readonly planning = signal<PlanningEvenement | null>(null);

  protected readonly openings = computed(() => standOpenings(this.ouvertures()));
  protected readonly coverage = computed(() => standCoverage(this.planning()));
  private readonly sortContext = computed<StandSortContext>(() => ({
    typologies: typologieLabels(this.store.typologies()),
    openings: this.openings(),
    coverage: this.coverage(),
  }));

  constructor() {
    super({
      rows: (store) => store.stands(),
      id: (stand) => stand.id,
      champsFiltre: (stand, store) => [
        stand.id,
        stand.code,
        stand.nom,
        ...typologieLabelsOf(stand, typologieLabels(store.typologies())),
        stand.emplacement?.nom,
        stand.emplacement?.id,
      ],
      // `?edit=` is answered by the route, which opens the stand's fiche with its form.
      editParam: null,
      // The name of a row, and Entrée on it: the stand's fiche, walking this table's order.
      open: (stand) =>
        void this.router.navigate(['/stands', stand.id], { queryParams: this.ficheParams() }),
      // The Lieux tab's table owns the same keys while it is on screen.
      viewParams: (view) => {
        if (this.onglet() === 'stands') {
          return view;
        }
        return this.tabSwitched ? { q: null, sort: null, dir: null } : {};
      },
      drafts: {
        type: 'stand',
        describe: (ids) => $localize`:@@stands.brouillon.orphelin:Le stand ${ids}:ids:`,
      },
      formulaire: (stand, dialog: MatDialog) => {
        dialog
          .open<StandFormDialog, StandFormData, boolean>(StandFormDialog, {
            data: { stand },
            width: '40rem',
            maxWidth: '95vw',
            autoFocus: 'first-tabbable',
          })
          .afterClosed()
          .pipe(takeUntilDestroyed(this.destroyRef))
          .subscribe((ecrit) => {
            // The openings are computed server-side from the schedule that was
            // just written: the « Ouvert » column follows it.
            if (ecrit) {
              void this.chargerOuvertures();
            }
          });
      },
      ressource: 'stands',
      libelle: () => $localize`:@@stands.entityLabel:Stand`,
      name: (stand) => stand.nom,
      libellePluriel: labelStandsPluriel,
      // The fiche walks the same order through `sortStands`, over the same values.
      sortValues: Object.fromEntries(
        SORTED_COLUMNS.map((column) => [
          column,
          (stand: Stand) => standSortValue(stand, column, this.sortContext()),
        ]),
      ),
      export: {
        name: 'stands',
        columns: (store) => [
          { title: $localize`:@@common.id:Id`, value: (stand) => stand.id },
          { title: $localize`:@@referentiel.field.code:Code`, value: (stand) => stand.code },
          { title: $localize`:@@common.nom:Nom`, value: (stand) => stand.nom },
          {
            title: $localize`:@@stands.field.effectifMin:Effectif minimum`,
            value: (stand) => stand.effectifMin,
          },
          {
            title: $localize`:@@stands.field.effectifMax:Effectif maximum`,
            value: (stand) => stand.effectifMax,
          },
          {
            title: $localize`:@@stands.column.typologies:Typologies`,
            value: (stand) => typologieLabelsOf(stand, typologieLabels(store.typologies())),
          },
          {
            title: $localize`:@@stands.column.lieu:Lieu`,
            value: (stand) => stand.emplacement?.nom,
          },
          {
            title: $localize`:@@stands.column.ouvert:Ouvert`,
            value: (stand) => this.ouvertLabel(stand),
          },
          ...(this.coverage().size > 0
            ? [
                {
                  title: $localize`:@@stands.column.couverture:Couverture`,
                  value: (stand: Stand) => this.coverageLabel(stand),
                },
              ]
            : []),
        ],
      },
      paste: () => [
        {
          key: 'code',
          title: $localize`:@@referentiel.field.code:Code`,
          read: (stand) => stand.code ?? '',
          write: (stand, text) => ({ ...stand, code: text }),
        },
        {
          key: 'nom',
          title: $localize`:@@common.nom:Nom`,
          read: (stand) => stand.nom,
          write: (stand, text) => ({ ...stand, nom: text }),
        },
        effectifColumn('effectifMin', $localize`:@@stands.field.effectifMin:Effectif minimum`),
        effectifColumn('effectifMax', $localize`:@@stands.field.effectifMax:Effectif maximum`),
      ],
      duplicate: (stand, dialog: MatDialog) => {
        dialog.open<StandFormDialog, StandFormData, boolean>(StandFormDialog, {
          data: { stand: null, modele: stand },
          width: '40rem',
          maxWidth: '95vw',
          autoFocus: 'first-tabbable',
        });
      },
    });
    // Followed rather than read once: a location of the « Lieu » column, or
    // the palette's « Stands › Lieux », navigates to this very route with
    // another `onglet`, and the router reuses the page instead of building it
    // again. `replaceState` (ADR 0018) emits nothing here, so the effect below
    // cannot feed this subscription.
    const route = inject(ActivatedRoute, { optional: true });
    let arrived = false;
    route?.queryParamMap?.pipe(takeUntilDestroyed()).subscribe((params) => {
      this.followAddress(params, arrived);
      arrived = true;
    });
    keepViewInQueryParams(() => ({
      onglet: this.onglet() === 'lieux' ? 'lieux' : null,
      typologie: optionalParam(this.typologieFiltre()),
      emplacement: optionalParam(this.emplacementFiltre()),
    }));
    void this.problemes.reloadFeasibility();
  }

  /**
   * The address, on arrival and on every later navigation to this route: the
   * tab and the two chip filters. A link landing on the Lieux tab keeps its
   * `q`, `sort` and `dir` for the Lieux table; one landing on the stands
   * applies them — on arrival the base class already did.
   */
  private followAddress(params: ParamMap, again: boolean): void {
    this.typologieFiltre.set(params.get('typologie') ?? '');
    this.emplacementFiltre.set(params.get('emplacement') ?? '');
    this.onglet.set(readOngletStands(params.get('onglet')));
    if (this.onglet() === 'lieux') {
      // The filter and the sort the address carries are the Lieux table's.
      this.tabSwitched = false;
      this.filtre.set('');
      this.sort.set(NO_SORT);
    } else if (again) {
      this.filtre.set(params.get('q') ?? '');
      this.sort.set(readSort(params));
    }
  }

  protected changerOnglet(onglet: OngletStands): void {
    this.tabSwitched = true;
    this.onglet.set(onglet);
  }

  /** « Ajouter » : the guided creation, then the new stand's fiche. */
  protected override openCreate(): void {
    this.dialog
      .open<StandCreationDialog, void, string | null>(StandCreationDialog, {
        width: '44rem',
        maxWidth: '95vw',
        autoFocus: 'first-tabbable',
      })
      .afterClosed()
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe((id) => {
        if (id) {
          void this.router.navigate(['/stands', id]);
        }
      });
  }

  /** The chip filters — the quick filter already ran, the sort comes after. */
  protected override refine(lignes: readonly Stand[]): readonly Stand[] {
    const typologie = this.typologieFiltre();
    const emplacement = this.emplacementFiltre();
    return lignes.filter(
      (stand) =>
        (!typologie || (stand.typologiesProposees ?? []).includes(typologie)) &&
        (!emplacement || stand.emplacement?.id === emplacement),
    );
  }

  protected removeChip(key: string): void {
    if (key === 'typologie') {
      this.typologieFiltre.set('');
    } else {
      this.emplacementFiltre.set('');
    }
  }

  protected clearChips(): void {
    this.typologieFiltre.set('');
    this.emplacementFiltre.set('');
  }

  ngOnInit(): void {
    void this.chargerOuvertures();
    void this.loadPlanning();
  }

  /** The seats already held, read from the persisted plan; a failure only costs the column. */
  private async loadPlanning(): Promise<void> {
    try {
      this.planning.set(await this.planningState.loadForDisplay());
    } catch {
      this.planning.set(null);
    }
  }

  /** Read again after a write: the « Ouvert » column follows the schedule that was just saved. */
  private async chargerOuvertures(): Promise<void> {
    try {
      this.ouvertures.set(await this.standsApi.openings());
    } catch {
      // The column simply stays empty; the Ouvertures page reports the failure itself.
      this.ouvertures.set(null);
    }
  }

  /** The step before the stands, named on the empty state. */
  protected previousStepLabel(): string {
    return $localize`:@@stands.empty.typologies:Saisir les typologies`;
  }

  /** The stand's game categories, by label: its ids are generated (T1, T2…) and read as nothing. */
  protected typologiesOf(stand: Stand): { id: string; label: string }[] {
    const labels = typologieLabels(this.store.typologies());
    return (stand.typologiesProposees ?? []).map((id) => ({ id, label: labels.get(id) ?? id }));
  }

  /** « 14 j · 56 postes » — the days the stand opens and the seats they ask for. */
  protected ouvertLabel(stand: Stand): string {
    const opening = this.openings().get(stand.id);
    if (!opening) {
      return '—';
    }
    return $localize`:@@stands.ouvert.valeur:${opening.joursOuverts}:jours: j · ${opening.postes}:postes: postes`;
  }

  /** « 54/56 » and its share; nothing for a stand without seats. */
  protected coverageLabel(stand: Stand): string {
    const coverage = this.coverage().get(stand.id);
    const rate = coverageRate(coverage);
    if (!coverage || rate === null) {
      return '—';
    }
    return `${coverage.pourvus}/${coverage.postes} (${Math.round(rate * 100)} %)`;
  }

  protected coverageIncomplete(stand: Stand): boolean {
    const rate = coverageRate(this.coverage().get(stand.id));
    return rate !== null && rate < 1;
  }

  protected effectifSuffix(stand: Stand): string {
    const majeurs = stand.reserveMajeurs ? $localize`:@@stands.suffix.majeurs: · majeurs` : '';
    const premium = stand.premium ? $localize`:@@stands.suffix.premium: · premium` : '';
    const epuisant =
      stand.niveauEffort === 'EPUISANT' ? $localize`:@@stands.suffix.epuisant: · épuisant` : '';
    return `${majeurs}${premium}${epuisant}`;
  }

  protected emplacementLabel(stand: Stand): string {
    return stand.emplacement?.nom || '—';
  }

  /**
   * Rewrites hand-entered dated windows as the recurring rules they repeat.
   * Always a dry run first: the report it returns is what the confirmation
   * dialog shows, so nothing is written before the user has seen the trade.
   */
  protected async compacterHoraires(): Promise<void> {
    this.compactageEnCours.set(true);
    try {
      await this.lancerCompactage();
    } catch (error) {
      // Same channel as every other write of this page: a snack bar, not an
      // unhandled rejection swallowed by the click handler.
      this.crud.reportError(error);
    } finally {
      this.compactageEnCours.set(false);
    }
  }

  private async lancerCompactage(): Promise<void> {
    const apercu = await this.standsApi.compactSchedules(false);
    if (apercu.standsCompactes === 0) {
      this.notifications.notify({
        title: $localize`:@@stands.compactage.rienATitle:Aucun horaire à compacter`,
        message: $localize`:@@stands.compactage.rienAMessage:Aucun stand ne répète un motif qui pourrait devenir une règle.`,
        variant: 'info',
      });
      return;
    }
    const confirme = await this.confirm.ask({
      title: $localize`:@@stands.compactage.confirmTitle:Compacter les horaires ?`,
      message: $localize`:@@stands.compactage.confirmMessage:${apercu.standsCompactes}:stands: stand(s) : ${apercu.fenetresAvant}:avant: plages datées remplacées par ${apercu.fenetresApres}:apres: règles et exceptions. Un stand dont les règles changeraient ses ouvertures est laissé inchangé.`,
      confirmLabel: $localize`:@@stands.compactage.confirmLabel:Compacter`,
    });
    if (!confirme) {
      return;
    }
    const rapport = await this.standsApi.compactSchedules(true);
    await this.crud.reload();
    this.notifications.notify({
      title: $localize`:@@stands.compactage.doneTitle:Horaires compactés`,
      message: $localize`:@@stands.compactage.doneMessage:${rapport.standsCompactes}:stands: stand(s) compacté(s), ${rapport.fenetresAvant}:avant: plages ramenées à ${rapport.fenetresApres}:apres: entrées.`,
      variant: 'success',
    });
  }

  /**
   * The link to the openings comparator for the ticked stands, or null outside
   * two to eight — one stand has nothing to be compared with, and past eight
   * the Consulter grid, filtered, is the tool.
   */
  protected readonly comparaisonParams = computed(() => {
    const ids = this.selection.selectedIds();
    return ids.length >= MIN_STANDS_COMPARES && ids.length <= MAX_STANDS_COMPARES
      ? { vue: 'comparer', stands: ids.join(',') }
      : null;
  });

  protected editSelection(): void {
    const selectionnes = new Set(this.selection.selectedIds());
    this.openBulkEdit(this.store.stands().filter((stand) => selectionnes.has(stand.id)));
  }

  /**
   * « Édition groupée » : the ticked stands, or, with none ticked, every stand
   * the table shows — the dialog names how many, and writes only the fields
   * explicitly opted into.
   */
  protected bulkEdit(): void {
    if (this.selection.hasSelection()) {
      this.editSelection();
      return;
    }
    this.openBulkEdit([...this.standsFiltres()]);
  }

  private openBulkEdit(stands: Stand[]): void {
    this.dialog.open<StandBulkEditDialog, StandBulkEditData, boolean>(StandBulkEditDialog, {
      data: { stands },
      width: '48rem',
      maxWidth: '95vw',
      autoFocus: 'first-tabbable',
    });
  }
}

/** A pasted headcount: a whole number of at least one, the minimum never above the maximum. */
function effectifColumn(key: 'effectifMin' | 'effectifMax', title: string): PasteColumn<Stand> {
  return {
    key,
    title,
    read: (stand) => String(stand[key]),
    write: (stand, text) => {
      const valeur = Number(text);
      if (!Number.isInteger(valeur) || valeur < 1) {
        return $localize`:@@stands.collage.effectif:un nombre entier d'au moins 1`;
      }
      const patched = { ...stand, [key]: valeur };
      return patched.effectifMin > patched.effectifMax
        ? $localize`:@@stands.collage.bornes:le minimum dépasserait le maximum`
        : patched;
    },
  };
}
