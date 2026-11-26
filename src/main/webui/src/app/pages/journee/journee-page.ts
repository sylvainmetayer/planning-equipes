import {
  afterNextRender,
  ChangeDetectionStrategy,
  Component,
  computed,
  ElementRef,
  inject,
  Injector,
  OnInit,
  signal,
  ViewEncapsulation,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { MatButtonModule } from '@angular/material/button';
import { MatButtonToggleModule } from '@angular/material/button-toggle';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { ActivatedRoute, ParamMap, RouterLink } from '@angular/router';
import { reglagesAvances } from '../../core/reglages-avances';
import { ReglagesAvancesToggle } from '../../shared/reglages-avances';
import { AnalysesApi } from '../../core/api/analyses-api';
import { dayNavigation } from '../../core/day-navigation';
import { errorPrefix } from '../../core/error-message';
import {
  Emplacement,
  HoursSource,
  PlanningEvenement,
  GroupedArrivalReport,
  WalkSequenceReport,
  RapportPauses,
  TypologieItem,
} from '../../core/models';
import { PlanningStateService } from '../../core/planning-state.service';
import {
  keepViewInQueryParams,
  NO_SORT,
  optionalParam,
  readSort,
  SortState,
  sortQueryParams,
} from '../../core/view-query-params';
import { ValidationsStore } from '../../core/validations.store';
import { ConsignesStore } from '../../core/consignes.store';
import { TableFilter } from '../../shared/table-filter';
import { SelectionRecherche } from '../../shared/selection-recherche';
import { CalendarDayView, FiltreSieges, readFiltreSieges } from '../calendar-day/calendar-day-vue';
import { readInstant } from '../carte-jour/carte-jour';
import { CarteJourView } from '../carte-jour/carte-jour-vue';
import { PorteeCharge, readPorteeCharge } from '../carte-jour/charge-emplacement';
import { ConsigneLigne } from './consigne-ligne';
import { MiniMois } from './mini-mois';
import { RelectureBarre } from './relecture-barre';
import { RailJourView, RailVue } from '../rail-jour/rail-jour-vue';
import {
  DensiteStand,
  readDensiteStand,
  readStandView,
  StandView,
} from '../planning-stand/planning-stand';
import { PlanningStandView } from '../planning-stand/planning-stand-vue';
import {
  DensitePersonne,
  readDensitePersonne,
  readHoursSource,
  readPersonView,
  PersonView,
} from '../planning-personne/planning-personne';
import { PlanningPersonneView } from '../planning-personne/planning-personne-vue';
import { GridSpan, readGridSpan } from '../planning-grille/jours-grille';
import { TRI_NOM } from '../planning-grille/planning-grille';
import { RepartitionHeuresView } from '../repartition-heures/repartition-heures-vue';
import {
  JourEvenement,
  dayKey,
  JourneeView,
  PlanningView,
  readAxe,
  requestedKey,
  jourDemande,
  jourInconnu,
  planningDays,
  readRendu,
  readView,
  viewParams,
  tabOf,
  PlanningAxe,
} from './journee';
import { StatusMessage } from '../../shared/status-message';
import { NewWindowLink } from '../../shared/new-window-link';
import { SeatPanel } from '../../shared/siege-panel/siege-panel';
import { resolveSeat, SeatRequest } from '../../shared/siege-panel/seat';

/** « Sans emplacement »: the stands that sit on none, as the location filter and the treemap name them. */
const NO_LOCATION = 'aucun';

/** The keys the treemap of « Par stand » writes, cleared by the page when the treemap leaves the screen. */
const TREEMAP_KEYS_CLEARED = {
  regroupement: null,
  semaine: null,
  jourTreemap: null,
  zoom: null,
} as const;

/**
 * The 3.x keys the page reads no more, or reads once: `axe` is rewritten into
 * `vue`, the others belonged to the views 4.0 removed. Left in the address, a
 * stale `axe=stand` would bring the grid back on the next reload of « Jour ».
 */
const RETIRED_KEYS_CLEARED = {
  axe: null,
  comparer: null,
  ecarts: null,
  relais: null,
  repas: null,
  reference: null,
  lecture: null,
} as const;

/** Which of the bar's five filters a rendering reads. */
interface ShownFilters {
  name: boolean;
  stand: boolean;
  animateur: boolean;
  emplacement: boolean;
  typologie: boolean;
}

const EVERY_FILTER: ShownFilters = {
  name: true,
  stand: true,
  animateur: true,
  emplacement: true,
  typologie: true,
};
const NO_FILTER: ShownFilters = {
  name: false,
  stand: false,
  animateur: false,
  emplacement: false,
  typologie: false,
};

/**
 * The filters each rendering honours. The two grids and the table read all
 * five; the treemap reads the location alone; the rail the name, the stand and
 * the animateur; the map the stand alone.
 */
function filtersShown(axe: PlanningAxe, standView: StandView, view: JourneeView): ShownFilters {
  switch (axe) {
    case 'stand':
      return standView === 'treemap' ? { ...NO_FILTER, emplacement: true } : EVERY_FILTER;
    case 'personne':
      return EVERY_FILTER;
    default:
      if (view === 'carte') {
        return { ...NO_FILTER, stand: true };
      }
      return view === 'calendrier'
        ? EVERY_FILTER
        : { ...NO_FILTER, name: true, stand: true, animateur: true };
  }
}

/**
 * The person axis's rendering an address without `vue` implies: a summary
 * sort or `colonnes=toutes` — the links written before the summary left the
 * day grid, `/equite` redirects included — name the synthesis.
 */
function impliedPersonView(query: ParamMap): string | null {
  const sort = query.get('sort');
  return (sort && sort !== TRI_NOM) || query.get('colonnes') === 'toutes' ? 'synthese' : null;
}

/** A stand or an animateur of the plan, as the two filter selectors list them. */
interface Option {
  id: string;
  label: string;
}

/**
 * « Planning » (issue #712, the former Journée): one day of the persisted plan
 * under four views — « Jour », in two modes: the rail animateur by animateur
 * (the default) or the table stands × timeslots —, « Stand » and « Personne »
 * (the event's days laid out as grids), and the map hour by hour — with the plan at the top: the title on one line, the day chosen
 * on a foldable month, the stand and animateur filters as autocompletes, the
 * relecture as four chips that narrow the rendering, the consigne in a line,
 * « Imprimer » and « Afficher sur la TV ». Everything is carried by the URL.
 * Switching the rendering changes nothing but the rendering: the plan, the
 * breaks, the typologies and the emplacements are read once here and handed
 * to whichever view is on screen.
 *
 * <p>Each view keeps the view state that is its own (which lines the rail
 * shows, the map's cursor…) and writes it to the URL next to the page's keys:
 * `keepViewInQueryParams` lets every writer own the keys it names.</p>
 */
@Component({
  selector: 'app-journee-page',
  imports: [
    ReglagesAvancesToggle,
    StatusMessage,
    MatButtonModule,
    MatButtonToggleModule,
    MatIconModule,
    MatProgressBarModule,
    RouterLink,
    NewWindowLink,
    TableFilter,
    SelectionRecherche,
    MiniMois,
    RelectureBarre,
    ConsigneLigne,
    CalendarDayView,
    RailJourView,
    PlanningStandView,
    PlanningPersonneView,
    RepartitionHeuresView,
    SeatPanel,
    // Rendered inside a `@defer` block only: `leaflet` travels with this
    // component and must not enter the chunk of the three other renderings.
    CarteJourView,
  ],
  templateUrl: './journee-page.html',
  styleUrl: './journee-page.css',
  // Global by design (AGENTS.md): loaded with the route, unscoped like the partial it was.
  encapsulation: ViewEncapsulation.None,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class JourneePage implements OnInit {
  private readonly analysesApi = inject(AnalysesApi);
  private readonly planningState = inject(PlanningStateService);
  private readonly route = inject(ActivatedRoute);
  private readonly validations = inject(ValidationsStore);
  private readonly consignes = inject(ConsignesStore);

  protected readonly loading = signal(false);
  protected readonly error = signal('');
  protected readonly planning = signal<PlanningEvenement | null>(null);
  /** Typologie referential, for the rail's legend; empty when it could not be read. */
  protected readonly typologies = signal<TypologieItem[]>([]);
  /** The breaks of the plan; null when the request failed — the rail and the calendar still draw. */
  protected readonly pauses = signal<RapportPauses | null>(null);
  /** The tight walks between two vacations; null when the request failed — the rail still draws. */
  protected readonly walks = signal<WalkSequenceReport | null>(null);
  /** The grouped arrivals (covoiturages), day by day; null when the request failed. */
  protected readonly groupedArrivals = signal<GroupedArrivalReport | null>(null);
  /** Emplacement referential, for the map; empty when it could not be read. */
  protected readonly emplacements = signal<Emplacement[]>([]);

  /** The axis (issue #713): the day, the stands, the people, the game categories. */
  protected readonly axe = signal<PlanningAxe>('jour');
  protected readonly view = signal<JourneeView>('rail');
  /** The view toggle's value: « Jour » for both of its modes. */
  protected readonly currentTab = computed(() => tabOf(this.view()));
  protected readonly filtre = signal('');
  protected readonly stand = signal('');
  protected readonly animateur = signal('');
  /** The two filters every axis keeps: a location (or `aucun`), a game category. */
  protected readonly emplacement = signal('');
  protected readonly typologie = signal('');
  /* The two grids' own state, owned here since the page writes every key it shows. */
  protected readonly standView = signal<StandView>('grille');
  protected readonly densiteStand = signal<DensiteStand>('noms');
  protected readonly personView = signal<PersonView>('grille');
  protected readonly densitePersonne = signal<DensitePersonne>('detail');
  /** How many days the two grids show around the page's day (`portee`); one value for both axes. */
  protected readonly gridSpan = signal<GridSpan>('semaine');
  protected readonly triPersonne = signal<SortState>(NO_SORT);
  protected readonly allColumns = signal(false);
  protected readonly noRestOnly = signal(false);
  /** The plan « Par personne » reads its hours from (`source`); null leaves it to the server. */
  protected readonly hoursSource = signal<HoursSource | null>(null);
  /* The views' own state, read from the URL here and handed over two-way. */
  protected readonly lignesRail = signal<RailVue>('tous');
  protected readonly instantCarte = signal<number | null>(null);
  protected readonly chargeCarte = signal<PorteeCharge>('jour');
  protected readonly seulementProblemes = signal(false);
  /** The table's seat filter, set by the relecture chips (`sieges`). */
  protected readonly sieges = signal<FiltreSieges>('tous');

  protected readonly jours = computed<JourEvenement[]>(() =>
    planningDays(this.planning()?.postes ?? []),
  );
  private readonly navigation = dayNavigation(this.jours, (jour) => jour.key, {
    initial: requestedKey(
      this.route.snapshot.queryParamMap.get('date'),
      this.route.snapshot.queryParamMap.get('jour'),
    ),
  });
  /**
   * The day on screen: the one the URL asked for — by its key, or by the
   * number the four former screens used — else the first of the plan.
   */
  protected readonly jourCourant = computed<JourEvenement | null>(
    () => jourDemande(this.jours(), this.navigation.selected()) ?? this.navigation.current(),
  );
  /** The day the URL asked for is none of the plan's: said, rather than opening the first in silence. */
  protected readonly jourDemandeInconnu = computed(() =>
    jourInconnu(this.jours(), this.navigation.selected()),
  );
  /** What the `date` param carries: nothing on the first day, which is the default. */
  private readonly dateParam = computed(() => {
    const courant = this.jourCourant();
    const premier = this.jours()[0];
    return courant && premier && courant.key !== premier.key ? courant.key : null;
  });

  protected readonly stands = computed<Option[]>(() => {
    const options = new Map<string, string>();
    for (const poste of this.planning()?.postes ?? []) {
      if (poste.stand && !options.has(poste.stand.id)) {
        options.set(poste.stand.id, poste.stand.nom || poste.stand.id);
      }
    }
    return [...options.entries()]
      .map(([id, label]) => ({ id, label }))
      .sort((gauche, droite) => gauche.label.localeCompare(droite.label));
  });
  protected readonly animateurs = computed<Option[]>(() =>
    (this.planning()?.animateurs ?? [])
      .map((animateur) => ({
        id: animateur.id,
        label: `${animateur.prenom ?? ''} ${animateur.nom ?? ''}`.trim() || animateur.id,
      }))
      .sort((gauche, droite) => gauche.label.localeCompare(droite.label)),
  );

  /** The locations of the plan's stands, and « Sans emplacement » when some sit on none. */
  protected readonly emplacementsOptions = computed<Option[]>(() => {
    const options = new Map<string, string>();
    let orphelins = false;
    for (const poste of this.planning()?.postes ?? []) {
      const emplacement = poste.stand?.emplacement;
      if (emplacement) {
        options.set(emplacement.id, emplacement.nom || emplacement.id);
      } else if (poste.stand) {
        orphelins = true;
      }
    }
    const triees = [...options.entries()]
      .map(([id, label]) => ({ id, label }))
      .sort((gauche, droite) => gauche.label.localeCompare(droite.label));
    return orphelins
      ? [
          ...triees,
          {
            id: NO_LOCATION,
            label: $localize`:@@repartitionHeures.noEmplacement:Sans emplacement`,
          },
        ]
      : triees;
  });
  /** The game categories the plan's stands offer, labelled from the referential. */
  protected readonly typologiesOptions = computed<Option[]>(() => {
    const labels = new Map(this.typologies().map((typologie) => [typologie.id, typologie.label]));
    const ids = new Set(
      (this.planning()?.postes ?? []).flatMap((poste) => poste.stand?.typologiesProposees ?? []),
    );
    return [...ids]
      .map((id) => ({ id, label: labels.get(id) ?? id }))
      .sort((gauche, droite) => gauche.label.localeCompare(droite.label));
  });

  /**
   * The stands the location and game-category filters leave, resolved once
   * for every axis; null when neither is set.
   */
  protected readonly standsRetenus = computed<ReadonlySet<string> | null>(() => {
    const emplacement = this.emplacement();
    const typologie = this.typologie();
    if (!emplacement && !typologie) {
      return null;
    }
    const retenus = new Set<string>();
    for (const poste of this.planning()?.postes ?? []) {
      const stand = poste.stand;
      if (
        stand &&
        (!emplacement ||
          (emplacement === NO_LOCATION
            ? !stand.emplacement
            : stand.emplacement?.id === emplacement)) &&
        (!typologie || (stand.typologiesProposees ?? []).includes(typologie))
      ) {
        retenus.add(stand.id);
      }
    }
    return retenus;
  });
  /** The stand filter folded into the location and category ones, for the two grids. */
  protected readonly gridStands = computed<ReadonlySet<string> | null>(() => {
    const stand = this.stand();
    const retenus = this.standsRetenus();
    if (!stand) {
      return retenus;
    }
    return new Set(retenus === null || retenus.has(stand) ? [stand] : []);
  });
  /** The emplacement and game category filters, folded by default (`?avance=1`). */
  protected readonly avance = reglagesAvances();

  /**
   * The filters of the bar the rendering on screen reads, and only those are
   * shown: a filter drawn and then ignored narrows nothing while saying it
   * does. A hidden filter keeps its value, for the rendering that reads it.
   */
  protected readonly shownFilters = computed<ShownFilters>(() =>
    filtersShown(this.axe(), this.standView(), this.view()),
  );

  /** The autocompletes speak in lists of ids; the page keeps one id, or none. */
  protected readonly standChoisi = computed(() => (this.stand() ? [this.stand()] : []));
  protected readonly animateurChoisi = computed(() => (this.animateur() ? [this.animateur()] : []));
  protected readonly emplacementChoisi = computed(() =>
    this.emplacement() ? [this.emplacement()] : [],
  );
  protected readonly typologieChoisie = computed(() =>
    this.typologie() ? [this.typologie()] : [],
  );

  /** What the `vue` param carries: the grid on screen, or the day's view (nothing on the rail). */
  private readonly viewParam = computed(() =>
    this.axe() === 'jour' ? viewParams(this.view()).vue : this.axe(),
  );
  /** What the `rendu` param carries: the rendering of the grid on screen, nothing on its default. */
  private readonly renduParam = computed(() => {
    switch (this.axe()) {
      case 'stand':
        return this.standView() === 'grille' ? null : this.standView();
      case 'personne':
        return this.personView() === 'grille' ? null : this.personView();
      default:
        return null;
    }
  });
  /** What the `mode` param carries, in the day's view: the table; nothing on the rail. */
  private readonly modeParam = computed(() =>
    this.axe() === 'jour' ? viewParams(this.view()).mode : null,
  );
  /** What the `densite` param carries, on the two grids only. */
  private readonly densiteParam = computed(() => {
    if (this.axe() === 'stand') {
      return this.densiteStand() === 'noms' ? null : this.densiteStand();
    }
    if (this.axe() === 'personne') {
      return this.densitePersonne() === 'detail' ? null : this.densitePersonne();
    }
    return null;
  });

  /**
   * The sort the address carries: the person axis's, except a summary column's
   * on the day grid, which shows only the names — kept in memory for the
   * synthesis, never written where nothing on screen says it.
   */
  private readonly shownPersonSort = computed<SortState>(() => {
    if (this.axe() !== 'personne') {
      return NO_SORT;
    }
    const tri = this.triPersonne();
    return this.personView() === 'grille' && tri.active !== TRI_NOM ? NO_SORT : tri;
  });

  /**
   * What the `portee` param carries: on the two axes whose grids read it, as
   * `densite` is, so that a reload on the frise or the synthesis keeps it for
   * the way back to the grid; absent for the week.
   */
  private readonly gridSpanParam = computed(() => {
    const axis = this.axe() === 'stand' || this.axe() === 'personne';
    return axis && this.gridSpan() !== 'semaine' ? this.gridSpan() : null;
  });

  /** True as soon as a filter or a view's own switch narrows the day; the day and the rendering are navigation. */
  protected readonly viewChanged = computed(
    () =>
      this.filtre().trim() !== '' ||
      this.stand() !== '' ||
      this.animateur() !== '' ||
      this.emplacement() !== '' ||
      this.typologie() !== '' ||
      this.densiteStand() !== 'noms' ||
      this.densitePersonne() !== 'detail' ||
      this.gridSpanParam() !== null ||
      this.shownPersonSort().active !== '' ||
      (this.allColumns() && this.axe() === 'personne' && this.personView() === 'synthese') ||
      this.noRestOnly() ||
      (this.hoursSource() !== null && this.axe() === 'personne') ||
      this.lignesRail() !== 'tous' ||
      this.instantCarte() !== null ||
      this.chargeCarte() !== 'jour' ||
      this.seulementProblemes() ||
      this.sieges() !== 'tous',
  );

  /**
   * Whether a filter hides part of the day — which the relecture panel,
   * accepting it whole, has to say. Only a filter the rendering reads counts:
   * a hidden one hides nothing.
   */
  protected readonly filtreActif = computed(() => {
    const shown = this.shownFilters();
    return (
      (shown.name && this.filtre().trim() !== '') ||
      (shown.stand && this.stand() !== '') ||
      (shown.animateur && this.animateur() !== '') ||
      (shown.emplacement && this.emplacement() !== '') ||
      (shown.typologie && this.typologie() !== '')
    );
  });

  /** The ISO date of the day on screen, which is what a reading names; null on an undated day. */
  protected readonly dateCourante = computed(() => this.jourCourant()?.date ?? null);
  /** What the page says after a reading was recorded or withdrawn. */
  protected readonly message = signal('');

  /*
   * The Siège panel (`siege`): the seat it is open on, by its poste id. An
   * older address names a timeslot instead (`creneau`, and `stand` when it
   * had one — the Banc de touche's keys): the page resolves it to a seat once
   * the plan is read, moves to its day, and writes `siege` in its place.
   */
  protected readonly openSeatId = signal<string | null>(null);
  /** Drawn once a plan is on screen: before it, the panel could only say its seat is missing. */
  protected readonly seatPanelShown = computed(
    () => this.openSeatId() !== null && this.planning() !== null,
  );
  /** `creneau` as the address gave it, until the plan says which seat it means. */
  private readonly creneauParam = signal<string | null>(null);
  /** What the address asked for before the plan was known: resolved on the first read. */
  private pendingSeat: SeatRequest | null = null;
  /** Where the focus was when the panel opened: it goes back there when the panel closes. */
  private seatOpener: HTMLElement | null = null;
  /**
   * The opener's `data-siege-cle`: a gesture re-reads the plan, the rendering
   * is drawn again, and the element the focus came from is gone — its key
   * finds the one drawn in its place.
   */
  private seatOpenerKey: string | null = null;
  /** A key to give the focus back to once the plan being re-read is drawn. */
  private focusKeyAfterLoad: string | null = null;
  private readonly injector = inject(Injector);
  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);

  protected readonly imprimerLabel = $localize`:@@planning.imprimer.aide:La journée entière sur papier : la mise en page de l'affichage mural`;
  protected readonly tvLabel = $localize`:@@planning.tv.aide:Créer le lien de la télévision de la salle de contrôle, et son QR code`;

  constructor() {
    // The axis and its rendering are followed rather than read once: the
    // palette's « Planning › Rail » or « Par personne » navigates to this very
    // route with another `axe` or `vue`, and the router reuses the component
    // instead of building it again.
    this.route.queryParamMap.pipe(takeUntilDestroyed()).subscribe((query) => {
      this.readRendering(query);
      this.readTargets(query);
    });
    const params = this.route.snapshot.queryParamMap;
    if (this.axe() === 'stand') {
      this.densiteStand.set(readDensiteStand(params.get('densite')));
    } else if (this.axe() === 'personne') {
      this.densitePersonne.set(readDensitePersonne(params.get('densite')));
    }
    this.gridSpan.set(readGridSpan(params.get('portee')));
    this.triPersonne.set(readSort(params));
    this.allColumns.set(params.get('colonnes') === 'toutes');
    this.noRestOnly.set(params.get('sansRepos') === '1');
    this.hoursSource.set(readHoursSource(params.get('source')));
    this.filtre.set(params.get('q') ?? '');
    this.emplacement.set(params.get('emplacement') ?? '');
    this.typologie.set(params.get('typologie') ?? '');
    this.lignesRail.set(RailJourView.readLignes(params.get('lignes')));
    this.instantCarte.set(readInstant(params.get('t')));
    this.chargeCarte.set(readPorteeCharge(params.get('charge')));
    this.seulementProblemes.set(params.get('problemes') === '1');
    this.sieges.set(readFiltreSieges(params.get('sieges')));
    // Every key of the screen, written by the one component that is always
    // mounted. The renderings hold their own state through `model()`, but a
    // rendering only writes while it is on screen: leaving the rail on
    // « libres » and switching to the calendar left `lignes=libres` in an
    // address nothing could clear any more — « Réinitialiser la vue » cleared
    // the signal, the rail was gone, and a reload brought the filter back.
    // `jour`, the key of the four former screens, is retired once the day is
    // known by its date.
    keepViewInQueryParams(() => ({
      ...RETIRED_KEYS_CLEARED,
      date: this.dateParam(),
      jour: this.jours().length > 0 ? null : this.route.snapshot.queryParamMap.get('jour'),
      vue: this.viewParam(),
      rendu: this.renduParam(),
      mode: this.modeParam(),
      densite: this.densiteParam(),
      portee: this.gridSpanParam(),
      ...sortQueryParams(this.shownPersonSort()),
      ...this.personAxisParams(),
      q: optionalParam(this.filtre()),
      stand: optionalParam(this.stand()),
      animateur: optionalParam(this.animateur()),
      emplacement: optionalParam(this.emplacement()),
      typologie: optionalParam(this.typologie()),
      lignes: this.lignesRail() === 'tous' ? null : this.lignesRail(),
      t: this.instantCarte() === null ? null : String(this.instantCarte()),
      charge: this.chargeCarte() === 'jour' ? null : this.chargeCarte(),
      problemes: this.seulementProblemes() ? '1' : null,
      sieges: this.sieges() === 'tous' ? null : this.sieges(),
      siege: this.openSeatId(),
      creneau: this.creneauParam(),
      // The treemap writes its own keys while it is on screen; once it is
      // gone, nothing would, and a reload would bring its period back unasked.
      ...(this.axe() === 'stand' && this.standView() === 'treemap' ? {} : TREEMAP_KEYS_CLEARED),
    }));
  }

  ngOnInit(): void {
    void this.refresh();
  }

  /**
   * The keys only « Par personne » writes: every column of the synthesis, the
   * people without rest, the plan the hours are read from.
   */
  private personAxisParams(): {
    colonnes: string | null;
    sansRepos: string | null;
    source: string | null;
  } {
    const personne = this.axe() === 'personne';
    return {
      colonnes: personne && this.personView() === 'synthese' && this.allColumns() ? 'toutes' : null,
      sansRepos: personne && this.noRestOnly() ? '1' : null,
      source: personne ? this.hoursSource() : null,
    };
  }

  /**
   * Reads everything the renderings need, in one go. The referentials
   * degrade rather than fail: a legend without labels or a map without
   * coordinates is still a day worth reading.
   */
  protected async refresh(): Promise<void> {
    this.loading.set(true);
    this.error.set('');
    try {
      const [planning, typologies, pauses, emplacements, walks, groupedArrivals] =
        await Promise.all([
          this.planningState.loadForDisplay(),
          this.analysesApi.typologies().catch(() => []),
          this.analysesApi.breaks().catch(() => null),
          this.analysesApi.emplacements().catch(() => []),
          this.analysesApi.walks().catch(() => null),
          this.analysesApi.groupedArrivals().catch(() => null),
        ]);
      this.planning.set(planning);
      this.typologies.set(typologies);
      this.pauses.set(pauses && typeof pauses === 'object' && 'journees' in pauses ? pauses : null);
      this.emplacements.set(emplacements);
      this.walks.set(walks && typeof walks === 'object' && 'walks' in walks ? walks : null);
      this.groupedArrivals.set(
        groupedArrivals && typeof groupedArrivals === 'object' && 'groups' in groupedArrivals
          ? groupedArrivals
          : null,
      );
      this.resolvePendingSeat(planning);
      // The banner is refreshed with the plan it comments on: a solve that
      // withdrew readings must not leave the old count on screen.
      void this.validations.reload();
      void this.consignes.reload();
    } catch (error) {
      this.planning.set(null);
      this.error.set(errorPrefix(error));
    } finally {
      this.loading.set(false);
      if (this.focusKeyAfterLoad !== null) {
        this.restoreFocusAfterRender();
      }
    }
  }

  /** A view rewrote the plan (a drop, a repair): drop the session's copy and re-read. */
  protected async recharger(): Promise<void> {
    this.planningState.set(null);
    await this.refresh();
  }

  /**
   * The seat an address named, once the plan is known: its day on screen,
   * its panel open. A timeslot the plan holds no seat on is said, not guessed.
   */
  private resolvePendingSeat(planning: PlanningEvenement | null): void {
    const request = this.pendingSeat;
    if (!request) {
      return;
    }
    this.pendingSeat = null;
    this.creneauParam.set(null);
    const poste = resolveSeat(planning?.postes ?? [], request);
    if (!poste?.creneau) {
      this.openSeatId.set(null);
      this.message.set(
        $localize`:@@journee.siege.introuvable:Ce siège n'est pas dans le planning enregistré : aucun siège sur ce créneau, ou créneau créé après la dernière résolution.`,
      );
      return;
    }
    this.navigation.select(dayKey(poste.creneau.jour, poste.creneau.date));
    this.openSeatId.set(poste.id);
  }

  /** A cell of a rendering was clicked: the Siège panel opens on the seat it names. */
  protected openSeat(request: SeatRequest): void {
    const poste = resolveSeat(this.planning()?.postes ?? [], request);
    if (!poste) {
      this.message.set(
        $localize`:@@journee.siege.absent:Ce siège n'est plus dans le planning affiché : actualisez la journée.`,
      );
      return;
    }
    const active = document.activeElement;
    // The keyed element around the one clicked: the rail's line rather than
    // the shift label inside its hidden track.
    const opener = active instanceof HTMLElement ? active : null;
    const keyed = opener?.closest<HTMLElement>('[data-siege-cle]') ?? null;
    this.seatOpener = keyed ?? opener;
    this.seatOpenerKey = keyed?.dataset['siegeCle'] ?? null;
    this.openSeatId.set(poste.id);
  }

  /**
   * « Fermer » or Escape: the panel goes, and the focus returns to the cell
   * that opened it — or, a gesture having re-read the plan in between, to the
   * cell drawn in its place, once the re-read plan is on screen.
   */
  protected closeSeat(): void {
    this.openSeatId.set(null);
    const opener = this.seatOpener;
    this.seatOpener = null;
    const key = this.seatOpenerKey;
    this.seatOpenerKey = null;
    if (opener?.isConnected) {
      afterNextRender(() => opener.focus(), { injector: this.injector });
      return;
    }
    this.focusKeyAfterLoad = key;
    if (key !== null && !this.loading()) {
      this.restoreFocusAfterRender();
    }
  }

  /** Focus on the cell of {@link focusKeyAfterLoad}, after the rendering is drawn. */
  private restoreFocusAfterRender(): void {
    afterNextRender(
      () => {
        const key = this.focusKeyAfterLoad;
        this.focusKeyAfterLoad = null;
        const cells = this.host.nativeElement.querySelectorAll<HTMLElement>('[data-siege-cle]');
        Array.from(cells)
          .find((cell) => cell.dataset['siegeCle'] === key)
          ?.focus();
      },
      { injector: this.injector },
    );
  }

  protected changeView(view: JourneeView): void {
    this.view.set(view);
  }

  /** The mode « Jour » was left in: coming back from the map or a grid restores it. */
  private dayMode: JourneeView = 'rail';

  /** The view toggle's value: the grid on screen, else « Jour » or the map. */
  protected readonly currentView = computed<PlanningView>(() =>
    this.axe() === 'jour' ? tabOf(this.view()) : this.axe(),
  );

  /** The view toggle: « Jour » comes back in the mode it was left in, the rail at first. */
  protected selectView(view: PlanningView): void {
    if (view === this.currentView()) {
      return;
    }
    if (this.currentView() === 'jour') {
      this.dayMode = this.view();
    }
    if (view === 'stand' || view === 'personne') {
      this.axe.set(view);
      return;
    }
    this.axe.set('jour');
    this.view.set(view === 'jour' ? this.dayMode : 'carte');
  }

  protected choisirStand(ids: string[]): void {
    this.stand.set(ids[0] ?? '');
  }

  protected choisirEmplacement(ids: string[]): void {
    this.emplacement.set(ids[0] ?? '');
  }

  protected choisirTypologie(ids: string[]): void {
    this.typologie.set(ids[0] ?? '');
  }

  /**
   * The view an address names: `vue` (`jour`, `stand`, `personne`, `carte`) and
   * the day's `mode`, or the 3.x `axe`. A grid's own rendering is `rendu`, or
   * the 3.x `vue` of that axis; the other axes keep theirs.
   */
  private readRendering(query: ParamMap): void {
    this.axe.set(readAxe(query.get('vue'), query.get('axe')));
    const rendu = readRendu(query);
    if (this.axe() === 'stand') {
      this.standView.set(readStandView(rendu));
    } else if (this.axe() === 'personne') {
      this.personView.set(readPersonView(rendu ?? impliedPersonView(query)));
    } else {
      this.view.set(readView(query.get('vue'), query.get('mode')));
    }
  }

  /**
   * What an address points at — a stand, an animateur, a day, a seat — applied
   * on every navigation that names it, not on the first one alone: the
   * palette's stand or timeslot lands on this very route, the router reuses
   * the page, and the stand or the day it asked for used to wait for a reload.
   * A key the address leaves out keeps what is on screen.
   */
  private readTargets(query: ParamMap): void {
    const stand = query.get('stand');
    if (stand !== null) {
      this.stand.set(stand);
    }
    const animateur = query.get('animateur');
    if (animateur !== null) {
      this.animateur.set(animateur);
    }
    const jour = requestedKey(query.get('date'), query.get('jour'));
    if (jour !== null) {
      this.navigation.select(jour);
    }
    const siege = query.get('siege');
    const creneau = Number(query.get('creneau'));
    if (siege) {
      this.openSeatId.set(siege);
      this.pendingSeat = { posteId: siege };
    } else if (Number.isFinite(creneau) && creneau > 0) {
      this.creneauParam.set(query.get('creneau'));
      // `animateur` names whose seat it is — « Qui peut remplacer » from the
      // Diagnostic's fragility — else the first free seat is the one meant.
      this.pendingSeat = { creneauId: creneau, standId: stand, animateurId: animateur };
    }
    // Once the plan is on screen, the seat is resolved now; before, the first
    // read resolves it.
    const planning = this.planning();
    if (this.pendingSeat && planning !== null && !this.loading()) {
      this.resolvePendingSeat(planning);
    }
  }

  /**
   * A cell of a grid was opened: the page moves to its day — so « Jour »
   * lands there — and opens the Siège panel on its seat.
   */
  protected openSeatOnDay(request: { posteId: string; jour: string }): void {
    this.navigation.select(request.jour);
    this.openSeat({ posteId: request.posteId });
  }

  /** A tile of the treemap: that stand, on « Jour », on the treemap's day when it had one. */
  protected openStand(demande: { standId: string; date: string | null }): void {
    this.stand.set(demande.standId);
    if (demande.date) {
      this.navigation.select(demande.date);
    }
    this.view.set('calendrier');
    this.axe.set('jour');
  }

  protected choisirAnimateur(ids: string[]): void {
    this.animateur.set(ids[0] ?? '');
  }

  protected selectJour(key: string): void {
    this.navigation.select(key);
    this.openSeatId.set(null);
  }

  /** The map's event-wide load grid asks for another day, by its number. */
  protected selectJourNumero(numero: number): void {
    const jour = this.jours().find((candidat) => candidat.jour === numero);
    if (jour) {
      this.navigation.select(jour.key);
      this.openSeatId.set(null);
    }
  }

  protected resetView(): void {
    this.filtre.set('');
    this.stand.set('');
    this.animateur.set('');
    this.emplacement.set('');
    this.typologie.set('');
    this.densiteStand.set('noms');
    this.densitePersonne.set('detail');
    this.gridSpan.set('semaine');
    this.triPersonne.set(NO_SORT);
    this.allColumns.set(false);
    this.noRestOnly.set(false);
    this.hoursSource.set(null);
    this.lignesRail.set('tous');
    this.instantCarte.set(null);
    this.chargeCarte.set('jour');
    this.seulementProblemes.set(false);
    this.sieges.set('tous');
  }
}
