// TypeScript mirror of the JSON exposed by the Quarkus API.
// Domain names stay in the French business vocabulary of the backend.

export type NiveauCompetence = 'DEBUTANT' | 'AUTONOME' | 'REFERENT';
export type NiveauEffort = 'NORMAL' | 'EPUISANT';
export type TypeContrainteAdHoc = 'INDISPONIBILITE_FORCEE' | 'INCOMPATIBILITE' | 'AFFECTATION_FORCEE' | 'AFFINITE';
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
  /** Contact address for the échange notifications (issue #165); null when not collected. */
  email?: string | null;
  /** Access token of the espace animateur — the link printed on their PDF planning. Read-only: rotated via `/api/animateurs/{id}/token`. */
  accessToken?: string | null;
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

/** One event day, and the amplitude its column's cells are measured against. */
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
  /**
   * Stagger family this generated vacation belongs to (0-based). The
   * découpage slices each amplitude once per family with offset relay cuts,
   * and each stand is assigned exactly one family — so a group generated with
   * N families holds N same-looking variants of every slot. Displaying it is
   * what tells those variants apart from accidental duplicates.
   */
  famille?: number;
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

export interface PlanningEvenement {
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
export type TypeCauseInfaisabilite = 'CRENEAU_SOUS_EFFECTIF' | 'CONTRAINTES_AD_HOC_CONTRADICTOIRES';

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
  contraintesAdHocEnCause: ContributionAdHoc[];
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
 * candidate that leaves the plan's hard score no worse, so
 * `violationsIntroduites` never holds a `HARD` one.
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

export interface ConstraintView {
  name: string;
  niveau: NiveauContrainte;
  categorie: string;
  description: string;
  actif: boolean;
  /**
   * The rule founds the plan in law (« Légal (…) ») or in the minors' safety
   * policy (« Sécurité (mineurs) »). Switching one off lets the solver return
   * a plan scoring zero hard that still breaks the Code du travail, so the UI
   * confirms first — see `LegalDisableDialog`.
   */
  protegee: boolean;
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
}

/** Ordre public ceiling for adults, in hours (Code du travail art. L3121-20). */
export const DUREE_HEBDOMADAIRE_MAX_HEURES = 48;

/** Ordre public ceiling for minors, in hours (Code du travail art. L3162-1). */
export const DUREE_HEBDOMADAIRE_MAX_MINEUR_HEURES = 35;

export type JobType = 'SOLVE' | 'SOLVE_INCREMENTAL' | 'ANALYZE';
/**
 * `QUEUED` waits for the solver without holding it; `PENDING` already holds it.
 * `INTERROMPU` is a job the server was running when it stopped: terminal, since
 * nothing will ever finish it (see SolverJobService.restaurer on the backend).
 */
export type JobStatus =
  | 'PENDING'
  | 'QUEUED'
  | 'RUNNING'
  | 'COMPLETED'
  | 'FAILED'
  | 'CANCELLED'
  | 'INTERROMPU';

/** `/api/jobs/...` view: the server owns the solver state, elapsed included. */
export interface JobView {
  id: string;
  type: JobType;
  status: JobStatus;
  /** Edition the job was submitted for — the one its result is written to. */
  editionId: string | null;
  /** Display name of that edition, resolved server-side at submit time. */
  editionNom: string | null;
  secondsLimit: number | null;
  submittedAt: string;
  startedAt: string | null;
  finishedAt: string | null;
  elapsedSeconds: number;
  error: string | null;
  result: unknown;
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
/** Pre-import answer of `/api/reference-data/cible-scenario(-fichier)`: where the file would write. */
export interface CibleImport {
  /** null = no `edition:` section — the import writes to the caller's current edition. */
  editionId: string | null;
  editionNomFichier: string | null;
  existe: boolean;
  editionNomExistant: string | null;
}

export interface ImportScenarioResult {
  decoupageAuto: boolean;
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

/** Payload of a finished incremental SOLVE job: a diagnostic plus what moved. */
export interface ResultatSolveIncremental {
  diagnostic: PlanningDiagnostic;
  statistiques: StatistiquesIncremental;
  changements: ChangementAffectation[];
}

/** `POST /api/planning/snapshots/{id}/restore` on success. */
export interface RestaurationSnapshot {
  restaure: boolean;
  affectations: number;
  referencesManquantes: string[];
}

/** Mutations sent to `POST /api/what-if` (issue #73). Nothing is persisted. */
export interface MutationsWhatIf {
  animateursAjoutes: number;
  animateursRetires: string[];
  standsFermes: string[];
  effectifsMin: Record<string, number>;
}

/** Answer of `POST /api/what-if`: the variant next to today's referential. */
export interface ResultatWhatIf {
  animateurs: number;
  animateursReference: number;
  standsOuverts: number;
  standsOuvertsReference: number;
  creneaux: number;
  reference: FeasibilityReport;
  simulation: FeasibilityReport;
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
}

/** A colleague an échange can target. */
export interface CollegueView {
  id: string;
  nomComplet: string;
}

/** `/api/espace-animateur/{jeton}`: the espace's home payload. */
export interface EspaceAnimateurView {
  /** ISO dates of the event days without any seat for this animateur — their « Repos » days. */
  joursRepos: string[];
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
  postes: PosteAnimateurView[];
  collegues: CollegueView[];
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
  | 'EN_ATTENTE_CIBLE'
  | 'PROPOSEE'
  | 'ACCEPTEE'
  | 'REFUSEE'
  | 'REFUSEE_CIBLE'
  | 'ANNULEE';

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
}

/** One row of `GET /api/kpi/historique` (issue #89) — survives its edition's deletion. */
export interface KpiHistoriqueEntry {
  id: number;
  editionId: string;
  editionNom: string | null;
  kpi: PlanningKpi;
  creeLe: string | null;
}
