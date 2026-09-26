import { LiveAnnouncer } from '@angular/cdk/a11y';
import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  ElementRef,
  computed,
  effect,
  inject,
  OnInit,
  signal,
  viewChild,
  ViewEncapsulation,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatButtonToggleModule } from '@angular/material/button-toggle';
import { MatCardModule } from '@angular/material/card';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MatDialog } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatMenuModule } from '@angular/material/menu';
import { MatSelectModule } from '@angular/material/select';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatSortModule } from '@angular/material/sort';
import { MatTableModule } from '@angular/material/table';
import { MatTooltipModule } from '@angular/material/tooltip';
import { RouterLink } from '@angular/router';
import { ActivatedRoute } from '@angular/router';
import {
  NO_SORT,
  SortState,
  consumeQueryParam,
  currentViewParams,
  keepViewInQueryParams,
  readSort,
  sortQueryParams,
} from '../../core/view-query-params';
import { ApiService } from '../../core/api.service';
import { StandsApi } from '../../core/api/stands-api';
import { CSV_CONTENT_TYPE, CsvColumn, csvFileName, toCsv } from '../../core/csv-export';
import { PasteColumn, pastedText, planPaste } from '../../core/paste-rows';
import { SortValue, compareSortValues } from '../../core/table-sort';
import { EmptyState } from '../../shared/empty-state';
import { PastePreviewService } from '../../shared/paste-preview-dialog';
import { RowMenu } from '../../shared/row-menu';
import { RowWarning } from '../../shared/row-warning';
import {
  IMPORTED_IDS_PARAM,
  importedIdsParam,
  keptByImportedIds,
  readImportedIds,
} from '../../core/imported-rows';
import { ImportedRowsFilter } from '../../shared/imported-rows-filter';
import { CreneauxApi } from '../../core/api/creneaux-api';
import { ConsignesStore } from '../../core/consignes.store';
import { JoursFeriesService } from '../../core/jours-feries.service';
import { PastilleFerie } from '../../shared/pastille-ferie';
import { NotificationService } from '../../core/notification.service';
import { ConfirmService } from '../../shared/confirm-dialog';
import { labelCreneauxPluriel } from '../../core/entity-labels';
import { PlanningResolutionStore } from '../../core/planning-resolution.store';
import { ProblemesStore } from '../../core/problemes.store';
import { ReferenceCrudService } from '../../core/reference-crud.service';
import { injectGelReferentiel } from '../../core/gel-referentiel.store';
import { GelNotice } from '../../shared/gel-notice';
import { ReferenceDataStore } from '../../core/reference-data.store';
import { creneauName } from '../../core/reference-labels';
import { SolverJobService } from '../../core/solver-job.service';
import { TableNavigation, trackRowById } from '../../core/table-navigation';
import { TableSelection } from '../../core/table-selection';
import {
  CauseInfaisabilite,
  Creneau,
  DiagnosticGrille,
  RapportDerivation,
  RapportGrille,
  RapportRecurrence,
} from '../../core/models';
import { BulkActionsBar } from '../../shared/bulk-actions-bar';
import { CreneauBulkEditData, CreneauBulkEditDialog } from './creneau-bulk-edit-dialog';
import { CreneauFormData, CreneauFormDialog } from './creneau-form-dialog';
import { CreneauDerivationData, CreneauDerivationDialog } from './creneau-derivation-dialog';
import { CreneauSerieData, CreneauSerieDialog } from './creneau-serie-dialog';
import { JourneesTypesCard } from './journees-types-card';
import {
  OuverturesCreneau,
  bilanGrille,
  gridAnomalyIcon,
  openingsByCreneau,
  trierAnomalies,
} from './grille-creneaux';
import { ImportButton } from '../../shared/import-button';

/**
 * Timeslots CRUD: event day, date and hours of every schedulable slot,
 *
 * Slots are multi-selectable, for a bulk delete or to move a whole batch to
 * another group / realign its hours.
 *
 * <p>The grid is also read as a whole here: the edition declares what its
 * créneaux <em>are</em> (amplitudes to slice, or final vacations — the data
 * alone cannot tell, and every verdict depends on it), a rule adds a whole
 * series at once, and the server's verdict on the grid is on the page rather
 * than behind an assistant.</p>
 */
@Component({
  selector: 'app-creneaux-page',
  imports: [
    ImportButton,
    ImportedRowsFilter,
    FormsModule,
    MatCardModule,
    MatCheckboxModule,
    MatFormFieldModule,
    MatInputModule,
    MatMenuModule,
    MatSelectModule,
    MatSortModule,
    MatTableModule,
    MatButtonModule,
    MatButtonToggleModule,
    MatIconModule,
    MatProgressSpinnerModule,
    MatTooltipModule,
    RouterLink,
    BulkActionsBar,
    EmptyState,
    JourneesTypesCard,
    PastilleFerie,
    RowMenu,
    RowWarning,
    GelNotice,
  ],
  templateUrl: './creneaux-page.html',
  styleUrls: [
    './creneaux.css',
    '../../../styles/grille-creneaux.css',
    '../../../styles/horaires-stand.css',
    '../../../styles/ouvertures.css',
  ],
  // Global by design (AGENTS.md): loaded with the route, unscoped like the partial it was.
  encapsulation: ViewEncapsulation.None,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class CreneauxPage implements OnInit {
  /** Bounds the dialog callbacks: this page is lazy and rebuilt on every visit, a callback on a dead one writes into nothing. */
  private readonly destroyRef = inject(DestroyRef);
  protected readonly store = inject(ReferenceDataStore);
  protected readonly jobs = inject(SolverJobService);
  /** Editing is disabled while a solve/analysis runs, to avoid corrupting the data it reads. */
  protected readonly editingLocked = this.jobs.editingLocked;
  private readonly gel = injectGelReferentiel();
  /** Creating, deleting or generating créneaux is what a CRENEAUX freeze refuses (ADR 0052). */
  protected readonly gridLocked = computed(
    () => this.editingLocked() || this.gel.isFrozen('CRENEAUX'),
  );

  private readonly problemes = inject(ProblemesStore);
  private readonly crud = inject(ReferenceCrudService);
  /** The consignes (issue #4): a date under one, and a créneau one added, are marked in the table. */
  protected readonly consignes = inject(ConsignesStore);
  /** The public holidays of the grid's dates, marked beside them. */
  protected readonly feries = inject(JoursFeriesService);
  private readonly dialog = inject(MatDialog);
  private readonly resolution = inject(PlanningResolutionStore);

  protected readonly columns = [
    'select',
    'jour',
    'date',
    'horaires',
    'ouvertures',
    'probleme',
    'actions',
  ];

  /** Sorting, so the slots at fault can be grouped instead of hunted for; `?sort=` in the URL. */
  protected readonly sort = signal<SortState>(NO_SORT);

  /**
   * `?ids=`: the timeslots an import just wrote, which its « Voir les N lignes
   * importées » opens the page on — shown as a filter, dropped by « Tout
   * afficher ». `null` when the address names none.
   */
  protected readonly importedIds = signal<ReadonlySet<string> | null>(
    readImportedIds(currentViewParams().get(IMPORTED_IDS_PARAM)),
  );

  /**
   * `store.creneaux()` is already chronological (jour, heureDebut), which is
   * the order of a list of timeslots: an unsorted view keeps it, and so do
   * the ties of a sorted column.
   */
  protected readonly creneauxAffiches = computed(() => {
    const ids = this.importedIds();
    const creneaux = this.store.creneaux().filter((creneau) => keptByImportedIds(ids, creneau.id));
    const { active, direction } = this.sort();
    if (!active || !direction) {
      return creneaux;
    }
    const rang = new Map(creneaux.map((creneau, index) => [creneau.id, index]));
    const signe = direction === 'asc' ? 1 : -1;
    return creneaux.sort((a, b) => {
      const gauche = this.valeurTri(a, active);
      const droite = this.valeurTri(b, active);
      const vide = (valeur: SortValue) => valeur === null || valeur === undefined;
      const compare =
        vide(gauche) || vide(droite)
          ? compareSortValues(gauche, droite)
          : signe * compareSortValues(gauche, droite);
      // Ties in the order of a list of timeslots: day, start, then the store's.
      return (
        compare ||
        a.jour - b.jour ||
        (a.heureDebut ?? '').localeCompare(b.heureDebut ?? '') ||
        (rang.get(a.id) ?? 0) - (rang.get(b.id) ?? 0)
      );
    });
  });

  /** What each column sorts on: `probleme` on the shortfall, so the worst slots come first. */
  private valeurTri(creneau: Creneau, colonne: string): SortValue {
    switch (colonne) {
      case 'jour':
        // The day, then its start: « J1 09:00 » before « J1 14:00 », both ways.
        return (
          creneau.jour * 10000 + Number((creneau.heureDebut ?? '').slice(0, 5).replace(':', ''))
        );
      case 'date':
        return creneau.date;
      case 'horaires':
        return creneau.heureDebut;
      case 'ouvertures':
        return this.ouvertures().get(creneau.id)?.postes ?? 0;
      case 'probleme':
        return this.causeParCreneau().get(creneau.id)?.manque ?? 0;
      default:
        return undefined;
    }
  }

  /** Timeslot id → the stands open on it and the seats they yield, from the openings report. */
  protected readonly ouvertures = signal<ReadonlyMap<number, OuverturesCreneau>>(new Map());

  /** The day templates' card: « Reconnaître » lives in this page's menu, and acts on the card. */
  private readonly journeesTypesCard = viewChild(JourneesTypesCard);

  /**
   * Keyed on the displayed slots, so "select all" only ever reaches what the
   * page is showing. It used to be justified by the groupe-de-créneaux
   * filter, which #172 removed along with the groups themselves; the keying
   * outlives it because any future narrowing of the list must behave the
   * same way.
   */
  protected readonly selection = new TableSelection<number>(
    computed(() => this.creneauxAffiches().map((creneau) => creneau.id)),
  );

  private readonly hote = inject<ElementRef<HTMLElement>>(ElementRef);

  /**
   * Roving tabindex over the rows: the arrows move the focus, Entrée opens the
   * row, Espace ticks it. `core/table-navigation.ts` holds the whole mechanism,
   * shared with the other reference-data tables.
   */
  /** Rows kept across a reload of the store, and the focus with them. */
  protected readonly trackById = trackRowById;
  protected readonly navigation = new TableNavigation({
    rows: this.creneauxAffiches,
    id: (creneau: Creneau) => creneau.id,
    host: () => this.hote.nativeElement,
    selection: this.selection,
    open: (creneau: Creneau) => this.ouvrirLigne(creneau),
    announcer: inject(LiveAnnouncer),
  });

  /**
   * Entrée on a row. A créneau has no read-only detail view — it carries a day,
   * a date and two times, all four already in the table — so the keyboard opens
   * the form straight away, and refuses it while a solve runs exactly like the
   * row's own "Modifier" button.
   *
   * <p>Refusing returns `false`, so the navigation leaves the key alone rather
   * than eating it for nothing. The refusal stays silent on purpose: the page
   * already carries a permanent banner saying the edition is locked for as
   * long as it is, and one snack bar per keystroke would only repeat it.</p>
   */
  private ouvrirLigne(creneau: Creneau): boolean {
    if (this.editingLocked()) {
      return false;
    }
    this.edit(creneau);
    return true;
  }

  /** « 08/07 10:00–12:00 »: what a row's menu is named after. */
  protected creneauLabel(creneau: Creneau): string {
    return creneauName(creneau);
  }

  protected ouverturesLabel(creneau: Creneau): string {
    const ouvert = this.ouvertures().get(creneau.id);
    return $localize`:@@creneaux.ouvertures.lien:${ouvert?.stands ?? 0}:stands: stand(s) ouvert(s), ${ouvert?.postes ?? 0}:postes: poste(s) : voir les horaires des stands ce jour`;
  }

  /** The date and hours of a row, clicked: its form, as Entrée does. */
  protected openRow(creneau: Creneau): void {
    this.ouvrirLigne(creneau);
  }

  /**
   * Créneau id → the feasibility cause naming it, re-keyed on the numeric id so
   * a row is a plain map lookup. The report carries `creneauId` as a string
   * while `Creneau.id` is a number, hence the `String(...)` normalisation here
   * rather than in every template.
   */
  protected readonly causeParCreneau = computed<Map<number, CauseInfaisabilite>>(() => {
    const parId = this.problemes.causeParCreneauId();
    const index = new Map<number, CauseInfaisabilite>();
    if (parId.size === 0) {
      return index;
    }
    for (const creneau of this.store.creneaux()) {
      const cause = parId.get(String(creneau.id));
      if (cause) {
        index.set(creneau.id, cause);
      }
    }
    return index;
  });

  constructor() {
    keepViewInQueryParams(() => ({ [IMPORTED_IDS_PARAM]: importedIdsParam(this.importedIds()) }));
    const params = inject(ActivatedRoute, { optional: true })?.snapshot?.queryParamMap;
    if (params) {
      this.sort.set(readSort(params));
    }
    keepViewInQueryParams(() => ({ ...sortQueryParams(this.sort()) }));
    const chargement = this.crud.reload();
    void this.problemes.reloadFeasibility();
    void this.consignes.reload();
    // The holidays follow the grid: a year is read once, whatever is added to it.
    effect(() => void this.feries.load(this.store.creneaux().map((creneau) => creneau.date)));
    // `?edit=<id>`: a link from a symptom lands here with the créneau to open.
    // Followed, obeyed, then dropped — see `reference-table-page.ts`.
    consumeQueryParam('edit', async (edit) => {
      await chargement;
      const creneau = this.store.creneaux().find((candidat) => String(candidat.id) === edit);
      if (creneau) {
        this.openDialog(creneau);
      }
    });
  }

  ngOnInit(): void {
    void this.chargerGrille();
  }

  /** « Tout afficher »: the whole grid again. */
  protected showAllRows(): void {
    this.importedIds.set(null);
  }

  /* -------------------------- The grid as a whole -------------------------- */

  /** Same wording as the grid's header: the marker means the same thing on both screens. */
  protected readonly relaisRepasTooltip = $localize`:@@ouvertures.saisie.relaisRepas:Relais repas : les sièges générés valent la moitié de l'effectif saisi, arrondie au supérieur`;

  protected readonly diagnostic = signal<DiagnosticGrille | null>(null);
  protected readonly controle = signal<RapportGrille | null>(null);
  protected readonly controleLoading = signal(false);

  protected readonly bilan = computed(() => {
    const controle = this.controle();
    return controle ? bilanGrille(controle) : null;
  });
  protected readonly anomaliesGrille = computed(() =>
    trierAnomalies(this.controle()?.anomalies ?? []),
  );
  protected readonly gridAnomalyIcon = gridAnomalyIcon;

  private async chargerGrille(): Promise<void> {
    await this.rechargerVerdict();
  }

  /**
   * The diagnostic, the verdict and the openings, read again after anything
   * that changes the grid: the control runs by itself, it is never a button
   * to remember.
   */
  protected async rechargerVerdict(): Promise<void> {
    this.controleLoading.set(true);
    try {
      const [diagnostic, controle] = await Promise.all([
        this.creneauxApi.diagnostic(),
        this.creneauxApi.control(),
      ]);
      this.diagnostic.set(diagnostic);
      this.controle.set(controle);
    } catch (error) {
      this.crud.reportError(error);
    } finally {
      this.controleLoading.set(false);
    }
    await this.loadOpenings();
  }

  /** The openings report, for the « stands ouverts · postes » of each timeslot; a failure leaves the column empty. */
  private async loadOpenings(): Promise<void> {
    try {
      this.ouvertures.set(openingsByCreneau(await this.standsApi.openings()));
    } catch {
      this.ouvertures.set(new Map());
    }
  }

  /** « Reconnaître les journées types », from the page's menu: the card owns the gesture. */
  protected reconnaitre(): void {
    void this.journeesTypesCard()?.reconnaitre();
  }

  /**
   * « Appliquer sans mémoriser » from a day template's dialog: the series
   * dialog, prefilled with that day's timeslots, previews and writes; the page
   * only has to read again.
   */
  protected openSerie(fenetres?: string): void {
    if (this.editingLocked()) {
      return;
    }
    const ref = this.dialog.open<CreneauSerieDialog, CreneauSerieData, RapportRecurrence | null>(
      CreneauSerieDialog,
      {
        data: { controleActuel: this.controle(), fenetres },
        width: '44rem',
        autoFocus: 'first-tabbable',
      },
    );
    ref
      .afterClosed()
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe((rapport) => {
        if (rapport) {
          void this.apresSerie(rapport);
        }
      });
  }

  /** « Dériver des horaires des stands » : the grid the stands imply, previewed and written by the dialog. */
  protected openDerivation(): void {
    if (this.editingLocked()) {
      return;
    }
    const dates = this.store
      .creneaux()
      .map((creneau) => creneau.date)
      .sort();
    const ref = this.dialog.open<
      CreneauDerivationDialog,
      CreneauDerivationData,
      RapportDerivation | null
    >(CreneauDerivationDialog, {
      data: { dateDebut: dates[0] ?? null, dateFin: dates.at(-1) ?? null },
      width: '44rem',
      autoFocus: 'first-tabbable',
    });
    ref
      .afterClosed()
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe((rapport) => {
        if (rapport) {
          void this.apresSerie({
            nombreGeneres: rapport.nombreGeneres,
            creneaux: rapport.creneaux,
            controle: rapport.controle,
          });
        }
      });
  }

  private async apresSerie(rapport: RapportRecurrence): Promise<void> {
    await Promise.all([
      this.crud.reload(),
      this.resolution.reload(),
      this.problemes.reloadFeasibility(),
    ]);
    this.controle.set(rapport.controle);
    this.diagnostic.set(await this.creneauxApi.diagnostic().catch(() => this.diagnostic()));
    this.notifications.notify({
      title: $localize`:@@creneaux.serie.done:${rapport.nombreGeneres}:count: créneau(x) ajoutés à la grille.`,
      variant: 'success',
      timeout: 6000,
    });
  }

  /**
   * The calendar of day templates was applied: the grid, its verdict and the
   * persisted plan may all have moved, and the card already told the user what
   * happened.
   */
  protected async apresJourneesTypes(): Promise<void> {
    await Promise.all([
      this.crud.reload(),
      this.resolution.reload(),
      this.problemes.reloadFeasibility(),
    ]);
    await this.rechargerVerdict();
  }

  protected openCreate(): void {
    this.openDialog(null);
  }

  protected edit(creneau: Creneau): void {
    this.openDialog(creneau);
  }

  private openDialog(creneau: Creneau | null): void {
    // The verdict below the table is read again after anything that changes the
    // grid: a card still listing an anomaly about a créneau that no longer
    // exists is a verdict people stop reading.
    const ref = this.dialog.open<CreneauFormDialog, CreneauFormData, boolean>(CreneauFormDialog, {
      data: { creneau },
      width: '36rem',
      maxWidth: '95vw',
      autoFocus: 'first-tabbable',
    });
    ref
      .afterClosed()
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe((ecrit) => {
        if (ecrit) {
          void this.rechargerVerdict();
        }
      });
  }

  protected async remove(creneau: Creneau): Promise<void> {
    await this.crud.remove('creneaux', creneau.id, $localize`:@@creneaux.entityLabel:Créneau`, {
      name: { text: creneauName(creneau) },
    });
    await this.rechargerVerdict();
  }

  protected async removeSelection(): Promise<void> {
    await this.crud.removeMany('creneaux', this.selection.selectedIds(), labelCreneauxPluriel());
    await this.rechargerVerdict();
  }

  protected editSelection(): void {
    const selectionnes = new Set(this.selection.selectedIds());
    const refBulk = this.dialog.open<CreneauBulkEditDialog, CreneauBulkEditData, boolean>(
      CreneauBulkEditDialog,
      {
        data: { creneaux: this.store.creneaux().filter((creneau) => selectionnes.has(creneau.id)) },
        width: '40rem',
        maxWidth: '95vw',
        autoFocus: 'first-tabbable',
      },
    );
    refBulk
      .afterClosed()
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe((ecrit) => {
        if (ecrit) {
          void this.rechargerVerdict();
        }
      });
  }

  /** « Dupliquer »: the form, on a copy of the timeslot — the same hours on another date. */
  protected duplicate(creneau: Creneau): void {
    const ref = this.dialog.open<CreneauFormDialog, CreneauFormData, boolean>(CreneauFormDialog, {
      data: { creneau: null, modele: creneau },
      width: '36rem',
      maxWidth: '95vw',
      autoFocus: 'first-tabbable',
    });
    ref
      .afterClosed()
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe((ecrit) => {
        if (ecrit) {
          void this.rechargerVerdict();
        }
      });
  }

  protected warnings(creneau: Creneau): readonly string[] {
    return this.crud.warningsOf('creneaux', creneau.id);
  }

  /** What « Exporter cette liste » writes: the columns the table shows. */
  private colonnesExport(): CsvColumn<Creneau>[] {
    return [
      { title: $localize`:@@creneaux.column.jour:Jour`, value: (creneau) => `J${creneau.jour}` },
      { title: $localize`:@@creneaux.column.date:Date`, value: (creneau) => creneau.date },
      {
        title: $localize`:@@creneaux.field.heureDebut:Début`,
        value: (creneau) => creneau.heureDebut,
      },
      { title: $localize`:@@creneaux.field.heureFin:Fin`, value: (creneau) => creneau.heureFin },
      {
        title: $localize`:@@creneaux.field.couverturePause:Relais repas (effectif divisé par deux)`,
        value: (creneau) => (creneau.couverturePause ? $localize`:@@common.oui:Oui` : ''),
      },
      {
        title: $localize`:@@creneaux.column.standsOuverts:Stands ouverts`,
        value: (creneau) => this.ouvertures().get(creneau.id)?.stands ?? 0,
      },
      {
        title: $localize`:@@creneaux.column.postes:Postes`,
        value: (creneau) => this.ouvertures().get(creneau.id)?.postes ?? 0,
      },
    ];
  }

  /** « Exporter cette liste »: the timeslots as displayed, sorted as they are. */
  protected exportList(): void {
    const status = this.api.saveText(
      toCsv(this.creneauxAffiches(), this.colonnesExport()),
      csvFileName('creneaux'),
      CSV_CONTENT_TYPE,
    );
    this.notifications.notify({ title: status, variant: 'success', timeout: 4000 });
  }

  /** The hours a block pasted from a spreadsheet can fill: `HH:mm`, as the form types them. */
  private colonnesCollage(): PasteColumn<Creneau>[] {
    const heure = (key: 'heureDebut' | 'heureFin', title: string): PasteColumn<Creneau> => ({
      key,
      title,
      read: (creneau) => (creneau[key] ?? '').slice(0, 5),
      write: (creneau, text) => {
        const lu = /^(\d{1,2})[:h](\d{2})$/.exec(text.trim());
        if (!lu || Number(lu[1]) > 23 || Number(lu[2]) > 59) {
          return $localize`:@@creneaux.collage.heure:une heure s'écrit 09:00 ou 9h30`;
        }
        return { ...creneau, [key]: `${lu[1].padStart(2, '0')}:${lu[2]}` };
      },
    });
    return [
      heure('heureDebut', $localize`:@@creneaux.field.heureDebut:Début`),
      heure('heureFin', $localize`:@@creneaux.field.heureFin:Fin`),
    ];
  }

  /** A block copied from a spreadsheet, pasted on a row: previewed, then saved on « Appliquer ». */
  protected async onPaste(event: ClipboardEvent): Promise<void> {
    const text = pastedText(event);
    if (text === null || this.gridLocked()) {
      return;
    }
    event.preventDefault();
    const plan = planPaste(text, {
      rows: this.creneauxAffiches(),
      id: (creneau) => String(creneau.id),
      label: (creneau) => creneauName(creneau),
      columns: this.colonnesCollage(),
      startRow: Math.max(0, this.navigation.index()),
    });
    if (!(await this.pastePreview.confirm(plan))) {
      return;
    }
    await this.crud.saveMany('creneaux', plan.rows, labelCreneauxPluriel());
    await this.rechargerVerdict();
  }

  private readonly creneauxApi = inject(CreneauxApi);
  private readonly standsApi = inject(StandsApi);
  private readonly api = inject(ApiService);
  private readonly pastePreview = inject(PastePreviewService);
  private readonly notifications = inject(NotificationService);
  private readonly confirm = inject(ConfirmService);
}
