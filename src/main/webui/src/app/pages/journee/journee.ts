// The pure side of the Journée page: which days the plan holds, which of the
// five renderings a query param names, how a day is keyed in the URL, and how
// two days are laid side by side in the comparison mode.

import { PosteAffectation, RapportPauses } from '../../core/models';
import { correspondAuFiltre } from '../../core/text-filter';
import { endMinutesOfDay, formatHeure, minutesOfDay } from '../../core/time-of-day';

/** The five renderings of one day, and the values of the `vue` query param. */
export type JourneeView = 'calendrier' | 'rail' | 'carte' | 'pauses' | 'changements';

export const JOURNEE_VIEWS: readonly JourneeView[] = [
  'calendrier',
  'rail',
  'carte',
  'pauses',
  'changements',
];

/** One event day of the plan, as the shared selector lists it. */
export interface JourEvenement {
  jour: number;
  /** ISO date; null when the créneaux carry none. */
  date: string | null;
  /** What the `date` query param carries: the ISO date, or `J<n>` for a day without one. */
  key: string;
  title: string;
}

/** Reads the `vue` query param; anything unknown is the calendar, the rendering the page opens on. */
export function readView(value: string | null): JourneeView {
  return (JOURNEE_VIEWS as readonly string[]).includes(value ?? '')
    ? (value as JourneeView)
    : 'calendrier';
}

/** The key of a day in the URL: its date when it has one, its number otherwise. */
export function dayKey(jour: number, date: string | null): string {
  return date ?? `J${jour}`;
}

/**
 * The days of the plan, in order, one per distinct day number of its
 * créneaux. Built once by the page and handed to every rendering, so the
 * selector offers the same days whatever the view — the breaks report only
 * knows the days somebody works, the plan knows them all.
 */
export function planningDays(postes: readonly PosteAffectation[]): JourEvenement[] {
  const jours = new Map<number, string | null>();
  for (const poste of postes) {
    const creneau = poste.creneau;
    if (creneau && !jours.has(creneau.jour)) {
      jours.set(creneau.jour, creneau.date ?? null);
    }
  }
  return [...jours.entries()]
    .sort(([gauche], [droite]) => gauche - droite)
    .map(([jour, date]) => ({
      jour,
      date,
      key: dayKey(jour, date),
      title: date
        ? $localize`:@@calendarDay.dayTitleWithDate:Jour ${jour}:jour: — ${date}:date:`
        : $localize`:@@calendarDay.dayTitle:Jour ${jour}:jour:`,
    }));
}

/**
 * Which day a URL asks for: `date` names it by its key; the older `jour`
 * param of the four screens this page replaced named it by its number, and a
 * bookmark carrying one still lands on the right day.
 */
export function requestedKey(date: string | null, jour: string | null): string | null {
  if (date) {
    return date;
  }
  const numero = Number(jour);
  return Number.isFinite(numero) && numero > 0 ? `J${numero}` : null;
}

/** The day a requested key resolves to, `J<n>` keys matching a dated day by number as well. */
export function jourDemande(
  jours: readonly JourEvenement[],
  key: string | null,
): JourEvenement | null {
  if (key === null) {
    return null;
  }
  const numero = /^J(\d+)$/.exec(key);
  return (
    jours.find((jour) => jour.key === key) ??
    (numero ? (jours.find((jour) => jour.jour === Number(numero[1])) ?? null) : null)
  );
}

/* ------------------------------ Comparing two days ------------------------------ */

/** The two renderings where a comparison has a meaning line by line. */
export type JourneeViewComparable = 'calendrier' | 'rail';

export function isComparable(view: JourneeView): view is JourneeViewComparable {
  return view === 'calendrier' || view === 'rail';
}

/** Why a requested second day was set aside: the same day as the first one, or no such day. */
export type RefusComparaison = 'identique' | 'inconnu';

/** What the `comparer` param resolves to: the second day, or why it was ignored. */
export interface ComparaisonResolue {
  jour: JourEvenement | null;
  refus: RefusComparaison | null;
}

/**
 * Resolves the `comparer` param against the days of the plan. A key naming no
 * day, or the very day on screen, is ignored — and the reason travels with
 * it, since the page says so rather than silently showing one day.
 */
export function resolveComparison(
  jours: readonly JourEvenement[],
  courant: JourEvenement | null,
  key: string | null,
): ComparaisonResolue {
  if (key === null) {
    return { jour: null, refus: null };
  }
  const jour = jourDemande(jours, key);
  if (!jour) {
    return { jour: null, refus: 'inconnu' };
  }
  if (courant && jour.key === courant.key) {
    return { jour: null, refus: 'identique' };
  }
  return { jour, refus: null };
}

function addDays(iso: string, delta: number): string {
  const date = new Date(`${iso}T00:00:00Z`);
  date.setUTCDate(date.getUTCDate() + delta);
  return date.toISOString().slice(0, 10);
}

/**
 * The same weekday one week before (`-1`) or after (`+1`) `courant`, when the
 * event holds that day. A dated day moves by seven calendar days; an undated
 * one by seven day numbers, which is the only week it knows.
 */
export function jourSemaineVoisine(
  jours: readonly JourEvenement[],
  courant: JourEvenement | null,
  sens: -1 | 1,
): JourEvenement | null {
  if (!courant) {
    return null;
  }
  if (courant.date) {
    const target = addDays(courant.date, 7 * sens);
    return jours.find((jour) => jour.date === target) ?? null;
  }
  return jours.find((jour) => jour.date === null && jour.jour === courant.jour + 7 * sens) ?? null;
}

/**
 * The day « Comparer avec… » opens on: the same weekday the week after, else
 * the week before, else the next day, else the previous one.
 */
export function defaultComparisonDay(
  jours: readonly JourEvenement[],
  courant: JourEvenement | null,
): JourEvenement | null {
  if (!courant) {
    return null;
  }
  const index = jours.findIndex((jour) => jour.key === courant.key);
  return (
    jourSemaineVoisine(jours, courant, 1) ??
    jourSemaineVoisine(jours, courant, -1) ??
    jours[index + 1] ??
    (index > 0 ? jours[index - 1] : null) ??
    null
  );
}

function nomAffiche(poste: PosteAffectation): string {
  const animateur = poste.animateur;
  if (!animateur) {
    return '';
  }
  return `${animateur.prenom ?? ''} ${animateur.nom ?? ''}`.trim() || animateur.id;
}

function fenetre(poste: PosteAffectation): { heureDebut: string; heureFin: string } {
  return {
    heureDebut: formatHeure(poste.heureDebutEffective ?? poste.creneau?.heureDebut ?? ''),
    heureFin: formatHeure(poste.heureFinEffective ?? poste.creneau?.heureFin ?? ''),
  };
}

function seatsOfDay(postes: readonly PosteAffectation[], jour: number): PosteAffectation[] {
  return postes.filter((poste) => poste.creneau?.jour === jour && poste.stand);
}

/** The figures of one day the comparison banner puts side by side. */
export interface SyntheseJournee {
  /**
   * The seats to fill; null when the scope is a set of animateurs, whose
   * seats are all held by definition — an empty seat belongs to nobody.
   */
  sieges: number | null;
  pourvus: number;
  /** The empty seats; null for the same reason as {@link sieges}. */
  vides: number | null;
  /** The breaks owed that day with nobody to relieve; null when the breaks could not be read. */
  unrelievedBreaks: number | null;
  animateurs: number;
  stands: number;
}

/**
 * What the banner sums up once a filter narrows the lines: the stands of the
 * calendar lines kept, or the animateurs of the rail lines kept. Absent, the
 * whole day counts.
 */
export type SummaryScope =
  { standIds: ReadonlySet<string> } | { animateurIds: ReadonlySet<string> };

function inScope(
  scope: SummaryScope | null,
  standId: string | undefined,
  animateurId: string | undefined,
): boolean {
  if (!scope) {
    return true;
  }
  if ('standIds' in scope) {
    return standId !== undefined && scope.standIds.has(standId);
  }
  return animateurId !== undefined && scope.animateurIds.has(animateurId);
}

/**
 * One day summed up: seats, who holds them, the breaks without relief, the
 * stands open — over the whole day, or over the {@link SummaryScope} the
 * filtered lines leave, so the banner and the lines under it tell one story.
 */
export function syntheseJournee(
  postes: readonly PosteAffectation[],
  jour: number,
  pauses: RapportPauses | null,
  scope: SummaryScope | null = null,
): SyntheseJournee {
  const daySeats = seatsOfDay(postes, jour).filter((poste) =>
    inScope(scope, poste.stand?.id, poste.animateur?.id),
  );
  const pourvus = daySeats.filter((poste) => poste.animateur).length;
  let unrelievedBreaks: number | null = null;
  if (pauses) {
    unrelievedBreaks = 0;
    for (const journee of pauses.journees) {
      if (journee.jour !== jour) {
        continue;
      }
      for (const sequence of journee.sequences) {
        unrelievedBreaks += sequence.pausesDues.filter(
          (pause) => !pause.relaisDisponible && inScope(scope, pause.standId, journee.animateurId),
        ).length;
      }
    }
  }
  const animateurs = new Set(
    daySeats.flatMap((poste) => (poste.animateur ? [poste.animateur.id] : [])),
  );
  // Scoped to animateurs, only held seats are left: counting them as the seats
  // to fill would claim a day without a single empty seat.
  const seatsKnown = !scope || 'standIds' in scope;
  return {
    sieges: seatsKnown ? daySeats.length : null,
    pourvus,
    vides: seatsKnown ? daySeats.length - pourvus : null,
    unrelievedBreaks,
    animateurs: animateurs.size,
    stands: new Set(daySeats.map((poste) => poste.stand?.id)).size,
  };
}

/**
 * How an écart B − A reads: `mieux` and `pire` when the figure has a
 * direction (more seats held is better, more empty seats is worse), `neutre`
 * when it only differs — more stands open is neither good nor bad.
 */
export type DeltaTone = 'nul' | 'mieux' | 'pire' | 'neutre' | 'inconnu';

export interface LigneSynthese {
  key: keyof SyntheseJournee;
  label: string;
  a: number | null;
  b: number | null;
  /** B − A; null when either side is unknown. */
  delta: number | null;
  tonalite: DeltaTone;
}

/** Which way is better for each figure: +1 more is better, -1 more is worse, 0 no direction. */
const SENS_SYNTHESE: Record<keyof SyntheseJournee, 1 | -1 | 0> = {
  sieges: 0,
  pourvus: 1,
  vides: -1,
  unrelievedBreaks: -1,
  animateurs: 0,
  stands: 0,
};

/** The banner's rows: each figure for both days, and the écart B − A with how it reads. */
export function lignesSynthese(a: SyntheseJournee, b: SyntheseJournee): LigneSynthese[] {
  const labels: Record<keyof SyntheseJournee, string> = {
    sieges: $localize`:@@journee.comparaison.synthese.sieges:Sièges à pourvoir`,
    pourvus: $localize`:@@journee.comparaison.synthese.pourvus:Pourvus`,
    vides: $localize`:@@journee.comparaison.synthese.vides:Vides`,
    unrelievedBreaks: $localize`:@@journee.comparaison.synthese.pausesSansRelais:Pauses sans relais`,
    animateurs: $localize`:@@journee.comparaison.synthese.animateurs:Animateurs mobilisés`,
    stands: $localize`:@@journee.comparaison.synthese.stands:Stands ouverts`,
  };
  return (Object.keys(SENS_SYNTHESE) as (keyof SyntheseJournee)[]).map((key) => {
    const valeurA = a[key];
    const valeurB = b[key];
    const delta = valeurA === null || valeurB === null ? null : valeurB - valeurA;
    const sens = SENS_SYNTHESE[key];
    let tonalite: DeltaTone;
    if (delta === null) {
      tonalite = 'inconnu';
    } else if (delta === 0) {
      tonalite = 'nul';
    } else if (sens === 0) {
      tonalite = 'neutre';
    } else {
      tonalite = delta * sens > 0 ? 'mieux' : 'pire';
    }
    return { key, label: labels[key], a: valeurA, b: valeurB, delta, tonalite };
  });
}

/** An écart as the banner prints it: signed, with a true minus sign, « = » when nothing moved. */
export function formatDelta(delta: number | null): string {
  if (delta === null) {
    return '—';
  }
  if (delta === 0) {
    return '=';
  }
  return delta > 0 ? `+${delta}` : `−${-delta}`;
}

/** One window of a stand on one day: its hours, its seats, who holds them. */
export interface PlageStand {
  heureDebut: string;
  heureFin: string;
  sieges: number;
  noms: string[];
}

/** A stand on one day; a line carries null instead when the stand is closed that day. */
export interface CelluleStand {
  plages: PlageStand[];
  sieges: number;
  pourvus: number;
}

/** One line of the calendar comparison: the same stand on both days. */
export interface LigneStandComparee {
  standId: string;
  standNom: string;
  a: CelluleStand | null;
  b: CelluleStand | null;
  /** The animateurs holding a seat of this stand on either day, for the shared filter. */
  animateurIds: string[];
  /** True when the stand's headcount or coverage differs from one day to the other. */
  different: boolean;
}

interface DayStand {
  nom: string;
  cellule: CelluleStand;
  ids: string[];
}

function cellulesStands(postes: readonly PosteAffectation[], jour: number): Map<string, DayStand> {
  const byStand = new Map<
    string,
    { nom: string; plages: Map<string, PlageStand>; ids: string[] }
  >();
  for (const poste of seatsOfDay(postes, jour)) {
    const stand = poste.stand;
    if (!stand) {
      continue;
    }
    let entree = byStand.get(stand.id);
    if (!entree) {
      entree = { nom: stand.nom || stand.id, plages: new Map(), ids: [] };
      byStand.set(stand.id, entree);
    }
    const { heureDebut, heureFin } = fenetre(poste);
    const key = `${heureDebut}-${heureFin}`;
    let plage = entree.plages.get(key);
    if (!plage) {
      plage = { heureDebut, heureFin, sieges: 0, noms: [] };
      entree.plages.set(key, plage);
    }
    plage.sieges += 1;
    if (poste.animateur) {
      plage.noms.push(nomAffiche(poste));
      entree.ids.push(poste.animateur.id);
    }
  }
  const cellules = new Map<string, DayStand>();
  for (const [id, entree] of byStand) {
    const plages = [...entree.plages.values()]
      .map((plage) => ({
        ...plage,
        noms: [...plage.noms].sort((gauche, droite) => gauche.localeCompare(droite)),
      }))
      .sort(
        (gauche, droite) =>
          gauche.heureDebut.localeCompare(droite.heureDebut) ||
          gauche.heureFin.localeCompare(droite.heureFin),
      );
    cellules.set(id, {
      nom: entree.nom,
      ids: entree.ids,
      cellule: {
        plages,
        sieges: plages.reduce((total, plage) => total + plage.sieges, 0),
        pourvus: plages.reduce((total, plage) => total + plage.noms.length, 0),
      },
    });
  }
  return cellules;
}

/** What the comparison measures of a stand: its windows, their seats and how many are held — not who. */
function empreinteStand(cellule: CelluleStand | null): string {
  return cellule
    ? cellule.plages
        .map(
          (plage) => `${plage.heureDebut}-${plage.heureFin}:${plage.sieges}:${plage.noms.length}`,
        )
        .join('|')
    : 'ferme';
}

/**
 * The calendar comparison: one line per stand open on either day, in the same
 * order on both sides (by name). A stand open on one day only has a null cell
 * on the other — « fermé ce jour-là ». A line differs when the windows, the
 * seats or the number held differ; who holds them does not count, since two
 * Saturdays are never staffed by the same people.
 */
export function alignerStands(
  postes: readonly PosteAffectation[],
  jourA: number,
  jourB: number,
): LigneStandComparee[] {
  const a = cellulesStands(postes, jourA);
  const b = cellulesStands(postes, jourB);
  const ids = new Set([...a.keys(), ...b.keys()]);
  return [...ids]
    .map((standId) => {
      const gauche = a.get(standId) ?? null;
      const droite = b.get(standId) ?? null;
      const celluleA = gauche?.cellule ?? null;
      const celluleB = droite?.cellule ?? null;
      return {
        standId,
        standNom: gauche?.nom ?? droite?.nom ?? standId,
        a: celluleA,
        b: celluleB,
        animateurIds: [...new Set([...(gauche?.ids ?? []), ...(droite?.ids ?? [])])],
        different: empreinteStand(celluleA) !== empreinteStand(celluleB),
      };
    })
    .sort(
      (gauche, droite) =>
        gauche.standNom.localeCompare(droite.standNom) ||
        gauche.standId.localeCompare(droite.standId),
    );
}

/** One shift of an animateur on one day. */
export interface VacationComparee {
  heureDebut: string;
  heureFin: string;
  standId: string;
  standNom: string;
}

/** An animateur on one day; a line carries null instead when they hold no seat that day. */
export interface CelluleAnimateur {
  vacations: VacationComparee[];
  minutes: number;
}

/** One line of the rail comparison: the same animateur on both days. */
export interface LigneAnimateurComparee {
  animateurId: string;
  nom: string;
  a: CelluleAnimateur | null;
  b: CelluleAnimateur | null;
  /** True when the shifts differ — hours or stand — from one day to the other. */
  different: boolean;
}

interface DayAnimateur {
  nom: string;
  cellule: CelluleAnimateur;
}

function cellulesAnimateurs(
  postes: readonly PosteAffectation[],
  jour: number,
): Map<string, DayAnimateur> {
  const byAnimateur = new Map<string, { nom: string; vacations: VacationComparee[] }>();
  for (const poste of seatsOfDay(postes, jour)) {
    if (!poste.animateur || !poste.stand) {
      continue;
    }
    let entree = byAnimateur.get(poste.animateur.id);
    if (!entree) {
      entree = { nom: nomAffiche(poste), vacations: [] };
      byAnimateur.set(poste.animateur.id, entree);
    }
    entree.vacations.push({
      ...fenetre(poste),
      standId: poste.stand.id,
      standNom: poste.stand.nom || poste.stand.id,
    });
  }
  const cellules = new Map<string, DayAnimateur>();
  for (const [id, entree] of byAnimateur) {
    const vacations = entree.vacations.sort(
      (gauche, droite) =>
        gauche.heureDebut.localeCompare(droite.heureDebut) ||
        gauche.standNom.localeCompare(droite.standNom),
    );
    cellules.set(id, {
      nom: entree.nom,
      cellule: {
        vacations,
        minutes: vacations.reduce(
          (total, vacation) =>
            total + endMinutesOfDay(vacation.heureFin) - minutesOfDay(vacation.heureDebut),
          0,
        ),
      },
    });
  }
  return cellules;
}

function empreinteAnimateur(cellule: CelluleAnimateur | null): string {
  return cellule
    ? cellule.vacations
        .map((vacation) => `${vacation.heureDebut}-${vacation.heureFin}@${vacation.standId}`)
        .join('|')
    : 'absent';
}

/**
 * The rail comparison: one line per animateur holding a seat on either day,
 * by name. A line differs when the shifts — their hours or their stand —
 * are not the same on both days.
 */
export function alignerAnimateurs(
  postes: readonly PosteAffectation[],
  jourA: number,
  jourB: number,
): LigneAnimateurComparee[] {
  const a = cellulesAnimateurs(postes, jourA);
  const b = cellulesAnimateurs(postes, jourB);
  const ids = new Set([...a.keys(), ...b.keys()]);
  return [...ids]
    .map((animateurId) => {
      const celluleA = a.get(animateurId)?.cellule ?? null;
      const celluleB = b.get(animateurId)?.cellule ?? null;
      return {
        animateurId,
        nom: a.get(animateurId)?.nom ?? b.get(animateurId)?.nom ?? animateurId,
        a: celluleA,
        b: celluleB,
        different: empreinteAnimateur(celluleA) !== empreinteAnimateur(celluleB),
      };
    })
    .sort(
      (gauche, droite) =>
        gauche.nom.localeCompare(droite.nom) ||
        gauche.animateurId.localeCompare(droite.animateurId),
    );
}

/** The page's shared filters, applied to both days at once, plus the comparison's own switch. */
export interface FiltresComparaison {
  filtre: string;
  stand: string;
  animateur: string;
  seulementEcarts: boolean;
}

export function filterComparedStands(
  lignes: readonly LigneStandComparee[],
  filtres: FiltresComparaison,
): LigneStandComparee[] {
  return lignes.filter(
    (ligne) =>
      (!filtres.seulementEcarts || ligne.different) &&
      (!filtres.stand || ligne.standId === filtres.stand) &&
      (!filtres.animateur || ligne.animateurIds.includes(filtres.animateur)) &&
      correspondAuFiltre(filtres.filtre, [
        ligne.standNom,
        ...[ligne.a, ligne.b].flatMap((cellule) =>
          (cellule?.plages ?? []).flatMap((plage) => plage.noms),
        ),
      ]),
  );
}

export function filterComparedAnimateurs(
  lignes: readonly LigneAnimateurComparee[],
  filtres: FiltresComparaison,
): LigneAnimateurComparee[] {
  return lignes.filter(
    (ligne) =>
      (!filtres.seulementEcarts || ligne.different) &&
      (!filtres.animateur || ligne.animateurId === filtres.animateur) &&
      (!filtres.stand ||
        [ligne.a, ligne.b].some((cellule) =>
          cellule?.vacations.some((vacation) => vacation.standId === filtres.stand),
        )) &&
      correspondAuFiltre(filtres.filtre, [
        ligne.nom,
        ...[ligne.a, ligne.b].flatMap((cellule) =>
          (cellule?.vacations ?? []).map((vacation) => vacation.standNom),
        ),
      ]),
  );
}
