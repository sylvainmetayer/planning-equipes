// TypeScript mirror of the JSON exposed by the Quarkus API.
// Domain names stay in the French business vocabulary of the backend.

export type NiveauCompetence = 'DEBUTANT' | 'AUTONOME' | 'REFERENT';
export type TypeContrainteAdHoc = 'INDISPONIBILITE_FORCEE' | 'INCOMPATIBILITE' | 'AFFECTATION_FORCEE';
export type NiveauContrainte = 'HARD' | 'MEDIUM' | 'SOFT';

/** `/api/typologies` items: the enum id plus a display label. */
export interface TypologieItem {
  id: string;
  label: string;
}

export interface Animateur {
  id: string;
  prenom: string;
  nom: string;
  dateNaissance: string | null;
  /** Manages other animateurs; every animateur (manager or not) is paid. */
  manager: boolean;
  competences: Record<string, NiveauCompetence>;
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
  /** Physical location (kiosque, mairie, ...), nullable. */
  emplacement: Emplacement | null;
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
  /** Empty = every stand is open on this timeslot (the default). */
  standsOuvertsIds: string[];
  /** Planning ("groupe de créneaux") this slot belongs to. */
  groupe: GroupeCreneau | null;
}

/**
 * Named set of timeslots (a "planning"), so an alternate schedule can be
 * prepared ahead of time and activated on short notice (`/api/groupes-creneaux`).
 * Exactly one group is active; the solver only uses the active group's créneaux.
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

/** The créneau where the animateur shortfall is worst, when infeasible. */
export interface CreneauManque {
  creneauId: string;
  date: string | null;
  heureDebut: string | null;
  heureFin: string | null;
  manque: number;
}

/**
 * Plain-language capacity check: is there even a theoretical chance to fill
 * every seat, regardless of solver time? `message` is ready to show as-is to
 * a non-technical user.
 */
export interface FeasibilityReport {
  feasible: boolean;
  manqueAnimateurs: number;
  creneauLePlusCritique: CreneauManque | null;
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

export type StrategieCouverturePendantPause = 'FERMETURE' | 'RELEVE';

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
