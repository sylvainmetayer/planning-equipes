import { LiveAnnouncer } from '@angular/cdk/a11y';
import {
  ChangeDetectionStrategy,
  Component,
  ElementRef,
  computed,
  inject,
  OnInit,
  signal,
  ViewEncapsulation,
} from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MatDialog } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatChipsModule } from '@angular/material/chips';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatMenuModule } from '@angular/material/menu';
import { MatSelectModule } from '@angular/material/select';
import { MatSortModule, Sort } from '@angular/material/sort';
import { MatTableModule } from '@angular/material/table';
import { MatTooltipModule } from '@angular/material/tooltip';
import {
  IMPORTED_IDS_PARAM,
  importedIdsParam,
  keptByImportedIds,
  readImportedIds,
} from '../../core/imported-rows';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { AnimateursApi } from '../../core/api/animateurs-api';
import { intlLocale } from '../../core/locale';
import {
  Animateur,
  ConfirmationView,
  NiveauCompetence,
  SyntheseConfirmations,
} from '../../core/models';
import { ApiService } from '../../core/api.service';
import { CSV_CONTENT_TYPE, CsvColumn, csvFileName, toCsv } from '../../core/csv-export';
import { lettreNiveau, libelleNiveau } from '../../core/niveau-competence';
import { PasteColumn, pastedText, planPaste } from '../../core/paste-rows';
import { PlanningStateService } from '../../core/planning-state.service';
import { compareNatural } from '../../core/table-sort';
import { EmptyState } from '../../shared/empty-state';
import { FilterChip, FilterChips } from '../../shared/filter-chips';
import { PastePreviewService } from '../../shared/paste-preview-dialog';
import { RowMenu } from '../../shared/row-menu';
import { RowWarning } from '../../shared/row-warning';
import { NotificationService } from '../../core/notification.service';
import { labelAnimateursPluriel } from '../../core/entity-labels';
import { ProblemesStore } from '../../core/problemes.store';
import { libelleDernierePublication } from '../../core/publication';
import { ReferenceCrudService } from '../../core/reference-crud.service';
import { ReferenceDataStore } from '../../core/reference-data.store';
import { animateurName } from '../../core/reference-labels';
import { SolverJobService } from '../../core/solver-job.service';
import { TableNavigation, trackRowById } from '../../core/table-navigation';
import { TableSelection } from '../../core/table-selection';
import { NO_SORT, consumeQueryParam, keepViewInQueryParams } from '../../core/view-query-params';
import { SESSION_DRAFT_STORAGE } from '../../core/brouillon-formulaire';
import { reportOrphanDrafts } from '../../shared/brouillon-dialog';
import { BulkActionsBar } from '../../shared/bulk-actions-bar';
import { ConfirmService } from '../../shared/confirm-dialog';
import { SortHeaderName } from '../../shared/sort-header-name';
import { TableFilter } from '../../shared/table-filter';
import { AnimateurBulkEditData, AnimateurBulkEditDialog } from './animateur-bulk-edit-dialog';
import { AnimateurFormData, AnimateurFormDialog } from './animateur-form-dialog';
import { errorMessage } from '../../core/error-message';
import { ModeAccuses, SILENCE_JOURS_DEFAUT } from './confirmation-filter';
import {
  RosterView,
  ageOn,
  confirmationLabel,
  filterRoster,
  firstDay,
  nomAffiche,
  readRosterView,
  rosterLinkParams,
  rosterViewParams,
  seatCounts,
  sortRoster,
} from './animateur-roster';
import { resumeRelance } from './relance-resume';
import { typologieLabel, typologieLabels } from '../../core/typologie-colors';
import { ImportButton } from '../../shared/import-button';

/**
 * Animateurs CRUD. Minor/adult status is never stored: it is derived from the
 * birth date at the date of each timeslot, so only the birth date is edited.
 * Availability is opt-out: an animator works unless a day is listed here.
 *
 * An animateur declared unavailable on a day that carries a CRITIQUE
 * feasibility cause is flagged: that single unavailability is one of the
 * reasons the day cannot be staffed at all.
 *
 * Rows are multi-selectable, for a bulk delete or a bulk edit of the fields
 * animateurs share (appréciation, souhaits, manager, indisponibilités). The
 * appréciations show as pastilles, and are read on the fiche, edited in the
 * Compétences grid.
 *
 * The acknowledgement column (issue #293) grew two URL-borne filters and one
 * bulk action (issue #504): « jamais confirmés », « silencieux depuis N
 * jours », and « Relancer maintenant », which mails the selected people the
 * same reminder the night would — once per publication, whichever hand.
 */
@Component({
  selector: 'app-animateurs-page',
  imports: [
    ImportButton,
    MatCardModule,
    MatButtonModule,
    MatCheckboxModule,
    MatFormFieldModule,
    MatChipsModule,
    MatIconModule,
    MatInputModule,
    MatMenuModule,
    MatSelectModule,
    FormsModule,
    MatTableModule,
    MatSortModule,
    MatTooltipModule,
    BulkActionsBar,
    EmptyState,
    FilterChips,
    RowMenu,
    RowWarning,
    SortHeaderName,
    TableFilter,
    RouterLink,
  ],
  templateUrl: './animateurs-page.html',
  styleUrls: ['../../../styles/animateur-form.css', './animateurs-page.css'],
  // Global by design (AGENTS.md): loaded with the route, unscoped like the partial it was.
  encapsulation: ViewEncapsulation.None,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class AnimateursPage implements OnInit {
  /** The seats of the persisted plan, by animateur; `null` while no plan holds anybody. */
  protected readonly seatsByAnimateur = signal<ReadonlyMap<string, number> | null>(null);

  protected readonly columns = computed(() => [
    'select',
    'nom',
    'age',
    'competences',
    'indisponibilites',
    ...(this.seatsByAnimateur() === null ? [] : ['postes']),
    'confirmation',
    'actions',
  ]);
  protected readonly sort = signal<Sort>(NO_SORT);
  /** Quick filter of the table: id, identity and compétences. Applied before the sort. */
  protected readonly filtre = signal('');
  /** The « Accusés » select: everybody, the never-confirmed, or the silent for N days (issue #504). */
  protected readonly accuses = signal<ModeAccuses>('tous');
  /** N of « silencieux depuis N jours »; kept, and in the URL, only while that mode is on. */
  protected readonly silenceJours = signal(SILENCE_JOURS_DEFAUT);
  /**
   * « Jamais relancés », on top of an acknowledgement mode: only the people no
   * reminder reached — the list the home screen's « silencieux à relancer »
   * counts. In the URL (`relance=jamais`) only while a mode other than
   * « Tous » is on, since it narrows that mode and nothing else.
   */
  protected readonly neverReminded = signal(false);
  /** True as soon as the table shows something other than the whole referential, unsorted. */
  /**
   * Typologie ids, comma-separated, the list is narrowed to: only the
   * animateurs holding an appreciation on one of them. Set by the links that
   * name a game category nobody masters (the staffing bottleneck, a scarce
   * competence of the fragility screen) — the `typologie` query param.
   */
  protected readonly typologie = signal('');
  protected readonly typologiesFiltrees = computed(() =>
    this.typologie()
      .split(',')
      .map((id) => id.trim())
      .filter((id) => id !== ''),
  );
  protected readonly typologieLabel = computed(() => {
    const ids = this.typologiesFiltrees();
    const libelles = this.store.typologies();
    return ids
      .map((id) => libelles.find((typologie) => typologie.id === id)?.label ?? id)
      .join(', ');
  });
  /**
   * A typologie id the list is narrowed to by wish: only the animateurs who
   * asked for it. Set by the Typologies screen on a category nobody masters —
   * « qui l'a souhaitée ? » is who to train first. The `souhait` query param.
   */
  protected readonly souhait = signal('');
  protected readonly souhaitLabel = computed(() => {
    const id = this.souhait();
    return this.store.typologies().find((typologie) => typologie.id === id)?.label ?? id;
  });
  /**
   * `?ids=`: the fiches an import just wrote, which its « Voir les N lignes
   * importées » opens the list on — a chip, removed like the others.
   */
  protected readonly importedIds = signal<ReadonlySet<string> | null>(null);
  /** `?mineurs=1`: only the people who are minors on the edition's first day. */
  protected readonly mineurs = signal(false);
  /** `?manager=1`: only the managers. */
  protected readonly managers = signal(false);

  /** The edition's first day, from its timeslots: the day a minor is a minor on; today when there is none. */
  private readonly premierJour = computed(() =>
    firstDay(this.store.creneaux().map((creneau) => creneau.date)),
  );

  /** Every filter in force, as a chip above the table: a filter that narrows a list must be seen doing it. */
  protected readonly chips = computed<FilterChip[]>(() => {
    const chips: FilterChip[] = [];
    const ids = this.importedIds();
    if (ids !== null) {
      chips.push({
        key: 'ids',
        label: $localize`:@@animateurs.filtreImport:Lignes importées (${ids.size}:count:)`,
      });
    }
    if (this.typologiesFiltrees().length > 0) {
      chips.push({
        key: 'typologie',
        label: $localize`:@@animateurs.filtreTypologie:Typologie : ${this.typologieLabel()}:INTERPOLATION:`,
      });
    }
    if (this.souhait()) {
      chips.push({
        key: 'souhait',
        label: $localize`:@@animateurs.filtreSouhait:Souhait : ${this.souhaitLabel()}:INTERPOLATION:`,
      });
    }
    if (this.mineurs()) {
      chips.push({ key: 'mineurs', label: $localize`:@@animateurs.chip.mineurs:Mineurs` });
    }
    if (this.managers()) {
      chips.push({ key: 'manager', label: $localize`:@@animateurs.chip.managers:Managers` });
    }
    if (this.accuses() === 'jamais') {
      chips.push({
        key: 'accuses',
        label: $localize`:@@animateurs.accuses.jamais:Jamais confirmés`,
      });
    } else if (this.accuses() === 'silence') {
      const jours = this.silenceJours();
      chips.push({
        key: 'accuses',
        label:
          jours > 1
            ? $localize`:@@animateurs.chip.silence:Silencieux depuis ${jours}:jours: jours`
            : $localize`:@@animateurs.chip.silence.un:Silencieux depuis ${jours}:jours: jour`,
      });
    }
    if (this.accuses() !== 'tous' && this.neverReminded()) {
      chips.push({
        key: 'relance',
        label: $localize`:@@animateurs.accuses.jamaisRelances:Jamais relancés`,
      });
    }
    return chips;
  });

  protected readonly viewChanged = computed(
    () =>
      this.filtre().trim() !== '' ||
      this.chips().length > 0 ||
      (this.sort().active !== '' && this.sort().direction !== ''),
  );
  /** The list's view as one value: what the rows and the fiche's « précédent / suivant » both read. */
  protected readonly view = computed<RosterView>(() => ({
    sort: this.sort(),
    filtre: this.filtre(),
    accuses: this.accuses(),
    silenceJours: this.silenceJours(),
    neverReminded: this.neverReminded(),
    typologie: this.typologie(),
    souhait: this.souhait(),
    mineurs: this.mineurs(),
    managers: this.managers(),
  }));
  /** The query params a link to a fiche carries, so its « précédent / suivant » walks this very list. */
  protected readonly ficheParams = computed(() => rosterLinkParams(this.view()));
  protected readonly animateursFiltres = computed(() => {
    const importedIds = this.importedIds();
    return filterRoster(this.store.animateurs(), this.view(), {
      confirmations: this.confirmations(),
      lastPublishedAt: this.synthese()?.dernierePublicationLe ?? null,
      typologies: typologieLabels(this.store.typologies()),
      now: new Date(),
      premierJour: this.premierJour(),
      postes: this.seatsByAnimateur(),
    }).filter((animateur) => keptByImportedIds(importedIds, animateur.id));
  });

  /**
   * Acknowledgement of the published planning, by animateur id (issue #293).
   * Loaded apart from the roster: an animateur clicking in their espace moves
   * it with nothing happening on the admin side, so it is not part of the
   * reference-data store that only reloads on a CRUD write.
   */
  protected readonly confirmations = signal<Map<string, ConfirmationView>>(new Map());

  /** The same answers in three numbers, for the head of the page; `null` until read, or when unreadable. */
  protected readonly synthese = signal<SyntheseConfirmations | null>(null);

  /** « Confirmés 12 · Relancés 3 · Silencieux 5 — Dernière publication le … », or nothing to say yet. */
  protected readonly syntheseLabel = computed(() => {
    const synthese = this.synthese();
    if (!synthese) {
      return '';
    }
    const publication = libelleDernierePublication(synthese, intlLocale());
    if (synthese.jamaisPublie) {
      return publication;
    }
    return $localize`:@@animateurs.synthese:Confirmés ${synthese.confirmes}:confirmes: · Relancés ${synthese.relances}:relances: · Silencieux ${synthese.silencieux}:silencieux: — ${publication}:publication:`;
  });

  /**
   * The rows in the order of the chosen column, ties and the unsorted table
   * in the natural order of the ids (A2 before A10).
   */
  protected readonly sortedAnimateurs = computed(() =>
    sortRoster(this.animateursFiltres(), this.sort(), {
      confirmations: this.confirmations(),
      premierJour: this.premierJour(),
      postes: this.seatsByAnimateur(),
    }),
  );

  protected readonly store = inject(ReferenceDataStore);
  protected readonly jobs = inject(SolverJobService);
  /** Editing is disabled while a solve/analysis runs, to avoid corrupting the data it reads. */
  protected readonly editingLocked = this.jobs.editingLocked;

  /** Keyed on the filtered, sorted rows, so "tout sélectionner" follows what the table shows. */
  protected readonly selection = new TableSelection<string>(
    computed(() => this.sortedAnimateurs().map((animateur) => animateur.id)),
  );

  private readonly hote = inject<ElementRef<HTMLElement>>(ElementRef);

  /**
   * Roving tabindex over the rows: the arrows move the focus, Entrée opens the
   * fiche, Espace ticks the row. `core/table-navigation.ts` holds the whole
   * mechanism, shared with the other reference-data tables.
   */
  /** Rows kept across a reload of the store, and the focus with them. */
  protected readonly trackById = trackRowById;
  protected readonly navigation = new TableNavigation({
    rows: this.sortedAnimateurs,
    id: (animateur: Animateur) => animateur.id,
    host: () => this.hote.nativeElement,
    selection: this.selection,
    open: (animateur: Animateur) => {
      this.openFiche(animateur);
      return true;
    },
    announcer: inject(LiveAnnouncer),
  });

  private readonly problemes = inject(ProblemesStore);
  private readonly crud = inject(ReferenceCrudService);
  private readonly dialog = inject(MatDialog);
  private readonly animateursApi = inject(AnimateursApi);
  private readonly notifications = inject(NotificationService);
  private readonly draftStorage = inject(SESSION_DRAFT_STORAGE);
  private readonly confirmDialog = inject(ConfirmService);
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);
  private readonly api = inject(ApiService);
  private readonly pastePreview = inject(PastePreviewService);
  private readonly planningState = inject(PlanningStateService);

  /** Copies the animateur's personal espace link (issue #165) — what the PDF prints. */
  protected async copierLienEspace(animateur: Animateur): Promise<void> {
    if (!animateur.accessToken) {
      return;
    }
    const lien = `${window.location.origin}/animateur/${animateur.accessToken}`;
    try {
      await navigator.clipboard.writeText(lien);
      this.notifications.notify({
        title: $localize`:@@animateurs.lienCopie:Lien de l'espace animateur copié.`,
        variant: 'success',
        timeout: 4000,
      });
    } catch {
      this.notifications.notify({
        title: $localize`:@@animateurs.lienCopieEchec:Impossible de copier le lien`,
        message: lien,
        variant: 'warning',
      });
    }
  }

  /** Rotates the espace access token: the link on already-distributed PDFs stops working. */
  protected async regenererJeton(animateur: Animateur): Promise<void> {
    const confirmed = await this.confirmDialog.ask({
      title: $localize`:@@animateurs.regenererJetonTitre:Régénérer le lien de ${animateur.prenom}:prenom: ${animateur.nom}:nom: ?`,
      message: $localize`:@@animateurs.regenererJetonMessage:L'ancien lien (déjà imprimé sur ses plannings PDF) cessera de fonctionner immédiatement.`,
      confirmLabel: $localize`:@@animateurs.regenererJetonConfirm:Régénérer`,
      danger: true,
    });
    if (!confirmed) {
      return;
    }
    try {
      await this.animateursApi.regenerateToken(animateur.id);
      await this.store.reload();
      this.notifications.notify({
        title: $localize`:@@animateurs.jetonRegenere:Nouveau lien généré.`,
        variant: 'success',
        timeout: 4000,
      });
    } catch (error) {
      this.notifications.notify({
        title: $localize`:@@crud.error:Erreur`,
        message: errorMessage(error),
        variant: 'error',
      });
    }
  }

  /**
   * Animateur id → the tooltip explaining that one of their unavailability days
   * is a day with a critical shortfall. Memoised as a map so each row is a
   * lookup rather than a scan of every cause.
   */
  protected readonly alerteParAnimateurId = computed<Map<string, string>>(() => {
    const causesParDate = this.problemes.causeCritiqueParDate();
    const alertes = new Map<string, string>();
    if (causesParDate.size === 0) {
      return alertes;
    }
    for (const animateur of this.store.animateurs()) {
      const jour = (animateur.joursIndisponibles ?? []).find((date) => causesParDate.has(date));
      if (jour) {
        alertes.set(
          animateur.id,
          this.indisponibiliteCritiqueMessage(jour, causesParDate.get(jour)!.message),
        );
      }
    }
    return alertes;
  });

  constructor() {
    const view = readRosterView(this.route.snapshot.queryParamMap);
    this.sort.set(view.sort);
    this.filtre.set(view.filtre);
    this.accuses.set(view.accuses);
    this.silenceJours.set(view.silenceJours);
    this.neverReminded.set(view.neverReminded);
    this.typologie.set(view.typologie);
    this.souhait.set(view.souhait);
    this.mineurs.set(view.mineurs);
    this.managers.set(view.managers);
    this.importedIds.set(
      readImportedIds(this.route.snapshot.queryParamMap.get(IMPORTED_IDS_PARAM)),
    );
    const chargement = this.crud.reload();
    void chargement.then((loaded) => {
      if (loaded) {
        reportOrphanDrafts(
          this.notifications,
          this.draftStorage,
          'animateur',
          (id) => this.store.animateurs().some((animateur) => animateur.id === id),
          (ids) => $localize`:@@animateurs.brouillon.orphelin:L'animateur ${ids}:ids:`,
        );
      }
    });
    void this.problemes.reloadFeasibility();
    keepViewInQueryParams(() => ({
      ...rosterViewParams(this.view()),
      [IMPORTED_IDS_PARAM]: importedIdsParam(this.importedIds()),
    }));
    // `?edit=<id>`: a link from a symptom (a problem, a warning) lands here
    // with the fiche to open. Followed rather than read once — the link often
    // points at this very screen — then dropped, so a reload does not reopen it.
    consumeQueryParam('edit', async (edit) => {
      await chargement;
      const animateur = this.store.animateurs().find((candidat) => candidat.id === edit);
      if (animateur) {
        this.openDialog(animateur);
      }
    });
  }

  ngOnInit(): void {
    void this.chargerConfirmations();
    void this.loadSeats();
  }

  /**
   * The seats of the persisted plan, counted by animateur — the « Postes »
   * column, shown once a plan sits somebody. A plan that cannot be read
   * leaves the column out, as an edition never solved does.
   */
  private async loadSeats(): Promise<void> {
    try {
      this.seatsByAnimateur.set(seatCounts(await this.planningState.loadForDisplay()));
    } catch {
      this.seatsByAnimateur.set(null);
    }
  }

  /**
   * A missing answer is not an error worth a snack bar: the column then simply
   * shows nothing, and every other feature of the page still works.
   */
  private async chargerConfirmations(): Promise<void> {
    try {
      const [confirmations, synthese] = await Promise.all([
        this.animateursApi.confirmations(),
        this.animateursApi.syntheseConfirmations(),
      ]);
      this.confirmations.set(
        new Map(confirmations.map((confirmation) => [confirmation.animateurId, confirmation])),
      );
      this.synthese.set(synthese);
    } catch {
      this.confirmations.set(new Map());
      this.synthese.set(null);
    }
  }

  /** A blank or non-positive N keeps the last one: the control never empties the filter. */
  protected changerSilenceJours(valeur: number | string | null): void {
    const jours = Number(valeur);
    if (Number.isInteger(jours) && jours > 0) {
      this.silenceJours.set(jours);
    }
  }

  /**
   * Writes the value actually in force back into the field when it is left.
   * A rejected entry leaves the signal untouched, so Angular has nothing to
   * push back through the one-way binding: « 0 » stayed on screen while the
   * table and the URL went on filtering on the last good number. Done on blur
   * rather than on every keystroke, so clearing the field to type another
   * number still works.
   */
  protected restoreSilenceJours(champ: HTMLInputElement): void {
    const actual = String(this.silenceJours());
    if (champ.value !== actual) {
      champ.value = actual;
    }
  }

  /**
   * « Relancer maintenant » (issue #504): the same reminder the night sends,
   * to the ticked rows, after a confirmation that says mails will leave. The
   * report names who was left alone and why; the answers are reloaded so the
   * column shows « Relancé » at once.
   */
  protected async remindSelection(): Promise<void> {
    const ids = this.selection.selectedIds();
    const confirmed = await this.confirmDialog.ask({
      title: $localize`:@@animateurs.relancer.titre:Relancer ${ids.length}:count: animateur(s) maintenant ?`,
      message: $localize`:@@animateurs.relancer.message:Chacun recevra un e-mail lui demandant de confirmer son planning. Personne n'est relancé deux fois pour une même publication.`,
      confirmLabel: $localize`:@@animateurs.relancer.confirm:Envoyer`,
    });
    if (!confirmed) {
      return;
    }
    try {
      const rapport = await this.animateursApi.remind(ids);
      const noms = new Map(this.store.animateurs().map((each) => [each.id, nomAffiche(each)]));
      const resume = resumeRelance(rapport, (id) => noms.get(id) || id);
      this.notifications.notify({
        title: resume.titre,
        message: resume.details ?? '',
        // Names on screen, counts in the journal: that log is persisted and
        // read back, and this report can name the whole roster.
        messageJournal: resume.detailsJournal ?? '',
        variant: resume.variant,
      });
      this.selection.clear();
      await this.chargerConfirmations();
    } catch (error) {
      this.notifications.notify({
        title: $localize`:@@crud.error:Erreur`,
        message: errorMessage(error),
        variant: 'error',
      });
    }
  }

  /**
   * What the three states mean — none of it is guessable from the labels, and
   * two of the rules actively surprise people who assume otherwise.
   *
   * Built in a method, never at module scope: `$localize` only resolves once
   * `main.ts` has loaded the translations.
   */
  protected confirmationAide(): string {
    return $localize`:@@animateurs.confirmation.aide:Ce que l'animateur a répondu.\n• Relancé : un seul rappel, de nuit ou à la main.\n• — : aucun poste au planning publié.\nRepublier ne remet à « silencieux » que ceux dont le planning a changé.`;
  }

  /** Wording of the acknowledgement column, and the text its quick filter matches on. */
  protected confirmationLabel(animateur: Animateur): string {
    return confirmationLabel(this.confirmations().get(animateur.id));
  }

  /**
   * The timestamp behind the label, as a tooltip: when they confirmed, or —
   * failing that — when the automatic reminder went out. Empty when there is
   * nothing to date, which is exactly the « silencieux » case.
   */
  protected confirmationDate(animateur: Animateur): string | null {
    const confirmation = this.confirmations().get(animateur.id);
    if (confirmation?.confirmeLe) {
      const date = new Date(confirmation.confirmeLe).toLocaleString(intlLocale());
      return $localize`:@@animateurs.confirmation.confirmeLe:Confirmé le ${date}:date:`;
    }
    if (confirmation?.relanceLe) {
      const date = new Date(confirmation.relanceLe).toLocaleString(intlLocale());
      return $localize`:@@animateurs.confirmation.relanceLe:Relancé le ${date}:date:`;
    }
    return null;
  }

  /** A chip's cross: that filter goes, the rest of the view stays. */
  protected removeChip(key: string): void {
    switch (key) {
      case 'typologie':
        this.typologie.set('');
        break;
      case 'souhait':
        this.souhait.set('');
        break;
      case 'mineurs':
        this.mineurs.set(false);
        break;
      case 'manager':
        this.managers.set(false);
        break;
      case 'ids':
        this.importedIds.set(null);
        break;
      case 'accuses':
        this.accuses.set('tous');
        this.neverReminded.set(false);
        break;
      case 'relance':
        this.neverReminded.set(false);
        break;
    }
  }

  /** Every chip at once; the quick filter and the sort stay. */
  protected clearChips(): void {
    this.importedIds.set(null);
    this.typologie.set('');
    this.souhait.set('');
    this.mineurs.set(false);
    this.managers.set(false);
    this.accuses.set('tous');
    this.silenceJours.set(SILENCE_JOURS_DEFAUT);
    this.neverReminded.set(false);
  }

  /** « Filtrer » menu: narrow to the people holding a typologie, or wishing for one. */
  protected filterByTypologie(id: string): void {
    this.typologie.set(id);
  }

  protected filterByWish(id: string): void {
    this.souhait.set(id);
  }

  /** Back to the whole referential, in the natural order of the ids. */
  protected resetView(): void {
    this.filtre.set('');
    this.clearChips();
    this.sort.set(NO_SORT);
  }

  private indisponibiliteCritiqueMessage(jour: string, cause: string): string {
    return $localize`:@@animateurs.alerte.indisponibiliteCritique:Indisponible le ${jour}:date:, un jour où l'effectif est structurellement insuffisant : ${cause}:cause:`;
  }

  /**
   * « 34 ans » or « 16 ans · mineur », at the edition's first day: the legal
   * regime is what the column exists for, never a bare yes/no.
   */
  protected ageLabel(animateur: Animateur): string {
    const age = ageOn(animateur, this.premierJour());
    if (age === null) {
      return '—';
    }
    return age < 18
      ? $localize`:@@animateurs.age.mineur:${age}:age: ans · mineur`
      : $localize`:@@animateurs.age.majeur:${age}:age: ans`;
  }

  /** The appreciations as pastilles, by game category label, each with its level's letter and word. */
  protected pastilles(animateur: Animateur): { label: string; lettre: string; titre: string }[] {
    const typologies = typologieLabels(this.store.typologies());
    return Object.entries(animateur.competences ?? {})
      .map(([id, niveau]) => ({
        label: typologieLabel(typologies, id),
        lettre: lettreNiveau(niveau as NiveauCompetence),
        titre: `${typologieLabel(typologies, id)} · ${libelleNiveau(niveau as NiveauCompetence)}`,
      }))
      .sort((a, b) => compareNatural(a.label, b.label));
  }

  /** The step before the animateurs, named on the empty state. */
  protected previousStepLabel(): string {
    return $localize`:@@stands.empty.typologies:Saisir les typologies`;
  }

  protected warnings(animateur: Animateur): readonly string[] {
    return this.crud.warningsOf('animateurs', animateur.id);
  }

  /** The name of a row names the person; the menu is named after them too. */
  protected nom(animateur: Animateur): string {
    return animateurName(animateur) || animateur.id;
  }

  /** « Dupliquer »: a new fiche with the same appreciations, wishes and days off — a new person, identity left blank. */
  protected duplicate(animateur: Animateur): void {
    this.dialog.open<AnimateurFormDialog, AnimateurFormData, boolean>(AnimateurFormDialog, {
      data: { animateur: null, modele: animateur },
      width: '44rem',
      maxWidth: '95vw',
      autoFocus: 'first-tabbable',
    });
  }

  /** What « Exporter cette liste » writes: the people as displayed, never their birth date. */
  private colonnesExport(): CsvColumn<Animateur>[] {
    const typologies = typologieLabels(this.store.typologies());
    return [
      { title: $localize`:@@common.id:Id`, value: (animateur) => animateur.id },
      {
        title: $localize`:@@animateurs.field.prenom:Prénom`,
        value: (animateur) => animateur.prenom,
      },
      { title: $localize`:@@stands.field.nom:Nom`, value: (animateur) => animateur.nom },
      {
        title: $localize`:@@animateurs.column.age:Âge`,
        value: (animateur) => this.ageLabel(animateur),
      },
      {
        title: $localize`:@@animateurs.field.manager:Manager`,
        value: (animateur) => (animateur.manager ? $localize`:@@common.oui:Oui` : ''),
      },
      {
        title: $localize`:@@animateurs.column.competences:Compétences`,
        value: (animateur) =>
          Object.entries(animateur.competences ?? {}).map(
            ([id, niveau]) =>
              `${typologieLabel(typologies, id)} (${libelleNiveau(niveau as NiveauCompetence)})`,
          ),
      },
      {
        title: $localize`:@@animateurs.souhaits.title:Souhaits`,
        value: (animateur) =>
          (animateur.souhaits ?? []).map((id) => typologieLabel(typologies, id)),
      },
      {
        title: $localize`:@@animateurs.column.indisponibilites:Indisponibilités`,
        value: (animateur) => animateur.joursIndisponibles ?? [],
      },
      ...(this.seatsByAnimateur() === null
        ? []
        : [
            {
              title: $localize`:@@animateurs.column.postes:Postes`,
              value: (animateur: Animateur) => this.seatsByAnimateur()?.get(animateur.id) ?? 0,
            },
          ]),
      {
        title: $localize`:@@animateurs.column.confirmation:Accusé de réception`,
        value: (animateur) => this.confirmationLabel(animateur),
      },
    ];
  }

  /** « Exporter cette liste »: the rows as displayed — filtered, sorted — built in the browser. */
  protected exportList(): void {
    const status = this.api.saveText(
      toCsv(this.sortedAnimateurs(), this.colonnesExport()),
      csvFileName('animateurs'),
      CSV_CONTENT_TYPE,
    );
    this.notifications.notify({ title: status, variant: 'success', timeout: 4000 });
  }

  /** The simple fields a block pasted from a spreadsheet can fill. */
  private colonnesCollage(): PasteColumn<Animateur>[] {
    const textColumn = (key: 'prenom' | 'nom', title: string): PasteColumn<Animateur> => ({
      key,
      title,
      read: (animateur) => animateur[key] ?? '',
      write: (animateur, text) => ({ ...animateur, [key]: text }),
    });
    return [
      textColumn('prenom', $localize`:@@animateurs.field.prenom:Prénom`),
      textColumn('nom', $localize`:@@stands.field.nom:Nom`),
      {
        key: 'email',
        title: $localize`:@@animateurs.field.email:E-mail`,
        read: (animateur) => animateur.email ?? '',
        write: (animateur, text) =>
          /^[^@\s]+@[^@\s]+$/.test(text)
            ? { ...animateur, email: text }
            : $localize`:@@animateurs.collage.email:une adresse e-mail`,
      },
      {
        key: 'manager',
        title: $localize`:@@animateurs.field.manager:Manager`,
        read: (animateur) =>
          animateur.manager ? $localize`:@@common.oui:Oui` : $localize`:@@common.non:Non`,
        write: (animateur, text) => {
          const valeur = readYesNo(text);
          return valeur === null
            ? $localize`:@@animateurs.collage.manager:oui ou non`
            : { ...animateur, manager: valeur };
        },
      },
    ];
  }

  /**
   * A block copied from a spreadsheet, pasted on a row (Ctrl+V): laid over
   * the displayed rows from the focused one, previewed, then saved on
   * « Appliquer » — each changed fiche once.
   */
  protected async onPaste(event: ClipboardEvent): Promise<void> {
    const text = pastedText(event);
    if (text === null || this.editingLocked()) {
      return;
    }
    event.preventDefault();
    const plan = planPaste(text, {
      rows: this.sortedAnimateurs(),
      id: (animateur) => animateur.id,
      label: (animateur) => this.nom(animateur),
      columns: this.colonnesCollage(),
      startRow: Math.max(0, this.navigation.index()),
    });
    if (!(await this.pastePreview.confirm(plan))) {
      return;
    }
    await this.crud.saveMany('animateurs', plan.rows, labelAnimateursPluriel());
  }

  /** The row's fiche, carrying this list's view so its « précédent / suivant » walks the same rows. */
  protected openFiche(animateur: Animateur): void {
    void this.router.navigate(['/animateurs', animateur.id], { queryParams: this.ficheParams() });
  }

  protected openCreate(): void {
    this.openDialog(null);
  }

  protected edit(animateur: Animateur): void {
    this.openDialog(animateur);
  }

  private openDialog(animateur: Animateur | null): void {
    this.dialog.open<AnimateurFormDialog, AnimateurFormData, boolean>(AnimateurFormDialog, {
      data: { animateur },
      width: '44rem',
      maxWidth: '95vw',
      autoFocus: 'first-tabbable',
    });
  }

  protected async remove(animateur: Animateur): Promise<void> {
    await this.crud.remove(
      'animateurs',
      animateur.id,
      $localize`:@@animateurs.entityLabel:Animateur`,
      // In the confirmation and the snack bar; the notifications log keeps the id.
      { name: { text: animateurName(animateur), personal: true } },
    );
  }

  protected async removeSelection(): Promise<void> {
    await this.crud.removeMany(
      'animateurs',
      this.selection.selectedIds(),
      labelAnimateursPluriel(),
    );
  }

  protected editSelection(): void {
    const selectionnes = new Set(this.selection.selectedIds());
    this.dialog.open<AnimateurBulkEditDialog, AnimateurBulkEditData, boolean>(
      AnimateurBulkEditDialog,
      {
        data: {
          animateurs: this.store.animateurs().filter((animateur) => selectionnes.has(animateur.id)),
        },
        width: '48rem',
        maxWidth: '95vw',
        autoFocus: 'first-tabbable',
      },
    );
  }
}

/** A pasted yes or no, in the words a spreadsheet uses; `null` for anything else. */
function readYesNo(text: string): boolean | null {
  const valeur = text.trim().toLowerCase();
  if (['oui', 'o', 'x', '1', 'vrai', 'true', 'yes', 'y'].includes(valeur)) {
    return true;
  }
  if (['non', 'n', '0', 'faux', 'false', 'no', ''].includes(valeur)) {
    return false;
  }
  return null;
}
