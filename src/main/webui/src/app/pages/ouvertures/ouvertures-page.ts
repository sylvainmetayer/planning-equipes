import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  ElementRef,
  Injector,
  OnInit,
  afterNextRender,
  computed,
  inject,
  resource,
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
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { MatTooltipModule } from '@angular/material/tooltip';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { StandsApi } from '../../core/api/stands-api';
import { ConsignesStore } from '../../core/consignes.store';
import { bandeLabel } from '../../core/consigne-wording';
import { isInformationalAnomaly } from '../../core/horaire-stand';
import {
  AccesGrille,
  RecopieGrille,
  hasLignePrecedente,
  applyColonne,
  copyLignePrecedente,
  ligneSourceColonne,
} from '../../core/grille-saisie';
import { NotificationService } from '../../core/notification.service';
import { ReferenceCrudService } from '../../core/reference-crud.service';
import { errorText } from '../../core/resource-state';
import { SolverJobService } from '../../core/solver-job.service';
import { keepViewInQueryParams } from '../../core/view-query-params';
import { ConfirmService } from '../../shared/confirm-dialog';
import { GelNotice } from '../../shared/gel-notice';
import { injectGelReferentiel } from '../../core/gel-referentiel.store';
import { PastilleFerie } from '../../shared/pastille-ferie';
import { buildJourneeStands } from './journee-stands';
import { OpeningsComparisonView } from './comparaison-vue';
import {
  COUCHES,
  Couche,
  explainCell,
  readCouchesParam,
  toggleCouche,
  writeCouchesParam,
} from './calendrier-couches';
import { readStandsParam, writeStandsParam } from './comparaison-ouvertures';
import { ReferenceDataStore } from '../../core/reference-data.store';
import { labelOf, labelsOf } from '../../core/reference-labels';
import {
  ColonneJourneeType,
  accesGrilleJourneesTypes,
  colonnesJourneesTypes,
  ecrireColonneJourneeType,
  libelleColonneJourneeType,
  resumeJourneesTypes,
  valeurJourneeType,
} from './grille-journees-types';
import { JourneesTypesApi } from '../../core/api/journees-types-api';
import {
  AnomalieOuverture,
  CelluleJourOuverture,
  EtatJourneesTypes,
  LayerCell,
  LayerDay,
  LigneStandOuverture,
  RapportOuvertures,
  SegmentCellule,
  SourceHoraire,
  TypeAnomalieOuverture,
} from '../../core/models';
import {
  anomaliesParStand,
  dureeCourte,
  filtrerStands,
  FiltreOuvertures,
  iconeAnomalie,
  OpeningsView,
  OPENINGS_VIEW_PARAMS,
  readOpeningsView,
  synthese,
} from './ouvertures';
import {
  AdresseCellule,
  Cellules,
  ColonneGrille,
  accesGrilleDates,
  aplatissement,
  cellulesDepuis,
  colonneId,
  cellulesPartielles,
  key,
  collerBloc,
  countCopied,
  colonnes,
  deplacement,
  ecrireCellule,
  isPartialCell,
  jourDeReference,
  libelleColonne,
  propagerClefs,
  propagerScission,
  readCell,
  recopierJour,
  saisie,
  scinder,
  cellSegments,
  segmentsPartiels,
  standsModifies,
} from './grille-horaires';
import {
  RenduCellule,
  columnSpan,
  portionsIn,
  renderCell,
  segmentSpans,
  windowSpans,
} from './rendu-grille';

/** What a cell whose dates disagree shows: the template view never flattens one. */
const ECART = '≠';

/** One cell as the template binds it: text, flags and bars computed once, no call per binding. */
interface CelluleView {
  clef: string;
  colonneId: string;
  premierDuJour: boolean;
  valeur: string;
  modifiee: boolean;
  partielle: boolean;
  fermee: boolean;
  /** Nothing typed for the stand: it follows its rule — open by default, at its headcount. */
  sansSaisie: boolean;
  /** A timeslot the day's consigne added: dotted, under the « Créneaux » layer. */
  ajouteeParConsigne: boolean;
  /** The column falls on a public holiday: tinted, never blocked. */
  ferie: boolean;
  desactivee: boolean;
  libelle: string;
  infobulle: string | null;
  rendu: RenduCellule;
}

interface LigneView {
  standId: string;
  nom: string;
  /** Whether the filter shows the row; a hidden row keeps its cells, and its typed values. */
  visible: boolean;
  /** Nothing to take from above: the row is the first one displayed, or is not displayed at all. */
  noLignePrecedente: boolean;
  modifiee: boolean;
  /** Its anomalies in one tooltip, empty when it has none. */
  anomalies: string;
  /** Open time and seats over the displayed days: the reading grid's total column. */
  minutesOuvertes: number;
  postes: number;
  cellules: CelluleView[];
}

/** The cell the focus is in, explained: what the admin reads on a click. */
export interface ExplicationCellule {
  standId: string;
  nom: string;
  date: string;
  jour: number | null;
  colonne: string;
  /** The day in one sentence, from the layers; `null` while they are read. */
  phrase: string | null;
  postes: number;
  /** Windows the stand declares at an hour no timeslot covers, that day. */
  horsGrille: string[];
  partielle: string | null;
  modifiee: boolean;
  journeeType: string | null;
  consigne: boolean;
}

/**
 * « Horaires des stands »: the stand × timeslot grid of the opening schedule,
 * read and typed in one place.
 *
 * <p>What a cell shows comes from `GET /api/ouvertures-stands`, which builds it
 * server-side from the very seats `PlanningService.construirePostes` would hand
 * the solver — recurring rules expanded, dated exceptions applied, windows
 * clamped to each timeslot — and its bars from `GET
 * /api/ouvertures-stands/couches`, the layers before the consigne. Nothing is
 * recomputed here: a validation screen offering a second interpretation of
 * the data validates nothing.</p>
 *
 * <p>The cells are typed the way the organiser's own spreadsheet holds them —
 * one integer per stand and column, arrows, Enter, a pasted block, a day
 * copied onto the others. Nothing is written until « Enregistrer », and only
 * the stands whose cells changed are sent, each with its whole schedule
 * (`PUT /api/ouvertures-stands/grille`). A cell typed and not yet saved
 * already draws the opening it will write.</p>
 */
@Component({
  selector: 'app-ouvertures-page',
  imports: [
    FormsModule,
    MatCardModule,
    MatButtonModule,
    MatButtonToggleModule,
    MatCheckboxModule,
    MatFormFieldModule,
    MatInputModule,
    MatIconModule,
    MatSelectModule,
    MatTooltipModule,
    RouterLink,
    OpeningsComparisonView,
    PastilleFerie,
    GelNotice,
  ],
  templateUrl: './ouvertures-page.html',
  // horaires-stand.css: « Comparer » opens the stands' bulk edit, whose rule editor it styles.
  styleUrls: [
    '../../../styles/ouvertures.css',
    '../../../styles/saisie-repetitive.css',
    '../../../styles/horaires-stand.css',
  ],
  // Global by design (AGENTS.md): loaded with the route, unscoped like the partial it was.
  encapsulation: ViewEncapsulation.None,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class OuverturesPage implements OnInit {
  private readonly standsApi = inject(StandsApi);
  private readonly journeesTypesApi = inject(JourneesTypesApi);
  /** The consignes (issue #4): a day under one is marked, and its closed cells are not anomalies. */
  private readonly consignes = inject(ConsignesStore);
  /** Date → the badge's wording, for the days under a consigne. */
  protected readonly consigneByDate = computed(() => {
    const badges = new Map<string, { libelle: string; motif: string }>();
    for (const [date, consigne] of this.consignes.byDate()) {
      badges.set(date, {
        libelle: $localize`:@@ouvertures.badge.consigne:fermé de ${bandeLabel(consigne.fermetureDebut, consigne.fermetureFin)}:bande: par consigne`,
        motif: consigne.motif,
      });
    }
    return badges;
  });
  private readonly crud = inject(ReferenceCrudService);
  private readonly notifications = inject(NotificationService);
  private readonly confirm = inject(ConfirmService);
  private readonly route = inject(ActivatedRoute);
  private readonly hote = inject<ElementRef<HTMLElement>>(ElementRef);
  /** Typing is disabled while a solve runs: the server would refuse the save, and the landing persist would revert it. */
  protected readonly editingLocked = inject(SolverJobService).editingLocked;
  private readonly gel = injectGelReferentiel();
  /**
   * The two entry grids write the stands' opening hours, which a STANDS
   * freeze covers (ADR 0052): the server refuses the save, so the cells and
   * every gesture that changes them are closed, the notice saying why. A
   * CRENEAUX freeze leaves them open — nothing here writes a timeslot.
   */
  protected readonly gridLocked = computed(
    () => this.editingLocked() || this.gel.isFrozen('STANDS'),
  );

  protected readonly rapport = signal<RapportOuvertures | null>(null);

  /** The stamps the displayed grid was built from, sent back as preconditions (issue #362). */
  private readonly modifieLeParStand = computed(
    () => new Map((this.rapport()?.stands ?? []).map((ligne) => [ligne.standId, ligne.modifieLe])),
  );
  protected readonly chargement = signal(true);
  protected readonly filtre = signal<FiltreOuvertures>('TOUS');
  /** `?q=`: the stand search, restored from the URL — a « Que faire ? » action opens the grid on one stand. */
  protected readonly recherche = signal(this.route.snapshot.queryParamMap.get('q') ?? '');
  /** What the filter field holds, before the grid follows it: sixty-five rows of sixty cells are not re-laid on every keystroke. */
  protected readonly rechercheSaisie = signal(this.recherche());
  private filtrePending: ReturnType<typeof setTimeout> | null = null;
  protected readonly view = signal<OpeningsView>(
    readOpeningsView(this.route.snapshot.queryParamMap.get('vue')),
  );

  /* ----------------------------- compare view ----------------------------- */

  /** `?stands=a,b,c`: the stands « Comparer » lays side by side. */
  protected readonly comparaisonStands = signal<string[]>(
    readStandsParam(this.route.snapshot.queryParamMap.get('stands')),
  );
  /** `?ref=`: the reference among them; absent = the first one. */
  protected readonly comparaisonReference = signal<string | null>(
    this.route.snapshot.queryParamMap.get('ref') || null,
  );
  /** `?ecarts=1`: only the days where a stand differs. */
  protected readonly comparaisonEcarts = signal(
    this.route.snapshot.queryParamMap.get('ecarts') === '1',
  );
  /** The referential « Comparer » reads the rules from, and the bulk edit it opens writes through. */
  private readonly store = inject(ReferenceDataStore);
  private referentielCharge = false;

  /* ------------------------------ grid rendering ------------------------------ */

  /** `?couches=`: what the bars of a cell draw; absent = all four. */
  protected readonly couchesAffichees = signal<Couche[]>(
    readCouchesParam(this.route.snapshot.queryParamMap.get('couches')),
  );
  protected readonly allLayers = COUCHES;
  protected readonly couchesVisibles = computed<ReadonlySet<Couche>>(
    () => new Set(this.couchesAffichees()),
  );

  /** `?du=` / `?au=`: the days the grid shows, both included; absent = from the first, to the last. */
  protected readonly rangeStart = signal<string | null>(
    this.route.snapshot.queryParamMap.get('du') || null,
  );
  protected readonly rangeEnd = signal<string | null>(
    this.route.snapshot.queryParamMap.get('au') || null,
  );

  /** The event days inside `[rangeStart, rangeEnd]`. */
  protected readonly joursAffiches = computed(() => {
    const start = this.rangeStart();
    const end = this.rangeEnd();
    return (this.rapport()?.jours ?? []).filter(
      (jour) => (start === null || jour.date >= start) && (end === null || jour.date <= end),
    );
  });
  private readonly datesAffichees = computed(
    () => new Set(this.joursAffiches().map((jour) => jour.date)),
  );
  /** The columns of the displayed days: what the moves, the paste and the keyboard walk over. */
  protected readonly colonnesAffichees = computed(() => {
    const dates = this.datesAffichees();
    return this.colonnes().filter((colonne) => dates.has(colonne.date));
  });

  /**
   * The layers of the displayed days, before any solve: the stand's own
   * windows and the consigne, read once per report and range — a save changes
   * them too. What a cell explains on a click, and what its thin bars draw.
   */
  private readonly layersData = resource({
    params: () => {
      const jours = this.joursAffiches();
      const rapport = this.rapport();
      return rapport && jours.length > 0
        ? { from: jours[0].date, to: jours[jours.length - 1].date, rapport }
        : undefined;
    },
    loader: ({ params }) => this.standsApi.openingLayers(params.from, params.to),
  });
  protected readonly erreurCouches = errorText(this.layersData);
  /** `standId|date` → that stand's layers that day. */
  private readonly layersByCell = computed(() => {
    const index = new Map<string, LayerCell>();
    if (!this.layersData.hasValue()) {
      return index;
    }
    for (const ligne of this.layersData.value().stands) {
      for (const cellule of ligne.jours) {
        index.set(`${ligne.standId}|${cellule.date}`, cellule);
      }
    }
    return index;
  });
  private readonly layersByDay = computed(
    () =>
      new Map<string, LayerDay>(
        this.layersData.hasValue()
          ? this.layersData.value().jours.map((jour) => [jour.date, jour])
          : [],
      ),
  );

  /** `standId|date` → the stand's day as the report has it. */
  private readonly daysByCell = computed(() => {
    const index = new Map<string, CelluleJourOuverture>();
    for (const ligne of this.rapport()?.stands ?? []) {
      for (const jour of ligne.jours) {
        index.set(`${ligne.standId}|${jour.date}`, jour);
      }
    }
    return index;
  });

  /** The day of each stand in one sentence, from its layers; empty until they are read. */
  private readonly explications = computed(() => {
    const jours = this.layersByDay();
    const phrases = new Map<string, string>();
    for (const [clef, cellule] of this.layersByCell()) {
      phrases.set(clef, explainCell(cellule, jours.get(cellule.date)?.consigne ?? null));
    }
    return phrases;
  });

  /** What every cell of the report really holds, keyed `standId#colonneId`: the result bar. */
  private readonly segmentsLus = signal<ReadonlyMap<string, SegmentCellule[]>>(new Map());

  /* ------------------------------- entry grid ------------------------------ */

  /** The cells as typed; reset from the report on every reload. */
  protected readonly cellules = signal<Cellules>(new Map());
  /** The cells as the server last reported them: what "modified" is measured against. */
  private readonly reference = signal<Cellules>(new Map());
  private readonly partielles = signal<ReadonlySet<string>>(new Set());
  /** What each partial cell really holds: the stretches a save keeps as long as the cell is not retyped. */
  private readonly segments = signal<ReadonlyMap<string, SegmentCellule[]>>(new Map());
  /** The stands with at least one partial cell, in report order: what « Aligner » sends. */
  protected readonly standsPartiels = computed(() =>
    (this.rapport()?.stands ?? [])
      .map((ligne) => ligne.standId)
      .filter((standId) => this.hasPartialCells(standId)),
  );
  /** The last cell focused: where a paste lands, which day a row copy takes, and what the panel explains. */
  protected readonly celluleActive = signal<AdresseCellule | null>(null);
  protected readonly enregistrement = signal(false);

  /**
   * The columns as displayed: the report's, plus the ones cut in this screen
   * (« scinder »), which exist nowhere else until a cell under them is saved.
   */
  protected readonly colonnes = signal<ColonneGrille[]>([]);
  /** The column whose header shows the hour field of a cut, or none. */
  protected readonly scissionActive = signal<string | null>(null);
  protected readonly standsModifies = computed(() =>
    standsModifies(this.cellules(), this.reference()),
  );
  private readonly standIdsAffiches = computed(() => this.lignes().map((ligne) => ligne.standId));

  /** Timeslot id → added by the consigne of its day, from the layers. */
  private readonly creneauxAjoutes = computed(() => {
    const ajoutes = new Set<number>();
    for (const jour of this.layersByDay().values()) {
      for (const vacation of jour.vacations) {
        if (vacation.addedByConsigne && vacation.id !== null) {
          ajoutes.add(vacation.id);
        }
      }
    }
    return ajoutes;
  });

  /**
   * The rows as the template binds them: every cell's text, flags, labels and
   * bars computed once per change, so the template reads properties instead
   * of calling a dozen functions per cell — four thousand cells make that the
   * difference between a filter that follows the keystroke and one that lags
   * behind it.
   */
  protected readonly rowViews = computed<LigneView[]>(() => {
    const colonnesGrille = this.colonnesAffichees();
    const dates = this.datesAffichees();
    const cellules = this.cellules();
    const reference = this.reference();
    const partielles = this.partielles();
    const segmentsLus = this.segmentsLus();
    const modifies = new Set(this.standsModifies());
    const verrouille = this.gridLocked();
    const feries = this.holidaysByDate();
    const couches = this.couchesVisibles();
    const layersByCell = this.layersByCell();
    const layersByDay = this.layersByDay();
    const daysByCell = this.daysByCell();
    const explications = this.explications();
    const ajoutes = this.creneauxAjoutes();
    const anomalies = this.anomaliesParStand();
    const spans = new Map(
      colonnesGrille.map((colonne) => [
        colonne.colonneId,
        columnSpan(colonne.heureDebut, colonne.heureFin),
      ]),
    );
    // Every stand is rendered once and the filter only hides rows: rebuilding
    // twenty-eight rows of sixty cells when the field empties is what lagged.
    const visibles = new Set(this.lignes().map((ligne) => ligne.standId));
    const first = this.standIdsAffiches()[0];
    return (this.rapport()?.stands ?? []).map((ligne) => {
      const nom = ligne.nom || ligne.standId;
      const typees = cellules.get(ligne.standId);
      const lues = reference.get(ligne.standId);
      const joursAffiches = ligne.jours.filter((jour) => dates.has(jour.date));
      return {
        standId: ligne.standId,
        nom,
        visible: visibles.has(ligne.standId),
        noLignePrecedente: !visibles.has(ligne.standId) || ligne.standId === first,
        modifiee: modifies.has(ligne.standId),
        anomalies: (anomalies.get(ligne.standId) ?? []).map((each) => each.message).join('\n'),
        minutesOuvertes: joursAffiches.reduce((total, jour) => total + jour.minutesOuvertes, 0),
        postes: joursAffiches.reduce((total, jour) => total + jour.postes, 0),
        cellules: colonnesGrille.map((colonne) => {
          const clef = key(ligne.standId, colonne.colonneId);
          const caseJour = `${ligne.standId}|${colonne.date}`;
          const partielle = partielles.has(clef);
          const effectif = typees?.get(colonne.colonneId) ?? null;
          const modifiee = effectif !== (lues?.get(colonne.colonneId) ?? null);
          const source: SourceHoraire = daysByCell.get(caseJour)?.source ?? 'REGLE';
          const span = spans.get(colonne.colonneId) ?? columnSpan('00:00', '00:00');
          const lus = segmentsLus.get(clef);
          const couchesJour = layersByCell.get(caseJour);
          const consigne = layersByDay.get(colonne.date)?.consigne ?? null;
          const explication = explications.get(caseJour) ?? null;
          const note = partielle ? this.infobullePartielle(ligne.standId, colonne.colonneId) : null;
          let resultat: (readonly [number, number])[] = [];
          if (lus !== undefined && !modifiee) {
            resultat = portionsIn(segmentSpans(lus, span), span);
          } else if (effectif !== null) {
            // A typed cell draws what its save will write: the whole column.
            resultat = [[0, 1]];
          }
          return {
            clef,
            colonneId: colonne.colonneId,
            premierDuJour: colonne.rang === 0,
            // One meaning for an empty cell: closed.
            valeur: effectif === null ? '' : String(effectif),
            modifiee,
            partielle,
            fermee: effectif === null,
            sansSaisie: source === 'DEFAUT' && !modifiee,
            ajouteeParConsigne: couches.has('creneaux') && ajoutes.has(colonne.creneauId),
            ferie: feries.has(colonne.date),
            desactivee: verrouille,
            libelle: `${nom} · ${this.libelleJour(colonne.date)} ${libelleColonne(colonne)}`,
            infobulle: [explication, note].filter(Boolean).join('\n') || null,
            rendu: renderCell(
              {
                resultat,
                source,
                nominal: couchesJour ? portionsIn(windowSpans(couchesJour.nominal), span) : null,
                bande: consigne
                  ? portionsIn([[consigne.debutMinutes, consigne.finMinutes]], span)
                  : [],
                reouvertures: couchesJour
                  ? portionsIn(windowSpans(couchesJour.reopenings), span)
                  : [],
              },
              couches,
            ),
          };
        }),
      };
    });
  });

  /* -------------------- entry grid, by kind of day (ADR 0033) -------------------- */

  /** The templates and their calendar; absent until the first read, and null when the read fails. */
  protected readonly etatJourneesTypes = signal<EtatJourneesTypes | null>(null);

  /** One column per timeslot of every template the calendar actually uses. */
  protected readonly colonnesJourneesTypes = computed<ColonneJourneeType[]>(() => {
    const rapport = this.rapport();
    return rapport ? colonnesJourneesTypes(rapport, this.etatJourneesTypes()) : [];
  });

  private readonly colonnesJourneesTypesParId = computed(
    () => new Map(this.colonnesJourneesTypes().map((colonne) => [colonne.colonneId, colonne])),
  );

  /** Date → the name of the template governing it, for the panel's link. */
  private readonly dayTemplateByDate = computed(() => {
    const etat = this.etatJourneesTypes();
    const noms = new Map((etat?.journeesTypes ?? []).map((each) => [each.id, each.nom]));
    return new Map(
      (etat?.calendrier ?? []).map((affectation) => [
        affectation.date,
        noms.get(affectation.journeeTypeId) ?? '',
      ]),
    );
  });

  /** Date → the public holiday's name, for the days of the report that fall on one. */
  protected readonly holidaysByDate = computed(() => {
    const feries = new Map<string, string>();
    for (const jour of this.rapport()?.jours ?? []) {
      if (jour.ferie) {
        feries.set(jour.date, jour.ferie);
      }
    }
    return feries;
  });

  /** Template → its dates that fall on a public holiday, « 14/07 Fête nationale », for its header. */
  private readonly holidaysByJourneeType = computed(() => {
    const feries = this.holidaysByDate();
    const byJourneeType = new Map<number, string[]>();
    for (const affectation of this.etatJourneesTypes()?.calendrier ?? []) {
      const libelle = feries.get(affectation.date);
      if (libelle) {
        const liste = byJourneeType.get(affectation.journeeTypeId) ?? [];
        liste.push(`${this.libelleJour(affectation.date)} ${libelle}`);
        byJourneeType.set(affectation.journeeTypeId, liste);
      }
    }
    return byJourneeType;
  });

  /** Where a template column starts a new template, for the header's own row. */
  protected readonly journeesTypesEntetes = computed(() => {
    const feries = this.holidaysByJourneeType();
    const entetes: {
      journeeTypeId: number;
      nom: string;
      colonnes: number;
      dates: number;
      /** Its holiday dates, named, joined; empty when none. */
      feries: string;
    }[] = [];
    for (const colonne of this.colonnesJourneesTypes()) {
      const dernier = entetes.at(-1);
      if (dernier?.journeeTypeId === colonne.journeeTypeId) {
        dernier.colonnes++;
        continue;
      }
      entetes.push({
        journeeTypeId: colonne.journeeTypeId,
        nom: colonne.nomJourneeType,
        colonnes: 1,
        dates: colonne.colonnes.length + colonne.datesSansColonne.length,
        feries: (feries.get(colonne.journeeTypeId) ?? []).join(', '),
      });
    }
    return entetes;
  });

  /** What the per-template grid saves, and what it cannot say — the line above the table. */
  protected readonly resumeJourneesTypes = computed(() =>
    resumeJourneesTypes(
      this.cellules(),
      (this.rapport()?.stands ?? []).map((ligne) => ligne.standId),
      this.colonnesJourneesTypes(),
    ),
  );

  /** The same rows as {@link rowViews}, one cell per template column instead of per date. */
  protected readonly rowViewsJourneesTypes = computed<LigneView[]>(() => {
    const colonnesJT = this.colonnesJourneesTypes();
    const cellules = this.cellules();
    const reference = this.reference();
    const modifies = new Set(this.standsModifies());
    const verrouille = this.gridLocked();
    const visibles = new Set(this.lignes().map((ligne) => ligne.standId));
    const first = this.standIdsAffiches()[0];
    const aucunRendu: RenduCellule = { image: null, size: null, position: null };
    return (this.rapport()?.stands ?? []).map((ligne) => {
      const nom = ligne.nom || ligne.standId;
      return {
        standId: ligne.standId,
        nom,
        visible: visibles.has(ligne.standId),
        noLignePrecedente: !visibles.has(ligne.standId) || ligne.standId === first,
        modifiee: modifies.has(ligne.standId),
        anomalies: '',
        minutesOuvertes: 0,
        postes: 0,
        cellules: colonnesJT.map((colonne) => {
          const valeur = valeurJourneeType(cellules, ligne.standId, colonne);
          const lue = valeurJourneeType(reference, ligne.standId, colonne);
          return {
            clef: key(ligne.standId, colonne.colonneId),
            colonneId: colonne.colonneId,
            premierDuJour: colonne.rang === 0,
            valeur: this.texteJourneeType(valeur),
            modifiee: valeur !== lue,
            partielle: valeur === 'ecart',
            fermee: valeur === null,
            sansSaisie: false,
            ajouteeParConsigne: false,
            ferie: false,
            desactivee: verrouille || colonne.colonnes.length === 0,
            libelle: `${nom} · ${colonne.nomJourneeType} ${libelleColonneJourneeType(colonne)}`,
            infobulle:
              valeur === 'ecart'
                ? $localize`:@@ouvertures.journeesTypes.ecartInfobulle:Les dates de cette journée type ne disent pas la même chose. Retapez la case pour les aligner, ou réglez-les une à une dans la grille par date.`
                : null,
            rendu: aucunRendu,
          };
        }),
      };
    });
  });

  protected readonly synthese = computed(() => {
    const rapport = this.rapport();
    return rapport ? synthese(rapport) : null;
  });
  /**
   * `?stand=<id>`: the one stand a « Que faire ? » action opened the grid on,
   * matched by its exact id — a search on « S1 » would also keep S10, S11…
   * Empty means no narrowing; « Tout afficher » clears it.
   */
  protected readonly onlyStand = signal(this.route.snapshot.queryParamMap.get('stand') ?? '');

  /**
   * `?date=` in the grid: the day a « Que faire ? » action opened it on. Once
   * the grid is laid, the first cell of that day — on the stand of `?stand=`,
   * else on the first row — takes the focus, which brings it into view; the
   * address keeps the day until the page leaves the grid.
   */
  private readonly saisieDate = signal(
    this.view() === 'GRILLE' ? (this.route.snapshot.queryParamMap.get('date') ?? '') : '',
  );
  /** The requested day is focused once, on the first load: a reload after a save leaves the focus where the user put it. */
  private saisieDateFocused = false;

  /** Stand id → name, from the report: what a message names a stand by, never its raw id. */
  private readonly nomsStands = computed<ReadonlyMap<string, string>>(
    () =>
      new Map(
        (this.rapport()?.stands ?? [])
          .filter((ligne) => ligne.nom)
          .map((ligne) => [ligne.standId, ligne.nom]),
      ),
  );

  /** The names of these stands, joined; an id the report does not know stays as it is. */
  private standNamesOf(standIds: readonly string[]): string {
    return labelsOf(this.nomsStands(), standIds).join(', ');
  }

  /** The name of that stand, for the note: never a raw id on screen. */
  protected readonly onlyStandName = computed(() => {
    const standId = this.onlyStand();
    if (!standId) {
      return '';
    }
    return labelOf(this.nomsStands(), standId);
  });

  /**
   * The focused cell, explained — what the stand's layers make of its day,
   * the seats it yields, a window no timeslot covers — with the three screens
   * that own those layers. `null` outside the grid or before any focus.
   */
  protected readonly explication = computed<ExplicationCellule | null>(() => {
    const active = this.celluleActive();
    const rapport = this.rapport();
    if (!active || !rapport || this.view() !== 'GRILLE') {
      return null;
    }
    const colonne = this.colonnes().find((each) => each.colonneId === active.colonneId);
    if (!colonne) {
      return null;
    }
    const caseJour = `${active.standId}|${colonne.date}`;
    const jour = this.daysByCell().get(caseJour);
    const journee = buildJourneeStands(rapport, colonne.date, new Set([active.standId]));
    const clef = key(active.standId, active.colonneId);
    return {
      standId: active.standId,
      nom: labelOf(this.nomsStands(), active.standId),
      date: colonne.date,
      jour: rapport.jours.find((each) => each.date === colonne.date)?.jour ?? null,
      colonne: libelleColonne(colonne),
      phrase: this.explications().get(caseJour) ?? null,
      postes: jour?.postes ?? 0,
      horsGrille: (journee?.lignes[0]?.horsGrille ?? []).map(
        (fenetre) => `${fenetre.heureDebut}–${fenetre.heureFin}`,
      ),
      partielle: this.partielles().has(clef)
        ? this.infobullePartielle(active.standId, active.colonneId)
        : null,
      modifiee: this.isModified(active.standId, active.colonneId),
      journeeType: this.dayTemplateByDate().get(colonne.date) || null,
      consigne: this.consigneByDate().has(colonne.date),
    };
  });

  private readonly injector = inject(Injector);
  private readonly pageTitle = viewChild<ElementRef<HTMLElement>>('pageTitle');

  /** Clears the narrowing to one stand; the button goes with it, the focus to the heading. */
  protected showAllStands(): void {
    this.onlyStand.set('');
    afterNextRender(() => this.pageTitle()?.nativeElement.focus(), { injector: this.injector });
  }

  protected readonly lignes = computed<LigneStandOuverture[]>(() => {
    const rapport = this.rapport();
    const lignes = rapport ? filtrerStands(rapport, this.filtre(), this.recherche()) : [];
    const standId = this.onlyStand();
    return standId ? lignes.filter((ligne) => ligne.standId === standId) : lignes;
  });
  private readonly anomaliesParStand = computed(() =>
    anomaliesParStand(this.rapport()?.anomalies ?? []),
  );

  /** Any narrowing of the grid a click can undo: a day range, a filter, a search, one stand, a layer hidden. */
  protected readonly viewChanged = computed(
    () =>
      this.rangeStart() !== null ||
      this.rangeEnd() !== null ||
      this.filtre() !== 'TOUS' ||
      this.recherche().trim() !== '' ||
      this.onlyStand() !== '' ||
      this.couchesAffichees().length !== COUCHES.length,
  );

  constructor() {
    // The view is followed rather than read once: the palette's « Ouvertures
    // des stands › Comparer » navigates to this very route, and the router
    // reuses the component. Through `changeView`, so unsaved cells still ask.
    this.route.queryParamMap.pipe(takeUntilDestroyed()).subscribe((params) => {
      const view = readOpeningsView(params.get('vue'));
      if (view !== this.view()) {
        void this.changeView(view);
      }
    });
    keepViewInQueryParams(() => ({
      vue: OPENINGS_VIEW_PARAMS[this.view()],
      date: this.view() === 'GRILLE' ? this.saisieDate() || null : null,
      q: this.recherche().trim() || null,
      stand: this.onlyStand() || null,
      stands: this.view() === 'COMPARER' ? writeStandsParam(this.comparaisonStands()) : null,
      ref: this.view() === 'COMPARER' ? this.comparaisonReference() : null,
      ecarts: this.view() === 'COMPARER' && this.comparaisonEcarts() ? '1' : null,
      couches: this.view() === 'GRILLE' ? writeCouchesParam(this.couchesAffichees()) : null,
      du: this.view() === 'GRILLE' ? this.rangeStart() : null,
      au: this.view() === 'GRILLE' ? this.rangeEnd() : null,
    }));
    inject(DestroyRef).onDestroy(() => {
      if (this.filtrePending !== null) {
        clearTimeout(this.filtrePending);
      }
    });
  }

  ngOnInit(): void {
    void this.recharger();
    this.loadReferentialForComparison();
  }

  /** The filter follows the field a beat after the last keystroke: typing « Village » re-lays the grid once, not seven times. */
  protected filterStands(texte: string): void {
    this.rechercheSaisie.set(texte);
    if (this.filtrePending !== null) {
      clearTimeout(this.filtrePending);
    }
    this.filtrePending = setTimeout(() => {
      this.filtrePending = null;
      this.recherche.set(texte);
    }, 150);
  }

  /** One layer ticked or unticked in the grid's rendering. */
  protected basculerCouche(couche: Couche, visible: boolean): void {
    this.couchesAffichees.set(toggleCouche(this.couchesAffichees(), couche, visible));
  }

  protected libelleCouche(couche: Couche): string {
    switch (couche) {
      case 'stand':
        return $localize`:@@ouvertures.couches.couche.stand:Horaires du stand`;
      case 'creneaux':
        return $localize`:@@ouvertures.couches.couche.creneaux:Créneaux`;
      case 'consigne':
        return $localize`:@@ouvertures.couches.couche.consigne:Consigne`;
      case 'resultat':
        return $localize`:@@ouvertures.couches.couche.resultat:Sièges`;
    }
  }

  /** « Du » moved past « au », or the reverse: the other bound follows rather than emptying the grid. */
  protected setRangeStart(date: string | null): void {
    this.rangeStart.set(date || null);
    const end = this.rangeEnd();
    if (date && end !== null && end < date) {
      this.rangeEnd.set(date);
    }
  }

  protected setRangeEnd(date: string | null): void {
    this.rangeEnd.set(date || null);
    const start = this.rangeStart();
    if (date && start !== null && start > date) {
      this.rangeStart.set(date);
    }
  }

  /** Every narrowing undone at once: all the days, all the stands, every layer. */
  protected resetView(): void {
    this.rangeStart.set(null);
    this.rangeEnd.set(null);
    this.filtre.set('TOUS');
    this.filterStands('');
    this.recherche.set('');
    this.onlyStand.set('');
    this.couchesAffichees.set([...COUCHES]);
  }

  protected async recharger(): Promise<void> {
    void this.consignes.reload();
    this.chargement.set(true);
    try {
      const rapport = await this.standsApi.openings();
      this.rapport.set(rapport);
      this.colonnes.set(colonnes(rapport));
      this.scissionActive.set(null);
      this.celluleActive.set(null);
      const cellules = cellulesDepuis(rapport);
      this.reference.set(cellules);
      this.cellules.set(cellules);
      this.partielles.set(cellulesPartielles(rapport));
      this.segments.set(segmentsPartiels(rapport));
      this.segmentsLus.set(cellSegments(rapport));
      // The templates come along, not on demand: the toggle to the grid by
      // kind of day must not wait on a second round trip, and an edition
      // without templates simply shows no such grid.
      this.etatJourneesTypes.set(await this.journeesTypesApi.etat().catch(() => null));
    } catch (error) {
      this.crud.reportError(error);
    } finally {
      this.chargement.set(false);
    }
    this.focusRequestedDay();
  }

  /** Hands the focus to the first cell of `?date=`, once the grid is on screen. */
  private focusRequestedDay(): void {
    const date = this.saisieDate();
    if (!date || this.saisieDateFocused || this.view() !== 'GRILLE') {
      return;
    }
    const standId = this.onlyStand() || this.standIdsAffiches()[0];
    if (standId && this.focusCell(standId, date)) {
      this.saisieDateFocused = true;
    }
  }

  /** The first cell of that stand on that day takes the focus, once the grid is laid; `false` when the day has none on screen. */
  private focusCell(standId: string, date: string): boolean {
    const colonne = this.colonnesAffichees().find((each) => each.date === date);
    if (!colonne) {
      return false;
    }
    afterNextRender(
      () =>
        this.hote.nativeElement
          .querySelector<HTMLInputElement>(`[data-cellule="${key(standId, colonne.colonneId)}"]`)
          ?.focus(),
      { injector: this.injector },
    );
    return true;
  }

  /** « Voir la case » on an anomaly: its stand on its day, the day brought back into the range if it had left it. */
  protected showCell(anomalie: AnomalieOuverture): void {
    if (!anomalie.date) {
      return;
    }
    if (!this.datesAffichees().has(anomalie.date)) {
      this.rangeStart.set(null);
      this.rangeEnd.set(null);
    }
    if (!this.lignes().some((ligne) => ligne.standId === anomalie.standId)) {
      this.filtre.set('TOUS');
      this.onlyStand.set(anomalie.standId);
    }
    this.focusCell(anomalie.standId, anomalie.date);
  }

  /**
   * Leaving the entry grids for « Comparer » with unsaved cells asks first:
   * they would silently survive, invisible, until the next reload. The two
   * grids write the same cells, so moving between them asks nothing.
   */
  protected async changeView(view: OpeningsView | undefined): Promise<void> {
    // The toggle group emits `undefined` on its first render, before any
    // click: taking it for a view would forget the `?date=` being entered.
    if (!view) {
      return;
    }
    if (view === 'COMPARER' && this.standsModifies().length > 0) {
      const abandon = await this.confirm.ask({
        title: $localize`:@@ouvertures.saisie.quitterTitle:Abandonner les modifications ?`,
        message: $localize`:@@ouvertures.saisie.quitterMessage:${this.standsModifies().length}:stands: stand(s) ont des cases modifiées non enregistrées.`,
        confirmLabel: $localize`:@@ouvertures.saisie.quitterLabel:Abandonner`,
        danger: true,
      });
      if (!abandon) {
        return;
      }
      this.cellules.set(this.reference());
    }
    if (view !== 'GRILLE') {
      this.saisieDate.set('');
    }
    this.view.set(view);
    this.loadReferentialForComparison();
  }

  /**
   * « Comparer » reads the stands' rules and their game categories, and its
   * copy opens the bulk edit, which needs the timeslots and the emplacements:
   * loaded once, the first time the view is shown, and never for the others.
   */
  private loadReferentialForComparison(): void {
    if (this.view() !== 'COMPARER' || this.referentielCharge) {
      return;
    }
    this.referentielCharge = true;
    void this.store
      .reload(['stands', 'typologies', 'creneaux', 'emplacements'])
      .catch((error: unknown) => this.crud.reportError(error));
  }

  /** A partial cell says what it holds, and that saving it as shown keeps it. */
  protected infobullePartielle(standId: string, colonneId: string): string {
    const detail = (this.segments().get(key(standId, colonneId)) ?? [])
      .map(
        (segment) =>
          `${this.heure(segment.heureDebut)}-${this.heure(segment.heureFin)} : ${segment.effectif}`,
      )
      .join(', ');
    return $localize`:@@ouvertures.saisie.partielle:Cette case porte plusieurs valeurs (${detail}:segments:). Enregistrée telle quelle, elle les garde ; modifiée, la valeur tapée s'applique à tout le créneau.`;
  }

  protected infobulleAligner(): string {
    return $localize`:@@ouvertures.saisie.alignerTooltip:Étend chaque case à plusieurs valeurs à son créneau entier, à sa valeur la plus haute. Enregistrez ou annulez d'abord vos modifications.`;
  }

  private hasPartialCells(standId: string): boolean {
    const prefixe = standId + '#';
    return Array.from(this.partielles()).some((clef) => clef.startsWith(prefixe));
  }

  /** What the field shows: the headcount, or nothing for closed — one meaning for an empty cell. */
  protected valeur(standId: string, colonneId: string): string {
    const colonneJT = this.colonnesJourneesTypesParId().get(colonneId);
    if (colonneJT) {
      return this.texteJourneeType(valeurJourneeType(this.cellules(), standId, colonneJT));
    }
    const effectif = this.cellules().get(standId)?.get(colonneId) ?? null;
    return effectif === null ? '' : String(effectif);
  }

  protected isModified(standId: string, colonneId: string): boolean {
    return (
      (this.cellules().get(standId)?.get(colonneId) ?? null) !==
      (this.reference().get(standId)?.get(colonneId) ?? null)
    );
  }

  protected isPartial(standId: string, colonneId: string): boolean {
    return isPartialCell(this.partielles(), { standId, colonneId });
  }

  /** Said on the column, not the cell: the organiser types what the stand needs, the seats are derived. */
  protected readonly relaisRepasTooltip = $localize`:@@ouvertures.saisie.relaisRepas:Relais repas : les sièges générés valent la moitié de l'effectif saisi, arrondie au supérieur`;

  protected libelleColonne(colonne: ColonneGrille): string {
    return libelleColonne(colonne);
  }

  protected libelleColonneJourneeType(colonne: ColonneJourneeType): string {
    return libelleColonneJourneeType(colonne);
  }

  /**
   * A keystroke in a cell: digits become the headcount; an emptied field, a
   * dash or a zero closes the stand — an empty cell is a closed one, there is
   * no third meaning. Anything else is left as typed, and put back on blur.
   */
  protected saisir(standId: string, colonneId: string, text: string): void {
    if (this.gridLocked()) {
      return;
    }
    const lu = readCell(text);
    if (lu === undefined) {
      return;
    }
    const colonneJT = this.colonnesJourneesTypesParId().get(colonneId);
    if (colonneJT) {
      // One keystroke, every date the template governs: that is the whole
      // point of the grid by kind of day.
      this.cellules.update((cellules) =>
        ecrireColonneJourneeType(cellules, standId, colonneJT, lu),
      );
      return;
    }
    this.cellules.update((cellules) => ecrireCellule(cellules, { standId, colonneId }, lu));
  }

  /** `2`, empty or `≠` — what a template cell shows, and what a blur puts back. */
  private texteJourneeType(valeur: number | null | 'ecart'): string {
    if (valeur === 'ecart') {
      return ECART;
    }
    return valeur === null ? '' : String(valeur);
  }

  /**
   * What is left in the field once it loses the focus: the model's own value.
   * A keystroke that is not a headcount (`5x`) is ignored by {@link saisir},
   * and without this the field would keep showing it while the grid holds — and
   * would save — the old number.
   */
  protected reafficher(event: Event, standId: string, colonneId: string): void {
    (event.target as HTMLInputElement).value = this.valeur(standId, colonneId);
  }

  protected focaliser(standId: string, colonneId: string): void {
    this.celluleActive.set({ standId, colonneId });
  }

  /* ------------------ one listener per event on the body, not per cell ------------------ */

  /**
   * The cell an event comes from, read off its `data-cellule` — five
   * listeners on the body instead of five per cell, which is what made
   * rendering a row of sixty cells expensive.
   */
  private addressOf(event: Event): AdresseCellule | null {
    const target = event.target as HTMLElement | null;
    const clef = target?.dataset?.['cellule'];
    if (!clef) {
      return null;
    }
    const separateur = clef.indexOf('#');
    return { standId: clef.slice(0, separateur), colonneId: clef.slice(separateur + 1) };
  }

  protected onInput(event: Event): void {
    const address = this.addressOf(event);
    if (address) {
      this.saisir(address.standId, address.colonneId, (event.target as HTMLInputElement).value);
    }
  }

  protected onFocus(event: FocusEvent): void {
    const address = this.addressOf(event);
    if (address) {
      this.focaliser(address.standId, address.colonneId);
    }
  }

  protected onBlur(event: FocusEvent): void {
    const address = this.addressOf(event);
    if (address) {
      this.reafficher(event, address.standId, address.colonneId);
    }
  }

  protected onKeydownBody(event: KeyboardEvent): void {
    const address = this.addressOf(event);
    if (address) {
      this.onKeydown(event, address.standId, address.colonneId);
    }
  }

  protected onPasteBody(event: ClipboardEvent): void {
    const address = this.addressOf(event);
    if (address) {
      this.onPaste(event, address.standId, address.colonneId);
    }
  }

  /** The column ids of the grid on screen, in display order: what the keyboard and the moves walk over. */
  private readonly colonneIdsAffiches = computed(() =>
    this.view() === 'JOURNEES_TYPES'
      ? this.colonnesJourneesTypes().map((colonne) => colonne.colonneId)
      : this.colonnesAffichees().map((colonne) => colonne.colonneId),
  );

  /**
   * Arrows, Enter, Home and End move between cells the way a spreadsheet
   * does. Left and right only when the caret cannot move inside the field
   * itself, so editing a two-digit value stays possible.
   */
  protected onKeydown(event: KeyboardEvent, standId: string, colonneId: string): void {
    if (this.mouvementClavier(event, standId, colonneId)) {
      return;
    }
    if (event.ctrlKey || event.metaKey || event.altKey) {
      return;
    }
    const champ = event.target as HTMLInputElement;
    if (event.key === 'ArrowLeft' && (champ.selectionStart ?? 0) > 0) {
      return;
    }
    if (event.key === 'ArrowRight' && (champ.selectionEnd ?? 0) < champ.value.length) {
      return;
    }
    const target = deplacement(
      event.key,
      { standId, colonneId },
      this.standIdsAffiches(),
      this.colonneIdsAffiches().map((id) => ({ colonneId: id })),
    );
    if (target === null) {
      return;
    }
    event.preventDefault();
    this.hote.nativeElement
      .querySelector<HTMLInputElement>(`[data-cellule="${key(target.standId, target.colonneId)}"]`)
      ?.focus();
  }

  /** A block copied from a spreadsheet lands from the cell it is pasted in; a single value pastes as typed. */
  protected onPaste(event: ClipboardEvent, standId: string, colonneId: string): void {
    const text = event.clipboardData?.getData('text') ?? '';
    if (this.gridLocked() || !/[\t\n]/.test(text) || this.view() !== 'GRILLE') {
      return;
    }
    event.preventDefault();
    this.cellules.update((cellules) =>
      collerBloc(
        cellules,
        text,
        { standId, colonneId },
        this.standIdsAffiches(),
        this.colonnesAffichees(),
      ),
    );
  }

  /* ---------------------------- repetitive entry ----------------------------- */

  /**
   * The two moves of repetitive entry from the keyboard: Ctrl+D takes the row
   * above, Ctrl+Maj+Bas pushes this cell down its column. Bound to the grid,
   * consuming only those two combinations; everything else travels up to the
   * application's global listener. `true` once handled, so the caller stops.
   */
  private mouvementClavier(event: KeyboardEvent, standId: string, colonneId: string): boolean {
    // AltGr is reported as Ctrl+Alt on Windows and Linux, so a guard on Ctrl
    // alone would let « AltGr+D » rewrite a whole row while the organiser was
    // only typing a character. Same guard as the global listener.
    if (!(event.ctrlKey || event.metaKey) || event.altKey) {
      return false;
    }
    if (!event.shiftKey && (event.key === 'd' || event.key === 'D')) {
      event.preventDefault();
      this.copyLignePrecedente(standId);
      return true;
    }
    if (event.shiftKey && event.key === 'ArrowDown') {
      event.preventDefault();
      this.applyColonne(colonneId);
      return true;
    }
    return false;
  }

  /** How the grid on screen reads and writes one cell: by date, or by template. */
  private readonly accesGrille = computed<AccesGrille<Cellules, number | null>>(() =>
    this.view() === 'JOURNEES_TYPES'
      ? accesGrilleJourneesTypes(this.colonnesJourneesTypesParId())
      : accesGrilleDates,
  );

  /** The row above this one, on screen, copied onto it — the stand that opens like its neighbour. */
  protected copyLignePrecedente(standId: string): void {
    if (this.gridLocked()) {
      return;
    }
    if (!hasLignePrecedente(standId, this.standIdsAffiches())) {
      this.notifications.notify({
        title: $localize`:@@ouvertures.saisie.sansLignePrecedente:La première ligne affichée n'a pas de ligne au-dessus d'elle.`,
        variant: 'warning',
        timeout: 6000,
      });
      return;
    }
    this.applyMouvement(
      copyLignePrecedente(
        this.cellules(),
        standId,
        this.standIdsAffiches(),
        this.colonneIdsAffiches(),
        this.accesGrille(),
      ),
      $localize`:@@ouvertures.saisie.dupliqueVide:Rien à reprendre : cette ligne dit déjà ce que dit celle du dessus.`,
    );
  }

  /**
   * One value posed on every displayed row of a column: the active cell's when
   * the focus is in that column, else the first row's. A cell whose dates
   * disagree says nothing to propagate, and the move stops there rather than
   * choosing one of them.
   */
  protected applyColonne(colonneId: string): void {
    if (this.gridLocked()) {
      return;
    }
    const lignes = this.standIdsAffiches();
    const active = this.celluleActive();
    const source = ligneSourceColonne(
      colonneId,
      active === null ? null : { ligneId: active.standId, colonneId: active.colonneId },
      lignes,
    );
    if (source === undefined) {
      return;
    }
    const acces = this.accesGrille();
    const valeur = acces.read(this.cellules(), source, colonneId);
    if (valeur === undefined) {
      this.notifications.notify({
        title: $localize`:@@ouvertures.saisie.colonneSansValeur:Cette case ne dit rien à propager : réglez-la d'abord, date par date s'il le faut.`,
        variant: 'warning',
        timeout: 6000,
      });
      return;
    }
    this.applyMouvement(
      applyColonne(this.cellules(), colonneId, valeur, lignes, acces),
      $localize`:@@ouvertures.saisie.colonneVide:Rien à appliquer : toutes les lignes affichées disent déjà cette valeur.`,
    );
  }

  /**
   * Takes a move and says what it did. A move that changed nothing leaves no
   * mark on screen, and silence would read as a failure — or worse, as a
   * success.
   */
  private applyMouvement(resultat: RecopieGrille<Cellules>, messageVide: string): void {
    this.cellules.set(resultat.cellules);
    if (resultat.changees === 0) {
      this.notifications.notify({ title: messageVide, variant: 'warning', timeout: 6000 });
    }
  }

  /** The day's cells, for every displayed stand, copied onto every other displayed day. */
  protected recopierJour(date: string): void {
    this.appliquerRecopie((cellules) =>
      recopierJour(cellules, date, this.standIdsAffiches(), this.colonnesAffichees()),
    );
  }

  /** One stand's day — the focused one when it is on that row, else its first day with a headcount — copied onto its other displayed days. */
  protected recopierLigne(standId: string): void {
    const active = this.celluleActive();
    const date =
      active?.standId === standId
        ? (this.colonnes().find((colonne) => colonne.colonneId === active.colonneId)?.date ?? null)
        : jourDeReference(this.cellules(), standId, this.colonnesAffichees());
    if (date !== null) {
      this.appliquerRecopie((cellules) =>
        recopierJour(cellules, date, [standId], this.colonnesAffichees()),
      );
    }
  }

  /**
   * Applies a day copy and says how many cells it changed. A day whose timeslots
   * were sliced differently matches none of the target columns, and the copy
   * then does nothing at all — silence would read as success.
   */
  private appliquerRecopie(recopie: (cellules: Cellules) => Cellules): void {
    if (this.gridLocked()) {
      return;
    }
    const before = this.cellules();
    const after = recopie(before);
    this.applyMouvement(
      { cellules: after, changees: countCopied(before, after, this.colonnesAffichees()) },
      $localize`:@@ouvertures.saisie.recopieVide:Aucune case recopiée : les créneaux des autres jours n'ont pas les mêmes horaires.`,
    );
  }

  /**
   * Cuts a column at `heure`, in the screen only: two columns where there
   * was one, every stand's value carried onto both, nothing modified until a
   * cell under them is typed. Saved, such a cell writes a window at the
   * column's bounds, and the server reports the boundary from then on. An
   * hour on the column's edge, or outside it, cuts nothing and says so.
   */
  protected scinder(colonne: ColonneGrille, heure: string): void {
    this.scissionActive.set(null);
    if (this.gridLocked()) {
      return;
    }
    const nouvelles = scinder(this.colonnes(), colonne.colonneId, heure);
    if (nouvelles === null) {
      this.notifications.notify({
        title: $localize`:@@ouvertures.saisie.scissionInvalide:L'heure de coupe doit tomber strictement entre ${libelleColonne(colonne)}:colonne:.`,
        variant: 'warning',
        timeout: 6000,
      });
      return;
    }
    const ids = [
      colonneId(colonne.creneauId, colonne.heureDebut, heure),
      colonneId(colonne.creneauId, heure, colonne.heureFin),
    ];
    const ancienne = colonne.colonneId;
    this.colonnes.set(nouvelles);
    this.cellules.update((cellules) => propagerScission(cellules, ancienne, ids));
    this.reference.update((cellules) => propagerScission(cellules, ancienne, ids));
    this.segments.update((segments) => propagerClefs(segments, ancienne, ids));
    this.segmentsLus.update((segments) => propagerClefs(segments, ancienne, ids));
    this.partielles.update((partielles) => this.propagerEnsemble(partielles, ancienne, ids));
    this.celluleActive.set(null);
  }

  private propagerEnsemble(
    ensemble: ReadonlySet<string>,
    ancienne: string,
    nouvelles: readonly string[],
  ): Set<string> {
    const marques = new Map(Array.from(ensemble, (clef) => [clef, true] as const));
    return new Set(propagerClefs(marques, ancienne, nouvelles).keys());
  }

  /** The columns of one day, cuts included — what the day header spans. */
  protected columnsOfDay(date: string): ColonneGrille[] {
    return this.colonnes().filter((colonne) => colonne.date === date);
  }

  protected annuler(): void {
    this.cellules.set(this.reference());
  }

  /**
   * Sends the modified stands, each with its whole schedule. A partial cell
   * saved as shown keeps its stretches; one that was retyped is named first,
   * because the value typed will then cover the whole timeslot.
   */
  protected async enregistrer(): Promise<void> {
    const modifies = this.standsModifies();
    if (modifies.length === 0 || this.enregistrement() || this.gridLocked()) {
      return;
    }
    const aplatis = modifies.filter((standId) =>
      Array.from(this.partielles()).some((clef) => {
        const [stand, id] = clef.split('#');
        return stand === standId && this.isModified(standId, id);
      }),
    );
    if (aplatis.length > 0) {
      const confirme = await this.confirm.ask({
        title: $localize`:@@ouvertures.saisie.aplatirTitle:Remplacer des cases à plusieurs valeurs ?`,
        message: $localize`:@@ouvertures.saisie.aplatirMessage:${this.standNamesOf(aplatis)}:stands: : des cases qui portaient plusieurs valeurs ont été modifiées ; la valeur tapée s'appliquera à tout le créneau.`,
        confirmLabel: $localize`:@@ouvertures.saisie.aplatirLabel:Enregistrer`,
      });
      if (!confirme) {
        return;
      }
    }
    await this.send(modifies, false);
  }

  /**
   * Every stand the server reported partial, sent back with `aplatir`: the
   * one gesture that extends a partial cell to its whole timeslot. The
   * confirmation prices it first — stands, cells, hours of opening added —
   * because on the reference event that is 12 stands and 74 hours. Refused
   * while cells are modified: the reload after the save would drop them.
   */
  protected async alignerPartiels(): Promise<void> {
    const partiels = this.standsPartiels();
    if (
      partiels.length === 0 ||
      this.standsModifies().length > 0 ||
      this.enregistrement() ||
      this.gridLocked()
    ) {
      return;
    }
    const cout = aplatissement(this.segments(), this.colonnes());
    const heures = Math.round(cout.minutes / 6) / 10;
    const confirme = await this.confirm.ask({
      title: $localize`:@@ouvertures.saisie.alignerTitle:Aligner les fenêtres sur les créneaux ?`,
      message: $localize`:@@ouvertures.saisie.alignerMessage:${partiels.length}:stands: stand(s), ${cout.cases}:cases: case(s) : chaque case sera étendue à son créneau entier, à sa valeur la plus haute, soit ${heures}:heures: h d'ouverture en plus (${this.standNamesOf(partiels)}:liste:).`,
      confirmLabel: $localize`:@@ouvertures.saisie.alignerLabel:Aligner`,
      danger: true,
    });
    if (!confirme) {
      return;
    }
    await this.send(partiels, true);
  }

  /** The save itself: the named stands, each with all its cells, then a reload and a line on what was written. */
  private async send(standIds: readonly string[], aplatir: boolean): Promise<void> {
    this.enregistrement.set(true);
    try {
      const rapport = await this.standsApi.saveOpeningsGrid(
        saisie(this.cellules(), standIds, this.colonnes(), {
          modifieLeParStand: this.modifieLeParStand(),
          aplatir,
        }),
      );
      const regles = rapport.stands.reduce((total, ligne) => total + ligne.regles, 0);
      const exceptions = rapport.stands.reduce((total, ligne) => total + ligne.exceptions, 0);
      // A stand whose rules would not reproduce its own segments stays fully
      // dated, and the server says so per stand — worth a line rather than a
      // number the reader cannot explain.
      const nonCompactes = rapport.stands
        .filter((ligne) => !ligne.compacte)
        .map((ligne) => ligne.standId);
      this.notifications.notify({
        title: $localize`:@@ouvertures.saisie.doneTitle:Horaires enregistrés`,
        message:
          $localize`:@@ouvertures.saisie.doneMessage:${rapport.stands.length}:stands: stand(s) réécrit(s) en ${regles}:regles: règle(s) et ${exceptions}:exceptions: exception(s) datée(s).` +
          (nonCompactes.length > 0
            ? ' ' +
              $localize`:@@ouvertures.saisie.doneNonCompactes:${this.standNamesOf(nonCompactes)}:stands: sont restés en fenêtres datées : leur motif ne se répète pas.`
            : ''),
        variant: 'success',
      });
      await this.recharger();
    } catch (error) {
      this.crud.reportError(error);
    } finally {
      this.enregistrement.set(false);
    }
  }

  protected readonly iconeAnomalie = iconeAnomalie;
  protected readonly isInformationalAnomaly = isInformationalAnomaly;

  /**
   * Where an anomaly is corrected: a window outside every timeslot is as much
   * the grid's doing as the stand's, so it names both; anything else is the
   * way the stand's own hours are written, on its fiche.
   */
  protected anomalyFixedInGrid(type: TypeAnomalieOuverture): boolean {
    return type === 'FENETRE_SANS_EFFET';
  }

  protected duree(minutes: number): string {
    return dureeCourte(minutes, {
      heures: $localize`:@@ouvertures.duree.heures:h`,
      minutes: $localize`:@@ouvertures.duree.minutes:min`,
    });
  }

  /** `2026-07-08` → `08/07`, short enough for a dozen columns. */
  protected libelleJour(date: string): string {
    const [, mois, jour] = date.split('-');
    return `${jour}/${mois}`;
  }

  /** `09:00:00` → `09:00`. */
  protected heure(valeur: string): string {
    return valeur.length > 5 ? valeur.slice(0, 5) : valeur;
  }
}
