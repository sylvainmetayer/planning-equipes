// TypeScript mirror of the JSON exposed by the Quarkus API.
// Domain names stay in the French business vocabulary of the backend.

export type NiveauCompetence = 'DEBUTANT' | 'AUTONOME' | 'REFERENT';
export type NiveauEffort = 'NORMAL' | 'EPUISANT';
export type TypeContrainteAdHoc = 'INDISPONIBILITE_FORCEE' | 'INCOMPATIBILITE' | 'AFFECTATION_FORCEE';
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
}

export interface Animateur {
  id: string;
  prenom: string;
  nom: string;
  dateNaissance: string | null;
  /** Manages other animateurs; every animateur (manager or not) is paid. */
  manager: boolean;
  /** Administrator's appreciation, after formation — shown as "Appréciation" in the UI. */
  competences: Record<string, NiveauCompetence>;
  /** Stand typologies the animateur wishes to be assigned to — unordered, no priority. */
  souhaits: string[];
  joursIndisponibles: string[];
}

export interface Stand {
  id: string;
  nom: string;
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
   * of one dated window per festival day. A dated entry above always wins over
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
}

/** `OUVERTURE` = open only on the listed windows; `FERMETURE` = closed only on them. */
export type ModeHoraire = 'OUVERTURE' | 'FERMETURE';

/**
 * Which days a `HoraireStand` applies to. Also its *specificity* order, least
 * specific first: when two rules cover the same day, the most specific one wins.
 */
export type TypeJoursHoraire = 'TOUS' | 'JOURS_SEMAINE' | 'PLAGE' | 'DATES';

export type JourSemaine =
  | 'MONDAY'
  | 'TUESDAY'
  | 'WEDNESDAY'
  | 'THURSDAY'
  | 'FRIDAY'
  | 'SATURDAY'
  | 'SUNDAY';

/**
 * A recurring opening/closing rule of a stand: windows plus the days they apply
 * to. One rule replaces as many dated windows as there are festival days it
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
  | 'STAND_JAMAIS_OUVERT'
  | 'FENETRE_SANS_EFFET'
  | 'SEGMENT_TROP_COURT';

/** One festival day, and the amplitude its column's cells are measured against. */
export interface JourAmplitude {
  date: string;
  jour: number;
  heureDebut: string;
  heureFin: string;
  minutes: number;
  nombreCreneaux: number;
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
}

export interface LigneStandOuverture {
  standId: string;
  nom: string;
  effectifMin: number;
  jours: CelluleJourOuverture[];
  minutesOuvertes: number;
  postes: number;
}

export interface AnomalieOuverture {
  type: TypeAnomalieOuverture;
  standId: string;
  standNom: string;
  date: string | null;
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

/** Editable GPS-located place a stand can be tied to (`/api/emplacements`). */
export interface Emplacement {
  id: string;
  nom: string;
  latitude: number | null;
  longitude: number | null;
}

export interface Creneau {
  id: number;
  jour: number;
  date: string;
  heureDebut: string;
  heureFin: string;
  /** Planning ("groupe de créneaux") this slot belongs to. */
  groupe: GroupeCreneau | null;
  /**
   * True when this slot is the short vacation covering a stand's meal pause,
   * generated by the découpage under the `EFFECTIF_REDUIT` strategy: the
   * stands are then deliberately staffed at half their usual headcount, which
   * the calendars flag as an indication rather than as a shortfall.
   * Absent on the payloads that predate the flag, hence optional.
   */
  couverturePause?: boolean;
}

/**
 * A whole edition of the festival — "Année 2025", "Année 2026" — and the scope
 * every piece of reference data belongs to (`/api/editions`). Not to be confused
 * with `GroupeCreneau`, which is one alternative slicing of the days *inside*
 * one `Edition`. `defaut` is not "the current one": that is this browser's own
 * choice, sent as `X-Edition-Id`; `defaut` is the server's fallback when no
 * edition is designated. See docs/editions.md.
 */
export interface Edition {
  id: string;
  nom: string;
  defaut: boolean;
  creeLe: string | null;
}

/**
 * Named set of timeslots (a "planning"), so an alternate schedule can be
 * prepared ahead of time and activated on short notice (`/api/groupes-creneaux`).
 * Exactly one group is active per `Edition`; the solver only uses the active
 * group's créneaux.
 */
export interface GroupeCreneau {
  id: string;
  nom: string;
  actif: boolean;
  /** Id of the "amplitudes" group this group's vacations were auto-generated from, or `null`. */
  groupeSourceId?: string | null;
}

/**
 * Which groupe de créneaux the last persisted solve (`/api/planning/persisted`)
 * was computed for, and when. `solved` is `false` when nothing has ever been
 * solved; `groupeCreneauId`/`groupeCreneauNom` can still be `null` even when
 * `solved` is `true` if that group was since deleted. `derniereModificationDonnees`
 * is when reference data was last edited (`null` if never, or since server start).
 */
export interface PlanningResolution {
  solved: boolean;
  groupeCreneauId: string | null;
  groupeCreneauNom: string | null;
  resoluLe: string | null;
  derniereModificationDonnees: string | null;
}

/** One seat to fill: a stand on a timeslot, with its animator once solved. */
export interface PosteAffectation {
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
  animateursConcernes: { id: string }[];
  /** Only `id` is populated by the backend; look up `Creneau` details from the reference store if needed. */
  creneau: { id: number } | null;
  stand: { id: string } | null;
  raison: string;
  creeParUtilisateurId?: string;
  creeLe?: string;
}

/** What a {@link VerrouillagePlanning} freezes (issue #87). */
export type TypeVerrouillage = 'ANIMATEUR' | 'STAND' | 'JOUR' | 'CRENEAU';

/**
 * A validated part of the planning the solver must not touch again
 * (`/api/verrouillages`). Exactly one target field is set, matching `type`,
 * and the lock only applies to its own `groupeCreneauId`.
 */
export interface VerrouillagePlanning {
  id: string;
  type: TypeVerrouillage;
  groupeCreneauId: string;
  animateurId: string | null;
  standId: string | null;
  creneauId: number | null;
  /** ISO date, for a `JOUR` lock. */
  jour: string | null;
  raison: string | null;
  creeLe?: string;
}

/**
 * Real scale of the problem the next solve will build, from `/api/planning/volumetrie`
 * (mirrors what Timefold's own "Problem scale" log line reports): `posteCount` is one
 * entry per required seat, not per stand, and `contrainteAdHocCount` are the extra
 * ad hoc rules layered on top.
 */
export interface Volumetrie {
  animateurCount: number;
  posteCount: number;
  contrainteAdHocCount: number;
}

export interface HardMediumSoftScore {
  hardScore: number;
  mediumScore: number;
  softScore: number;
}

export interface PlanningFestival {
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
 */
export type TypeCauseInfaisabilite = 'CRENEAU_SOUS_EFFECTIF';

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
  /** Always at least one stand. */
  standIds: string[];
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
  message: string;
}

/**
 * Payload of a SOLVE or ANALYZE job: score, unfilled seats and feasibility.
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
}

/**
 * One constraint's impact on a single poste, from `/api/postes/{id}/explication`
 * or `/api/postes/{id}/simulation-swap` — either a violation it is party to,
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
 * `/api/postes/{posteId}/simulation-swap?animateurId=…`: score impact of
 * giving that poste to a different animateur, without changing anything.
 * `delta` is `scoreApres - scoreAvant`: positive/less-negative means the
 * swap would improve the score.
 */
export interface SwapSimulation {
  posteId: string;
  animateurActuelId: string | null;
  animateurCandidatId: string;
  scoreAvant: HardMediumSoftScore;
  scoreApres: HardMediumSoftScore;
  delta: HardMediumSoftScore;
  contraintesVioleesAvant: ContrainteImpact[];
  contraintesVioleesApres: ContrainteImpact[];
}

export interface ConstraintView {
  name: string;
  niveau: NiveauContrainte;
  categorie: string;
  description: string;
  actif: boolean;
  score: string | null;
  matchCount: number | null;
  /** One human-readable line per match, only populated for HARD constraints. */
  violations: string[];
}

export interface ConstraintsView {
  analysedAt: string | null;
  scoreGlobal: string | null;
  postesNonPourvus: number | null;
  faisabilite: FeasibilityReport | null;
  /** Hard score of the last analysed solve; see {@link PlanningDiagnostic.hardScore}. */
  hardScore: number | null;
  contraintes: ConstraintView[];
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
}

export type StrategieCouverturePendantPause = 'FERMETURE' | 'RELEVE' | 'EFFECTIF_REDUIT';

/**
 * `/api/parametres-decoupage`: generation-time parameters consumed by
 * `VacationGeneratorService` to split a day-long amplitude into shorter,
 * overlapping work vacations. Never seen by the solver.
 */
export interface ParametresDecoupage {
  dureeVacationCibleMinutes: number;
  dureeVacationMinMinutes: number;
  dureeVacationMaxMinutes: number;
  dureeChevauchementMinutes: number;
  dureePauseRepasMinutes: number;
  fenetreRepasMidiDebut: string;
  fenetreRepasMidiFin: string;
  fenetreRepasSoirDebut: string;
  fenetreRepasSoirFin: string;
  strategieCouverturePendantPause: StrategieCouverturePendantPause;
  /** Nombre de grilles de relais décalées (1 = désactivé, comportement historique inchangé). */
  nombreFamillesDecalage: number;
  /** Étalement (min) des coupures internes des familles autour de la cible ; ignoré si `nombreFamillesDecalage` ≤ 1. */
  dureeDecalageMaxMinutes: number;
}

/**
 * `/api/parametres-solveur`: the solver's default termination duration, set
 * from the Débogage tab. Persisted server-side (not localStorage) so every
 * browser reads and writes the same value.
 */
export interface ParametresSolveur {
  dureeResolutionSecondes: number;
}

export interface DecoupageRequest {
  groupeSourceId: string;
  groupeCibleId: string;
  nomGroupeCible: string;
  activerGroupeCible: boolean;
}

/** Ordre public ceiling for adults, in hours (Code du travail art. L3121-20). */
export const DUREE_HEBDOMADAIRE_MAX_HEURES = 48;

/** Ordre public ceiling for minors, in hours (Code du travail art. L3162-1). */
export const DUREE_HEBDOMADAIRE_MAX_MINEUR_HEURES = 35;

export type JobType = 'SOLVE' | 'ANALYZE';
export type JobStatus = 'PENDING' | 'RUNNING' | 'COMPLETED' | 'FAILED' | 'CANCELLED';

/** `/api/jobs/...` view: the server owns the solver state, elapsed included. */
export interface JobView {
  id: string;
  type: JobType;
  status: JobStatus;
  secondsLimit: number | null;
  submittedAt: string;
  startedAt: string | null;
  finishedAt: string | null;
  elapsedSeconds: number;
  error: string | null;
  result: unknown;
}

/**
 * One festival day of `GET /api/staffing`: what its generated seats demand.
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
}

/** Which bound `GET /api/staffing` ended up retaining for `minimumTotal`. */
export type BorneStaffing = 'PIC_SIMULTANE' | 'PIC_AVEC_PAUSE' | 'CHARGE_HORAIRE';

/**
 * `GET /api/staffing`: the minimum number of animateurs the current stands and
 * créneaux require, computed server-side on the very seats a solve would have
 * to fill.
 */
export interface StaffingSummary {
  parJour: JourStaffing[];
  picSimultane: number;
  picAvecPause: number;
  jourCritique: JourStaffing | null;
  totalDemandeHeures: number;
  nombreSemaines: number;
  capaciteHeuresParAnimateur: number;
  chargeTotal: number;
  minimumTotal: number;
  borneRetenue: BorneStaffing;
  minimumMajeurs: number;
  minimumMineurs: number;
  pauseMinimaleMinutes: number;
  dureeHebdomadaireMaxMinutes: number;
}

/** One row of `/api/planning/hours`: hours planned per ISO week (`AAAA-Wss`) plus the total. */
export interface HeuresAnimateur {
  animateurId: string;
  nom: string;
  heuresParSemaine: Record<string, number>;
  total: number;
}

export interface HeuresRapport {
  semaines: string[];
  animateurs: HeuresAnimateur[];
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

/**
 * Body returned by `/api/reference-data/import-scenario` and
 * `.../import-scenario-fichier` when the scenario carried a `decoupageAuto:`
 * section — `null`/absent otherwise, in which case the import ran plain.
 */
export interface ImportScenarioResult {
  decoupageAutoGroupeCibleNom: string | null;
}

/** `/api/config`: observability keys, blank when the matching feature is disabled server-side. */
export interface ObservabilityConfig {
  sentryDsn: string;
  sentryEnvironment: string;
  posthogApiKey: string;
  posthogHost: string;
  cloudflareWebAnalyticsToken: string;
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
  groupeCreneauId: string | null;
  groupeNom: string | null;
  score: string | null;
  nombreAffectations: number;
  creeLe: string | null;
}

/** `POST /api/planning/snapshots/{id}/restore` on success. */
export interface RestaurationSnapshot {
  restaure: boolean;
  affectations: number;
  referencesManquantes: string[];
}
