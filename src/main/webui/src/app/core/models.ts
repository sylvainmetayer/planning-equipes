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
  creneau: { id: string } | null;
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
 */
export interface PlanningDiagnostic {
  score: string;
  postesNonPourvus: number;
  contraintes: ConstraintDiagnostic[];
  faisabilite: FeasibilityReport | null;
}

export interface ConstraintView {
  name: string;
  niveau: NiveauContrainte;
  categorie: string;
  description: string;
  actif: boolean;
  score: string | null;
  matchCount: number | null;
}

export interface ConstraintsView {
  analysedAt: string | null;
  scoreGlobal: string | null;
  postesNonPourvus: number | null;
  faisabilite: FeasibilityReport | null;
  contraintes: ConstraintView[];
}

/**
 * `/api/parametres-legaux`: admin-configurable legal parameters, consumed by
 * the `dureeHebdomadaireMax` hard constraint. Default is 48h (2880 min), the
 * weekly working-time ceiling set by the Code du travail (art. L3121-20) and
 * the Convention collective nationale de l'Animation (ÉCLAT, IDCC 1518).
 */
export interface ParametresLegaux {
  dureeHebdomadaireMaxMinutes: number;
}

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
