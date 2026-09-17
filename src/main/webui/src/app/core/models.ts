// TypeScript mirror of the JSON exposed by the Quarkus API.
// Domain names stay in the French business vocabulary of the backend.

export type NiveauCompetence = 'DEBUTANT' | 'AUTONOME' | 'REFERENT';
export type NiveauEffort = 'NORMAL' | 'EPUISANT';
export type TypeContrainteAdHoc =
  'INDISPONIBILITE_FORCEE' | 'INCOMPATIBILITE' | 'AFFECTATION_FORCEE' | 'AFFINITE';
export type NiveauContrainte = 'HARD' | 'MEDIUM' | 'SOFT';

/** `/api/typologies` items: the enum id plus a display label. */
export interface TypologieItem {
  id: string;
  label: string;
  /**
   * The single "ninja" typologie of the referential: animateurs who hold it are
   * polyvalent — the solver may dispatch them on any stand, and keeps some of
   * them free as a buffer against last-minute absences. Promoting one typologie
   * demotes the previous holder server-side.
   */
  ninja?: boolean;
  /**
   * How many créneaux one animateur may hold on this typologie over the WHOLE
   * edition (issue #594). Absent or `null` means no cap. A poste counts for
   * every typologie its stand proposes.
   */
  maxCreneauxParAnimateur?: number | null;
  /**
   * A free note the organiser writes for themselves — « cette typologie
   * nécessite d'apprendre 45 jeux ». Read on the Typologies screen and in the
   * planning-by-typologie view, nowhere else: not on a PDF, not in an
   * animateur's espace. Absent or `null` until somebody writes one.
   */
  description?: string | null;
  /**
   * When the row was last written server-side (issue #362). Sent back as is on
   * an edit: the server refuses the write (409, `MODIFICATION_CONCURRENTE`) if
   * the row moved since, and the CRUD service then offers to reload or to
   * overwrite. Absent or `null` = no precondition, the write is not checked.
   */
  modifieLe?: string | null;
}

export interface Animateur {
  ninja?: boolean;
  id: string;
  prenom: string;
  nom: string;
  /**
   * When the row was last written server-side (issue #362). Sent back as is on
   * an edit: the server refuses the write (409, `MODIFICATION_CONCURRENTE`) if
   * the row moved since, and the CRUD service then offers to reload or to
   * overwrite. Absent or `null` = no precondition, the write is not checked.
   */
  modifieLe?: string | null;

  /** ISO date; required on both directions of the API — the legal regime is derived from it. */
  dateNaissance: string;
  /** Manages other animateurs; every animateur (manager or not) is paid. */
  manager: boolean;
  /** Administrator's appreciation, after formation — shown as "Appréciation" in the UI. */
  competences: Record<string, NiveauCompetence>;
  /** Stand typologies the animateur wishes to be assigned to — unordered, no priority. */
  souhaits: string[];
  joursIndisponibles: string[];
  /** Contact address for the échange notifications (issue #165); null when not collected. */
  email?: string | null;
  /** Access token of the espace animateur — the link printed on their PDF planning. Read-only: rotated via `/api/animateurs/{id}/token`. */
  accessToken?: string | null;
}

export interface Stand {
  id: string;
  nom: string;
  /**
   * When the row was last written server-side (issue #362). Sent back as is on
   * an edit: the server refuses the write (409, `MODIFICATION_CONCURRENTE`) if
   * the row moved since, and the CRUD service then offers to reload or to
   * overwrite. Absent or `null` = no precondition, the write is not checked.
   */
  modifieLe?: string | null;
  typologiesProposees: string[];
  effectifMin: number;
  effectifMax: number;
  reserveMajeurs: boolean;
  /** Editor/publisher-tier stand: the solver avoids rotating staff and favors experienced animateurs. */
  premium: boolean;
  /** Physical-effort tier: EPUISANT drives rest-after-effort and pénibilité-fairness balancing. */
  niveauEffort: NiveauEffort;
  /** Physical location (kiosque, mairie, ...), nullable. */
  emplacement: Emplacement | null;
  /**
   * Dated closure exceptions, e.g. "closed 14:00-16:00 on 2026-07-18". Empty =
   * always open (the default), unless `ouvertures` or a `horaires` rule says
   * otherwise for that day.
   */
  indisponibilites: IndisponibiliteStand[];
  /**
   * Dated opening exceptions — the inverse of `indisponibilites`, for a stand
   * normally closed and only staffed during specific windows. Empty = no day is
   * opening-only (the default). A day can never have both an entry here and
   * one in `indisponibilites`.
   */
  ouvertures: OuvertureStand[];
  /**
   * Recurring opening/closing rules — how a stable pattern is entered, instead
   * of one dated window per event day. A dated entry above always wins over
   * these for the day it names; see `core/horaire-stand.ts` for the resolution.
   */
  horaires: HoraireStand[];
}

/**
 * A single dated closure window of a stand — possibly only part of a créneau
 * (issue #60). A `null` `heureFin` means "until closing time": the window runs
 * to the end of whatever créneau it is evaluated against.
 */
export interface IndisponibiliteStand {
  id: number | null;
  date: string;
  heureDebut: string;
  heureFin: string | null;
  /** Free-text reason, nullable — purely informative, never read by the solver. */
  motif: string | null;
}

/** A single dated opening window of a stand — the inverse of `IndisponibiliteStand`. */
export interface OuvertureStand {
  id: number | null;
  date: string;
  heureDebut: string;
  heureFin: string | null;
  /** Free-text reason, nullable — purely informative, never read by the solver. */
  motif: string | null;
  /** Seats to fill on this dated window; absent or null = the stand's `effectifMin`. Never below 1. */
  effectif?: number | null;
}

/** `OUVERTURE` = open only on the listed windows; `FERMETURE` = closed only on them. */
export type ModeHoraire = 'OUVERTURE' | 'FERMETURE';

/**
 * Which days a `HoraireStand` applies to. Also its *specificity* order, least
 * specific first: when two rules cover the same day, the most specific one wins.
 */
export type TypeJoursHoraire = 'TOUS' | 'JOURS_SEMAINE' | 'PLAGE' | 'DATES';

export type JourSemaine =
  'MONDAY' | 'TUESDAY' | 'WEDNESDAY' | 'THURSDAY' | 'FRIDAY' | 'SATURDAY' | 'SUNDAY';

/**
 * A recurring opening/closing rule of a stand: windows plus the days they apply
 * to. One rule replaces as many dated windows as there are event days it
 * covers — "open 10:00-12:00 then 14:00 to closing, every day" is one rule with
 * two windows instead of twenty-four dated entries.
 *
 * The day selector is flat: `jours` names which of the sibling fields applies.
 */
export interface HoraireStand {
  id: number | null;
  mode: ModeHoraire;
  jours: TypeJoursHoraire;
  /** Only for `JOURS_SEMAINE`. */
  joursSemaine: JourSemaine[];
  /** Only for `PLAGE`, bounds included. */
  dateDebut: string | null;
  dateFin: string | null;
  /** Only for `DATES`. */
  dates: string[];
  fenetres: FenetreHoraire[];
  /** Free-text reason, nullable — purely informative, never read by the solver. */
  motif: string | null;
}

/**
 * One window of a `HoraireStand`, without a date — the date comes from the
 * rule's day selector. A `null` `heureFin` means "until closing time", which is
 * what lets one rule cover a day closing at 20:00 and a day closing at midnight.
 */
export interface FenetreHoraire {
  heureDebut: string;
  heureFin: string | null;
  /** Seats to fill on this window; absent or null = the stand's `effectifMin`. Never below 1. */
  effectif?: number | null;
}

/**
 * What `POST /api/stands/compactage-horaires` reports, per stand: dated windows
 * before, rules and exceptions after, plus why a stand was left alone. A call
 * with `appliquer=false` returns the same shape without writing anything.
 */
export interface LigneCompactage {
  standId: string;
  fenetresAvant: number;
  reglesApres: number;
  exceptionsApres: number;
  /**
   * Difference in minutes between the open segments before and after — non-zero
   * only where a `23:59` window becomes a genuine "until closing time" one.
   */
  ecartMinutes: number;
  compacte: boolean;
  /**
   * Closures the pruning took away: the rule that only shut the days the
   * openings did not name, and the "shut, all day" markers the rules already
   * imply. Openings are never pruned.
   */
  reglesElaguees: number;
  raison: string;
}

export interface RapportCompactage {
  applique: boolean;
  standsCompactes: number;
  fenetresAvant: number;
  fenetresApres: number;
  stands: LigneCompactage[];
}

/* ------------------ Ouvertures des stands (`/api/ouvertures-stands`) ------------------ */

/** How much of a day's amplitude a stand covers. */
export type EtatOuverture = 'OUVERT_TOTAL' | 'OUVERT_PARTIEL' | 'FERME';

/** Which layer decided a day: nothing, a recurring rule, or a dated exception. */
export type SourceHoraire = 'DEFAUT' | 'REGLE' | 'EXCEPTION';

export type TypeAnomalieOuverture =
  'STAND_JAMAIS_OUVERT' | 'FENETRE_SANS_EFFET' | 'SEGMENT_TROP_COURT';

/**
 * One column of the entry grid: a tranche of a créneau — the whole créneau
 * when no stand cuts it, else one stretch between two boundaries a stand's
 * windows draw inside it. `id` is the créneau's, `heureDebut`/`heureFin` the
 * column's own bounds, `tranche` its rank inside the créneau.
 */
export interface ColonneCreneau {
  id: number;
  tranche: number;
  heureDebut: string;
  heureFin: string;
  couverturePause: boolean;
}

/** One event day, the amplitude its column's cells are measured against, and its créneaux. */
export interface JourAmplitude {
  date: string;
  jour: number;
  heureDebut: string;
  heureFin: string;
  minutes: number;
  nombreCreneaux: number;
  creneaux: ColonneCreneau[];
}

/** One open stretch of a cell, in wall-clock hours, with the headcount it asks for. */
export interface SegmentCellule {
  heureDebut: string;
  heureFin: string;
  effectif: number;
}

/**
 * What one stand does on one créneau, as the entry grid shows it: the
 * configured headcount, `null` when closed. `partiel` flags windows that do
 * not follow the créneau's edges, or a headcount that changes during it — a
 * shape one integer cannot hold; `effectif` is then the highest one and
 * `segments` says what the cell really holds. A save keeps those segments as
 * long as the cell is not retyped.
 */
export interface CelluleCreneauOuverture {
  creneauId: number;
  /** The column's rank inside its créneau, matching `ColonneCreneau.tranche`. */
  tranche: number;
  effectif: number | null;
  partiel: boolean;
  /** The open stretches of the cell, empty when closed; one spanning the créneau when not partial. */
  segments: SegmentCellule[];
}

export interface FenetreEffective {
  heureDebut: string;
  heureFin: string;
}

export interface CelluleJourOuverture {
  date: string;
  etat: EtatOuverture;
  source: SourceHoraire;
  fenetres: FenetreEffective[];
  minutesOuvertes: number;
  minutesAmplitude: number;
  postes: number;
  creneaux: CelluleCreneauOuverture[];
}

export interface LigneStandOuverture {
  standId: string;
  nom: string;
  effectifMin: number;
  jours: CelluleJourOuverture[];
  minutesOuvertes: number;
  postes: number;
  /** The stand's stamp as this grid read it, echoed back by the save as its precondition (issue #362). */
  modifieLe: string | null;
}

export interface AnomalieOuverture {
  type: TypeAnomalieOuverture;
  standId: string;
  standNom: string;
  date: string | null;
  /** The window a `FENETRE_SANS_EFFET` names, so the day timeline draws it where it falls; absent on the other types. */
  heureDebut?: string | null;
  /** `null` on an open-ended window (« jusqu'à la fermeture »). */
  heureFin?: string | null;
  message: string;
}

/**
 * What `GET /api/ouvertures-stands` returns: the opening schedule actually in
 * force, built server-side from the very postes a solve would receive — so the
 * screen validates the real thing rather than a second interpretation of it.
 */
export interface RapportOuvertures {
  jours: JourAmplitude[];
  stands: LigneStandOuverture[];
  standsJamaisOuverts: number;
  postesTotal: number;
  anomalies: AnomalieOuverture[];
}

/** One stand of the grid as submitted to `PUT /api/ouvertures-stands/grille`: all its cells. */
export interface SaisieStandGrille {
  standId: string;
  /** The stand's `modifieLe` as the grid read it, sent back as the write's precondition (issue #362). */
  modifieLe: string | null;
  /** One per column: the créneau, the column's bounds inside it (both absent for the créneau in one piece), the headcount. */
  cellules: {
    creneauId: number;
    heureDebut: string | null;
    heureFin: string | null;
    effectif: number | null;
  }[];
  /** `true` to extend every partial cell to its whole column; `false` keeps the segments of a cell saved unchanged. */
  aplatir: boolean;
}

/** What the save did to one stand: the rules and exceptions it now holds, and the bounds derived from the cells. */
export interface LigneSaisieGrille {
  standId: string;
  regles: number;
  exceptions: number;
  effectifMin: number;
  effectifMax: number;
  compacte: boolean;
  raison: string;
}

export interface RapportSaisieGrille {
  stands: LigneSaisieGrille[];
}

/* ------------------ Fragilité du planning (`/api/fragilite`) ------------------ */

/** How badly a fragility row hurts, worst first. */
export type SeveriteFragilite = 'CRITIQUE' | 'ELEVEE' | 'MODEREE';

/** One stand × créneau an animateur's withdrawal would leave under its effectif. */
export interface PosteFragile {
  standId: string;
  standNom: string;
  creneauId: number;
  date: string;
  jour: number;
  heureDebut: string;
  heureFin: string;
  /** The effectif configured for this window (the window's own, or the stand's minimum) — not this group's floor, which `siegesRequis` carries. */
  effectifMin: number;
  /** Break-covering shift: seats are generated at half the headcount, rounded up. */
  couverturePause: boolean;
  siegesRequis: number;
  siegesPourvus: number;
  siegesLiberes: number;
  remplacants: number;
  irremplacable: boolean;
}

export interface AnimateurFragilite {
  animateurId: string;
  nom: string;
  ninja: boolean;
  affectations: number;
  postesEffondres: number;
  postesIrremplacables: number;
  competencesRares: number;
  severite: SeveriteFragilite;
  postes: PosteFragile[];
  postesNonDetailles: number;
}

/**
 * One stand × créneau at most one *specialist* can hold. Ninjas are counted
 * apart, as `renforts`: they are competent everywhere, so folding them in would
 * erase the scarcity this list exists to show.
 */
export interface CompetenceRare {
  standId: string;
  standNom: string;
  creneauId: number;
  date: string;
  jour: number;
  heureDebut: string;
  heureFin: string;
  typologies: string[];
  specialistes: number;
  animateurId: string | null;
  nom: string | null;
  renforts: number;
  pourvu: boolean;
  severite: SeveriteFragilite;
}

/** What `GET /api/fragilite` returns — computed without any solve. */
export interface RapportFragilite {
  animateurs: AnimateurFragilite[];
  competencesRares: CompetenceRare[];
  totalCompetencesRares: number;
  /** Counted in stand × créneau × fenêtre groups, like `groupesAnalyses`. */
  groupesSansSpecialiste: number;
  groupesAnalyses: number;
  groupesDejaSousEffectif: number;
  animateursIrremplacables: number;
  ninjaConfigure: boolean;
  /** The edition holds no animateur at all: nothing is feasible, and `message` says so. */
  aucunAnimateur: boolean;
  message: string;
}

/* -------------------- Marge disponible (`/api/marge`) -------------------- */

/**
 * Which capacity the margin is read against: `AVANT` compares the raw capacity
 * — who has not declared the date unavailable — to every seat a solve would
 * have to fill, `APRES` compares the people really free on the persisted plan
 * to the seats it left empty.
 */
export type ModeMarge = 'AVANT' | 'APRES';

/** One column of the margin heatmap: a timeslot of the grid, named by its hours. */
export interface TrancheMarge {
  debut: string;
  fin: string;
}

/** One day × timeslot cell: available animateurs against the seats still to staff. */
export interface CelluleMarge {
  date: string;
  jour: number;
  debut: string;
  fin: string;
  /** The timeslot the cell is read from — what the bench link carries. */
  creneauId: number;
  sieges: number;
  /** Always 0 in `AVANT`, where no assignment is read. */
  siegesPourvus: number;
  /** Every seat before a solve, the unfilled ones after. */
  besoin: number;
  disponibles: number;
  /** `disponibles - besoin`: negative is a hole nobody on the roster can fill. */
  marge: number;
}

/** One event day, with the synthesis line the screen shows under the grid. */
export interface JourMarge {
  date: string;
  jour: number;
  cellules: CelluleMarge[];
  pireCellule: CelluleMarge | null;
}

/** What `GET /api/marge` returns — computed without any solve. */
export interface RapportMarge {
  mode: ModeMarge;
  tranches: TrancheMarge[];
  jours: JourMarge[];
  animateursTotal: number;
  cellulesDeficitaires: number;
  /** The tightest cell of the whole event; `null` when the grid holds none. */
  pireCellule: CelluleMarge | null;
  /** The legal break the `APRES` mode keeps either side of a cell, in minutes. */
  pauseMinimaleMinutes: number;
  referentielsManquants: ReferentielManquant[];
  message: string;
}

/** Editable GPS-located place a stand can be tied to (`/api/emplacements`). */
export interface Emplacement {
  id: string;
  nom: string;
  /**
   * When the row was last written server-side (issue #362). Sent back as is on
   * an edit: the server refuses the write (409, `MODIFICATION_CONCURRENTE`) if
   * the row moved since, and the CRUD service then offers to reload or to
   * overwrite. Absent or `null` = no precondition, the write is not checked.
   */
  modifieLe?: string | null;
  latitude: number | null;
  longitude: number | null;
}

/**
 * What the server noticed about a write it accepted — never a refusal (those
 * come back as an `ApiError` carrying a 4xx). Raised at the creation and at the
 * edit of an animateur or a créneau, and carried in the success body next to
 * the entity; nothing is recomputed here, the rules live in the backend so an
 * import or an MCP call reads the same ones.
 */
export type TypeAvertissement =
  | 'INDISPONIBILITE_HORS_EVENEMENT'
  | 'INDISPONIBILITE_JOUR_SANS_CRENEAU'
  | 'MINEUR_PENDANT_EVENEMENT'
  | 'CRENEAU_HORS_OUVERTURE_STANDS'
  | 'STAND_EXCEPTION_HORS_EVENEMENT'
  | 'STAND_FENETRE_SANS_EFFET'
  | 'STAND_JAMAIS_OUVERT'
  | 'CRENEAU_DEBORDE_OUVERTURE_STANDS'
  | 'AFFECTATION_FORCEE_JOUR_INDISPONIBLE';

export interface Avertissement {
  type: TypeAvertissement;
  /** Already names the dates and entities involved: shown as typed. */
  message: string;
}

/**
 * Warning types whose sentence must not be written to the notification log.
 *
 * Every notification is appended to a 200-entry journal kept in `localStorage`,
 * which outlives the logout and is readable from the Notifications page by
 * anyone reopening that browser profile. A sentence saying that a named person
 * is a minor — and, when they turn 18 during the event, saying exactly when —
 * has no business surviving the screen it was shown on (`docs/rgpd.md` §7).
 * The snack bar still shows it: it is the persistence that is refused, not the
 * warning.
 */
const AVERTISSEMENTS_HORS_JOURNAL: readonly TypeAvertissement[] = ['MINEUR_PENDANT_EVENEMENT'];

/** See {@link AVERTISSEMENTS_HORS_JOURNAL}. */
export function estJournalisable(avertissement: Avertissement): boolean {
  return !AVERTISSEMENTS_HORS_JOURNAL.includes(avertissement.type);
}

/** Body of `POST`/`PUT /api/animateurs`: the fiche as written, and its warnings. */
export interface WrittenAnimateur {
  animateur: Animateur;
  avertissements: Avertissement[];
}

/** Body of `POST`/`PUT /api/creneaux` — same shape, same reason. */
export interface WrittenCreneau {
  creneau: Creneau;
  avertissements: Avertissement[];
}

/** Body of `POST`/`PUT /api/stands` — the schedule's warnings ride along. */
export interface WrittenStand {
  stand: Stand;
  avertissements: Avertissement[];
}

export interface Creneau {
  dureeMinutes?: number;
  id: number;
  jour: number;
  /**
   * When the row was last written server-side (issue #362). Sent back as is on
   * an edit: the server refuses the write (409, `MODIFICATION_CONCURRENTE`) if
   * the row moved since, and the CRUD service then offers to reload or to
   * overwrite. Absent or `null` = no precondition, the write is not checked.
   */
  modifieLe?: string | null;
  date: string;
  heureDebut: string;
  heureFin: string;
  /**
   * True when this slot is the short vacation relieving a stand over a meal
   * service: the stands are then deliberately staffed at half their usual
   * headcount, rounded up, which the calendars flag as an indication rather
   * than as a shortfall. Set on the créneau itself, or carried by the vacation
   * of a journée type. Absent on the payloads that predate the flag, hence
   * optional.
   */
  couverturePause?: boolean;
}

/**
 * A whole edition of the event — "Année 2025", "Année 2026" — and the scope
 * every piece of reference data belongs to (`/api/editions`). Not to be confused
 * with the former timeslot groups (removed by issue #172: the edition is
 * the only variant carrier). `defaut` is not "the current one": that is this browser's own
 * choice, sent as `X-Edition-Id`; `defaut` is the server's fallback when no
 * edition is designated. See docs/decisions/0001-cloisonnement-par-edition.md.
 */
export interface Edition {
  id: string;
  nom: string;
  defaut: boolean;
  creeLe: string | null;
}

/**
 * When the persisted plan (`/api/planning/persisted`) was last solved.
 * `solved` is `false` when nothing has ever been solved.
 * `derniereModificationDonnees` is when reference data was last edited
 * (`null` if never, or since server start).
 */
export interface PlanningResolution {
  solved: boolean;
  resoluLe: string | null;
  derniereModificationDonnees: string | null;
}

/** One seat to fill: a stand on a timeslot, with its animator once solved. */
export interface PosteAffectation {
  dureeEffectiveMinutes?: number;
  verrouille?: boolean;
  id: string;
  stand: Stand | null;
  creneau: Creneau | null;
  animateur: Animateur | null;
  /**
   * Narrower window this poste actually covers within `creneau`, when the
   * stand is only partially closed on that créneau (issue #60). Null/absent
   * in the overwhelming common case — fall back to `creneau.heureDebut`/`heureFin`.
   */
  heureDebutEffective?: string | null;
  heureFinEffective?: string | null;
}

export interface ContrainteAdHoc {
  id: string;
  type: TypeContrainteAdHoc;
  /**
   * When the row was last written server-side (issue #362). Sent back as is on
   * an edit: the server refuses the write (409, `MODIFICATION_CONCURRENTE`) if
   * the row moved since, and the CRUD service then offers to reload or to
   * overwrite. Absent or `null` = no precondition, the write is not checked.
   */
  modifieLe?: string | null;
  animateursConcernes: { id: string }[];
  /** Only `id` is populated by the backend; look up `Creneau` details from the reference store if needed. */
  creneau: { id: number } | null;
  stand: { id: string } | null;
  raison: string;
  creeParUtilisateurId?: string;
  creeLe?: string;
}

/**
 * One emplacement of one meal window, on one day (`GET /api/pauses/intendance`,
 * issue #598): how many people are on a break there, half-hour by half-hour.
 *
 * `personnes` and `mineurs` hold one count per slot of `FenetreIntendance.tranches`,
 * in the same order. `total` is the distinct people over the whole window —
 * never the sum of `personnes`, which counts somebody once per half-hour their
 * break spans.
 */
export interface LigneEmplacementIntendance {
  emplacementId: string;
  emplacementNom: string;
  personnes: number[];
  mineurs: number[];
  total: number;
  totalMineurs: number;
}

/** One meal window of one day, as a table: half-hours across, emplacements down. */
export interface FenetreIntendance {
  libelle: string;
  debut: string;
  fin: string;
  tranches: string[];
  emplacements: LigneEmplacementIntendance[];
  total: number;
  totalMineurs: number;
}

/** One day of the event, one table per window it declares. */
export interface JourneeIntendance {
  date: string;
  fenetres: FenetreIntendance[];
}

/**
 * « Combien de sandwichs, et où les porter » — the meal breaks of the
 * persisted plan, counted rather than named. `message` says why the report is
 * empty when it is: a screen saying « 0 » and a screen saying « rien n'est
 * résolu » are not the same screen.
 */
export interface RapportIntendance {
  pasMinutes: number;
  journees: JourneeIntendance[];
  message: string;
}

/** What a {@link VerrouillagePlanning} freezes (issue #87; ANIMATEUR_CRENEAU: issue #165). */
export type TypeVerrouillage = 'ANIMATEUR' | 'STAND' | 'JOUR' | 'CRENEAU' | 'ANIMATEUR_CRENEAU';

/**
 * A validated part of the planning the solver must not touch again
 * (`/api/verrouillages`). Exactly one target field is set, matching `type`,
 * and the lock belongs to its edition like the rest of the referential.
 */
export interface VerrouillagePlanning {
  id: string;
  type: TypeVerrouillage;
  animateurId: string | null;
  standId: string | null;
  /**
   * The créneau the lock resolves to today, re-read on every request by
   * matching the natural key below against the grid (issue #577) — `null`
   * while the grid holds no such vacation.
   */
  creneauId: number | null;
  /** ISO date of the vacation a `CRENEAU`/`ANIMATEUR_CRENEAU` lock names. */
  creneauDate?: string | null;
  creneauHeureDebut?: string | null;
  creneauHeureFin?: string | null;
  /** ISO date, for a `JOUR` lock. */
  jour: string | null;
  raison: string | null;
  creeLe?: string;
  /**
   * True when the lock names a vacation the grid no longer holds: it waits for
   * it rather than having been deleted with the créneau, which is what used to
   * happen — silently — on every grid regeneration (issue #577).
   */
  vacationMissing?: boolean;
}

/**
 * Real scale of the problem the next solve will build, from `/api/planning/volumetrie`
 * (mirrors what Timefold's own "Problem scale" log line reports): `posteCount` is one
 * entry per required seat, not per stand, and `contrainteAdHocCount` are the extra
 * ad hoc rules layered on top. `hoursToFill` sums the effective duration of every
 * seat (stand closures deducted); `hoursAvailable` is the legal ceiling of what the
 * animateurs may work over the event's days, unavailable days deducted.
 */
export interface Scale {
  animateurCount: number;
  posteCount: number;
  contrainteAdHocCount: number;
  hoursToFill: number;
  hoursAvailable: number;
}

/**
 * The explicit state of one constraint for this edition, as the server attaches
 * it to the problem. Absent from the list means « whatever the catalogue says »,
 * which is active for all but the rules shipped switched off (issue #595).
 */
export interface ConstraintToggle {
  nom?: string;
  actif?: boolean;
}

/**
 * `/api/parametres-qualite`: the quality ceilings of the edition. Stored per
 * edition since issue #591; what the server's own configuration carries is the
 * default an edition starts from.
 */
export interface ParametresQualite {
  maxEmplacementsDistinctsParJour?: number;
  /** Hour from which a day's work counts as a late closing (`HH:mm:ss`); empty when the rule is neutralised. */
  heureServiceTardif?: string | null;
  /** Hour up to which a day's work counts as an early opening (`HH:mm:ss`); empty when the rule is neutralised. */
  heureServiceMatinal?: string | null;
  reposSouhaiteApresServiceTardifMinutes?: number;
  /** Distinct typologies one animateur may cover over the WHOLE edition before being penalised. */
  typologiesDistinctesMax?: number;
}

export interface HardMediumSoftScore {
  hardScore: number;
  mediumScore: number;
  softScore: number;
  /**
   * Sent by the server and missing from here until now: the frontend was blind
   * to this field (OpenAPI contract, `docs/schema/openapi.json`). Declared
   * optional rather than required because the schema carries no `required` for
   * it — asserting a guarantee the contract does not express would be an
   * invention.
   */
  feasible?: boolean;
  zero?: boolean;
}

/**
 * One meal window of the event day, as the solver reads it: a break of
 * `dureeMinutes` must fit entirely inside `debut`–`fin` for whoever works
 * either side of it. Not the twenty-minute legal break — see
 * `CoupureRepasView`.
 */
export interface FenetreRepas {
  /** `midi` / `soir`. */
  libelle: string;
  /** `HH:mm:ss`. */
  debut: string;
  fin: string;
  dureeMinutes: number;
  /**
   * Which end of the window the break is preferred at: `true` at midday, where
   * the stands have just opened, `false` in the evening, eaten early so they
   * reopen (issue #596).
   */
  auPlusTard?: boolean;
}

/** How many créneaux one animateur may hold on one typologie, over the whole edition (issue #594). */
export interface QuotaTypologie {
  typologie?: string;
  maxCreneaux?: number;
}

export interface PlanningEvenement {
  constraintsDesactivees?: ConstraintToggle[];
  parametresLegaux?: ParametresLegaux[];
  parametresQualite?: ParametresQualite[];
  /** Only the typologies that carry a cap; empty when the edition caps nothing. */
  quotasTypologies?: QuotaTypologie[];
  /** The edition's meal windows, as `coupureRepasObligatoire` reads them; empty when none applies. */
  fenetresRepas?: FenetreRepas[];
  verrouillages?: VerrouillagePlanning[];
  dateDebutFestival?: string;
  animateurs: Animateur[];
  postes: PosteAffectation[];
  contraintesAdHoc?: ContrainteAdHoc[];
  score: HardMediumSoftScore | null;
}

export interface ConstraintDiagnostic {
  name: string;
  score: string;
  matchCount: number;
  /** One human-readable line per match, only populated for HARD constraints. */
  violations: string[];
}

/**
 * Why a planning cannot be filled, in business terms:
 * - `CRENEAU_SOUS_EFFECTIF`: an open créneau demands more seats than there are
 *   available animateurs for it. Competence (appreciation) is no longer part
 *   of this capacity check: it is a medium constraint now, not a coverage
 *   requirement — any available animateur can literally be assigned to any
 *   stand, just penalised on a mismatch.
 * - `CONTRAINTES_AD_HOC_CONTRADICTOIRES`: two hand-entered exceptions that
 *   cannot both hold, whatever the solver does. Refused at entry time, so this
 *   only ever reports what was recorded before that check existed or imported
 *   in one go — `contrainteIds` names the exceptions to arbitrate.
 */
export type TypeCauseInfaisabilite =
  | 'CRENEAU_SOUS_EFFECTIF'
  | 'CONTRAINTES_AD_HOC_CONTRADICTOIRES'
  | 'AFFECTATION_FORCEE_JOUR_INDISPONIBLE';

/** `CRITIQUE` = no coverage possible at all; `ELEVE` = partial coverage only. */
export type SeveriteInfaisabilite = 'CRITIQUE' | 'ELEVE';

/**
 * One ranked reason the plan is not feasible, carrying the concrete entities
 * involved so the UI can badge the matching rows.
 *
 * `creneauId` is declared as a string because it is only ever compared or
 * displayed here; the backend serialises a `Long`, so always normalise both
 * sides with `String(...)` before matching it against `Creneau.id` (a number).
 */
export interface CauseInfaisabilite {
  type: TypeCauseInfaisabilite;
  severite: SeveriteInfaisabilite;
  /** Ready-to-display French sentence, built server-side. */
  message: string;
  creneauId: string | null;
  date: string | null;
  heureDebut: string | null;
  heureFin: string | null;
  /** Stands concerned, empty on a cause that names none. */
  standIds: string[];
  /** Ad hoc constraints concerned, empty on a cause that names none. */
  contrainteIds: string[];
  demande: number;
  capacite: number;
  manque: number;
}

/**
 * Plain-language capacity check: is there even a theoretical chance to fill
 * every seat, regardless of solver time? `message` is ready to show as-is to
 * a non-technical user, and `causes` details it, sorted most severe first and
 * capped server-side — `totalCauses` counts them all, so the UI can say
 * "+N autres".
 *
 * Available without solving, from `GET /api/feasibility`, and also embedded in
 * the post-solve diagnostics.
 */
export interface FeasibilityReport {
  feasible: boolean;
  /** Worst single-créneau shortfall, `0` when no créneau is short-staffed. */
  manqueAnimateurs: number;
  causes: CauseInfaisabilite[];
  totalCauses: number;
  /** Blocking causes, counted before the cap on `causes`. */
  causesCritiques: number;
  /** The others, counted before that same cap. */
  causesElevees: number;
  message: string;
}

/**
 * Payload of a solve job: score, unfilled seats and feasibility.
 * Deliberately excludes the solved planning itself (animateurs/stands/
 * créneaux/postes) — that payload can reach several dozens of MB and is
 * consulted through the dedicated screens instead, which load it from
 * `/api/planning/persisted`.
 *
 * `hardScore` is the hard score actually reached by this solve, distinct from
 * `faisabilite`: the latter is a cheap, optimistic pre-solve capacity estimate
 * that can say "réalisable" for a plan the solver still could not bring to
 * zero hard (see the backend `FeasibilityAnalyzer` javadoc). Whether the plan
 * actually in hand is fully legal/staffed is `hardScore === 0`, not
 * `faisabilite?.feasible`.
 */
export interface PlanningDiagnostic {
  score: string;
  postesNonPourvus: number;
  contraintes: ConstraintDiagnostic[];
  faisabilite: FeasibilityReport | null;
  hardScore: number;
  contraintesAdHocEnCause: ContributionAdHoc[];
  /**
   * The score with the floors taken out — the raw score minus, level by
   * level, what the rules read as a floor cost (see
   * {@link PlancherContrainte}). Same format as `score`, equal to it when
   * nothing is a floor: the part a solve can actually move. Null when the
   * plan carries no score.
   */
  scoreHorsPlancher: string | null;
  /** Constant part of the medium score, signed like it (−5000 for a floor of five thousand points). */
  plancherMedium: number;
  plancherSoft: number;
  /** Where the breaches concentrate (issue #496) — see {@link ConstraintsView.pivotEcarts}. */
  pivotEcarts: CellulePivot[];
}

/**
 * One hand-entered exception the last analysis found still violated, most
 * violated first. What turns "affectationForcee: 12" into a list of exceptions
 * to arbitrate: a hard-negative solve names the rule, this names which of the
 * user's own exceptions the solver could not honour.
 */
export interface ContributionAdHoc {
  contrainteId: string;
  type: TypeContrainteAdHoc | null;
  raison: string | null;
  /** Number of matches this exception accounts for. */
  violations: number;
  /** Names of the solver rules it broke, usually one. */
  contraintes: string[];
}

/**
 * One constraint's impact on a single poste, from `/api/postes/{id}/explication`
 * or `/api/postes/{id}/suggestions-reparation` — either a violation it is party to,
 * or an entry meaning it has no match involving that poste. "Respectée" only
 * means no violation was found for this poste, not that the constraint is
 * even applicable to it — never present it as a positive endorsement.
 */
export interface ContrainteImpact {
  name: string;
  niveau: NiveauContrainte | null;
  categorie: string | null;
  description: string | null;
  matchCount: number;
  /** One human-readable line per match, empty when not violated. */
  details: string[];
}

/** `/api/postes/{posteId}/explication`: per-assignment explainability ("Pourquoi lui ?"). */
export interface AffectationExplanation {
  posteId: string;
  /** The poste's current occupant, `null` when unassigned. */
  animateurId: string | null;
  score: HardMediumSoftScore;
  contraintesViolees: ContrainteImpact[];
  contraintesRespectees: ContrainteImpact[];
}

/**
 * One viable replacement proposed by the repair assistant: only ever a
 * candidate that leaves the plan's hard score no worse *and* breaks no hard
 * rule on the seat it takes, so `violationsIntroduites` never holds a `HARD`
 * one.
 */
export interface SuggestionReparation {
  animateurId: string;
  scoreApres: HardMediumSoftScore;
  /** `scoreApres - scoreAvant`: the greater, the better the repair. */
  delta: HardMediumSoftScore;
  violationsResolues: ContrainteImpact[];
  violationsIntroduites: ContrainteImpact[];
}

/**
 * `/api/postes/{posteId}/suggestions-reparation`: viable replacements for one
 * poste, best impact first, nothing persisted.
 *
 * The search is bounded — `candidatsEvalues` of the `candidatsEligibles` were
 * actually simulated. When the two differ the list is the best of what was
 * seen, not an exhaustive answer, and the UI has to say so.
 */
export interface SuggestionsReparation {
  posteId: string;
  animateurActuelId: string | null;
  scoreAvant: HardMediumSoftScore;
  contraintesVioleesAvant: ContrainteImpact[];
  candidatsEligibles: number;
  candidatsEvalues: number;
  plafond: number;
  suggestions: SuggestionReparation[];
}

/** One hard-constraint match, as a sentence and as the objects it names; any id may be null. */
export interface ViolationReference {
  texte: string;
  animateurId: string | null;
  standId: string | null;
  creneauId: number | null;
}

export interface ConstraintView {
  name: string;
  niveau: NiveauContrainte;
  categorie: string;
  description: string;
  /**
   * What an organiser can do about this rule being in default — hire, open a
   * stand later, vet somebody, lower a weight. The pivot of issue #496 says
   * where the breaches concentrate; this says what to do about them, which a
   * count on its own never did. Absent on an older payload.
   */
  remediation?: string;
  actif: boolean;
  /**
   * The rule founds the plan in law (« Légal (…) »), in the minors' safety
   * policy (« Sécurité (mineurs) ») or in the meal rule the event is built on
   * (« Organisation (repas) »). Switching one off lets the solver return a plan
   * scoring zero hard that still breaks the Code du travail — or holds a
   * ten-hour day with no meal break — so the UI confirms first; see
   * `LegalDisableDialog`.
   */
  protegee: boolean;
  /**
   * Among the protected rules, the ones an article of the Code du travail
   * actually founds. The others are the organiser's own — no less binding on
   * them, but switching one off does not make the plan unlawful, and the
   * confirmation must not claim it does.
   */
  legale: boolean;
  /**
   * Whether the catalogue ships this rule **on**. A rule shipped off (issue
   * #595) is not a rule somebody switched off: the « règle légale ou de
   * sécurité désactivée » banner skips it, the Contraintes screen still says
   * it is off, and turning it back on is a click like any other.
   */
  activeByDefault?: boolean;
  /**
   * The rule is one of those meant to be **dosed** rather than switched off:
   * the MEDIUM rules of « Qualité d'organisation », the only ones whose
   * relative importance genuinely varies from one organiser to the next.
   */
  dosable: boolean;
  /** What one match of the rule is worth on the next solve (1 to 100). */
  poids: number;
  score: string | null;
  matchCount: number | null;
  /** One human-readable line per match, only populated for HARD constraints. */
  violations: string[];
  /**
   * How many items the rule evaluated on the last analysis, at its own grain
   * (seats, stand × créneau groups, consecutive pairs…) — the denominator of
   * `plancher.ratio`. Null for a hard rule, for a rule whose match count is
   * not per item, and when never analysed.
   */
  postesEvalues: number | null;
  /**
   * Set when the rule matched at least 95 % of what it evaluated: its points
   * are a floor no solve will move, usually because a referential data is
   * missing altogether. Reported, never acted on — the rule stays active.
   */
  plancher: PlancherContrainte | null;
  /** The same lines with the ids they name, so a screen can open the fiche in question. */
  references: ViolationReference[];
}

/**
 * A rule read as a floor: what share of its items it matched, and which
 * referential data — when one — explains it, with the screen to enter it.
 */
export interface PlancherContrainte {
  /** matches ÷ evaluated items, 0.95 and above. */
  ratio: number;
  /** Code of the missing data (`SOUHAITS`, `REFERENTS`…), null when none explains the floor. */
  motif: string | null;
  /** The sentence to show, in the server's language like the rule descriptions. */
  libelle: string;
  /** Angular route of the entry screen, null when there is no data to enter. */
  lien: string | null;
}

export interface ConstraintsView {
  analysedAt: string | null;
  scoreGlobal: string | null;
  postesNonPourvus: number | null;
  faisabilite: FeasibilityReport | null;
  /** Hard score of the last analysed solve; see {@link PlanningDiagnostic.hardScore}. */
  hardScore: number | null;
  contraintes: ConstraintView[];
  /** Empty when the plan honours every exception, and when nothing was ever analysed. */
  contraintesAdHocEnCause: ContributionAdHoc[];
  /** See {@link PlanningDiagnostic.scoreHorsPlancher}; null when never analysed. */
  scoreHorsPlancher: string | null;
  plancherMedium: number | null;
  plancherSoft: number | null;
  /**
   * Where the breaches concentrate (issue #496): one cell per constraint and
   * per key of an axis. Only the cells carrying at least one breach are sent —
   * the table of zeroes is drawn here. Empty until something is analysed.
   */
  pivotEcarts: CellulePivot[];
}

/** The axes a pivot of the breaches can be read against. */
export type AxePivot = 'JOUR' | 'STAND' | 'ANIMATEUR';

/**
 * One cell of the pivot: how many matches of `contrainte` name `cle` on `axe`.
 * `cle` is an ISO date on `JOUR`, a stand id or an animateur id otherwise —
 * ids, never names, so the screen resolves the labels from its referential.
 */
export interface CellulePivot {
  contrainte: string;
  axe: AxePivot;
  cle: string;
  ecarts: number;
}

/**
 * `/api/parametres-legaux`: admin-configurable legal parameters, consumed by
 * the `dureeHebdomadaireMax` and `dureeHebdomadaireMaxMineur` hard constraints.
 *
 * Both are ordre public ceilings, refused above their legal maximum by the
 * server (`ReferenceDataService.updateParametresLegaux`); a lower, more
 * protective value stays free.
 *
 * - `dureeHebdomadaireMaxMinutes`: 48 h (2880 min) by default — Code du travail
 *   art. L3121-20, Convention collective de l'Animation (ÉCLAT, IDCC 1518).
 * - `dureeHebdomadaireMaxMineurMinutes`: 35 h (2100 min) by default — Code du
 *   travail art. L3162-1 (art. D4153-3 under 16).
 */
export interface ParametresLegaux {
  dureeHebdomadaireMaxMinutes: number;
  dureeHebdomadaireMaxMineurMinutes: number;
  /** Minimum gap (minutes) between two same-day vacations of one animateur. Default 30. */
  pauseMinimaleEntreVacationsMinutes: number;
  /** Minimum daily rest (minutes) between two calendar days, all animateurs. Default 660 (11h, art. L3131-1). */
  reposQuotidienMinimalMinutes: number;
  /**
   * Legal break (20 min at 6 h for an adult, 30 min at 4 h 30 for a minor) taken on
   * the post by relay between colleagues, rather than as a gap between two
   * vacations. Default false: the organiser declares it.
   */
  pauseSurPoste: boolean;
  /**
   * How long that break lasts. Floors of ordre public — 20 min for an adult
   * (art. L3121-16), 30 for a minor (art. L3162-3) — refused below by the
   * server; above them the organiser is free, a thirty-minute relay being
   * easier to organise than a twenty-minute one (issue #592).
   */
  dureePauseMajeurMinutes: number;
  dureePauseMineurMinutes: number;
  /**
   * The meal break: how long it lasts, and the midday and evening windows it
   * must fall in — the rule `coupureRepasObligatoire` judges (issue #438).
   * Not a legal obligation, but the rule the organisation sets itself, so it
   * travels with the parameters the organiser looks for here. Times as HH:MM.
   */
  coupureRepasMinutes: number;
  coupureRepasMidiDebut: string;
  coupureRepasMidiFin: string;
  coupureRepasSoirDebut: string;
  coupureRepasSoirFin: string;
  /**
   * When the evening starts for the Équité screen (HH:MM): the minutes of a
   * poste past this hour are its evening hours. The organisation's rule, not
   * the law's — a minor's legal night is not settable.
   */
  heureDebutSoiree: string;
  /**
   * How long one vacation may run before the grid check warns (minutes).
   * Default 6 h, the art. L3121-16 threshold at which a break becomes
   * mandatory — a vacation under it never needs an internal one.
   */
  dureeVacationMaxMinutes: number;
}

export type SeveriteGrille = 'ERREUR' | 'AVERTISSEMENT';

export type TypeAnomalieGrille =
  | 'CRENEAU_INCOMPLET'
  | 'DUREE_NULLE'
  | 'DOUBLON'
  | 'REPOS_QUOTIDIEN_IMPOSSIBLE'
  | 'TROU_DANS_LA_JOURNEE'
  | 'VACATION_TROP_LONGUE'
  | 'DATE_ISOLEE'
  | 'RELAIS_REPAS_HORS_FENETRE';

export interface AnomalieGrille {
  severite: SeveriteGrille;
  type: TypeAnomalieGrille;
  date: string | null;
  message: string;
}

/** `GET /api/creneaux/controle`: the grid's verdict. */
export interface RapportGrille {
  nombreCreneaux: number;
  anomalies: AnomalieGrille[];
  ouvertures: AnomalieOuverture[];
  /** `null` when there is nothing to judge — no stand, or no créneau. */
  faisabilite: FeasibilityReport | null;
}

/** `GET /api/creneaux/diagnostic`: what the grid holds — how many, over which dates, how many relays. */
export interface DiagnosticGrille {
  nombreCreneaux: number;
  premiereDate: string | null;
  derniereDate: string | null;
  contientCouverturePause: boolean;
  explication: string;
}

/** A recurrence rule as `POST /api/creneaux/recurrence` reads it. */
export interface RegleRecurrence {
  jours: TypeJoursHoraire;
  dateDebut: string | null;
  dateFin: string | null;
  joursSemaine: JourSemaine[];
  dates: string[];
  exclusions: string[];
  fenetres: FenetreHoraire[];
}

/** A derivation of the grid from the stands' hours, as `POST /api/creneaux/derivation` reads it. */
export interface DerivationRequest {
  dateDebut: string;
  dateFin: string;
  /** `00:00` reads as midnight: the last créneau then crosses it. */
  heureFermeture: string;
  dureeMinimaleMinutes: number;
  remplacer: boolean;
}

/** One cut of one day, and the first stands whose windows start or end there. */
export interface CoupureDerivation {
  date: string;
  heure: string;
  standIds: string[];
  nombreStands: number;
}

export interface RapportDerivation {
  nombreGeneres: number;
  creneaux: Creneau[];
  coupures: CoupureDerivation[];
  joursSansFenetre: string[];
  controle: RapportGrille;
}

/** What a rule produced (or would produce), and the verdict on the resulting grid. */
export interface RapportRecurrence {
  nombreGeneres: number;
  creneaux: Creneau[];
  controle: RapportGrille;
}

/* ------------------------------ Day templates (ADR 0032) ------------------------------ */

/** One vacation of a day template; `couverturePause` means a meal relay, as on a créneau. */
export interface VacationType {
  heureDebut: string;
  heureFin: string;
  couverturePause: boolean;
}

/** A named day template — « Jour normal », « Nocturne » — the vacations of one kind of day. */
export interface JourneeType {
  id?: number | null;
  nom: string;
  vacations: VacationType[];
  /** The store's write stamp, sent back as the precondition of an edit (issue #362). */
  modifieLe?: string | null;
}

/** One date governed by one template. */
export interface AffectationJourneeType {
  date: string;
  journeeTypeId: number;
}

/** Templates, calendar, and the dates whose créneaux no longer match their template. */
export interface EtatJourneesTypes {
  journeesTypes: JourneeType[];
  calendrier: AffectationJourneeType[];
  datesEnEcart: string[];
}

/** What applying the calendar does or would do, and the verdict on the resulting grid. */
export interface RapportApplicationJourneesTypes {
  conserves: number;
  misAJour: number;
  crees: number;
  supprimes: number;
  creneauxSupprimes: Creneau[];
  supprimesAvecPostes: Creneau[];
  postesSupprimes: number;
  datesEnEcart: string[];
  aucunChangement: boolean;
  controle: RapportGrille;
}

/** The templates a grid implies, and the calendar mapping its dates onto them. */
export interface ReconnaissanceJourneesTypes {
  journeesTypes: JourneeType[];
  calendrier: AffectationJourneeType[];
}

/**
 * `/api/parametres-solveur`: the solver's default termination duration, set
 * from the Débogage tab. Persisted server-side (not localStorage) so every
 * browser reads and writes the same value.
 */
export interface ParametresSolveur {
  dureeResolutionSecondes: number;
  /** Mails the outcome of a finished solve to the admin address (off by default). */
  mailFinResolution: boolean;
}

/**
 * `GET /api/branding` — the identity this deployment wears. Read once before
 * the application bootstraps: the product name goes in the browser tab and in
 * every page title, the logo in the toolbars, the accent colour into the
 * `--app-accent` custom property.
 *
 * <p>An empty `logoUrl` means "show no logo", never "show a default one": one
 * instance per customer, and no customer inherits another's mark.</p>
 */
export interface Branding {
  /** Never empty: the server falls back to a neutral name rather than to nothing. */
  productName: string;
  /** Customer this instance is deployed for; empty when the deployment did not say. */
  organisation: string;
  logoUrl: string;
  /** Any CSS colour; empty leaves the compiled Material accent in place. */
  accentColor: string;
  /** Full-size mascot; empty disables the easter egg that shows it. */
  mascotUrl: string;
  /** Same mascot cut out small; empty falls back to a Material icon. */
  mascotIconUrl: string;
  /** Address the help page names for support; empty hides that paragraph and its link. */
  supportEmail: string;
}

/**
 * `GET /api/mentions-legales` — deployment-specific facts of the legal notice.
 * Every field is an empty string when the deployment did not configure it; the
 * page then says what is missing rather than inventing it.
 */
export interface MentionsLegales {
  editeur: string;
  directeurPublication: string;
  hebergeur: string;
  contact: string;
  /** Data controller when it differs from the publisher; empty falls back to it. */
  responsableTraitement: string;
  baseLegale: string;
  conservation: string;
  /**
   * Whether this deployment actually runs Cloudflare Web Analytics / sends
   * error reports to a Sentry-protocol endpoint. The privacy notice describes
   * only the tools that are on: naming a processing that does not happen —
   * a transfer outside the EU, for the first one — costs the credit of every
   * other sentence on that page.
   */
  mesureAudience: boolean;
  suiviErreurs: boolean;
}

/** Ordre public ceiling for adults, in hours (Code du travail art. L3121-20). */
export const DUREE_HEBDOMADAIRE_MAX_HEURES = 48;

/** Ordre public ceiling for minors, in hours (Code du travail art. L3162-1). */
export const DUREE_HEBDOMADAIRE_MAX_MINEUR_HEURES = 35;

/** Ordre public floor for an adult's break, in minutes (Code du travail art. L3121-16). */
export const DUREE_PAUSE_MAJEUR_MIN_MINUTES = 20;

/** Ordre public floor for a minor's break, in minutes (Code du travail art. L3162-3). */
export const DUREE_PAUSE_MINEUR_MIN_MINUTES = 30;

export type JobType = 'SOLVE' | 'SOLVE_INCREMENTAL';
/**
 * `QUEUED` waits for the solver without holding it; `PENDING` already holds it.
 * `INTERROMPU` is a job the server was running when it stopped: terminal, since
 * nothing will ever finish it. After a full restart its run is lost; when the
 * container stopped under it (graceful stop, live reload) its partial plan was
 * kept only if better than the persisted one, and `error` says which (see
 * SolverJobService on the backend).
 */
export type JobStatus =
  'PENDING' | 'QUEUED' | 'RUNNING' | 'COMPLETED' | 'FAILED' | 'CANCELLED' | 'INTERROMPU';

/** `/api/jobs/...` view: the server owns the solver state, elapsed included. */
export interface JobView {
  message?: string;
  id: string;
  type: JobType;
  status: JobStatus;
  /** Edition the job was submitted for — the one its result is written to. */
  editionId: string | null;
  /** Display name of that edition, resolved server-side at submit time. */
  editionNom: string | null;
  secondsLimit: number | null;
  /** Where a full solve was asked to start from (issue #174); null for an incremental job. */
  reamorcage?: Reamorcage | null;
  submittedAt: string;
  startedAt: string | null;
  finishedAt: string | null;
  elapsedSeconds: number;
  error: string | null;
  result: unknown;
}

/**
 * One sample of the running solve's score curve (issue #304): how long the
 * solver had been running, and the best score it had reached by then. Three
 * levels kept apart, never merged — a hard score at -36 and a soft one at
 * -400 000 share no scale, and it is the hard one that decides feasibility.
 */
export interface ScorePoint {
  tempsMs: number;
  hard: number;
  medium: number;
  soft: number;
}

/**
 * The curve of the solve currently running (or of the last one, until the next
 * starts). Read whole from `GET /api/jobs/score`, and pushed point by point as
 * `score` events of `/api/jobs/stream`.
 */
export interface ScoreTrace {
  jobId: string;
  /** Edition that run writes to: the curve is only shown on that edition. */
  editionId: string | null;
  /** Moves whenever the series stops being an append-only extension of itself. */
  generation: number;
  /** Current sampling interval, which widens as a long run is decimated. */
  intervalleMs: number;
  /**
   * How long the run has been going — the curve's right edge, and the only
   * thing that can show a plateau: Timefold announces a new best score *only
   * when it strictly improves*, so a solve that stops progressing stops
   * producing points entirely. Read from the points alone, such a run would
   * draw as if it were still climbing. Frozen once the run is over.
   */
  dureeMs: number;
  /** True once the run is over, whichever way it ended. */
  termine: boolean;
  points: ScorePoint[];
}

/**
 * One event day of `GET /api/staffing`: what its generated seats demand.
 * `heures` are person-hours, `sieges` the number of postes that day.
 */
export interface JourStaffing {
  date: string;
  jour: number;
  standsOuverts: number;
  sieges: number;
  heures: number;
  /** Seats open at the same instant, at the busiest moment of the day. */
  picSimultane: number;
  /**
   * Same peak over intervals extended by the legal break between two
   * vacations — the exact minimum number of distinct animateurs the day needs.
   */
  picAvecPause: number;
  /** What the meal windows force; 0 when `coupureRepasObligatoire` is switched off. */
  picRepas: number;
  /**
   * The distinct animateurs the day provably needs: the peak above, or the
   * day's hours over the daily legal ceiling when a long, flat day demands
   * more people than its peak shows.
   */
  minimumJour: number;
  /** Known animateurs not declaring that day off. Equals the pool while nothing is declared. */
  disponibles: number;
}

/**
 * One ISO week of `GET /api/staffing`: the window the workload and rotation
 * bounds are proved inside. An event-wide aggregate proves nothing — the hours
 * of a week can only be covered by the people working that week.
 */
export interface SemaineStaffing {
  /** ISO label, e.g. `2026-W28`. */
  semaine: string;
  /** Monday of that week, so the label needs no parsing. */
  debut: string;
  /** Event days the week holds. */
  jours: number;
  /** How many of them one animateur may work — six at most (art. L3132-1). */
  joursTravaillables: number;
  heures: number;
  /** Weekly ceiling, capped by `joursTravaillables` × the daily ceiling. */
  capaciteHeuresParAnimateur: number;
  chargeTotal: number;
  /** Sum of the days' `minimumJour`: the (animateur, jour travaillé) pairs required. */
  joursPersonne: number;
  rotationTotal: number;
}

/** Which bound `GET /api/staffing` ended up retaining for `minimumTotal`. */
export type BorneStaffing =
  'PIC_SIMULTANE' | 'PIC_AVEC_PAUSE' | 'CHARGE_HORAIRE' | 'ROTATION_JOURS' | 'COUPURE_REPAS';

/**
 * A referential the edition has not filled in yet, as `GET /api/staffing`
 * names it. The seats need the first two only: without an animateur the
 * bounds are still proven, and only the comparison against a pool is missing.
 */
export type ReferentielManquant = 'STANDS' | 'CRENEAUX' | 'ANIMATEURS';

/**
 * One game category of `GET /api/staffing`: the same bounds, computed on
 * the seats that provably require it, against the animateurs who declare it.
 */
export interface TypologieStaffing {
  typologie: string;
  label: string;
  /** True for the referential's polyvalent ("ninja") typologie, if any. */
  ninja: boolean;
  sieges: number;
  heures: number;
  nombreSemaines: number;
  picSimultane: number;
  picAvecPause: number;
  /** What the meal windows force; 0 when `coupureRepasObligatoire` is switched off. */
  picRepas: number;
  chargeTotal: number;
  rotationTotal: number;
  minimumTotal: number;
  borneRetenue: BorneStaffing;
  /**
   * Animateurs declaring this typologie among their competences. Polyvalents
   * are not counted here unless they declared it: a ninja is a reinforcement,
   * never a specialist.
   */
  specialistes: number;
  /** What the pool is short of; `0` while no animateur is known at all. */
  manque: number;
}

/**
 * The bottleneck view of `GET /api/staffing`: which game category the plan is
 * short of competent animateurs on. See `StaffingAnalyzer` for the two
 * attribution rules — seats are claimed by a typologie only when their stand
 * proposes it alone, and a polyvalent counts in the typologies they declared
 * plus once in the shared reserve, never in every row.
 */
export interface CompetenceStaffing {
  parTypologie: TypologieStaffing[];
  /** Animateurs holding the ninja typologie: dispatchable anywhere, one seat at a time. */
  polyvalents: number;
  /** Seats of stands proposing **several** typologies: claimed by no row. */
  siegesNonAttribues: number;
  /**
   * Seats of stands proposing **no** typologie — the opposite case, and the
   * tightest demand there is: only a polyvalent can hold one. Folded into the
   * ninja row when the referential has one, holdable by nobody when it has not.
   */
  siegesReservesAuxPolyvalents: number;
  manqueTotal: number;
  /**
   * The part of `manqueTotal` carried by the ninja row itself. No reinforcement
   * absorbs it: that row's pool *is* the reserve.
   */
  manquePolyvalents: number;
  /** `0` means the animateur referential is still empty and nothing was compared. */
  animateursTotal: number;
  /**
   * Whether the referential marks a ninja typologie at all. Without one nobody
   * is polyvalent, and `polyvalents: 0` must read as "no such notion here"
   * rather than as a shortage of backup.
   */
  typologieNinjaDefinie: boolean;
}

/**
 * `GET /api/staffing`: the minimum number of animateurs the current stands and
 * créneaux require, computed server-side on the very seats a solve would have
 * to fill.
 */
export interface StaffingSummary {
  parJour: JourStaffing[];
  parSemaine: SemaineStaffing[];
  /** The week that set `chargeTotal` and `rotationTotal`; `null` on an empty edition. */
  semaineCritique: SemaineStaffing | null;
  picSimultane: number;
  picAvecPause: number;
  /** What the meal windows force; 0 when `coupureRepasObligatoire` is switched off. */
  picRepas: number;
  jourCritique: JourStaffing | null;
  totalDemandeHeures: number;
  nombreSemaines: number;
  /** What one animateur may work during `semaineCritique`, not an event-wide total. */
  capaciteHeuresParAnimateur: number;
  chargeTotal: number;
  /** Person-days of the busiest week over the six days one animateur may work in it. */
  rotationTotal: number;
  minimumTotal: number;
  borneRetenue: BorneStaffing;
  /**
   * `minimumTotal` corrected by the days the animateurs declared off — a
   * projection, not a bound: it assumes the people yet to be recruited will be
   * unavailable as often as the ones already known. Equals `minimumTotal`
   * while nothing is declared.
   */
  minimumAvecIndisponibilites: number;
  /** Whether anybody declared a day off at all — what tells "all free" from "unknown". */
  indisponibilitesDeclarees: boolean;
  minimumMajeurs: number;
  minimumMineurs: number;
  pauseMinimaleMinutes: number;
  dureeHebdomadaireMaxMinutes: number;
  /** Art. L3121-18: 10 h. Not configurable — the bounds read it, they do not set it. */
  dureeQuotidienneMaxMinutes: number;
  /** Art. L3132-1: six days. Same, and what the rotation bound divides by. */
  joursTravaillesMaxParSemaine: number;
  parCompetence: CompetenceStaffing;
  /** What is not entered yet, so the screen names it instead of showing a zero. */
  referentielsManquants: ReferentielManquant[];
}

/**
 * One reason an animateur is not on a seat, as `/api/banc-de-touche` returns
 * it: the name of a constraint the solver really enforces, plus the wording the
 * constraint catalogue holds for it. Nothing here is worded by the frontend —
 * that is the whole point of issue #303.
 */
export interface MotifExclusion {
  contrainte: string;
  niveau: 'HARD' | 'MEDIUM' | 'SOFT' | null;
  categorie: string | null;
  description: string | null;
}

/**
 * One line of the banc de touche, with two verdicts that are deliberately not
 * the same one:
 *
 * - `disponible` — not a single hard rule stands between this animateur and the
 *   seat, measured against that seat being empty. This is what the screen shows.
 * - `degradeLePlan` — the plan's hard score would actually get worse than it is
 *   today. Taking over from someone who already breaks a rule can break another
 *   and leave the plan no worse overall, so the two verdicts come apart.
 *
 * `disponible` implies `!degradeLePlan`, never the reverse.
 */
export interface AnimateurBanc {
  animateurId: string;
  disponible: boolean;
  degradeLePlan: boolean;
  /** Score delta the assignment would cause; null when no hypothesis was evaluated. */
  delta: HardMediumSoftScore | null;
  motifs: MotifExclusion[];
}

/**
 * Why the bench has, or has not, anything to say about a timeslot.
 *
 * The two empty answers are not the same advice, so they are not the same
 * value: `NO_PLAN` calls for a solve, `NO_SEAT` for another timeslot. The
 * screen's selector is fed by the referential, which legitimately holds more
 * timeslots than the saved plan does — a stand closed then, or a créneau added
 * after the last solve.
 */
export type StatutBanc = 'NO_PLAN' | 'NO_SEAT' | 'EVALUATED';

/**
 * One timeslot the saved plan holds a seat on — everything the bench's selector
 * needs to label it, and nothing else.
 */
export interface CreneauSiege {
  id: number;
  jour: number;
  date: string;
  heureDebut: string;
  heureFin: string;
}

/** Answer of `GET /api/banc-de-touche[/{creneauId}]`. */
export interface BancDeTouche {
  /** The timeslot actually answered on; null only when the plan staffs none. */
  creneauId: number | null;
  statut: StatutBanc;
  /** The seat every reason is relative to; null unless `statut` is `EVALUATED`. */
  posteCibleId: string | null;
  standCibleId: string | null;
  /** Its current occupant, null when the seat is free. */
  animateurCibleId: string | null;
  total: number;
  disponibles: number;
  /**
   * Every timeslot the saved plan holds a seat on, and the whole of what the
   * selector offers. The referential holds more than the plan does, and
   * offering those was how a user landed on one with nothing to show. Empty
   * means nothing is staffed at all — which is exactly `statut === 'NO_PLAN'`.
   */
  creneauxAvecSieges: CreneauSiege[];
  animateurs: AnimateurBanc[];
}

/** One row of `/api/planning/hours`: hours planned per ISO week (`AAAA-Wss`) plus the total. */
export interface HeuresAnimateur {
  animateurId: string;
  nom: string;
  heuresParSemaine: Record<string, number>;
  total: number;
  /** Hours held on a Sunday — Saturday excluded, only Sunday carries a premium (issue #597). */
  heuresDimanche: number;
  /** Hours held on a French public holiday, whatever the day of the week. */
  heuresJourFerie: number;
  /** The overlap of the two above: a Sunday that is also a holiday counts in both columns. */
  heuresDimancheFerie: number;
  /** Hours past 22:00, prorated — not the whole vacation. */
  heuresNuit: number;
}

export interface HeuresRapport {
  semaines: string[];
  animateurs: HeuresAnimateur[];
}

/**
 * One row of `/api/planning/typologies`: the persisted plan read by typologie
 * of jeu (issue #590). Coarse grain on purpose — the whole edition, never a
 * grid slot by slot.
 */
/** One animateur of a typologie's lists: the name to read, the id to link on. */
export interface AnimateurTypologie {
  animateurId: string;
  nom: string;
}

export interface LigneTypologie {
  typologie: string;
  label: string;
  ninja: boolean;
  /** The quota of issue #594; `null` when the typologie caps nothing. */
  maxCreneauxParAnimateur: number | null;
  /** The organiser's own note on the typologie; `null` when none was written. */
  description: string | null;
  /** Distinct animateurs the plan sat at this game. */
  animateursAffectes: AnimateurTypologie[];
  /** Those the referential vets on it. */
  animateursCompetents: AnimateurTypologie[];
  /** Vetted, never used — a reserve nobody drew on. */
  competentsJamaisAffectes: AnimateurTypologie[];
  /** Used without being vetted, which a stand proposing several typologies makes ordinary. */
  affectesSansCompetence: AnimateurTypologie[];
  heures: number;
  postes: number;
  /** The same hours, ISO day by ISO day; a day the typologie was not held has no entry. */
  heuresParJour: Record<string, number>;
}

export interface RapportTypologies {
  typologies: LigneTypologie[];
  /** Every day the plan holds a seat on, sorted — the heatmap's columns. */
  jours: string[];
}

/**
 * One row of `/api/planning/equite`: what one assigned animateur was given,
 * read from the persisted plan. Hours are decimal hours, rates are ratios in
 * `[0, 1]` (seats on a wished / appreciated game category over all seats).
 */
export interface LigneEquite {
  animateurId: string;
  nom: string;
  heuresTotal: number;
  heuresParSemaine: Record<string, number>;
  heuresSoiree: number;
  heuresWeekEnd: number;
  heuresJourFerie: number;
  postes: number;
  postesPenibles: number;
  standsDistincts: number;
  typologiesDistinctes: number;
  emplacementsDistinctsParJourMax: number;
  tauxSouhaits: number;
  tauxAppreciation: number;
  joursTravailles: number;
  joursRepos: number;
  plusLongueSerie: number;
}

/** Median, min, max and standard deviation of one column over the rows. */
export interface SyntheseColonne {
  mediane: number;
  min: number;
  max: number;
  ecartType: number;
}

/** A column a solver rule measures: the row's field, the rule, and whether it is switched on. */
export interface ColonneSolveur {
  colonne: string;
  contrainte: string;
  active: boolean;
}

export interface RapportEquite {
  /** The evening the rows were read under, HH:MM:SS. */
  heureDebutSoiree: string;
  semaines: string[];
  lignes: LigneEquite[];
  /** Keyed by the row's field name, the ISO weeks by their own name; empty when there is no row. */
  syntheses: Record<string, SyntheseColonne>;
  colonnesSolveur: ColonneSolveur[];
}

export interface ResetSummary {
  animateurs: number;
  stands: number;
  creneaux: number;
  postes: number;
}

export interface PersistenceStatus {
  assignments: number;
}

export interface ImportSummary {
  message: string;
}

/** Pre-import answer of `/api/reference-data/cible-scenario(-fichier)`: where the file would write. */
export interface CibleImport {
  /** null = no `edition:` section — the import writes to the caller's current edition. */
  editionId: string | null;
  editionNomFichier: string | null;
  existe: boolean;
  editionNomExistant: string | null;
}

/** Body returned by `/api/reference-data/import-scenario(-fichier)`: where the data landed. */
export interface ImportScenarioResult {
  /** Edition the scenario's `edition:` section routed the import into — null when the file named none. */
  editionId: string | null;
  editionNom: string | null;
  /** True when that edition was created by this very import; null without an `edition:` section. */
  editionCreee: boolean | null;
}

/**
 * `/api/config`, fetched once before bootstrap: the observability keys — blank
 * when the matching feature is disabled server-side — and how the server was
 * launched.
 */
export interface AppConfig {
  sentryDsn: string;
  sentryEnvironment: string;
  cloudflareWebAnalyticsToken: string;
  /** Server running under `quarkus:dev`: the Dev UI exists at `/q/dev-ui`. */
  devMode: boolean;
}

/**
 * One saved plan (issue #138). `automatique` marks the capture taken on its own
 * before a solve — those are purged beyond the last few; the ones asked for by
 * hand never are.
 */
export interface PlanSnapshot {
  id: number;
  libelle: string;
  automatique: boolean;
  score: string | null;
  nombreAffectations: number;
  creeLe: string | null;
  /** Edition the snapshot was captured in — `/comparables` spans them all (issue #70). */
  editionId: string;
  editionNom: string | null;
  /** KPI at capture time, null on snapshots taken before they were stored. */
  kpi: PlanningKpi | null;
  /**
   * Last referential change of *that snapshot's* edition (issue #170), null
   * when none is known. Per snapshot, not per current edition: the comparator
   * lists snapshots across editions.
   */
  referenceModifieLe: string | null;
  /**
   * True when the referential moved after the capture: the plan no longer
   * describes today's data. Computed server-side so the badge, the restore
   * guard and the MCP tool cannot disagree.
   */
  perime: boolean;
}

/**
 * One side of the A/B comparison (issue #70). `snapshotId` null means the side
 * is the currently persisted plan rather than a capture, and `libelle` is then
 * null too: naming that side is the UI's job, in the user's language.
 */
export interface CoteComparaison {
  snapshotId: number | null;
  libelle: string | null;
  editionId: string;
  editionNom: string | null;
  creeLe: string | null;
  kpi: PlanningKpi;
  /** The KPI were not stored and had to be recomputed: violations are unmeasured. */
  kpiRecalcule: boolean;
}

/** Violations of one constraint on each side; null = not measured, which is not zero. */
export interface DiffContrainte {
  contrainte: string;
  base: number | null;
  variante: number | null;
}

/** `GET /api/planning/snapshots/compare` — a read of measured metrics, never a solve. */
export interface ComparaisonSnapshots {
  base: CoteComparaison;
  variante: CoteComparaison;
  editionsDifferentes: boolean;
  volumetriesDifferentes: boolean;
  diffViolations: DiffContrainte[];
}

/**
 * What an incremental re-solve (issue #86) re-opens on purpose, on top of what
 * a late change already invalidated. Empty everywhere = purely automatic
 * perimeter.
 */
export interface PerimetreReplanification {
  animateurIds: string[];
  /** ISO days (`2026-07-08`). */
  jours: string[];
  standIds: string[];
}

/** How much of an incremental problem was frozen versus re-opened. */
export interface StatistiquesIncremental {
  postesTotal: number;
  postesFiges: number;
  postesLiberes: number;
  postesLiberesManuellement: number;
  postesNouveaux: number;
}

/** One stand × créneau whose crew changed, both crews spelled out by name. */
export interface ChangementAffectation {
  standId: string;
  standNom: string;
  creneauId: number;
  date: string | null;
  heureDebut: string | null;
  heureFin: string | null;
  avant: string[];
  apres: string[];
}

/**
 * The plan a solve replaced, and whether replacing it made things worse
 * (issue #274). `score` is null when it could not be established — the
 * comparison is then not shown at all rather than shown half-empty.
 */
export interface PreviousPlan {
  snapshotId: number;
  score: string | null;
  degraded: boolean;
}

/**
 * Where a full solve is asked to start from (issue #174). `AUTO` re-seeds from
 * the persisted plan when there is one and starts cold otherwise; it is the
 * default everywhere, because starting cold is what silently loses the plan
 * already reached. `AUCUN` is the cold start, by name — the one gesture that
 * can lose it, and the screen asks for confirmation.
 */
export type Reamorcage = 'AUTO' | 'PLAN_COURANT' | 'AUCUN';

/** Where a finished full solve actually started from: `AUTO` resolved into one of the two. */
export interface ReamorcageEffectue {
  mode: 'PLAN_COURANT' | 'AUCUN';
  /** Seats carrying an animateur from the persisted plan, left movable; 0 on a cold start. */
  postes: number;
  /** Seats the persisted plan staffed but that had to start empty (animateur gone, or since unavailable). */
  postesLiberes: number;
}

/**
 * How many people would have to be told if the plan just solved were published
 * now — the same count the publication screen shows, computed at the end of
 * every solve. `null` when nothing was ever published.
 */
export interface ImpactPublication {
  personnes: number;
  /** When the plan compared against was published. */
  publieLe: string;
}

/**
 * Days somebody had marked « relu et accepté » and on which this solve moved a
 * seat: their reading was withdrawn, because nobody has read what the solver
 * just wrote. A day also frozen keeps its reading and is not counted.
 */
export interface ImpactValidations {
  journees: number;
}

/** Payload of a finished full SOLVE job: a diagnostic plus the plan it replaced. */
export interface ResultatSolve {
  diagnostic: PlanningDiagnostic;
  previousPlan: PreviousPlan | null;
  /** Absent on payloads from before issue #174. */
  reamorcage?: ReamorcageEffectue | null;
  /** Absent on payloads from before the stability rule. */
  impactPublication?: ImpactPublication | null;
  /** Readings this solve invalidated; absent when the edition carries none. */
  impactValidations?: ImpactValidations | null;
  /** Set on an `INTERROMPU` job the server stopped under; absent on a finished solve. */
  interruption?: Interruption | null;
}

/** Payload of a finished incremental SOLVE job: a diagnostic plus what moved. */
export interface ResultatSolveIncremental {
  diagnostic: PlanningDiagnostic;
  statistiques: StatistiquesIncremental;
  changements: ChangementAffectation[];
  previousPlan: PreviousPlan | null;
  impactPublication?: ImpactPublication | null;
  /** Readings this solve invalidated; absent when the edition carries none. */
  impactValidations?: ImpactValidations | null;
  /** Set on an `INTERROMPU` job the server stopped under; absent on a finished solve. */
  interruption?: Interruption | null;
}

/**
 * What became of the best plan of a solve the server stopped under: kept only
 * when it scored strictly higher than the persisted plan.
 */
export interface Interruption {
  partialPlanKept: boolean;
  partialScore: string;
  /** Score of the plan that was in place; `null` when there was none. */
  persistedScore: string | null;
}

/** `POST /api/planning/snapshots/{id}/restore` on success. */
export interface RestaurationSnapshot {
  restaure: boolean;
  affectations: number;
  referencesManquantes: string[];
}

/* ----------------------- Foire au planning (issue #165) ----------------------- */

/** `/api/auth/me`: whether the browser holds a valid admin session. */
export interface StatutSession {
  authentifie: boolean;
  nom: string | null;
}

/** Whether this deployment has an MCP API key at all, and the header it travels in. Never the key. */
export interface StatutMcp {
  configuree: boolean;
  header: string;
}

/** The MCP API key, returned only in exchange for the admin password. Never stored. */
export interface CleMcp {
  cle: string;
  /** Pangolin access-proxy token id, only when PLANNING_MCP_PANGOLIN_ACCESS_TOKEN_ID is set server-side. */
  pangolinAccessTokenId: string | null;
  /** Pangolin access-proxy token, only when PLANNING_MCP_PANGOLIN_ACCESS_TOKEN is set server-side. */
  pangolinAccessToken: string | null;
}

/**
 * One prompt the MCP server announces, as `/api/mcp/prompts` serves it.
 *
 * Read from the server rather than written into the page: the page used to
 * carry its own copies of these texts, and one of them named a tool the
 * application has never exposed.
 */
export interface PromptMcp {
  /** Technical name, the one an MCP client shows in its prompt list. */
  nom: string;
  /** What the prompt is for, one sentence. */
  description: string;
  /** The prompt itself, ready to paste. Served in French, like the domain. */
  texte: string;
}

/** One of the animateur's seats, as shown in their espace. */
export interface PosteAnimateurView {
  creneauId: number;
  /** ISO date. */
  date: string | null;
  heureDebut: string | null;
  heureFin: string | null;
  standId: string;
  standNom: string;
  coequipiers: string[];
  /**
   * Where the stand is set up (issue #534), `null` when it is attached to no
   * emplacement. Read from the referential at display time, never a frozen
   * copy: a renamed hall reads renamed.
   */
  emplacementNom: string | null;
  /** `null` unless the emplacement is geocoded — both coordinates, or no map link. */
  emplacementLatitude: number | null;
  emplacementLongitude: number | null;
}

/** A colleague an échange can target. */
export interface CollegueView {
  id: string;
  nomComplet: string;
}

/** A colleague on the same stand at a break's deadline, who can take the relay. */
export interface RelaisView {
  animateurId: string;
  nomComplet: string;
}

/** One legal break a working stretch owes (`GET /api/pauses`), placed in the stand's rotation. */
export interface PauseDueView {
  /** When the break starts, `HH:mm:ss` — as late as its window allows, after the colleague's break on the stand. */
  debut: string;
  fin: string;
  /** Latest possible start, `HH:mm:ss`: the stretch reaches the legal mark then. */
  heureLimite: string;
  /** 20 for an adult, 30 for a minor. */
  dureeMinutes: number;
  standId: string;
  standNom: string;
  /** The timeslot of the seat held during the break: what opens the bench on it. Absent on an older payload. */
  creneauId?: number | null;
  relais: RelaisView[];
  /** False when nobody else is on the stand for the whole break. */
  relaisDisponible: boolean;
  /** True when the windows left no room: two people of the stand are out at once. */
  simultanee: boolean;
}

/** An uninterrupted working stretch of one animateur's day, with the breaks it owes. */
export interface SequenceView {
  debut: string;
  fin: string;
  minutes: number;
  pausesDues: PauseDueView[];
}

/** A break the grid already schedules: the gap between two stretches. */
export interface PausePlanifieeView {
  debut: string;
  fin: string;
  minutes: number;
}

/**
 * The meal break one day owes on one window, and what the plan leaves for it.
 * Listed only for the days that straddle the window — starting when it opens,
 * or stopping when it closes, owes nothing.
 */
export interface CoupureRepasView {
  /** `midi` / `soir`. */
  libelle: string;
  /** `HH:mm:ss`, the window's own bounds. */
  fenetreDebut: string;
  fenetreFin: string;
  dureeRequiseMinutes: number;
  /** `HH:mm:ss`, earliest the break can be taken; `null` when the day leaves no room. */
  debut: string | null;
  fin: string | null;
  /** Longest free stretch entirely inside the window. */
  plusGrandTrouMinutes: number;
  /** False when that stretch is too short: the day `coupureRepasObligatoire` penalises. */
  satisfaite: boolean;
}

/** One animateur on one day, as `GET /api/pauses` reads it. */
export interface JourneeAnimateurPauses {
  animateurId: string;
  nomComplet: string;
  /** The minors' figures applied: 4 h 30 and 30 minutes. */
  mineur: boolean;
  /** ISO date. */
  date: string;
  jour: number;
  sequences: SequenceView[];
  pausesPlanifiees: PausePlanifieeView[];
  coupuresRepas: CoupureRepasView[];
}

/**
 * `GET /api/pauses`: where the legal breaks of the persisted plan fall, and
 * which days owe a meal break.
 */
export interface RapportPauses {
  /** The organiser's declaration that breaks are taken on the post, as it stands today. */
  pauseSurPoste: boolean;
  journeesAnalysees: number;
  pausesDues: number;
  relaisManquants: number;
  coupuresRepasDues: number;
  /** Those the plan leaves no room for — the days `coupureRepasObligatoire` penalises. */
  coupuresRepasManquantes: number;
  /** Only the days that owe a break, list a scheduled one, or owe a meal break; by date then name. */
  journees: JourneeAnimateurPauses[];
  message: string;
}

/** One break of the animateur's own planning, read from the published plan. */
export interface PauseAnimateurView {
  /** ISO date. */
  date: string;
  /** `HH:mm:ss`: « pause de 18:20 à 18:40 ». */
  debut: string;
  fin: string;
  /** `HH:mm:ss`, latest possible start. */
  heureLimite: string;
  dureeMinutes: number;
  standId: string;
  standNom: string;
  relaisDisponible: boolean;
  /** Where the stand held during the break is set up (issue #534), `null` when it has no emplacement. */
  emplacementNom: string | null;
  emplacementLatitude: number | null;
  emplacementLongitude: number | null;
}

/** `/api/espace-animateur/{jeton}`: the espace's home payload. */
export interface EspaceAnimateurView {
  /** ISO dates of the event days without any seat for this animateur — their « Repos » days. */
  joursRepos: string[];
  /** The legal breaks their days owe, read from the same published plan as `postes`. */
  pauses: PauseAnimateurView[];
  animateurId: string;
  prenom: string;
  nom: string;
  /**
   * When the plan on display was communicated (issue #245). `null` means
   * nothing has been published yet: `postes` is then empty on purpose, and the
   * espace says so rather than showing a planning nobody announced.
   */
  publieLe: string | null;
  /** False turns the espace read-only: the foire is closed by the admin (enforced server-side too). */
  foireOuverte: boolean;
  /**
   * The day the foire opens, when it is shut only because it has not started
   * yet — `null` when it is open, or shut for good. `foireOuverte` alone is one
   * boolean for two situations that say the opposite to the person reading.
   */
  foireOuvreLe: string | null;
  /** Last day demandes are accepted, `null` when the window has no end. */
  foireFermeLe: string | null;
  postes: PosteAnimateurView[];
  collegues: CollegueView[];
  /** Where this animateur stands with the published plan (issue #293). */
  statutConfirmation: StatutConfirmation;
  /** When « j'ai lu et je serai là » was clicked, `null` while it has not been. */
  confirmeLe: string | null;
  /**
   * Credential of the permanent calendar feed
   * (`/api/abonnements/<token>/planning.ics`, issue #324) — a second token,
   * distinct from the espace one in the URL: it opens that one document and
   * nothing else, and the espace rotates it on its own.
   */
  abonnementToken: string;
  /**
   * What the last publication that concerned this animateur told them (issue
   * #532) — the very sentences their mail carried, stored at send time and
   * replayed here, in the order they were sent. Empty when they were never
   * written to, and empty on a first delivery: a planning announced whole is
   * not a list of corrections.
   */
  changements: string[];
  /**
   * When that publication left, `null` when there is nothing to show. Not
   * `publieLe`: the edition may have published again since without this
   * person's own schedule moving.
   */
  changementsLe: string | null;
  /**
   * The date a developer froze on this server (`/api/debug/date-du-jour`),
   * `null` on the real clock — always `null` where the simulated clock is not allowed. The day
   * marker then reads it in place of the phone's date, and the toolbar says so.
   */
  dateDuJourFigee: string | null;
  /** `HH:mm:ss` frozen with it, `null` while the phone's own time is the one to read. */
  heureDuJourFigee: string | null;
}

/**
 * Acknowledgement of the published planning (issue #293). `NON_VU` is the
 * state everybody starts in and the one a republication sends back the people
 * whose own schedule moved; `RELANCE` means the automatic reminder went out
 * and is still unanswered.
 */
export type StatutConfirmation = 'NON_VU' | 'CONFIRME' | 'RELANCE';

/** One animateur's acknowledgement, as the Animateurs table shows it. */
export interface ConfirmationView {
  animateurId: string;
  nomAffiche: string;
  statut: StatutConfirmation;
  /** Holds at least one seat in the published plan: the only people the question is asked of. */
  affecte: boolean;
  confirmeLe: string | null;
  relanceLe: string | null;
}

/** What the espace reads back after the click: its own new state, and nothing about anybody else. */
export interface AccuseReception {
  statut: StatutConfirmation;
  confirmeLe: string | null;
}

/**
 * The edition's answers in three numbers (`/api/animateurs/confirmations/synthese`),
 * counted among the people holding a seat in the published plan, next to the
 * date of the publication they answer. All zero and meaningless while
 * `jamaisPublie`.
 */
export interface SyntheseConfirmations {
  confirmes: number;
  relances: number;
  silencieux: number;
  dernierePublicationLe: string | null;
  jamaisPublie: boolean;
}

/** Body of « Relancer maintenant » (`POST /api/animateurs/relances`): the ids to write to. */
export interface RelanceDemande {
  animateurIds: string[];
}

/**
 * Who a manual reminder reached, by id, and who it left alone and why: the
 * one-reminder rule (`dejaRelancesPourCettePublication`) holds across the
 * night and the hand.
 */
export interface RapportRelance {
  envoyes: string[];
  dejaConfirmes: string[];
  sansEmail: string[];
  dejaRelancesPourCettePublication: string[];
  echecs: string[];
  sansPoste: string[];
}

/**
 * The foire window as the admin sets it, on the model of the collection window
 * of issue #291: an optional start and end bounding an explicit switch.
 */
export interface ConfigurationFoire {
  /** The switch, and the master: a dated window that is switched off accepts nothing. */
  foireOuverte: boolean;
  debut: string | null;
  fin: string | null;
  /** Read-only: the switch AND today's date against the bounds. */
  ouverteAujourdhui: boolean;
}

/**
 * An alert raised by one of the nightly jobs (issues #298, #299, #300).
 *
 * Server-side on purpose, unlike the rest of the Notifications page: these
 * happen at four in the morning with nobody watching, so a log kept in this
 * browser's `localStorage` would never see them.
 */
/**
 * One line of the action history, from `GET /api/historique` (issue #406).
 *
 * The table stores identifiers and field *names*, never values and never an
 * identity: `acteurNom` and `entiteNom` are resolved server-side when the list
 * is read, so a fiche deleted since leaves a line that names nobody.
 */
/**
 * What changed in the problem since a given moment: `GET
 * /api/historique/changements`. Only the actions that change what a solve
 * would be given are counted — a send or an export leaves the plan as valid as
 * it was.
 */
export interface ChangementsDonnees {
  /** How many changes since, all families together. */
  total: number;
  /** One entry per referential family touched, families untouched absent. */
  parEntite: CompteEntite[];
  /** The most recent lines, newest first, at most five. */
  dernieres: EntreeHistorique[];
}

/** How many times one referential family moved. */
export interface CompteEntite {
  entite: string;
  nombre: number;
}

export interface EntreeHistorique {
  id: number;
  survenuLe: string;
  /** Who did it, as coarsely as the application really knows. */
  acteur: 'ADMIN' | 'ANIMATEUR' | 'ANONYME' | 'ASSISTANT' | 'SYSTEME';
  acteurId: string | null;
  /** Resolved at read time; `null` outside an animateur still on the roster. */
  acteurNom: string | null;
  /** Stable code of the action, never translated. */
  action: string;
  /** What it says in French, from the server's catalogue. */
  libelle: string;
  entite: string | null;
  entiteId: string | null;
  entiteNom: string | null;
  /** Names of the fields an edit changed — never their values. */
  champs: string[];
  resultat: 'SUCCES' | 'REFUS';
  /** HTTP status when the action came from a request, `null` for a scheduled one. */
  statut: number | null;
}

/** One entry of the action inventory, from `GET /api/historique/actions`. */
export interface ActionHistorique {
  code: string;
  libelle: string;
  entite: string | null;
}

export interface AlerteView {
  /** Which job raised it: `RAPPEL_VEILLE_INJOIGNABLE`, `RELANCE_INJOIGNABLE`, `ALERTE_ECHANGE`. */
  type: string;
  cle: string;
  declencheLe: string;
  libelle: string;
  severite: 'INFO' | 'WARNING' | 'ALERTE';
  /** `null` for an alert about nobody in particular — a stale swap request, say. */
  animateurId: string | null;
  /** Resolved server-side at read time; `null` when the fiche is gone. */
  nomAffiche: string | null;
}

/**
 * What an échange would actually do for the animateur who asked. Three answers,
 * listed apart because they are not interchangeable for the person reading.
 */
export type NatureEchange =
  /** The colleague is free then: the créneau leaves your hands entirely. */
  | 'LIBERE'
  /** They work that same créneau: you swap seats and only change stand. */
  | 'CROISE'
  /** A seat on another créneau comes back — « je te laisse mon lundi, je prends ton mardi ». */
  | 'DIRIGE';

/**
 * One viable way out of a créneau, found by
 * `/api/espace-animateur/{jeton}/suggestions-echange`: the trade really holds
 * against the current planning, hard constraints included.
 */
export interface SuggestionEchangeView {
  animateurId: string;
  nomComplet: string;
  nature: NatureEchange;
  /** Set for `DIRIGE` only: the colleague's seat you would take in return. */
  creneauCibleId: number | null;
  /** ISO date of that seat. */
  dateCible: string | null;
  heureDebutCible: string | null;
  heureFinCible: string | null;
  standCibleId: string | null;
  standCibleNom: string | null;
}

/** Answer of « qui peut me remplacer ? » on one of my own seats. */
export interface SuggestionsEchangeView {
  creneauId: number;
  standId: string;
  /** Options, not colleagues: one colleague can hold several. */
  optionsEligibles: number;
  optionsEvaluees: number;
  /** The search stopped at its ceiling: the list is the best of what was tried, not everything. */
  listeTronquee: boolean;
  suggestions: SuggestionEchangeView[];
}

export type StatutDemandeEchange =
  'EN_ATTENTE_CIBLE' | 'PROPOSEE' | 'ACCEPTEE' | 'REFUSEE' | 'REFUSEE_CIBLE' | 'ANNULEE';

/** One demande d'échange with every label resolved, shared by the espace and the admin screen. */
export interface DemandeEchangeView {
  id: string;
  creneauId: number;
  date: string | null;
  heureDebut: string | null;
  heureFin: string | null;
  standId: string;
  standNom: string;
  demandeurId: string;
  demandeurNom: string;
  cibleId: string;
  cibleNom: string;
  /** Directed exchange only: the colleague's seat wanted in return — null on a plain (same-créneau) demande. */
  creneauCibleId: number | null;
  dateCible: string | null;
  heureDebutCible: string | null;
  heureFinCible: string | null;
  standCibleId: string | null;
  standCibleNom: string | null;
  motif: string | null;
  statut: StatutDemandeEchange;
  /** Hard-constraint prevalidation at submission; null while not evaluated. */
  prevalidationOk: boolean | null;
  /** Business descriptions of the hard constraints the échange would break. */
  contraintesViolees: string[];
  commentaireAdmin: string | null;
  creeLe: string;
  /** When the targeted colleague agreed or declined; null while they have not answered. */
  cibleDecideLe: string | null;
  decideLe: string | null;
  /**
   * When the publication announcing the decision left; null while the decision
   * has been taken but not yet communicated — the espace then still shows the
   * planning from before it (issue #531).
   */
  communiqueeLe: string | null;
}

/** Payload of a new demande, one entry of the submission batch. */
export interface NouvelleDemandeEchange {
  creneauId: number;
  standId: string;
  cibleId: string;
  motif: string | null;
  /** Set together, they make the exchange directed: the colleague's seat wanted in return. */
  creneauCibleId?: number | null;
  standCibleId?: string | null;
}

/**
 * `/api/{stands|animateurs|creneaux}/usages`: what deleting a selection would
 * take with it, totalled over the whole selection — the figures the delete
 * confirmation shows. It informs and never blocks: no threshold, no refusal.
 */
export interface ReferenceUsage {
  /** Filled seats of the persisted plan referencing the selection. */
  affectations: number;
  /** Ad hoc constraints (« ajustements manuels ») naming it. */
  contraintesAdHoc: number;
  /** Locks freezing it. */
  verrouillages: number;
}

/* ---------- Self-service declaration of availability (issue #291) ---------- */

/** Lifecycle of a declaration: only one is ever `EN_ATTENTE` per animateur. */
export type StatutDeclaration = 'EN_ATTENTE' | 'APPLIQUEE' | 'REFUSEE';

/** One game category, as the espace offers it to pick from. */
export interface TypologieChoixView {
  id: string;
  label: string;
}

/** One declaration seen from the espace: what I said, and what became of it. */
export interface DeclarationView {
  id: string;
  statut: StatutDeclaration;
  /** ISO dates. */
  joursIndisponibles: string[];
  souhaits: string[];
  /** The same wishes, spelled out — a category dropped since falls back to its id. */
  souhaitsLabels: string[];
  commentaire: string | null;
  commentaireAdmin: string | null;
  creeLe: string;
  decideLe: string | null;
}

/** `/api/espace-animateur/{jeton}/disponibilites`: everything the declaration tab needs. */
export interface DeclarationEspaceView {
  /** False hides the form: the closure is enforced server-side too. */
  collecteOuverte: boolean;
  collecteDebut: string | null;
  collecteFin: string | null;
  /** ISO dates of the event days — the only ones a declaration may name. */
  joursEvenement: string[];
  typologies: TypologieChoixView[];
  /** What the organisation currently holds for me: the form opens on it. */
  joursActuels: string[];
  souhaitsActuels: string[];
  /** My single pending proposal, `null` when I have none — resending replaces it. */
  enAttente: DeclarationView | null;
  historique: DeclarationView[];
}

/** Payload of a declaration sent from the espace. */
export interface NouvelleDeclaration {
  joursIndisponibles: string[];
  souhaits: string[];
  commentaire: string | null;
}

/** One declaration on the admin screen, with the animateur named and the wishes spelled out. */
export interface DeclarationAdminView {
  id: string;
  animateurId: string;
  animateurNom: string;
  statut: StatutDeclaration;
  joursIndisponibles: string[];
  souhaits: string[];
  souhaitsLabels: string[];
  commentaire: string | null;
  commentaireAdmin: string | null;
  creeLe: string;
  decideLe: string | null;
  /** What the fiche says today, so the screen can show what applying would change. */
  joursActuels: string[];
  souhaitsActuelsLabels: string[];
}

/** Who the invitation mails reached, when the admin asked for them. */
export interface InvitationReport {
  envoyes: number;
  sansEmail: string[];
  echecs: string[];
}

/** `/api/disponibilites/configuration`: the collection window, closed by default. */
export interface ConfigurationCollecte {
  collecteOuverte: boolean;
  debut: string | null;
  fin: string | null;
  /** Request only: mail every animateur their espace link now. Never echoed back. */
  prevenirAnimateurs?: boolean;
  /** Response only: `null` when no invitation was asked for. */
  invitation?: InvitationReport | null;
}

/** `/api/reference-data/impact-import`: what a scenario import would touch, for the confirmation dialog. */
export interface ImpactImport {
  animateurs: number;
  stands: number;
  postes: number;
  planningResolu: boolean;
  demandesEchange: number;
  demandesEnAttente: number;
  verrous: number;
}

/**
 * One person the next publication would write to (`/api/planning/publication`),
 * with the exact sentences they would read — the admin reviews them before
 * anything leaves.
 */
export interface DestinatairePublication {
  animateurId: string;
  nomAffiche: string;
  email: string | null;
  /** Nothing was ever published to them: their whole planning is the news. */
  premiereDiffusion: boolean;
  /** One line per moved vacation, in reading order. */
  changements: string[];
  /** Where their échange requests stand, if any. */
  demandes: string[];
}

/** `/api/planning/publication`: who is concerned, and what publishing would say. */
export interface ApercuPublication {
  /** No plan was ever published on this edition: the first one concerns everybody. */
  jamaisPublie: boolean;
  /** Nothing is persisted to publish at all. */
  planVide: boolean;
  /** A solve is running: publishing would freeze a plan about to be overwritten. */
  solveEnCours: boolean;
  dernierePublicationLe: string | null;
  nombreConcernes: number;
  /**
   * Days of the edition nobody marked « relu et accepté ». Said, never
   * enforced: publishing an unreviewed day is ordinary, doing it without
   * knowing is not.
   */
  journeesNonValidees: number;
  destinataires: DestinatairePublication[];
}

/** Outcome of a publication: display names, ready to show. */
export interface RapportPublication {
  snapshotId: number;
  publieLe: string;
  envoyes: number;
  sansEmail: string[];
  echecs: string[];
}

/** Outcome of mailing the individual plannings (`/api/planning/envoi/*`): display names, ready to show. */
export interface CompteRenduEnvoi {
  envoyes: number;
  sansEmail: string[];
  echecs: string[];
}

/** One hard constraint a simulated échange would newly violate, in business words. */
export interface ViolationDure {
  name: string;
  description: string;
  matchesSupplementaires: number;
}

/**
 * A seat moved by hand on a day view (issue #308), as the server scored — and,
 * on `POST /api/postes/{id}/deplacement`, applied — it. After the move the
 * source seat holds `animateurCibleId` (nobody when null) and `posteCibleId`,
 * when set, holds `animateurSourceId`: a drop on an empty seat moves, a drop on
 * a held seat or on a person who already works that créneau swaps, a drop on a
 * free person hands the seat over.
 */
export interface DeplacementSimulation {
  posteSourceId: string;
  posteCibleId: string | null;
  animateurSourceId: string;
  animateurCibleId: string | null;
  scoreAvant: HardMediumSoftScore;
  scoreApres: HardMediumSoftScore;
  delta: HardMediumSoftScore;
  /** True when the plan's hard score would get worse: the server refuses the write. */
  casseContrainteDure: boolean;
  nouvellesViolationsDures: ViolationDure[];
}

/** `/api/echanges/{id}/impact`: fresh simulation of a demande against the persisted planning. */
export interface EchangeSimulation {
  posteDemandeurId: string;
  posteCibleId: string | null;
  echangeCroise: boolean;
  standCibleId: string | null;
  scoreAvant: HardMediumSoftScore;
  scoreApres: HardMediumSoftScore;
  delta: HardMediumSoftScore;
  casseContrainteDure: boolean;
  nouvellesViolationsDures: ViolationDure[];
}

/**
 * Aggregate KPI of a plan (issue #89). The nullable fields are the ones that
 * depend on a stored score analysis: absent rather than invented when none
 * exists, because a zero score and an unknown score are not the same fact.
 * Deliberately non-nominative — fairness is a dispersion of hours, never a
 * ranking of named animateurs.
 */
export interface PlanningKpi {
  score: string | null;
  scoreHard: number | null;
  scoreMedium: number | null;
  scoreSoft: number | null;
  postesTotal: number;
  postesPourvus: number;
  animateursAffectes: number;
  standsDistincts: number;
  creneauxDistincts: number;
  heuresTotal: number | null;
  heuresMoyenne: number | null;
  heuresEcartType: number | null;
  heuresMin: number | null;
  heuresMax: number | null;
  heuresIncompletes: boolean;
  modificationsManuelles: number | null;
  tauxModificationsManuelles: number | null;
  dureeSolveSecondes: number | null;
  violationsParContrainte: Record<string, number>;
  /**
   * The medium score minus its floor — what a solve can actually move, the
   * figure to compare between two runs. Null when the floor was not measured
   * (a snapshot captured before it existed), like `violationsParContrainte`.
   */
  scoreMediumHorsPlancher: number | null;
  /** Constant part of the medium score, signed like it; null when not measured. */
  plancherMedium: number | null;
}

/** One row of `GET /api/kpi/historique` (issue #89) — survives its edition's deletion. */
export interface KpiHistoriqueEntry {
  id: number;
  editionId: string;
  editionNom: string | null;
  kpi: PlanningKpi;
  creeLe: string | null;
}

/** One dump present in the automatic-backup directory (`GET /api/backups`). */
export interface FichierSauvegarde {
  name: string;
  sizeBytes: number;
  createdAt: string;
}

/** Outcome of the last automatic backup, successful or not. */
export interface ExecutionSauvegarde {
  attemptedAt: string | null;
  succeeded: boolean;
  file: string | null;
  message: string | null;
}

/**
 * The automatic backup as the Paramètres screen sees it. Everything but
 * `active` is read-only here: the destination directory and the retention are
 * environment variables of the deployment, because a disk path and how many
 * copies a volume holds are decided with that volume, not from a browser.
 */
export interface EtatSauvegarde {
  configured: boolean;
  directory: string | null;
  active: boolean;
  retention: number;
  cron: string;
  zone: string;
  nextRun: string | null;
  lastRun: ExecutionSauvegarde;
  files: FichierSauvegarde[];
  directoryError: string | null;
}

/* --------------------------- Mode « jour J » ------------------------------ */

/**
 * `/api/debug/date-du-jour`: the development- and staging-only override of the server's
 * notion of today, and whether this server would accept one.
 */
export interface DateJourJView {
  /** `AAAA-MM-JJ`, or `null` when the real clock is in use. */
  dateDuJour: string | null;
  /** `HH:mm`, `null` while the wall clock gives the time — always `null` without a date. */
  heureDuJour: string | null;
  /**
   * Server under `quarkus:dev` or launched with `HORLOGE_SIMULEE_AUTORISEE=true`. Hides the field when false — the guard
   * itself is server-side, on the write endpoint.
   */
  modifiable: boolean;
}

/** One timeslot of the day still ahead of the reference time. */
export interface CreneauJourJ {
  id: number;
  date: string;
  heureDebut: string;
  heureFin: string;
  /** Started but not over: the one nobody is standing at right now. */
  enCours: boolean;
}

/** Somebody holding at least one seat over the remaining timeslots. */
export interface AnimateurAffecte {
  animateurId: string;
  nomAffiche: string;
  postesRestants: number;
  absent: boolean;
}

/** An unstaffed seat on a remaining timeslot. */
export interface PosteAPourvoir {
  posteId: string;
  standId: string;
  standNom: string;
  creneauId: number;
  heureDebut: string;
  heureFin: string;
  /** A lock covers it: the repair assistant refuses to write here until it is lifted. */
  verrouille: boolean;
}

/** One timeslot of an absence, with the trace the ad hoc exception carries. */
export interface EntreeAbsence {
  contrainteId: string;
  creneauId: number;
  heureDebut: string | null;
  heureFin: string | null;
  raison: string | null;
  creeParUtilisateurId: string | null;
  creeLe: string | null;
  /** False when the exception names several animateurs: it is not this screen's to undo. */
  annulable: boolean;
}

/** Somebody missing today, and over which timeslots. */
export interface AbsenceJourJ {
  animateurId: string;
  nomAffiche: string;
  entrees: EntreeAbsence[];
}

/** An animateur of the edition, named. */
export interface AnimateurNomme {
  animateurId: string;
  nomAffiche: string;
}

/** `/api/jour-j`: the whole event-day screen in one answer. */
export interface EtatJourJ {
  /** The journée being looked at — it starts at its first timeslot, not at midnight. */
  date: string;
  /**
   * The moment "remaining" is counted from, as the *server* reads its clock. A
   * full instant, not an hour: a journée running past midnight puts it on the
   * calendar date *after* `date`.
   */
  maintenant: string;
  creneauxDuJour: number;
  creneauxRestants: CreneauJourJ[];
  animateursDeService: AnimateurAffecte[];
  postesAPourvoir: PosteAPourvoir[];
  absences: AbsenceJourJ[];
  /**
   * The whole roster. The replacements the assistant proposes are by definition
   * *not* on duty, and they still have to be named on the button that hands
   * them a seat.
   */
  animateurs: AnimateurNomme[];
}

/** What one « marquer absent » wrote, so the screen goes straight to the holes it opened. */
export interface AbsenceMarquee {
  animateurId: string;
  nomAffiche: string;
  entrees: EntreeAbsence[];
  postesLiberes: PosteAPourvoir[];
}

/* ----------------------- Tabular import of animateurs ---------------------- */

/**
 * Which column of an uploaded CSV feeds which field of an `Animateur`, by
 * **column index**. `null` means "not in the file": an unmapped field never
 * touches an animateur who already exists, which is what makes a partial
 * catch-up file safe.
 */
export interface AnimateurCsvMapping {
  id: number | null;
  prenom: number | null;
  nom: number | null;
  dateNaissance: number | null;
  email: number | null;
  manager: number | null;
  competences: number | null;
  souhaits: number | null;
  joursIndisponibles: number | null;
}

/** What one row of the file does. */
export type ImportCsvAction = 'CREATED' | 'UPDATED' | 'REJECTED';

/** One data row of the file, as the report shows it. */
export interface ImportCsvLigne {
  /** Physical line of the file, counted from 1 — what the operator acts on. */
  line: number;
  label: string;
  animateurId: string | null;
  action: ImportCsvAction;
  reasons: string[];
  warnings: string[];
  /** The off days the fiche would carry *after* the import, merged or replaced. */
  joursIndisponibles: string[];
}

/** The same shape answers the preview and the write; `applied` tells them apart. */
export interface ImportCsvRapport {
  applied: boolean;
  columns: string[];
  mapping: AnimateurCsvMapping;
  separator: string;
  total: number;
  accepted: number;
  rejected: number;
  created: number;
  updated: number;
  deleted: number;
  rows: ImportCsvLigne[];
  warnings: string[];
}

/* ------------------ Import de la grille des stands (`/api/stands/import-grille`) ------------------ */

export type ImportGrilleAction = 'UPDATED' | 'REJECTED';

/** One column of the file: the (date, band) read, and the créneau it landed on — or why it did not. */
export interface ImportGrilleColonne {
  index: number;
  label: string;
  date: string | null;
  heureDebut: string | null;
  heureFin: string | null;
  creneauId: number | null;
  /** How many créneaux the column lands on: more than one when the grid is staggered into families. */
  creneaux: number;
  reason: string | null;
}

export interface ImportGrilleLigne {
  line: number;
  label: string;
  standId: string | null;
  action: ImportGrilleAction;
  reasons: string[];
  cellulesOuvertes: number;
  regles: number;
  exceptions: number;
  effectifMin: number | null;
  effectifMax: number | null;
}

/** What the matrix would do, stand by stand — the same shape once applied. */
export interface ImportGrilleRapport {
  applied: boolean;
  separator: string;
  columns: ImportGrilleColonne[];
  creneauxAbsents: string[];
  total: number;
  accepted: number;
  rejected: number;
  rows: ImportGrilleLigne[];
  warnings: string[];
}

export interface ImportGrilleDemande {
  fileName: string;
  content: string;
}

/** How many rows each referential would write, keyed by its export target. */
export type VolumesExportCsv = Partial<Record<ExportCsvTarget, number>>;

/** The six referentials the CSV export offers, each optional. */
export type ExportCsvTarget =
  'TYPOLOGIES' | 'EMPLACEMENTS' | 'STANDS' | 'CRENEAUX' | 'JOURNEES_TYPES' | 'ANIMATEURS';

/* ---- Referential CSV imports (typologies, emplacements, stands, grid, day templates) ---- */

/**
 * Which referential a file is read against.
 *
 * <p>`CRENEAUX` is matched on `(date, heureDebut, heureFin)` rather than on an
 * id — a timeslot has none of its own — and `JOURNEES_TYPES` on the template's
 * name, its dates merged into the calendar. Neither of the two moves the grid:
 * materialising day templates stays the « Appliquer » of their own screen.</p>
 */
export type ReferentielImportTarget =
  'TYPOLOGIES' | 'EMPLACEMENTS' | 'STANDS' | 'CRENEAUX' | 'JOURNEES_TYPES';

export type ActionImportReferentiel = 'CREE' | 'MIS_A_JOUR' | 'REFUSE';

export interface LigneImportReferentiel {
  line: number;
  id: string | null;
  libelle: string | null;
  action: ActionImportReferentiel;
  raisons: string[];
  details: string[];
}

/** What a referential CSV does, or would do — the same shape for the preview and the write. */
export interface RapportImportReferentiel {
  applied: boolean;
  cible: ReferentielImportTarget;
  columns: string[];
  separator: string;
  total: number;
  accepted: number;
  rejected: number;
  created: number;
  updated: number;
  /** Typologie ids a stand named without them existing: created, and listed so nothing is silent. */
  typologiesCreees: string[];
  rows: LigneImportReferentiel[];
}

/* ------------- Competence grid (`/api/animateurs/competences`) ------------- */

/**
 * One row of a grid save: the animateur's whole map of appreciations — a
 * typologie left out is an appreciation removed, as the fiche form does — and
 * the stamp the grid read, sent back as this row's own precondition (issue #362).
 */
export interface SaisieAnimateurCompetences {
  animateurId: string;
  modifieLe: string | null;
  competences: Record<string, NiveauCompetence>;
}

/** How one saved row ended: written, refused because the fiche moved since the read, or refused otherwise. */
export type ResultatLigneCompetences = 'WRITTEN' | 'STALE' | 'REJECTED';

export interface LigneSaisieCompetences {
  animateurId: string;
  resultat: ResultatLigneCompetences;
  message: string | null;
  /** The stamp written, or on a `STALE` row the fiche's current one. */
  modifieLe: string | null;
}

/** One line per row sent, in the order sent — the rows are independent. */
export interface RapportSaisieCompetences {
  animateurs: LigneSaisieCompetences[];
}

export type ImportCompetencesAction = 'UPDATED' | 'UNCHANGED' | 'REJECTED';

/** One column of the file: the header read, and the typologie it names — or why it names none. */
export interface ImportCompetencesColonne {
  index: number;
  label: string;
  typologieId: string | null;
  reason: string | null;
}

export interface ImportCompetencesLigne {
  line: number;
  label: string;
  animateurId: string | null;
  action: ImportCompetencesAction;
  reasons: string[];
  /** Appreciations the row adds or changes. */
  cellules: number;
}

/** What the matrix would do, animateur by animateur — the same shape once applied. */
export interface ImportCompetencesRapport {
  applied: boolean;
  separator: string;
  columns: ImportCompetencesColonne[];
  total: number;
  accepted: number;
  unchanged: number;
  rejected: number;
  rows: ImportCompetencesLigne[];
  warnings: string[];
}

export interface ImportCompetencesDemande {
  fileName: string;
  content: string;
}

/** Body of both calls — the file travels again, so the write re-validates it. */
export interface ImportCsvDemande {
  fileName: string;
  content: string;
  mapping: AnimateurCsvMapping | null;
  replaceAnimateurs: boolean;
  replaceJoursIndisponibles: boolean;
}

/** Per-edition settings of the scheduled notifications (`/api/parametres-notifications`). */
export interface ParametresNotifications {
  actives: boolean;
  /** `HH:mm` local time, from which the day-before reminder may go out. */
  heureRappelVeille: string;
  delaiRelanceHeures: number;
  ancienneteEchangeJours: number;
}

/** What `/api/reference-data/valider-scenario-fichier` says of an uploaded scenario. */
export interface ScenarioValidationResult {
  valide: boolean;
  erreurs: string[];
}

/* --------------------------- Edition status (home) --------------------------- */

/**
 * The states a line of the home checklist can be in
 * (`/api/editions/courant/etat`), from the step still ahead to the step
 * behind. `INFO` carries figures worth reading that hold nothing back — the
 * screen draws it apart from `ATTENTION`, which is what waits on a decision.
 */
export type StatutEtat = 'A_FAIRE' | 'ATTENTION' | 'INFO' | 'FAIT';

export interface EtatReferentiels {
  stands: number;
  animateurs: number;
  creneaux: number;
  statut: StatutEtat;
}

export interface EtatCollecte {
  ouverte: boolean;
  declarationsEnAttente: number;
  declarationsTraitees: number;
  statut: StatutEtat;
}

export interface EtatOuvertures {
  anomalies: number;
  /** Windows that overlap no créneau of their date: a stand said open at an hour the grid does not have. */
  fenetresSansEffet: number;
  standsJamaisOuverts: number;
  statut: StatutEtat;
}

export interface EtatBesoin {
  animateurs: number;
  minimum: number;
  manque: number;
  statut: StatutEtat;
}

export interface EtatResolution {
  resolue: boolean;
  resoluLe: string | null;
  /** `null` without an analysis of the persisted plan in memory (a restart). */
  score: string | null;
  scoreHorsPlancher: string | null;
  faisable: boolean | null;
  /** Computed server-side: reference data changed after the solve. */
  dataStale: boolean;
  solveEnCours: boolean;
  statut: StatutEtat;
}

export interface EtatProblemes {
  bloquants: number;
  avertissements: number;
  /**
   * False when no rule analysis is in memory — the store is per-process, so
   * after a restart nothing has measured the rules until a solve or a visit to
   * Contraintes. The capacity causes are counted either way.
   */
  reglesAnalysees: boolean;
  statut: StatutEtat;
}

export interface EtatPublication {
  jamaisPublie: boolean;
  dernierePublicationLe: string | null;
  personnesAPrevenir: number;
  statut: StatutEtat;
}

export interface EtatConfirmations {
  confirmes: number;
  relances: number;
  silencieux: number;
  statut: StatutEtat;
}

export interface EtatFoire {
  ouverte: boolean;
  demandesEnAttente: number;
  statut: StatutEtat;
}

/** How far the « relu et accepté » of the edition has got. */
export interface EtatRelecture {
  /** Days the timeslots span; zero before the grid exists. */
  journees: number;
  journeesValidees: number;
  statut: StatutEtat;
}

/**
 * Where the current edition stands in its cycle — the checklist of the home
 * screen, one block per step, computed server-side in one call. Counts and
 * dates only: no name ever travels here.
 */
export interface EtatEdition {
  editionId: string;
  editionNom: string;
  referentiels: EtatReferentiels;
  collecte: EtatCollecte;
  ouvertures: EtatOuvertures;
  besoin: EtatBesoin;
  resolution: EtatResolution;
  problemes: EtatProblemes;
  relecture: EtatRelecture;
  publication: EtatPublication;
  confirmations: EtatConfirmations;
  foire: EtatFoire;
}

/**
 * One day marked « relu et accepté ». A review
 * mark, not a lock: it freezes nothing, and the panel offers the lock beside it
 * without ever implying it.
 */
export interface ValidationJournee {
  id: string;
  /** `AAAA-MM-JJ`. */
  jour: string;
  valideLe: string;
  /** The admin account the reading was written under; `null` when there was none. */
  validePar: string | null;
  commentaire: string | null;
}

/** What the panel posts to accept a day. */
export interface DemandeValidationJournee {
  jour: string;
  commentaire?: string | null;
  /** Lay a day lock down at the same time. Never implied by the acceptance. */
  poserVerrou?: boolean;
}

/** A reading, and whether the lock it asked for was actually laid down. */
export interface ResultatValidationJournee {
  validation: ValidationJournee;
  verrouPose: boolean;
}

/** The codes of the four prerequisites, as the server names them. */
export type CodePrerequis =
  'ECARTS_DURS' | 'SIEGES_VIDES' | 'PAUSES_NON_RELAYEES' | 'POSTES_IRREMPLACABLES';

/** One prerequisite of a day, as a figure — the wording is the screen's. */
export interface PrerequisValidation {
  code: CodePrerequis;
  /** False when no analysis is available: « non vérifié », never « satisfait ». */
  connu: boolean;
  satisfait: boolean;
  nombre: number;
}

/** What to check before accepting one day. */
export interface PrerequisJournee {
  jour: string;
  validee: boolean;
  /** The reading that stands, so the panel withdraws exactly the one it shows. */
  validationId: string | null;
  valideeLe: string | null;
  validePar: string | null;
  commentaire: string | null;
  prerequis: PrerequisValidation[];
  tousSatisfaits: boolean;
}

/** How far the reading has got — « 3 journées sur 12 validées ». */
export interface ProgressionValidations {
  journees: number;
  journeesValidees: number;
  /** The accepted days themselves, `AAAA-MM-JJ`, ascending. */
  joursValides: string[];
}
