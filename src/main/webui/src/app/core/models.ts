// TypeScript mirror of the JSON exposed by the Quarkus API.
// Domain names stay in the French business vocabulary of the backend.

export type StatutAnimateur = 'BENEVOLE' | 'SALARIE';
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
  statut: StatutAnimateur;
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
}

export interface Creneau {
  id: string;
  jour: number;
  date: string;
  heureDebut: string;
  heureFin: string;
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

/** Payload of an ANALYZE job. */
export interface PlanningDiagnostic {
  score: string;
  postesNonPourvus: number;
  contraintes: ConstraintDiagnostic[];
  planning: PlanningFestival | null;
}

export interface ConstraintView {
  name: string;
  niveau: NiveauContrainte;
  categorie: string;
  description: string;
  score: string | null;
  matchCount: number | null;
}

export interface ConstraintsView {
  analysedAt: string | null;
  scoreGlobal: string | null;
  postesNonPourvus: number | null;
  contraintes: ConstraintView[];
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
