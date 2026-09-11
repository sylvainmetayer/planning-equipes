import { LiveAnnouncer } from '@angular/cdk/a11y';
import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  ElementRef,
  computed,
  inject,
  signal,
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
import { MatSelectModule } from '@angular/material/select';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatSortModule, Sort } from '@angular/material/sort';
import { MatTableModule } from '@angular/material/table';
import { MatTooltipModule } from '@angular/material/tooltip';
import { RouterLink } from '@angular/router';
import { CreneauxApi } from '../../core/api/creneaux-api';
import { NotificationService } from '../../core/notification.service';
import { ConfirmService } from '../../shared/confirm-dialog';
import { summarizeVacationsByDay } from './decoupage';
import { labelCreneauxPluriel } from '../../core/entity-labels';
import { PlanningResolutionStore } from '../../core/planning-resolution.store';
import { ProblemesStore } from '../../core/problemes.store';
import { ReferenceCrudService } from '../../core/reference-crud.service';
import { ReferenceDataStore } from '../../core/reference-data.store';
import { SolverJobService } from '../../core/solver-job.service';
import { TableNavigation } from '../../core/table-navigation';
import { TableSelection } from '../../core/table-selection';
import {
  CauseInfaisabilite,
  Creneau,
  DiagnosticGrille,
  ModeGrilleCreneaux,
  ParametresDecoupage,
  RapportDerivation,
  RapportGrille,
  RapportRecurrence,
} from '../../core/models';
import { BulkActionsBar } from '../../shared/bulk-actions-bar';
import { CreneauBulkEditData, CreneauBulkEditDialog } from './creneau-bulk-edit-dialog';
import { CreneauFormData, CreneauFormDialog } from './creneau-form-dialog';
import { CreneauDerivationData, CreneauDerivationDialog } from './creneau-derivation-dialog';
import { CreneauSerieData, CreneauSerieDialog } from './creneau-serie-dialog';
import { bilanGrille, gridAnomalyIcon, trierAnomalies } from './grille-creneaux';

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
    FormsModule,
    MatCardModule,
    MatCheckboxModule,
    MatFormFieldModule,
    MatInputModule,
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
  ],
  templateUrl: './creneaux-page.html',
  styleUrls: [
    './creneaux.css',
    '../../../styles/decoupage.css',
    '../../../styles/horaires-stand.css',
    '../../../styles/ouvertures.css',
  ],
  // Global by design (AGENTS.md): loaded with the route, unscoped like the partial it was.
  encapsulation: ViewEncapsulation.None,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class CreneauxPage {
  /** Bounds the dialog callbacks: this page is lazy and rebuilt on every visit, a callback on a dead one writes into nothing. */
  private readonly destroyRef = inject(DestroyRef);
  protected readonly store = inject(ReferenceDataStore);
  protected readonly jobs = inject(SolverJobService);
  /** Editing is disabled while a solve/analysis runs, to avoid corrupting the data it reads. */
  protected readonly editingLocked = this.jobs.editingLocked;

  private readonly problemes = inject(ProblemesStore);
  private readonly crud = inject(ReferenceCrudService);
  private readonly dialog = inject(MatDialog);
  private readonly resolution = inject(PlanningResolutionStore);

  /**
   * The « Famille » column only appears when the displayed slots actually
   * carry several stagger families: a group generated with N families holds N
   * same-looking variants of every slot (each stand is assigned exactly one),
   * which read as inexplicable duplicates without it — and as noise with it,
   * on the groups that have a single family.
   */
  protected readonly columns = computed(() =>
    this.afficherFamilles()
      ? ['select', 'jour', 'date', 'horaires', 'famille', 'probleme', 'actions']
      : ['select', 'jour', 'date', 'horaires', 'probleme', 'actions'],
  );
  protected readonly afficherFamilles = computed(() =>
    this.creneauxAffiches().some((creneau) => (creneau.famille ?? 0) > 0),
  );

  /** Sorting, so the slots at fault can be grouped instead of hunted for. */
  protected readonly sort = signal<Sort>({ active: '', direction: '' });

  /**
   * `store.creneaux()` is already chronological (jour, heureDebut); the sort
   * below is stable, so an unsorted view keeps that order.
   */
  protected readonly creneauxAffiches = computed(() => {
    const creneaux = [...this.store.creneaux()];
    const { active, direction } = this.sort();
    if (!active || !direction) {
      return creneaux;
    }
    const facteur = direction === 'asc' ? 1 : -1;
    return creneaux.sort((a, b) => facteur * this.comparer(a, b, active));
  });

  /** `probleme` sorts on the shortfall, so the worst slots come first. */
  private comparer(a: Creneau, b: Creneau, colonne: string): number {
    if (colonne === 'probleme') {
      const manque = (creneau: Creneau) => this.causeParCreneau().get(creneau.id)?.manque ?? 0;
      return manque(a) - manque(b);
    }
    return a.jour - b.jour || (a.heureDebut ?? '').localeCompare(b.heureDebut ?? '');
  }

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
    void this.crud.reload();
    void this.problemes.reloadFeasibility();
    void this.chargerGrille();
  }

  /* -------------------------- The grid as a whole -------------------------- */

  /** The edition's découpage settings, carrying the declared mode; `null` until read. */
  protected readonly parametresDecoupage = signal<ParametresDecoupage | null>(null);
  protected readonly mode = computed<ModeGrilleCreneaux>(
    () => this.parametresDecoupage()?.modeGrille ?? 'AMPLITUDES',
  );
  protected readonly diagnostic = signal<DiagnosticGrille | null>(null);
  protected readonly controle = signal<RapportGrille | null>(null);
  protected readonly controleLoading = signal(false);
  protected readonly modeLoading = signal(false);

  protected readonly bilan = computed(() => {
    const controle = this.controle();
    return controle ? bilanGrille(controle) : null;
  });
  protected readonly anomaliesGrille = computed(() =>
    trierAnomalies(this.controle()?.anomalies ?? []),
  );
  /** The data proves a mode the declaration contradicts: worth one line, never a silent switch. */
  protected readonly desaccordMode = computed(() => {
    const diagnostic = this.diagnostic();
    return (
      diagnostic !== null &&
      diagnostic.modeCertain &&
      diagnostic.modeProbable !== null &&
      diagnostic.modeProbable !== this.mode()
    );
  });
  protected readonly gridAnomalyIcon = gridAnomalyIcon;

  private async chargerGrille(): Promise<void> {
    try {
      this.parametresDecoupage.set(await this.creneauxApi.slicingParameters());
    } catch (error) {
      this.crud.reportError(error);
      return;
    }
    await this.rechargerVerdict();
  }

  /** The diagnostic and the verdict, read again after anything that changes the grid or its mode. */
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
  }

  /** Declares the mode, with the rest of the découpage settings untouched, then reads the verdict in it. */
  protected async changerMode(mode: ModeGrilleCreneaux): Promise<void> {
    const actuels = this.parametresDecoupage();
    // Only the two modes are ever written: a toggle group settling on nothing
    // would otherwise send an absent mode, which the server reads as the
    // default — a silent reset.
    if (
      (mode !== 'AMPLITUDES' && mode !== 'VACATIONS') ||
      !actuels ||
      actuels.modeGrille === mode ||
      this.modeLoading()
    ) {
      return;
    }
    this.modeLoading.set(true);
    try {
      // Its own endpoint: the mode is declared here while the rest of the
      // slicing settings are edited on Paramètres, and sending the whole object
      // would let a stale tab there revert this choice.
      this.parametresDecoupage.set(await this.creneauxApi.setGridMode(mode));
      await this.rechargerVerdict();
    } catch (error) {
      this.crud.reportError(error);
    } finally {
      this.modeLoading.set(false);
    }
  }

  /** « Créer une série » : the dialog previews and writes; the page only has to read again. */
  protected openSerie(): void {
    if (this.editingLocked()) {
      return;
    }
    const ref = this.dialog.open<CreneauSerieDialog, CreneauSerieData, RapportRecurrence | null>(
      CreneauSerieDialog,
      {
        data: { mode: this.mode(), controleActuel: this.controle() },
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
      data: { mode: this.mode(), dateDebut: dates[0] ?? null, dateFin: dates.at(-1) ?? null },
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
    await this.crud.remove('creneaux', creneau.id, $localize`:@@creneaux.entityLabel:Créneau`);
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

  /* --------------------- Découpage automatique en vacations --------------------- */
  // Lives here rather than on a page of its own: the generation reads the
  // créneaux above as amplitudes and REPLACES them in place (issue #172), so
  // it belongs next to the list it rewrites. Its parameters are edited on the
  // Paramètres page.

  private readonly creneauxApi = inject(CreneauxApi);
  private readonly notifications = inject(NotificationService);
  private readonly confirm = inject(ConfirmService);

  protected readonly previewVacations = signal<Creneau[] | null>(null);
  protected readonly previewLoading = signal(false);
  protected readonly genererLoading = signal(false);

  protected readonly resumeDecoupage = computed(() => {
    const vacations = this.previewVacations();
    return vacations ? summarizeVacationsByDay(vacations) : [];
  });

  protected async previsualiserDecoupage(): Promise<void> {
    this.previewLoading.set(true);
    this.previewVacations.set(null);
    try {
      this.previewVacations.set(await this.creneauxApi.previewSlicing());
    } catch (error) {
      this.crud.reportError(error);
    } finally {
      this.previewLoading.set(false);
    }
  }

  /** Confirmed first: the generation replaces the edition's créneaux and erases the persisted plan with them. */
  protected async genererDecoupage(): Promise<void> {
    const confirme = await this.confirm.ask({
      title: $localize`:@@decoupage.generer.title:Générer le découpage`,
      message: $localize`:@@decoupage.generer.confirm:Les amplitudes actuelles seront remplacées par les vacations générées et le planning résolu sera effacé. Re-découper ensuite demandera de ré-importer le scénario source.`,
      confirmLabel: $localize`:@@decoupage.generer.submitCourt:Générer les vacations`,
      danger: true,
    });
    if (!confirme) {
      return;
    }
    this.genererLoading.set(true);
    try {
      await this.creneauxApi.generateSlicing();
      await Promise.all([this.crud.reload(), this.resolution.reload()]);
      // The server declares the grid as vacations when it slices it, so the
      // mode is read back rather than written from here — an assistant slicing
      // over MCP has to get the same declaration.
      this.parametresDecoupage.set(await this.creneauxApi.slicingParameters());
      await this.rechargerVerdict();
      this.notifications.notify({
        title: $localize`:@@decoupage.generated:Découpage généré : les vacations ont remplacé les amplitudes.`,
        variant: 'success',
        timeout: 6000,
      });
    } catch (error) {
      this.crud.reportError(error);
    } finally {
      this.genererLoading.set(false);
    }
  }
}
