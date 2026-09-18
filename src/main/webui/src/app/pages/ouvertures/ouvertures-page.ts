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
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatButtonToggleModule } from '@angular/material/button-toggle';
import { MatCardModule } from '@angular/material/card';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { MatTooltipModule } from '@angular/material/tooltip';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { StandsApi } from '../../core/api/stands-api';
import { ConsignesStore } from '../../core/consignes.store';
import { bandeLabel } from '../consignes/consignes';
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
import { SolverJobService } from '../../core/solver-job.service';
import { dayNavigation } from '../../core/day-navigation';
import { keepViewInQueryParams } from '../../core/view-query-params';
import { ConfirmService } from '../../shared/confirm-dialog';
import { JourneeStandsVue, buildJourneeStands, pasHoraire } from './journee-stands';
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
  LigneStandOuverture,
  RapportOuvertures,
  SegmentCellule,
} from '../../core/models';
import {
  anomaliesParStand,
  classeCellule,
  dureeCourte,
  filtrerStands,
  FiltreOuvertures,
  iconeAnomalie,
  largeurPourcent,
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
  segmentsPartiels,
  standsModifies,
} from './grille-horaires';

/**
 * Reading grid, entry grid by date, entry grid by kind of day, or one day laid
 * on time (ADR 0032 and 0033). The two entry grids write the same cells: the
 * one by kind of day says a vacation once for every date its template governs.
 */
export type VueOuvertures = 'CONSULTER' | 'SAISIR' | 'JOURNEES_TYPES' | 'JOURNEE';

/** The `vue` query param of each view; the reading grid, the default, writes none. */
const PARAM_VUE: Record<VueOuvertures, string | null> = {
  CONSULTER: null,
  SAISIR: 'saisie',
  JOURNEES_TYPES: 'journees-types',
  JOURNEE: 'journee',
};

function lireVue(param: string | null): VueOuvertures {
  if (param === 'saisie') {
    return 'SAISIR';
  }
  if (param === 'journees-types') {
    return 'JOURNEES_TYPES';
  }
  return param === 'journee' ? 'JOURNEE' : 'CONSULTER';
}

/** What a cell whose dates disagree shows: the template view never flattens one. */
const ECART = '≠';

/** What a closed cell shows, and one of the things typed to close one (`readCell`). */
const FERME = '-';

/** One cell as the template binds it: text and flags computed once, no call per binding. */
interface CelluleView {
  clef: string;
  colonneId: string;
  premierDuJour: boolean;
  valeur: string;
  modifiee: boolean;
  partielle: boolean;
  fermee: boolean;
  desactivee: boolean;
  libelle: string;
  infobulle: string | null;
}

interface LigneView {
  standId: string;
  nom: string;
  /** Whether the filter shows the row; a hidden row keeps its cells, and its typed values. */
  visible: boolean;
  /** Nothing to take from above: the row is the first one displayed, or is not displayed at all. */
  noLignePrecedente: boolean;
  modifiee: boolean;
  cellules: CelluleView[];
}

/**
 * Read-only stand × jour grid of the opening schedule actually in force, so an
 * administrator can validate it visually before spending minutes on a solve.
 *
 * <p>Everything shown comes from `GET /api/ouvertures-stands`, which builds it
 * server-side from the very postes `PlanningService.construirePostes` would hand
 * the solver — recurring horaires expanded, dated exceptions applied, windows
 * clamped to each créneau. Deliberately not recomputed here: a validation screen
 * that offers a second interpretation of the data validates nothing.
 *
 * <p>The same grid is also where the schedule is typed (« Saisir »): one
 * integer per stand and créneau, the way the organiser's own spreadsheet holds
 * it, with the moves a spreadsheet user expects — arrows, Enter, a pasted
 * block, a day copied onto the others. Nothing is written until « Enregistrer »,
 * and only the stands whose cells changed are sent, each with its whole
 * schedule (`PUT /api/ouvertures-stands/grille`).</p>
 */
@Component({
  selector: 'app-ouvertures-page',
  imports: [
    FormsModule,
    MatCardModule,
    MatButtonModule,
    MatButtonToggleModule,
    MatFormFieldModule,
    MatInputModule,
    MatIconModule,
    MatSelectModule,
    MatTooltipModule,
    RouterLink,
  ],
  templateUrl: './ouvertures-page.html',
  styleUrls: ['../../../styles/ouvertures.css', '../../../styles/saisie-repetitive.css'],
  // Global by design (AGENTS.md): loaded with the route, unscoped like the partial it was.
  encapsulation: ViewEncapsulation.None,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class OuverturesPage {
  private readonly standsApi = inject(StandsApi);
  private readonly journeesTypesApi = inject(JourneesTypesApi);
  /** The consignes (issue #4): a day under one is marked, and its closed cells are not anomalies. */
  private readonly consignes = inject(ConsignesStore);
  /** Date → the badge's wording, for the days under a consigne. */
  protected readonly consigneParDate = computed(() => {
    const badges = new Map<string, { libelle: string; motif: string }>();
    for (const [date, consigne] of this.consignes.parDate()) {
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

  protected readonly rapport = signal<RapportOuvertures | null>(null);

  /** The stamps the displayed grid was built from, sent back as preconditions (issue #362). */
  private readonly modifieLeParStand = computed(
    () => new Map((this.rapport()?.stands ?? []).map((ligne) => [ligne.standId, ligne.modifieLe])),
  );
  protected readonly chargement = signal(true);
  protected readonly filtre = signal<FiltreOuvertures>('TOUS');
  protected readonly recherche = signal('');
  /** What the filter field holds, before the grid follows it: sixty-five rows of sixty cells are not re-laid on every keystroke. */
  protected readonly rechercheSaisie = signal('');
  private filtrePending: ReturnType<typeof setTimeout> | null = null;
  protected readonly view = signal<VueOuvertures>(
    lireVue(this.route.snapshot.queryParamMap.get('vue')),
  );

  /* ------------------------------- day view ------------------------------- */

  /** The day on screen in « Journée », keyed by its date; the URL's `date` names it. */
  private readonly navigationJour = dayNavigation(
    computed(() => this.rapport()?.jours ?? []),
    (jour) => jour.date,
    { initial: this.route.snapshot.queryParamMap.get('date') },
  );
  protected readonly jourCourant = computed(() => this.navigationJour.current());
  protected readonly isPremierJour = this.navigationJour.isFirst;
  protected readonly isDernierJour = this.navigationJour.isLast;
  /** The day laid on time, narrowed by the same filter and search as the grid. */
  protected readonly journee = computed<JourneeStandsVue | null>(() => {
    const rapport = this.rapport();
    const jour = this.jourCourant();
    if (!rapport || !jour) {
      return null;
    }
    return buildJourneeStands(
      rapport,
      jour.date,
      new Set(this.lignes().map((ligne) => ligne.standId)),
    );
  });
  protected readonly pasHoraire = computed(() => {
    const vue = this.journee();
    return vue ? pasHoraire(vue) : null;
  });

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
  /** The last cell focused: where a paste lands, and which day a row copy takes. */
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

  /**
   * The rows as the template binds them: every cell's text, flags and
   * labels computed once per change, so the template reads properties
   * instead of calling a dozen functions per cell — four thousand cells make
   * that the difference between a filter that follows the keystroke and one
   * that lags behind it.
   */
  protected readonly rowViews = computed<LigneView[]>(() => {
    const colonnes = this.colonnes();
    const cellules = this.cellules();
    const reference = this.reference();
    const partielles = this.partielles();
    const modifies = new Set(this.standsModifies());
    const verrouille = this.editingLocked();
    // Every stand is rendered once and the filter only hides rows: rebuilding
    // twenty-eight rows of sixty cells when the field empties is what lagged.
    const visibles = new Set(this.lignes().map((ligne) => ligne.standId));
    const first = this.standIdsAffiches()[0];
    return (this.rapport()?.stands ?? []).map((ligne) => {
      const nom = ligne.nom || ligne.standId;
      const typees = cellules.get(ligne.standId);
      const lues = reference.get(ligne.standId);
      return {
        standId: ligne.standId,
        nom,
        visible: visibles.has(ligne.standId),
        noLignePrecedente: !visibles.has(ligne.standId) || ligne.standId === first,
        modifiee: modifies.has(ligne.standId),
        cellules: colonnes.map((colonne) => {
          const clef = key(ligne.standId, colonne.colonneId);
          const partielle = partielles.has(clef);
          const effectif = typees?.get(colonne.colonneId) ?? null;
          const valeur = effectif === null ? FERME : String(effectif);
          return {
            clef,
            colonneId: colonne.colonneId,
            premierDuJour: colonne.rang === 0,
            valeur,
            modifiee: effectif !== (lues?.get(colonne.colonneId) ?? null),
            partielle,
            fermee: effectif === null,
            desactivee: verrouille,
            libelle: `${nom} · ${this.libelleJour(colonne.date)} ${libelleColonne(colonne)}`,
            infobulle: partielle ? this.infobullePartielle(ligne.standId, colonne.colonneId) : null,
          };
        }),
      };
    });
  });

  /* -------------------- entry grid, by kind of day (ADR 0033) -------------------- */

  /** The templates and their calendar; absent until the first read, and null when the read fails. */
  protected readonly etatJourneesTypes = signal<EtatJourneesTypes | null>(null);

  /** One column per vacation of every template the calendar actually uses. */
  protected readonly colonnesJourneesTypes = computed<ColonneJourneeType[]>(() => {
    const rapport = this.rapport();
    return rapport ? colonnesJourneesTypes(rapport, this.etatJourneesTypes()) : [];
  });

  private readonly colonnesJourneesTypesParId = computed(
    () => new Map(this.colonnesJourneesTypes().map((colonne) => [colonne.colonneId, colonne])),
  );

  /** Where a template column starts a new template, for the header's own row. */
  protected readonly journeesTypesEntetes = computed(() => {
    const entetes: { journeeTypeId: number; nom: string; colonnes: number; dates: number }[] = [];
    for (const colonne of this.colonnesJourneesTypes()) {
      const dernier = entetes[entetes.length - 1];
      if (dernier && dernier.journeeTypeId === colonne.journeeTypeId) {
        dernier.colonnes++;
        continue;
      }
      entetes.push({
        journeeTypeId: colonne.journeeTypeId,
        nom: colonne.nomJourneeType,
        colonnes: 1,
        dates: colonne.colonnes.length + colonne.datesSansColonne.length,
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
    const verrouille = this.editingLocked();
    const visibles = new Set(this.lignes().map((ligne) => ligne.standId));
    const first = this.standIdsAffiches()[0];
    return (this.rapport()?.stands ?? []).map((ligne) => {
      const nom = ligne.nom || ligne.standId;
      return {
        standId: ligne.standId,
        nom,
        visible: visibles.has(ligne.standId),
        noLignePrecedente: !visibles.has(ligne.standId) || ligne.standId === first,
        modifiee: modifies.has(ligne.standId),
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
            desactivee: verrouille || colonne.colonnes.length === 0,
            libelle: `${nom} · ${colonne.nomJourneeType} ${libelleColonneJourneeType(colonne)}`,
            infobulle:
              valeur === 'ecart'
                ? $localize`:@@ouvertures.journeesTypes.ecartInfobulle:Les dates de cette journée type ne disent pas la même chose. Retapez la case pour les aligner, ou réglez-les une à une dans la grille par date.`
                : null,
          };
        }),
      };
    });
  });

  protected readonly synthese = computed(() => {
    const rapport = this.rapport();
    return rapport ? synthese(rapport) : null;
  });
  protected readonly lignes = computed<LigneStandOuverture[]>(() => {
    const rapport = this.rapport();
    return rapport ? filtrerStands(rapport, this.filtre(), this.recherche()) : [];
  });
  private readonly anomaliesParStand = computed(() =>
    anomaliesParStand(this.rapport()?.anomalies ?? []),
  );

  constructor() {
    keepViewInQueryParams(() => ({
      vue: PARAM_VUE[this.view()],
      date: this.view() === 'JOURNEE' ? this.navigationJour.queryParam() : null,
    }));
    inject(DestroyRef).onDestroy(() => {
      if (this.filtrePending !== null) {
        clearTimeout(this.filtrePending);
      }
    });
    void this.recharger();
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
      // The templates come along, not on demand: the toggle to the grid by
      // kind of day must not wait on a second round trip, and an edition
      // without templates simply shows no such grid.
      this.etatJourneesTypes.set(await this.journeesTypesApi.etat().catch(() => null));
    } catch (error) {
      this.crud.reportError(error);
    } finally {
      this.chargement.set(false);
    }
  }

  /** « Voir la journée » from a day header of the grid: the same day, laid on time. */
  protected voirJournee(date: string): void {
    this.navigationJour.select(date);
    this.view.set('JOURNEE');
  }

  protected selectJour(date: string): void {
    this.navigationJour.select(date);
  }

  protected decalerJour(delta: number): void {
    this.navigationJour.step(delta);
  }

  /** Leaving the entry view with unsaved cells asks first: they would silently survive, invisible, until the next reload. */
  protected async changeView(view: VueOuvertures): Promise<void> {
    if (view !== 'SAISIR' && this.standsModifies().length > 0) {
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
    this.view.set(view);
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

  /**
   * What the field shows: the headcount, or « - » for closed — a closed cell
   * is a statement, and it reads as one. An inert cell shows nothing.
   */
  protected valeur(standId: string, colonneId: string): string {
    const colonneJT = this.colonnesJourneesTypesParId().get(colonneId);
    if (colonneJT) {
      return this.texteJourneeType(valeurJourneeType(this.cellules(), standId, colonneJT));
    }
    const effectif = this.cellules().get(standId)?.get(colonneId) ?? null;
    return effectif === null ? FERME : String(effectif);
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
   * A keystroke in a cell: digits become the headcount, a dash or a zero
   * closes the stand. An emptied field says nothing — the cell keeps its
   * value, which the placeholder keeps showing, the way the fiche reads an
   * empty effectif as « celui du stand ». Anything else is left as typed.
   */
  protected saisir(standId: string, colonneId: string, text: string): void {
    if (text.trim() === '') {
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

  /** `2`, `-` or `≠` — what a template cell shows, and what a blur puts back. */
  private texteJourneeType(valeur: number | null | 'ecart'): string {
    if (valeur === 'ecart') {
      return ECART;
    }
    return valeur === null ? FERME : String(valeur);
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
      this.colonnes(),
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
    if (!/[\t\n]/.test(text)) {
      return;
    }
    event.preventDefault();
    this.cellules.update((cellules) =>
      collerBloc(cellules, text, { standId, colonneId }, this.standIdsAffiches(), this.colonnes()),
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

  /** The columns of the grid on screen, in display order. */
  private readonly colonneIdsAffiches = computed(() =>
    this.view() === 'JOURNEES_TYPES'
      ? this.colonnesJourneesTypes().map((colonne) => colonne.colonneId)
      : this.colonnes().map((colonne) => colonne.colonneId),
  );

  /** The row above this one, on screen, copied onto it — the stand that opens like its neighbour. */
  protected copyLignePrecedente(standId: string): void {
    if (this.editingLocked()) {
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
    if (this.editingLocked()) {
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

  /** The day's cells, for every displayed stand, copied onto every other day. */
  protected recopierJour(date: string): void {
    this.appliquerRecopie((cellules) =>
      recopierJour(cellules, date, this.standIdsAffiches(), this.colonnes()),
    );
  }

  /** One stand's day — the focused one when it is on that row, else its first day with a headcount — copied onto its other days. */
  protected recopierLigne(standId: string): void {
    const active = this.celluleActive();
    const date =
      active?.standId === standId
        ? (this.colonnes().find((colonne) => colonne.colonneId === active.colonneId)?.date ?? null)
        : jourDeReference(this.cellules(), standId, this.colonnes());
    if (date !== null) {
      this.appliquerRecopie((cellules) => recopierJour(cellules, date, [standId], this.colonnes()));
    }
  }

  /**
   * Applies a day copy and says how many cells it changed. A day whose créneaux
   * were sliced differently matches none of the target columns, and the copy
   * then does nothing at all — silence would read as success.
   */
  private appliquerRecopie(recopie: (cellules: Cellules) => Cellules): void {
    const before = this.cellules();
    const after = recopie(before);
    this.applyMouvement(
      { cellules: after, changees: countCopied(before, after, this.colonnes()) },
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
   * because the value typed will then cover the whole créneau.
   */
  protected async enregistrer(): Promise<void> {
    const modifies = this.standsModifies();
    if (modifies.length === 0 || this.enregistrement()) {
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
        message: $localize`:@@ouvertures.saisie.aplatirMessage:${aplatis.join(', ')}:stands: : des cases qui portaient plusieurs valeurs ont été modifiées ; la valeur tapée s'appliquera à tout le créneau.`,
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
   * one gesture that extends a partial cell to its whole créneau. The
   * confirmation prices it first — stands, cells, hours of opening added —
   * because on the reference event that is 12 stands and 74 hours. Refused
   * while cells are modified: the reload after the save would drop them.
   */
  protected async alignerPartiels(): Promise<void> {
    const partiels = this.standsPartiels();
    if (partiels.length === 0 || this.standsModifies().length > 0 || this.enregistrement()) {
      return;
    }
    const cout = aplatissement(this.segments(), this.colonnes());
    const heures = Math.round(cout.minutes / 6) / 10;
    const confirme = await this.confirm.ask({
      title: $localize`:@@ouvertures.saisie.alignerTitle:Aligner les fenêtres sur les créneaux ?`,
      message: $localize`:@@ouvertures.saisie.alignerMessage:${partiels.length}:stands: stand(s), ${cout.cases}:cases: case(s) : chaque case sera étendue à son créneau entier, à sa valeur la plus haute, soit ${heures}:heures: h d'ouverture en plus (${partiels.join(', ')}:liste:).`,
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
              $localize`:@@ouvertures.saisie.doneNonCompactes:${nonCompactes.join(', ')}:stands: sont restés en fenêtres datées : leur motif ne se répète pas.`
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

  protected readonly largeurPourcent = largeurPourcent;
  protected readonly classeCellule = classeCellule;
  protected readonly iconeAnomalie = iconeAnomalie;

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

  protected anomaliesDe(standId: string): AnomalieOuverture[] {
    return this.anomaliesParStand().get(standId) ?? [];
  }

  protected infobulleStand(ligne: LigneStandOuverture): string {
    const anomalies = this.anomaliesDe(ligne.standId);
    return anomalies.length === 0 ? '' : anomalies.map((anomaly) => anomaly.message).join('\n');
  }

  /** Everything a cell says, for its tooltip — state, windows, decisive layer, postes. */
  protected infobulleCellule(cellule: CelluleJourOuverture): string {
    const lignes: string[] = [this.libelleEtat(cellule)];
    if (cellule.fenetres.length > 0) {
      lignes.push(
        cellule.fenetres
          .map((fenetre) => `${this.heure(fenetre.heureDebut)} → ${this.heure(fenetre.heureFin)}`)
          .join(', '),
      );
    }
    lignes.push(
      $localize`:@@ouvertures.tooltip.ouvert:Ouvert ${this.duree(cellule.minutesOuvertes)}:ouvert: sur ${this.duree(cellule.minutesAmplitude)}:amplitude:`,
    );
    lignes.push(
      $localize`:@@ouvertures.tooltip.postes:${cellule.postes}:postes: poste(s) généré(s)`,
    );
    lignes.push(this.libelleSource(cellule));
    return lignes.join('\n');
  }

  protected libelleEtat(cellule: CelluleJourOuverture): string {
    switch (cellule.etat) {
      case 'OUVERT_TOTAL':
        return $localize`:@@ouvertures.etat.total:Ouvert toute l'amplitude`;
      case 'OUVERT_PARTIEL':
        return $localize`:@@ouvertures.etat.partiel:Ouvert partiellement`;
      case 'FERME':
        // Closed on a day under consigne is the consigne's doing, not a hole in the schedule.
        return this.consigneParDate().has(cellule.date)
          ? $localize`:@@ouvertures.etat.fermeParConsigne:Fermé par consigne`
          : $localize`:@@ouvertures.etat.ferme:Fermé`;
    }
  }

  /** The cell's class, muted rather than alarming when the consigne is what closed it. */
  protected classeCelluleConsigne(cellule: CelluleJourOuverture): string {
    const classe = classeCellule(cellule);
    return cellule.etat === 'FERME' && this.consigneParDate().has(cellule.date)
      ? `${classe} etat-ferme-consigne`
      : classe;
  }

  protected libelleSource(cellule: CelluleJourOuverture): string {
    switch (cellule.source) {
      case 'DEFAUT':
        return $localize`:@@ouvertures.source.defaut:Aucune règle ni exception : ouvert par défaut`;
      case 'REGLE':
        return $localize`:@@ouvertures.source.regle:Décidé par une règle d'horaire récurrente`;
      case 'EXCEPTION':
        return $localize`:@@ouvertures.source.exception:Décidé par une exception datée, qui prime sur les règles`;
    }
  }

  /** `09:00:00` → `09:00`. */
  protected heure(valeur: string): string {
    return valeur.length > 5 ? valeur.slice(0, 5) : valeur;
  }
}
