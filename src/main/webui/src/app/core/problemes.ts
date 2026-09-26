// Merges the two independent sources of "what is wrong with this planning"
// into a single list ranked by severity, shared by the Problèmes page and the
// summary banner of the Solveur page.
//
// The two sources answer different questions and neither subsumes the other:
// - `FeasibilityReport.causes` (`GET /api/feasibility`) is a pre-solve capacity
//   check, available at any time — even before a solve has ever run;
// - `ConstraintsView.contraintes` (`GET /api/constraints`) is the diagnostic of
//   the last analysed solve, so it only exists after one.
//
// Every function here is pure and called at runtime (never at module scope), so
// the `$localize` labels resolve after `main.ts` has loaded the translations.

import { importanceAtMinimum } from './importance';
import {
  ActionType,
  CauseInfaisabilite,
  CellulePivot,
  ConstraintView,
  ContributionAdHoc,
  Creneau,
  Hotspot,
  ViolationReference,
  FeasibilityReport,
  NiveauContrainte,
  GroupDayView,
  GroupedArrivalReport,
  WalkSequenceReport,
  RapportPauses,
  TypeCauseInfaisabilite,
} from './models';
import { LabelIndex, labelOf, labelsOf } from './reference-labels';
import { compareCodeUnits } from './string-order';
import { formatHeure } from './time-of-day';
import { walkLabel } from './walks-index';

/** Display severity of the merged list, from the most to the least blocking. */
export type NiveauProbleme = 'BLOQUANT' | 'AVERTISSEMENT' | 'MINEUR';

export type SourceProbleme = 'FAISABILITE' | 'CONTRAINTE' | 'PAUSES' | 'TRAJETS' | 'COVOITURAGE';

export interface Probleme {
  /** Stable within one merge, used as the `@for` track key. */
  id: string;
  niveau: NiveauProbleme;
  source: SourceProbleme;
  /** Short qualification of the problem (cause type or constraint name). */
  titre: string;
  message: string;
  /** Concrete entities involved: créneau, stands, or one line per violation. */
  details: string[];
  /**
   * Where to go to act on the problem. A cause names a créneau and stands, and
   * the answer is almost always to edit one of them: without the link the user
   * has to memorise an id and go hunting for it in another screen.
   */
  liens: LienProbleme[];
  /** The matches of a rule in default, each with the fiche it names — read by the Problèmes page next to `details`. */
  references: LinkedViolation[];
  /**
   * « Que faire ? » — the gestures that solve it, in the catalogue's order.
   * Each one is a navigation to the screen that makes the gesture, positioned
   * on the problem's object — but « Qui peut tenir ce siège ? », which the
   * Diagnostic asks in place when it names a timeslot (see `seat`).
   */
  actions: ActionProbleme[];
  /**
   * Où — the places the problem bites hardest, three at most, each opening
   * the Journée on that stand and that day, its Siège panel on the timeslot.
   */
  ou: LienProbleme[];
  /** Qui — the people it names most, three at most, each opening their fiche. */
  qui: LienProbleme[];
  /** How many more people the problem names beyond `qui`. */
  quiRestants: number;
  /** The rule in default, by name, when the problem is one: the pivot below the cards filters on it. */
  regle: string | null;
}

/** The seat a « Qui peut tenir ce siège ? » is asked about: a timeslot, and its stand when the problem names one. */
export interface SeatTarget {
  creneauId: number;
  standId: string | null;
}

/** One action of the playbook, ready for a `routerLink`. */
export interface ActionProbleme {
  code: string;
  libelle: string;
  explication: string;
  route: string;
  queryParams: Record<string, string>;
  /**
   * `'fr'` on an action the server wrote — its words are French whatever the
   * language of the screen, and a screen reader must pronounce them so —;
   * null on one written here, translated like the rest of the page.
   */
  lang: 'fr' | null;
  /**
   * Set on « Qui peut tenir ce siège ? » when it names a timeslot: the
   * Diagnostic asks the question in place, on a free seat of it, instead of
   * leaving for the Journée.
   */
  seat?: SeatTarget;
}

/** The bench's action code: the one gesture the Diagnostic makes in place. */
export const CODE_BANC = 'VOIR_BANC';

/** The lowering of a rule's weight: always last, and hidden once the weight is at its floor. */
export const CODE_BAISSER_POIDS = 'BAISSER_POIDS';

/** A server action as the page links it. */
export function actionOf(action: ActionType): ActionProbleme {
  const link: ActionProbleme = {
    code: action.code,
    libelle: action.libelle,
    explication: action.explication,
    route: action.route,
    queryParams: { ...action.parametres },
    lang: 'fr',
  };
  const creneauId = Number(action.parametres['creneau']);
  if (action.code === CODE_BANC && Number.isInteger(creneauId) && creneauId > 0) {
    link.seat = { creneauId, standId: action.parametres['stand'] ?? null };
  }
  return link;
}

/**
 * The actions of a rule in default. The server positions them on the rule's
 * first breach — its timeslot for the bench, its day and stand for the
 * repair and the openings — and they are kept as sent; what it cannot know is
 * which hand-entered exceptions the rule failed on, and those are added here.
 * A rule whose matches name nothing — an aggregate like the balance of the
 * workload — keeps its screens bare.
 *
 * « Baisser l'importance » stays last whatever is added here, and goes once
 * the weight is already at its floor: a button that can lower nothing is a
 * button that lies.
 */
export function actionsOfRule(
  contrainte: ConstraintView,
  enCause: ContributionAdHoc[],
): ActionProbleme[] {
  const actions = (contrainte.actions ?? []).map((action) => {
    const link = actionOf(action);
    if (link.code === 'REVOIR_AJUSTEMENTS' && enCause.length > 0) {
      link.queryParams = {
        ...link.queryParams,
        ids: enCause.map((contribution) => contribution.contrainteId).join(','),
      };
    }
    return link;
  });
  if (enCause.length > 0 && !actions.some((action) => action.code === 'REVOIR_AJUSTEMENTS')) {
    actions.push({
      code: 'REVOIR_AJUSTEMENTS',
      libelle: $localize`:@@problemes.action.revoirAjustements:Revoir l'ajustement`,
      explication: $localize`:@@problemes.action.revoirAjustements.explication:La règle a buté sur des ajustements écrits à la main : revoyez-les ou supprimez-les.`,
      route: '/consignes-solveur',
      queryParams: {
        onglet: 'ajustements',
        ids: enCause.map((contribution) => contribution.contrainteId).join(','),
      },
      lang: null,
    });
  }
  const gestures = actions.filter((action) => action.code !== CODE_BAISSER_POIDS);
  const weight = actions.find((action) => action.code === CODE_BAISSER_POIDS);
  return weight && !importanceAtMinimum(contrainte) ? [...gestures, weight] : gestures;
}

/** Où and Qui show three each: past that, the pivot under the cards says where the rest is. */
const MAX_WHERE_WHO = 3;

/** What names a place on screen, beside the stands' names: the hours of a timeslot. */
export type TimeslotIndex = ReadonlyMap<number, Pick<Creneau, 'date' | 'heureDebut' | 'heureFin'>>;

/** The keys of one axis of the pivot for one rule, most breaches first. */
export function topKeys(
  pivot: readonly CellulePivot[],
  contrainte: string,
  axe: CellulePivot['axe'],
): string[] {
  return pivot
    .filter((cellule) => cellule.contrainte === contrainte && cellule.axe === axe)
    .sort((a, b) => b.ecarts - a.ecarts || compareCodeUnits(a.cle, b.cle))
    .map((cellule) => cellule.cle);
}

/**
 * The Journée on one place of a rule: the timeslot's Siège panel when the
 * place names one — the page resolves it to a seat of that stand — else the
 * day and the stand as filters.
 */
export function placeLink(
  hotspot: Hotspot,
  nomsStands: LabelIndex,
  creneaux: TimeslotIndex,
): LienProbleme {
  const queryParams: Record<string, string> = {};
  const parts: string[] = [];
  if (hotspot.standId) {
    parts.push(labelOf(nomsStands, hotspot.standId));
  }
  const creneau =
    hotspot.creneauId === null || hotspot.creneauId === undefined
      ? undefined
      : creneaux.get(hotspot.creneauId);
  const date = hotspot.date ?? creneau?.date ?? null;
  if (date) {
    parts.push(date);
  }
  if (creneau?.heureDebut && creneau.heureFin) {
    parts.push(`${formatHeure(creneau.heureDebut)}–${formatHeure(creneau.heureFin)}`);
  }
  if (hotspot.creneauId !== null && hotspot.creneauId !== undefined) {
    queryParams['creneau'] = String(hotspot.creneauId);
  } else if (date) {
    queryParams['date'] = date;
  }
  if (hotspot.standId) {
    queryParams['stand'] = hotspot.standId;
  }
  return { route: '/journee', queryParams, libelle: parts.join(' · ') };
}

/**
 * Où a rule bites: its hotspots when the server crossed stand and timeslot,
 * else its most breached stands, else its most breached days — whatever the
 * breaches name, as long as it is a place.
 */
export function placesOfRule(
  contrainte: ConstraintView,
  pivot: readonly CellulePivot[],
  nomsStands: LabelIndex,
  creneaux: TimeslotIndex,
): LienProbleme[] {
  const hotspots = contrainte.hotspots ?? [];
  if (hotspots.length > 0) {
    return hotspots
      .slice(0, MAX_WHERE_WHO)
      .map((hotspot) => placeLink(hotspot, nomsStands, creneaux));
  }
  const stands = topKeys(pivot, contrainte.name, 'STAND');
  if (stands.length > 0) {
    return stands
      .slice(0, MAX_WHERE_WHO)
      .map((standId) => placeLink({ standId, ecarts: 0 }, nomsStands, creneaux));
  }
  return topKeys(pivot, contrainte.name, 'JOUR')
    .slice(0, MAX_WHERE_WHO)
    .map((date) => placeLink({ date, ecarts: 0 }, nomsStands, creneaux));
}

/** Qui: each person's fiche, the first three of `ids` — already ranked by the caller. */
export function peopleLinks(ids: readonly string[], nomsAnimateurs: LabelIndex): LienProbleme[] {
  return [...new Set(ids)].slice(0, MAX_WHERE_WHO).map((id) => ({
    route: `/animateurs/${id}`,
    libelle: labelOf(nomsAnimateurs, id),
  }));
}

/** How many people `ids` names beyond the three shown. */
function remainingPeople(ids: readonly string[]): number {
  return Math.max(0, new Set(ids).size - MAX_WHERE_WHO);
}

/** The line of a rule on « Règles du planning », on the tab of its level. */
export function ruleLink(contrainte: ConstraintView): LienProbleme {
  return {
    route: '/regles',
    queryParams: {
      onglet: contrainte.niveau === 'HARD' ? 'legal' : 'qualite',
      regle: contrainte.name,
    },
    libelle: $localize`:@@problemes.lien.contraintes:Voir la règle`,
  };
}

/**
 * The relay-less breaks have no type server-side — they come from the breaks
 * report, not from a rule — so their two gestures are written here: open one
 * more seat on the stand at the time of the break, or shorten the shift that
 * owes it. Positioned on the first break of the list.
 */
export function actionsOfBreaks(pauses: RapportPauses): ActionProbleme[] {
  const firstBreak = pauses.journees
    .flatMap((journee) => journee.sequences)
    .flatMap((sequence) => sequence.pausesDues)
    .find((pause) => !pause.relaisDisponible);
  // The stand by its exact id: a search on « S1 » would also bring S10, S11…
  const reliefParams: Record<string, string> = { vue: 'saisie' };
  if (firstBreak?.standId) {
    reliefParams['stand'] = firstBreak.standId;
  }
  const shiftParams: Record<string, string> = {};
  if (firstBreak?.creneauId !== null && firstBreak?.creneauId !== undefined) {
    shiftParams['edit'] = String(firstBreak.creneauId);
  }
  return [
    {
      code: 'OUVRIR_RELAIS',
      libelle: $localize`:@@problemes.action.ouvrirRelais:Ouvrir une place de relais`,
      explication: $localize`:@@problemes.action.ouvrirRelais.explication:Une place de plus sur ce stand à l'heure de la pause : quelqu'un peut alors relayer la personne seule.`,
      route: '/ouvertures',
      queryParams: reliefParams,
      lang: null,
    },
    {
      code: 'RACCOURCIR_VACATION',
      libelle: $localize`:@@problemes.action.raccourcirVacation:Raccourcir la vacation`,
      explication: $localize`:@@problemes.action.raccourcirVacation.explication:Une vacation plus courte ne doit plus la pause : elle tombe alors hors de la séquence.`,
      route: '/creneaux',
      queryParams: shiftParams,
      lang: null,
    },
  ];
}

/** One match of a rule in default: its sentence, and the fiches it names as links. */
export interface LinkedViolation {
  texte: string;
  liens: LienProbleme[];
}

/** One line per relay-less break, day by day: who, when, where. */
function detailsDePauses(pauses: RapportPauses): string[] {
  const lignes: string[] = [];
  for (const journee of pauses.journees) {
    for (const sequence of journee.sequences) {
      for (const pause of sequence.pausesDues) {
        if (!pause.relaisDisponible) {
          lignes.push(
            $localize`:@@problemes.pauses.detail:${journee.date}:date: · ${journee.nomComplet}:animateur: · ${formatHeure(pause.debut)}:debut: – ${formatHeure(pause.fin)}:fin: · ${pause.standNom}:stand:`,
          );
        }
      }
    }
  }
  return lignes;
}

/** A route the problem can be acted upon from. */
export interface LienProbleme {
  route: string;
  /** The rendering, the tab or the object the screen should open on — pre-filtered, never bare, when the target reads them. */
  queryParams?: Record<string, string>;
  libelle: string;
}

export interface ComptageProblemes {
  bloquants: number;
  avertissements: number;
  mineurs: number;
  total: number;
}

const RANG_NIVEAU: Record<NiveauProbleme, number> = { BLOQUANT: 0, AVERTISSEMENT: 1, MINEUR: 2 };
// Within one severity tier, a structural capacity problem comes before a
// constraint violation: it must be fixed first, since no solve can work around it.
const RANG_SOURCE: Record<SourceProbleme, number> = {
  FAISABILITE: 0,
  CONTRAINTE: 1,
  PAUSES: 2,
  TRAJETS: 3,
  COVOITURAGE: 4,
};

export function niveauDeCause(severite: CauseInfaisabilite['severite']): NiveauProbleme {
  return severite === 'CRITIQUE' ? 'BLOQUANT' : 'AVERTISSEMENT';
}

export function niveauDeContrainte(niveau: NiveauContrainte): NiveauProbleme {
  switch (niveau) {
    case 'HARD':
      return 'BLOQUANT';
    case 'MEDIUM':
      return 'AVERTISSEMENT';
    default:
      return 'MINEUR';
  }
}

export function niveauProblemeLabel(niveau: NiveauProbleme): string {
  switch (niveau) {
    case 'BLOQUANT':
      return $localize`:@@problemes.niveau.bloquant:Bloquant`;
    case 'AVERTISSEMENT':
      return $localize`:@@problemes.niveau.avertissement:Avertissement`;
    default:
      return $localize`:@@problemes.niveau.mineur:Mineur`;
  }
}

export function typeCauseLabel(type: TypeCauseInfaisabilite): string {
  switch (type) {
    case 'CONTRAINTES_AD_HOC_CONTRADICTOIRES':
      return $localize`:@@problemes.cause.contraintesAdHocContradictoires:Ajustements manuels contradictoires`;
    case 'AFFECTATION_FORCEE_JOUR_INDISPONIBLE':
      return $localize`:@@problemes.cause.affectationForceeJourIndisponible:Affectation forcée un jour d'indisponibilité`;
    case 'AFFECTATION_FORCEE_MOTIF_LEGAL':
      return $localize`:@@problemes.cause.affectationForceeMotifLegal:Affectation forcée contre une règle dure`;
    case 'AFFECTATION_FORCEE_SIEGE_VERROUILLE':
      return $localize`:@@problemes.cause.affectationForceeSiegeVerrouille:Affectation forcée sur un emploi du temps verrouillé`;
    default:
      return $localize`:@@problemes.cause.creneauSousEffectif:Créneau en sous-effectif`;
  }
}

/** A cause naming a dozen stands gets a dozen links nobody clicks: three, then the list. */
const MAX_LIENS_STANDS = 3;

/** The fiches a hard-constraint match names, each as a link opening it for editing. */
export function linksOfViolation(violation: ViolationReference): LienProbleme[] {
  const liens: LienProbleme[] = [];
  if (violation.animateurId) {
    liens.push({
      route: `/animateurs/${violation.animateurId}`,
      libelle: $localize`:@@problemes.lien.animateur:Fiche animateur`,
    });
  }
  if (violation.standId) {
    liens.push({
      route: '/stands',
      queryParams: { edit: violation.standId },
      libelle: $localize`:@@problemes.lien.standFiche:Fiche stand`,
    });
  }
  if (violation.creneauId !== null && violation.creneauId !== undefined) {
    liens.push({
      route: '/creneaux',
      queryParams: { edit: String(violation.creneauId) },
      libelle: $localize`:@@problemes.lien.creneauFiche:Fiche créneau`,
    });
  }
  return liens;
}

/**
 * Screens a feasibility cause can be acted upon from, in the order one would try them.
 * `nomsStands` names the stands the cause lists by id; an unknown one keeps its id.
 */
export function causeLinks(
  cause: CauseInfaisabilite,
  nomsStands: LabelIndex = new Map(),
): LienProbleme[] {
  const liens: LienProbleme[] = [];
  if (cause.creneauId !== null && cause.creneauId !== undefined) {
    // Straight to the fiche, open for editing: the reader came to fix it.
    liens.push({
      route: '/creneaux',
      queryParams: { edit: String(cause.creneauId) },
      libelle: $localize`:@@problemes.lien.creneaux:Voir le créneau`,
    });
  }
  for (const standId of cause.standIds.slice(0, MAX_LIENS_STANDS)) {
    const nom = labelOf(nomsStands, standId);
    liens.push({
      route: '/stands',
      queryParams: { edit: standId },
      libelle: $localize`:@@problemes.lien.stand:Voir le stand ${nom}:stand:`,
    });
  }
  if (cause.standIds.length > 0) {
    liens.push({
      route: '/ouvertures',
      libelle: $localize`:@@problemes.lien.ouvertures:Vérifier les ouvertures`,
    });
  }
  if (cause.manque > 0) {
    liens.push({
      route: '/diagnostic',
      queryParams: { onglet: 'besoin' },
      libelle: $localize`:@@problemes.lien.staffing:Besoin en animateurs`,
    });
  }
  if ((cause.contrainteIds ?? []).length > 0) {
    liens.push({
      route: '/consignes-solveur',
      queryParams: { onglet: 'ajustements' },
      libelle: $localize`:@@problemes.lien.adHoc:Voir les ajustements manuels`,
    });
  }
  // The deadlock has two halves and the cause only names one: the exception is
  // on the Ajustements screen, the lock that blocks it on the other.
  if (cause.type === 'AFFECTATION_FORCEE_SIEGE_VERROUILLE') {
    liens.push({
      route: '/consignes-solveur',
      queryParams: { onglet: 'verrouillages' },
      libelle: $localize`:@@problemes.lien.verrouillages:Voir les verrouillages`,
    });
  }
  return liens;
}

export function causeDetails(
  cause: CauseInfaisabilite,
  nomsStands: LabelIndex = new Map(),
): string[] {
  const details: string[] = [];
  if (cause.creneauId !== null && cause.creneauId !== undefined) {
    const creneauId = String(cause.creneauId);
    const date = cause.date ?? '';
    const heures =
      cause.heureDebut && cause.heureFin ? `${cause.heureDebut}–${cause.heureFin}` : '';
    details.push(
      $localize`:@@problemes.detail.creneau:Créneau ${creneauId}:id: ${date}:date: ${heures}:hours:`,
    );
  }
  if (cause.standIds.length > 0) {
    const stands = labelsOf(nomsStands, cause.standIds).join(', ');
    details.push($localize`:@@problemes.detail.stands:Stands : ${stands}:stands:`);
  }
  if ((cause.contrainteIds ?? []).length > 0) {
    const contraintes = cause.contrainteIds.join(', ');
    details.push(
      $localize`:@@problemes.detail.contraintesAdHoc:Ajustements manuels : ${contraintes}:contraintes:`,
    );
  }
  if (cause.manque > 0) {
    const manque = cause.manque;
    const demande = cause.demande;
    const capacite = cause.capacite;
    details.push(
      $localize`:@@problemes.detail.effectif:Il manque ${manque}:manque: animateur(s) : ${demande}:demande: demandé(s) pour ${capacite}:capacite: disponible(s).`,
    );
  }
  return details;
}

/** One line naming the exceptions a rule failed on, with how much each accounts for. */
function relatedDetails(contributions: ContributionAdHoc[]): string[] {
  if (contributions.length === 0) {
    return [];
  }
  const noms = contributions
    .map((contribution) => `${contribution.contrainteId} (${contribution.violations})`)
    .join(', ');
  return [$localize`:@@problemes.detail.adHocEnCause:Ajustements en cause : ${noms}:noms:`];
}

/**
 * Builds the ranked problem list. Both arguments are optional so the caller can
 * render whatever it already has: the feasibility report is available before any
 * solve, the constraint diagnostic only after one.
 *
 * Only constraints with at least one match are kept — a satisfied rule is not a
 * problem. HARD constraints carry their per-match `violations` lines; MEDIUM and
 * SOFT ones only report how many matches they scored.
 *
 * `contraintesAdHocEnCause` comes from the same diagnostic and attributes the
 * ad hoc rules' violations to the exceptions that caused them. `nomsStands`
 * names the stands a cause lists by id, `nomsAnimateurs` the animateurs a
 * reading lists by id; an unknown one keeps its id.
 *
 * `lieux` says where each rule in default bites and on whom: the pivot of the
 * same analysis ranks its stands, days and people, and the timeslots name the
 * hours of its hotspots. Every card then carries « Où » and « Qui » when its
 * source names a place or a person.
 */
export function construireProblemes(
  report: FeasibilityReport | null,
  contraintes: ConstraintView[] = [],
  contraintesAdHocEnCause: ContributionAdHoc[] = [],
  pauses: RapportPauses | null = null,
  nomsStands: LabelIndex = new Map(),
  nomsAnimateurs: LabelIndex = new Map(),
  walks: WalkSequenceReport | null = null,
  groupedArrivals: GroupedArrivalReport | null = null,
  lieux: { pivot?: readonly CellulePivot[]; creneaux?: TimeslotIndex } = {},
): Probleme[] {
  const problemes: Probleme[] = [];
  const pivot = lieux.pivot ?? [];
  const creneaux: TimeslotIndex = lieux.creneaux ?? new Map();

  // The days a covoiturage does not hold, group by group: a soft rule the
  // solver may have traded away, and the organiser decides whether to act.
  const misaligned = (groupedArrivals?.groups ?? []).filter((groupe) => groupe.misalignedDays > 0);
  if (misaligned.length > 0) {
    const count = misaligned.reduce((total, groupe) => total + groupe.misalignedDays, 0);
    const days = [
      ...new Set(
        misaligned.flatMap((groupe) =>
          groupe.days.filter((jour) => !jour.aligned).map((jour) => jour.date),
        ),
      ),
    ].sort(compareCodeUnits);
    const membres = misaligned.flatMap((groupe) => groupe.animateurIds);
    problemes.push({
      id: 'arrivees-groupees-desalignees',
      ou: days
        .slice(0, MAX_WHERE_WHO)
        .map((date) => placeLink({ date, ecarts: 0 }, nomsStands, creneaux)),
      qui: peopleLinks(membres, nomsAnimateurs),
      quiRestants: remainingPeople(membres),
      regle: null,
      niveau: 'MINEUR',
      source: 'COVOITURAGE',
      titre: $localize`:@@problemes.covoiturage.titre:Covoiturages désalignés`,
      message: $localize`:@@problemes.covoiturage.message:${count}:count: jour(s) où un groupe de covoiturage n'arrive ou ne repart pas ensemble : un membre travaille sans les autres, ou leurs horaires s'écartent au-delà de la tolérance.`,
      details: misaligned.flatMap((groupe) =>
        groupe.days
          .filter((jour) => !jour.aligned)
          .map(
            (jour) =>
              `${labelsOf(nomsAnimateurs, groupe.animateurIds).join(', ')} · ${misalignmentLabel(jour, nomsAnimateurs)}`,
          ),
      ),
      references: [],
      actions: [],
      liens: [
        {
          route: '/consignes-solveur',
          queryParams: {
            onglet: 'ajustements',
            ids: misaligned.map((groupe) => groupe.contrainteId).join(','),
          },
          libelle: $localize`:@@problemes.lien.adHoc:Voir les ajustements manuels`,
        },
        ...days.map((date) => ({
          route: '/journee',
          queryParams: { vue: 'rail', date },
          libelle: $localize`:@@problemes.lien.railDuJour:Voir le rail du ${date}:date:`,
        })),
      ],
    });
  }

  // A walk the gap does not leave time for is a reading of the persisted plan,
  // not a score: the solver may still price it (trajetInsuffisantEntrePostes),
  // but the pairs it leaves to its neighbour rule, and the past, are listed too.
  const tightWalks = walks?.walks ?? [];
  if (tightWalks.length > 0) {
    const count = tightWalks.length;
    const days = [...new Set(tightWalks.map((walk) => walk.date ?? ''))]
      .filter(Boolean)
      .sort(compareCodeUnits);
    const marcheurs = tightWalks.flatMap((walk) => (walk.animateurId ? [walk.animateurId] : []));
    problemes.push({
      id: 'enchainements-serres',
      ou: tightWalks.slice(0, MAX_WHERE_WHO).map((walk) =>
        placeLink(
          {
            standId: walk.toStandId ?? null,
            date: walk.date ?? null,
            creneauId: walk.toCreneauId ?? null,
            ecarts: 0,
          },
          nomsStands,
          creneaux,
        ),
      ),
      qui: peopleLinks(marcheurs, nomsAnimateurs),
      quiRestants: remainingPeople(marcheurs),
      regle: null,
      niveau: 'AVERTISSEMENT',
      source: 'TRAJETS',
      titre: $localize`:@@problemes.trajets.titre:Enchaînements serrés`,
      message: $localize`:@@problemes.trajets.message:${count}:count: enchaînement(s) entre deux emplacements ne laissent pas le temps de marcher de l'un à l'autre, ou le prennent sur la pause. Élargissez le battement, ou rapprochez les stands.`,
      details: tightWalks.map(
        (walk) =>
          `${walk.date ?? ''} · ${labelOf(nomsAnimateurs, walk.animateurId ?? '')} · ${labelOf(nomsStands, walk.fromStandId ?? '')} → ${labelOf(nomsStands, walk.toStandId ?? '')} · ${walkLabel(walk)}`,
      ),
      references: [],
      actions: [],
      liens: days.map((date) => ({
        route: '/journee',
        queryParams: { vue: 'rail', date },
        libelle: $localize`:@@problemes.lien.railDuJour:Voir le rail du ${date}:date:`,
      })),
    });
  }

  // A break nobody can relay is not a violation the solver sees — the seat is
  // held — but it is one person alone on a stand for twenty minutes, and the
  // organiser must know before the day, not during it.
  if (pauses && pauses.relaisManquants > 0) {
    const seuls = pauses.journees.flatMap((journee) =>
      journee.sequences
        .flatMap((sequence) => sequence.pausesDues)
        .filter((pause) => !pause.relaisDisponible)
        .map((pause) => ({ journee, pause })),
    );
    const personnes = seuls.map(({ journee }) => journee.animateurId);
    problemes.push({
      id: 'pauses-sans-relais',
      ou: seuls.slice(0, MAX_WHERE_WHO).map(({ journee, pause }) =>
        placeLink(
          {
            standId: pause.standId,
            date: journee.date,
            creneauId: pause.creneauId ?? null,
            ecarts: 0,
          },
          nomsStands,
          creneaux,
        ),
      ),
      qui: peopleLinks(personnes, nomsAnimateurs),
      quiRestants: remainingPeople(personnes),
      regle: null,
      niveau: 'AVERTISSEMENT',
      source: 'PAUSES',
      titre: $localize`:@@problemes.pauses.titre:Pauses sans relais`,
      message: $localize`:@@problemes.pauses.message:${pauses.relaisManquants}:count: pause(s) légale(s) tombent sur un stand où personne d'autre n'est présent : la personne est seule, personne ne peut la relayer. Prévoyez un relais extérieur, ou renforcez le stand.`,
      details: detailsDePauses(pauses),
      references: [],
      actions: actionsOfBreaks(pauses),
      liens: [
        {
          route: '/journee',
          queryParams: { vue: 'pauses' },
          libelle: $localize`:@@problemes.lien.pauses:Voir les pauses`,
        },
      ],
    });
  }

  (report?.causes ?? []).forEach((cause, index) => {
    // A cause is a shortfall of capacity: it names stands and a timeslot, and nobody.
    problemes.push({
      id: `cause-${index}`,
      ou: cause.standIds.slice(0, MAX_WHERE_WHO).map((standId) =>
        placeLink(
          {
            standId,
            date: cause.date ?? null,
            creneauId: cause.creneauId ? Number(cause.creneauId) : null,
            ecarts: 0,
          },
          nomsStands,
          creneaux,
        ),
      ),
      qui: [],
      quiRestants: 0,
      regle: null,
      niveau: niveauDeCause(cause.severite),
      source: 'FAISABILITE',
      titre: typeCauseLabel(cause.type),
      message: cause.message,
      details: causeDetails(cause, nomsStands),
      references: [],
      actions: (cause.actions ?? []).map(actionOf),
      liens: causeLinks(cause, nomsStands),
    });
  });

  contraintes
    .filter((contrainte) => (contrainte.matchCount ?? 0) > 0)
    .forEach((contrainte) => {
      const matchCount = contrainte.matchCount ?? 0;
      // Which of the user's own exceptions this rule failed on, before the
      // per-match lines: "affectationForcee : 12" is where reading stops
      // otherwise, and the exceptions are the only thing anyone can act on.
      const enCause = contraintesAdHocEnCause.filter((contribution) =>
        contribution.contraintes.includes(contrainte.name),
      );
      // Each match with the fiches it names, when the server said which;
      // the bare sentences otherwise (an older analysis), the count failing that.
      const references: LinkedViolation[] = (contrainte.references ?? []).map((violation) => ({
        texte: violation.texte,
        liens: linksOfViolation(violation),
      }));
      let lignes: string[] = [];
      if (references.length === 0) {
        lignes =
          contrainte.violations.length > 0
            ? contrainte.violations
            : [
                $localize`:@@problemes.detail.matches:${matchCount}:count: correspondance(s) sur la dernière analyse.`,
              ];
      }
      // The rule's own line on « Règles du planning »: what it measures and
      // what sets it — never the first gesture, which is on the plan.
      const liens: LienProbleme[] = [ruleLink(contrainte)];
      if (enCause.length > 0) {
        liens.push({
          route: '/consignes-solveur',
          queryParams: { onglet: 'ajustements' },
          libelle: $localize`:@@problemes.lien.adHoc:Voir les ajustements manuels`,
        });
      }
      const personnes = topKeys(pivot, contrainte.name, 'ANIMATEUR');
      problemes.push({
        id: `contrainte-${contrainte.name}`,
        ou: placesOfRule(contrainte, pivot, nomsStands, creneaux),
        qui: peopleLinks(personnes, nomsAnimateurs),
        quiRestants: remainingPeople(personnes),
        regle: contrainte.name,
        niveau: niveauDeContrainte(contrainte.niveau),
        source: 'CONTRAINTE',
        // The short label, not the camelCase name: this list is read by
        // organisers, and the rule's own line on Contraintes keeps the name.
        titre: contrainte.libelleCourt || contrainte.name,
        message: contrainte.description,
        details: [...relatedDetails(enCause), ...lignes],
        references,
        actions: actionsOfRule(contrainte, enCause),
        liens,
      });
    });

  // Array.prototype.sort is stable, so problems of the same tier and source keep
  // the order the server ranked them in.
  return problemes.sort(
    (a, b) =>
      RANG_NIVEAU[a.niveau] - RANG_NIVEAU[b.niveau] ||
      RANG_SOURCE[a.source] - RANG_SOURCE[b.source],
  );
}

/** « 2026-07-10 — sans Oscar Petit », « 2026-07-11 — arrivées à 45 min d'écart ». */
function misalignmentLabel(jour: GroupDayView, nomsAnimateurs: LabelIndex): string {
  const date = jour.date;
  if (jour.absent.length > 0) {
    const missing = labelsOf(nomsAnimateurs, jour.absent).join(', ');
    return $localize`:@@problemes.covoiturage.absents:${date}:date: — sans ${missing}:absents:`;
  }
  const arrivee = jour.arrivalSpreadMinutes;
  const depart = jour.departureSpreadMinutes;
  return $localize`:@@problemes.covoiturage.ecarts:${date}:date: — arrivées à ${arrivee}:arrivee: min d'écart, départs à ${depart}:depart: min`;
}

export function compterProblemes(problemes: Probleme[]): ComptageProblemes {
  return {
    bloquants: problemes.filter((probleme) => probleme.niveau === 'BLOQUANT').length,
    avertissements: problemes.filter((probleme) => probleme.niveau === 'AVERTISSEMENT').length,
    mineurs: problemes.filter((probleme) => probleme.niveau === 'MINEUR').length,
    total: problemes.length,
  };
}
