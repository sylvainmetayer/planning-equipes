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
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatSelectModule } from '@angular/material/select';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { AnalysesApi } from '../../core/api/analyses-api';
import { ReferenceChangementsParam } from '../../core/api/journees-api';
import { dayNavigation } from '../../core/day-navigation';
import { errorPrefix } from '../../core/error-message';
import {
  Emplacement,
  PlanningEvenement,
  GroupedArrivalReport,
  WalkSequenceReport,
  RapportPauses,
  TypologieItem,
} from '../../core/models';
import { PlanningStateService } from '../../core/planning-state.service';
import { keepViewInQueryParams, optionalParam } from '../../core/view-query-params';
import { ValidationsStore } from '../../core/validations.store';
import { ConsignesStore } from '../../core/consignes.store';
import { TableFilter } from '../../shared/table-filter';
import { SelectionRecherche } from '../../shared/selection-recherche';
import { CalendarDayView, FiltreSieges, readFiltreSieges } from '../calendar-day/calendar-day-vue';
import { readInstant } from '../carte-jour/carte-jour';
import { CarteJourView } from '../carte-jour/carte-jour-vue';
import { PorteeCharge, readPorteeCharge } from '../carte-jour/charge-emplacement';
import { PausesView } from '../pauses/pauses-vue';
import { ChangementsReading, readReading, readReference } from './changements';
import { ChangementsView } from './changements-vue';
import { ConsigneLigne } from './consigne-ligne';
import { MiniMois } from './mini-mois';
import { PastilleRelecture, RelectureBarre } from './relecture-barre';
import { RailJourView, RailVue } from '../rail-jour/rail-jour-vue';
import { ComparaisonView } from './comparaison-vue';
import {
  isComparable,
  JourEvenement,
  dayKey,
  defaultComparisonDay,
  JourneeView,
  jourSemaineVoisine,
  requestedKey,
  jourDemande,
  planningDays,
  readView,
  resolveComparison,
} from './journee';
import { StatusMessage } from '../../shared/status-message';
import { NewWindowLink } from '../../shared/new-window-link';
import { SeatPanel } from '../../shared/siege-panel/siege-panel';
import { resolveSeat, SeatRequest } from '../../shared/siege-panel/seat';

/** A stand or an animateur of the plan, as the two filter selectors list them. */
interface Option {
  id: string;
  label: string;
}

/**
 * « Planning » (issue #712, the former Journée): one day of the persisted plan
 * under five renderings — the table stands × timeslots, the rail animateur by
 * animateur, the map hour by hour, the breaks and meals, what changed since a
 * reference — with the plan at the top: the title on one line, the day chosen
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
    StatusMessage,
    MatButtonModule,
    MatButtonToggleModule,
    MatFormFieldModule,
    MatIconModule,
    MatProgressBarModule,
    MatSelectModule,
    RouterLink,
    NewWindowLink,
    TableFilter,
    SelectionRecherche,
    MiniMois,
    RelectureBarre,
    ConsigneLigne,
    ChangementsView,
    ComparaisonView,
    CalendarDayView,
    RailJourView,
    PausesView,
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

  protected readonly view = signal<JourneeView>('calendrier');
  protected readonly filtre = signal('');
  protected readonly stand = signal('');
  protected readonly animateur = signal('');
  /* The views' own state, read from the URL here and handed over two-way. */
  protected readonly lignesRail = signal<RailVue>('tous');
  protected readonly instantCarte = signal<number | null>(null);
  protected readonly chargeCarte = signal<PorteeCharge>('jour');
  protected readonly withoutRelais = signal(false);
  protected readonly coupuresManquantes = signal(false);
  protected readonly seulementProblemes = signal(false);
  /** The table's seat filter, set by the relecture chips (`sieges`). */
  protected readonly sieges = signal<FiltreSieges>('tous');
  /** The Changements rendering's own state: the reference nobody chose stays null, the server picks. */
  protected readonly referenceChangements = signal<ReferenceChangementsParam | null>(null);
  protected readonly lectureChangements = signal<ChangementsReading>('vacations');

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
  /** What the `date` param carries: nothing on the first day, which is the default. */
  private readonly dateParam = computed(() => {
    const courant = this.jourCourant();
    const premier = this.jours()[0];
    return courant && premier && courant.key !== premier.key ? courant.key : null;
  });

  /*
   * The comparison mode: a second day, named by the `comparer` param in the
   * same key format as `date`. It shows on the calendar and the rail only;
   * the three other renderings stay on one day and keep the param for the
   * way back.
   */
  /** The key the URL or the second selector asked for, as given; null out of the comparison. */
  protected readonly comparerDemande = signal<string | null>(null);
  private readonly comparaison = computed(() =>
    resolveComparison(this.jours(), this.jourCourant(), this.comparerDemande()),
  );
  /** The second day, when it names a day of the plan other than the one on screen. */
  protected readonly jourCompare = computed(() => this.comparaison().jour);
  /** True when the rendering on screen can compare. */
  protected readonly comparableView = computed(() => isComparable(this.view()));
  /** True when two days are on screen: the renderings, and their drag and drop, give way to the comparison. */
  protected readonly comparaisonActive = computed(
    () => this.comparableView() && this.jourCompare() !== null,
  );
  /** Why the requested second day was set aside — said once the plan is known, never guessed before. */
  protected readonly comparaisonIgnoree = computed(() => {
    if (this.jours().length === 0) {
      return '';
    }
    switch (this.comparaison().refus) {
      case 'identique':
        return $localize`:@@journee.comparaison.ignoree.identique:Comparaison ignorée : le jour demandé est celui déjà affiché.`;
      case 'inconnu':
        return $localize`:@@journee.comparaison.ignoree.inconnu:Comparaison ignorée : ce jour n'existe pas dans le planning.`;
      default:
        return '';
    }
  });
  /** What the `comparer` param carries: the resolved key, or the request as given until the plan says. */
  private readonly comparerParam = computed(() => {
    const demande = this.comparerDemande();
    if (demande === null || this.jours().length === 0) {
      return demande;
    }
    return this.jourCompare()?.key ?? demande;
  });
  /** « Même jour, semaine précédente / suivante », when the event holds it. */
  protected readonly semainePrecedente = computed(() =>
    jourSemaineVoisine(this.jours(), this.jourCourant(), -1),
  );
  protected readonly semaineSuivante = computed(() =>
    jourSemaineVoisine(this.jours(), this.jourCourant(), 1),
  );
  /** The days the second selector offers: all but the one on screen. */
  protected readonly joursComparables = computed(() =>
    this.jours().filter((jour) => jour.key !== this.jourCourant()?.key),
  );
  /** « Seulement les différences » of the comparison (`ecarts`). */
  protected readonly seulementEcarts = signal(false);

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

  /** The autocompletes speak in lists of ids; the page keeps one id, or none. */
  protected readonly standChoisi = computed(() => (this.stand() ? [this.stand()] : []));
  protected readonly animateurChoisi = computed(() => (this.animateur() ? [this.animateur()] : []));

  /** The chip of the relecture bar the rendering is narrowed by, read back from the state it set. */
  protected readonly pastilleActive = computed<PastilleRelecture>(() => {
    const view = this.view();
    if (view === 'calendrier' && this.sieges() !== 'tous') {
      return this.sieges() === 'vides' ? 'vides' : 'verrous';
    }
    if (view === 'pauses' && this.withoutRelais()) {
      return 'pauses';
    }
    return view === 'changements' ? 'changements' : 'aucune';
  });

  /** True as soon as a filter or a view's own switch narrows the day; the day and the rendering are navigation. */
  protected readonly viewChanged = computed(
    () =>
      this.filtre().trim() !== '' ||
      this.stand() !== '' ||
      this.animateur() !== '' ||
      this.lignesRail() !== 'tous' ||
      this.instantCarte() !== null ||
      this.chargeCarte() !== 'jour' ||
      this.withoutRelais() ||
      this.coupuresManquantes() ||
      this.seulementProblemes() ||
      this.sieges() !== 'tous' ||
      this.seulementEcarts() ||
      this.comparerDemande() !== null ||
      this.referenceChangements() !== null ||
      this.lectureChangements() !== 'vacations',
  );

  /** Whether a filter hides part of the day — which the relecture panel, accepting it whole, has to say. */
  protected readonly filtreActif = computed(
    () => this.filtre().trim() !== '' || this.stand() !== '' || this.animateur() !== '',
  );

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
    // The rendering is followed rather than read once: the palette's
    // « Journée › Rail » navigates to this very route with another `vue`, and
    // the router reuses the component instead of building it again.
    this.route.queryParamMap.pipe(takeUntilDestroyed()).subscribe((query) => {
      this.view.set(readView(query.get('vue')));
    });
    const params = this.route.snapshot.queryParamMap;
    this.filtre.set(params.get('q') ?? '');
    this.stand.set(params.get('stand') ?? '');
    this.animateur.set(params.get('animateur') ?? '');
    this.lignesRail.set(RailJourView.readLignes(params.get('lignes')));
    this.instantCarte.set(readInstant(params.get('t')));
    this.chargeCarte.set(readPorteeCharge(params.get('charge')));
    this.withoutRelais.set(params.get('relais') === 'sans');
    this.coupuresManquantes.set(params.get('repas') === 'manquantes');
    this.seulementProblemes.set(params.get('problemes') === '1');
    this.sieges.set(readFiltreSieges(params.get('sieges')));
    this.referenceChangements.set(readReference(params.get('reference')));
    this.lectureChangements.set(readReading(params.get('lecture')));
    this.comparerDemande.set(params.get('comparer') || null);
    this.seulementEcarts.set(params.get('ecarts') === '1');
    const siege = params.get('siege');
    const creneau = Number(params.get('creneau'));
    this.creneauParam.set(params.get('creneau'));
    if (siege) {
      this.openSeatId.set(siege);
      this.pendingSeat = { posteId: siege };
    } else if (Number.isFinite(creneau) && creneau > 0) {
      this.pendingSeat = { creneauId: creneau, standId: params.get('stand') };
    }
    // Every key of the screen, written by the one component that is always
    // mounted. The renderings hold their own state through `model()`, but a
    // rendering only writes while it is on screen: leaving the rail on
    // « libres » and switching to the calendar left `lignes=libres` in an
    // address nothing could clear any more — « Réinitialiser la vue » cleared
    // the signal, the rail was gone, and a reload brought the filter back.
    // `jour`, the key of the four former screens, is retired once the day is
    // known by its date.
    keepViewInQueryParams(() => ({
      date: this.dateParam(),
      jour: this.jours().length > 0 ? null : this.route.snapshot.queryParamMap.get('jour'),
      vue: this.view() === 'calendrier' ? null : this.view(),
      q: optionalParam(this.filtre()),
      stand: optionalParam(this.stand()),
      animateur: optionalParam(this.animateur()),
      lignes: this.lignesRail() === 'tous' ? null : this.lignesRail(),
      t: this.instantCarte() === null ? null : String(this.instantCarte()),
      charge: this.chargeCarte() === 'jour' ? null : this.chargeCarte(),
      relais: this.withoutRelais() ? 'sans' : null,
      repas: this.coupuresManquantes() ? 'manquantes' : null,
      problemes: this.seulementProblemes() ? '1' : null,
      sieges: this.sieges() === 'tous' ? null : this.sieges(),
      reference: this.referenceChangements(),
      lecture: this.lectureChangements() === 'vacations' ? null : this.lectureChangements(),
      comparer: this.comparerParam(),
      ecarts: this.seulementEcarts() ? '1' : null,
      siege: this.openSeatId(),
      creneau: this.creneauParam(),
    }));
  }

  ngOnInit(): void {
    void this.refresh();
  }

  /** « Comparer avec… »: opens the second selector on the likeliest twin of the day on screen. */
  protected openComparison(): void {
    const jour = defaultComparisonDay(this.jours(), this.jourCourant());
    if (jour) {
      this.comparerDemande.set(jour.key);
    }
  }

  protected compareWith(key: string): void {
    this.comparerDemande.set(key);
  }

  /** Back to one day: the renderings, and their drag and drop, return. */
  protected quitterComparaison(): void {
    this.comparerDemande.set(null);
    this.seulementEcarts.set(false);
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

  /**
   * A relecture chip: the rendering narrowed to what it counts — the table on
   * its empty or locked seats, the breaks without relay, the changes — or
   * widened back when the active chip is pressed again.
   */
  protected applyChip(pastille: PastilleRelecture): void {
    this.sieges.set('tous');
    this.withoutRelais.set(false);
    switch (pastille) {
      case 'vides':
      case 'verrous':
        this.sieges.set(pastille);
        this.view.set('calendrier');
        break;
      case 'pauses':
        this.withoutRelais.set(true);
        this.view.set('pauses');
        break;
      case 'changements':
        this.view.set('changements');
        break;
      default:
        if (this.view() === 'changements') {
          this.view.set('calendrier');
        }
    }
  }

  protected choisirStand(ids: string[]): void {
    this.stand.set(ids[0] ?? '');
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
    this.lignesRail.set('tous');
    this.instantCarte.set(null);
    this.chargeCarte.set('jour');
    this.withoutRelais.set(false);
    this.coupuresManquantes.set(false);
    this.seulementProblemes.set(false);
    this.sieges.set('tous');
    this.comparerDemande.set(null);
    this.seulementEcarts.set(false);
    this.referenceChangements.set(null);
    this.lectureChangements.set('vacations');
  }
}
