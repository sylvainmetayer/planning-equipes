// Builders of the rest-days grid: one line per animateur, one column per day
// of the event, each cell saying whether the person works, rests or is
// unavailable that day.
//
// The other views answer "who is where"; this one answers the question the
// other way round — "who gets a day off, and when". A roster where somebody
// works every single day is a legal problem (repos hebdomadaire) long before
// it is a fairness one, and no existing screen shows it: the heatmap counts
// postes per day without ever naming a rest day, and the day rail only shows
// one day at a time.
//
// Pure functions, kept out of the component so the counting and the wording
// are unit-tested without rendering 150 rows.

import { parseDateKey } from '../../core/date-utils';
import { intlLocale } from '../../core/locale';
import { Animateur, ContrainteAdHoc, PosteAffectation } from '../../core/models';
import { correspondAuFiltre } from '../../core/text-filter';
import { endMinutesOfDay, formatDuration, minutesOfDay } from '../../core/time-of-day';

/**
 * What one animateur does on one day of the event.
 *
 * `repos` and `indisponible` are deliberately not the same state: the first is
 * a day off the plan granted them, the second a day they were never available
 * for. Merging the two would turn "this person never rests" into "this person
 * has four days off" for anybody who declared four days away.
 */
export type StatutJour = 'travaille' | 'repos' | 'indisponible';

/** One column: a day of the event, in the plan's own numbering. */
export interface ColonneJour {
  jour: number;
  date: string | null;
  /** `J3`, the column header — the date is on the header's tooltip. */
  label: string;
  /** `Jour 3 — 2026-07-16`, used by the tooltips and the accessible labels. */
  titre: string;
  /**
   * Narrow weekday initial (`L`, `M`, … in French) in the UI's own locale, or
   * an empty string on a plan whose créneaux carry no date. A month-long
   * edition is thirty columns of `J1 J2 J3 …`, which nobody can locate a
   * Saturday in; the initial is what turns the header back into a calendar.
   */
  initiale: string;
  /**
   * Saturday or Sunday. Not a rest day — the event runs on them, that is the
   * point of it — but the reading grid the weekly-rest rules are argued in,
   * so the column is tinted rather than left to be counted out.
   */
  weekEnd: boolean;
  /**
   * First column of a week band, never the very first column of the grid: a
   * Monday, or every seventh day on a dateless plan. Draws the rule that lets
   * the eye jump seven days at a time instead of counting them.
   */
  debutSemaine: boolean;
}

export interface CelluleJour {
  jour: number;
  statut: StatutJour;
  /** Vacations held that day; 0 unless `statut` is `travaille`. */
  postes: number;
  /** Minutes worked that day, the sum of the vacations' own windows. */
  minutes: number;
  /** `6 h 30` on a worked day, empty otherwise — the colour carries the rest. */
  label: string;
  tooltip: string;
  /**
   * True when the animateur declared the day unavailable *and* holds a
   * vacation on it anyway. The cell stays `travaille` — they are on the plan,
   * that is the fact — but the anomaly is named rather than swallowed.
   */
  conflit: boolean;
}

/**
 * A run of consecutive days in the same state, which is how the rest question
 * is actually asked: nobody reads "worked, worked, worked, worked, worked,
 * worked", they read "six days in a row". One `LigneRepos` is the same
 * information as its cells, collapsed — and on a month-long edition it is
 * three or four shapes instead of thirty cells, which is what lets the frise
 * draw a whole line without a single pixel of horizontal scrolling.
 */
export interface SegmentJours {
  statut: StatutJour;
  /** First and last day of the run, in the plan's own numbering, both inclusive. */
  premierJour: number;
  dernierJour: number;
  /** Number of days the run spans — the weight the frise gives it. */
  jours: number;
  /** True when any day of the run carries the assigned-yet-unavailable anomaly. */
  conflit: boolean;
  tooltip: string;
}

export interface LigneRepos {
  animateurId: string;
  nom: string;
  cellules: CelluleJour[];
  /** The same days as {@link cellules}, collapsed into runs. Never empty when the cells are not. */
  segments: SegmentJours[];
  joursTravailles: number;
  joursRepos: number;
  joursIndisponibles: number;
  /** Longest run of consecutive worked days — what the weekly-rest rules are about. */
  serieMax: number;
  /**
   * True when every single day of the event is worked. Not "no `repos` cell":
   * a day the person was unavailable on is a day they did not work, and the
   * weekly-rest rules count days off, not days granted.
   */
  sansRepos: boolean;
  /** What a screen reader reads for the whole line; the cells are read one by one too. */
  resume: string;
}

/** Per-day tally shown as the grid's footer: how many people are off, and who is left to call. */
export interface TotalJour {
  jour: number;
  travaillent: number;
  repos: number;
  indisponibles: number;
}

export interface TableauRepos {
  jours: ColonneJour[];
  lignes: LigneRepos[];
}

/**
 * One bar of the per-day rest histogram: the same figure as the grid's footer
 * row, given a height. A row of thirty numbers is read one number at a time;
 * the shape of the same thirty is read at once, and the day nobody rests on is
 * what this screen is looking for.
 */
export interface BarreJour {
  jour: number;
  label: string;
  initiale: string;
  weekEnd: boolean;
  debutSemaine: boolean;
  repos: number;
  /** 0 to 100: the share of the counted lines resting that day, the bar's height. */
  part: number;
  tooltip: string;
}

/** Display label of an animateur, disambiguated by id when two share a name. */
function libelles(animateurs: Animateur[]): Map<string, string> {
  const bruts = new Map<string, string>();
  animateurs.forEach((animateur) =>
    bruts.set(
      animateur.id,
      `${animateur.prenom ?? ''} ${animateur.nom ?? ''}`.trim() || animateur.id,
    ),
  );
  const compte = new Map<string, number>();
  bruts.forEach((label) => compte.set(label, (compte.get(label) ?? 0) + 1));
  const libelle = new Map<string, string>();
  bruts.forEach((label, id) =>
    libelle.set(id, (compte.get(label) ?? 0) > 1 ? `${label} (${id})` : label),
  );
  return libelle;
}

/**
 * Every animateur the grid has a line for: the edition's referential first —
 * somebody who holds no seat at all rests every day, which is precisely what
 * this screen is for — plus, defensively, anyone holding a seat who is missing
 * from it.
 */
function tousLesAnimateurs(postes: PosteAffectation[], animateurs: Animateur[]): Animateur[] {
  const connus = new Map(animateurs.map((animateur) => [animateur.id, animateur]));
  postes.forEach((poste) => {
    if (poste.animateur && !connus.has(poste.animateur.id)) {
      connus.set(poste.animateur.id, poste.animateur);
    }
  });
  return Array.from(connus.values());
}

/**
 * Animateurs a recorded ad hoc exception keeps off the **whole** event: an
 * `INDISPONIBILITE_FORCEE` carrying neither stand nor créneau. Narrower ones
 * are left out on purpose — an exception on one créneau or one stand still
 * leaves the day workable, and reading it as a day off would invent rest days
 * nobody granted.
 */
function indisponiblesSurToutLEvenement(contraintes: ContrainteAdHoc[]): Set<string> {
  const cibles = new Set<string>();
  contraintes.forEach((contrainte) => {
    if (contrainte.type !== 'INDISPONIBILITE_FORCEE' || contrainte.stand || contrainte.creneau) {
      return;
    }
    (contrainte.animateursConcernes ?? []).forEach((target) => cibles.add(target.id));
  });
  return cibles;
}

/** Minutes one poste occupies: its own narrowed window when it has one, its créneau's otherwise. */
function minutesDuPoste(poste: PosteAffectation): number {
  const creneau = poste.creneau!;
  const debut = minutesOfDay(poste.heureDebutEffective ?? creneau.heureDebut);
  const fin = endMinutesOfDay(poste.heureFinEffective ?? creneau.heureFin);
  return Math.max(fin - debut, 0);
}

interface ChargeJour {
  postes: number;
  minutes: number;
}

/**
 * The day columns, in the plan's own order, each carrying what a calendar
 * reading of them needs: the weekday initial, the week-end, and the week
 * boundaries.
 *
 * Dates are what makes all three exact; without them the plan is a bare
 * sequence of days, and the band is cut every seventh column so the eye still
 * has a step to count by. The initial then stays empty rather than being
 * guessed — a wrong Saturday is worse than no Saturday on a screen read for
 * weekly rest.
 */
function colonnes(dates: Map<number, string | null>): ColonneJour[] {
  const initiales = new Intl.DateTimeFormat(intlLocale(), { weekday: 'narrow' });
  return Array.from(dates.entries())
    .sort((left, right) => left[0] - right[0])
    .map(([jour, date], index) => {
      const jourSemaine = date ? parseDateKey(date).getDay() : null;
      return {
        jour,
        date,
        label: $localize`:@@heatmap.dayColumn:J${jour}:jour:`,
        titre: date
          ? $localize`:@@calendarDay.dayTitleWithDate:Jour ${jour}:jour: — ${date}:date:`
          : $localize`:@@calendarDay.dayTitle:Jour ${jour}:jour:`,
        initiale: date ? initiales.format(parseDateKey(date)) : '',
        weekEnd: jourSemaine === 0 || jourSemaine === 6,
        // Never on the first column: the grid's own edge is already there, and
        // a rule drawn over it only thickens a border.
        debutSemaine: index > 0 && (jourSemaine === null ? index % 7 === 0 : jourSemaine === 1),
      };
    });
}

/**
 * The whole grid, built from the plan alone. `animateurs` is the edition's
 * referential and `contraintes` its recorded exceptions, both read-only.
 *
 * The days are the ones the plan actually holds créneaux for: a day nothing is
 * scheduled on is not a rest day, it is a day outside the event.
 */
export function buildTableauRepos(
  postes: PosteAffectation[],
  animateurs: Animateur[],
  contraintes: ContrainteAdHoc[] = [],
): TableauRepos {
  const dates = new Map<number, string | null>();
  const charges = new Map<string, Map<number, ChargeJour>>();
  postes.forEach((poste) => {
    const creneau = poste.creneau;
    if (!creneau) {
      return;
    }
    if (!dates.has(creneau.jour) || (!dates.get(creneau.jour) && creneau.date)) {
      dates.set(creneau.jour, creneau.date ?? null);
    }
    const animateur = poste.animateur;
    if (!animateur) {
      return;
    }
    let parJour = charges.get(animateur.id);
    if (!parJour) {
      parJour = new Map();
      charges.set(animateur.id, parJour);
    }
    const charge = parJour.get(creneau.jour) ?? { postes: 0, minutes: 0 };
    charge.postes += 1;
    charge.minutes += minutesDuPoste(poste);
    parJour.set(creneau.jour, charge);
  });

  const jours: ColonneJour[] = colonnes(dates);

  const effectif = tousLesAnimateurs(postes, animateurs);
  const noms = libelles(effectif);
  const ecartes = indisponiblesSurToutLEvenement(contraintes);

  const lignes = effectif
    .map((animateur) =>
      buildLigne(
        animateur,
        noms.get(animateur.id) ?? animateur.id,
        jours,
        charges.get(animateur.id) ?? new Map(),
        ecartes.has(animateur.id),
      ),
    )
    // Longest run of consecutive worked days first, then the busiest: the top
    // of the grid is the list of people to look at, not the beginning of the
    // alphabet.
    .sort(
      (left, right) =>
        right.serieMax - left.serieMax ||
        right.joursTravailles - left.joursTravailles ||
        left.nom.localeCompare(right.nom),
    );

  return { jours, lignes };
}

function buildLigne(
  animateur: Animateur,
  nom: string,
  jours: ColonneJour[],
  charges: Map<number, ChargeJour>,
  ecarteDeToutLEvenement: boolean,
): LigneRepos {
  let joursTravailles = 0;
  let joursRepos = 0;
  let joursIndisponibles = 0;
  let serieMax = 0;
  let serie = 0;

  const declarees = new Set(animateur.joursIndisponibles ?? []);
  const cellules = jours.map((jour) => {
    const charge = charges.get(jour.jour);
    const declare = jour.date !== null && declarees.has(jour.date);
    const indisponible = declare || ecarteDeToutLEvenement;
    if (charge && charge.postes > 0) {
      joursTravailles += 1;
      serie += 1;
      serieMax = Math.max(serieMax, serie);
      const duree = formatDuration(charge.minutes);
      return {
        jour: jour.jour,
        statut: 'travaille' as const,
        postes: charge.postes,
        minutes: charge.minutes,
        label: duree,
        tooltip: declare
          ? $localize`:@@repos.cell.conflit:${nom}:animateur: — ${jour.titre}:jour: : ${charge.postes}:count: vacation(s), ${duree}:duree: — alors que la journée est déclarée indisponible`
          : $localize`:@@repos.cell.travaille:${nom}:animateur: — ${jour.titre}:jour: : ${charge.postes}:count: vacation(s), ${duree}:duree:`,
        conflit: declare,
      };
    }
    serie = 0;
    if (indisponible) {
      joursIndisponibles += 1;
      return {
        jour: jour.jour,
        statut: 'indisponible' as const,
        postes: 0,
        minutes: 0,
        label: '',
        tooltip: $localize`:@@repos.cell.indisponible:${nom}:animateur: — ${jour.titre}:jour: : indisponible, la journée n'était pas mobilisable`,
        conflit: false,
      };
    }
    joursRepos += 1;
    return {
      jour: jour.jour,
      statut: 'repos' as const,
      postes: 0,
      minutes: 0,
      label: '',
      tooltip: $localize`:@@repos.cell.repos:${nom}:animateur: — ${jour.titre}:jour: : jour de repos, disponible mais non affecté`,
      conflit: false,
    };
  });

  const sansRepos = joursTravailles > 0 && joursTravailles === cellules.length;
  const base = $localize`:@@repos.row.resume:${nom}:animateur: — ${joursTravailles}:travailles: jour(s) travaillé(s), ${joursRepos}:repos: jour(s) de repos, ${joursIndisponibles}:indisponibles: jour(s) d'indisponibilité, ${serieMax}:serie: jour(s) travaillé(s) d'affilée au plus`;

  return {
    animateurId: animateur.id,
    nom,
    cellules,
    segments: segmentsDe(cellules, nom),
    joursTravailles,
    joursRepos,
    joursIndisponibles,
    serieMax,
    sansRepos,
    resume: sansRepos
      ? $localize`:@@repos.row.resumeSansRepos:${base}:ligne: — attention, aucun jour de repos sur tout l'événement`
      : base,
  };
}

/**
 * The cells of one line collapsed into runs of the same state, in order.
 *
 * Adjacent cells merge whatever their day numbers: the columns are the days
 * the plan actually holds créneaux for, so two consecutive columns can be day 3
 * and day 5 — a run is a run of *columns*, which is what the frise draws, and
 * its label names the two days it spans rather than claiming the gap.
 */
function segmentsDe(cellules: CelluleJour[], nom: string): SegmentJours[] {
  const segments: SegmentJours[] = [];
  cellules.forEach((cellule) => {
    const courant = segments.at(-1);
    if (courant?.statut === cellule.statut) {
      courant.dernierJour = cellule.jour;
      courant.jours += 1;
      courant.conflit = courant.conflit || cellule.conflit;
      return;
    }
    segments.push({
      statut: cellule.statut,
      premierJour: cellule.jour,
      dernierJour: cellule.jour,
      jours: 1,
      conflit: cellule.conflit,
      tooltip: '',
    });
  });
  segments.forEach((segment) => (segment.tooltip = tooltipSegment(nom, segment)));
  return segments;
}

/** What a run of days says out loud: who, which days, and how many of them in that state. */
function tooltipSegment(nom: string, segment: SegmentJours): string {
  const plage =
    segment.jours === 1
      ? $localize`:@@heatmap.dayColumn:J${segment.premierJour}:jour:`
      : $localize`:@@repos.segment.plage:J${segment.premierJour}:debut: à J${segment.dernierJour}:fin:`;
  const jours = segment.jours;
  switch (segment.statut) {
    case 'travaille':
      return $localize`:@@repos.segment.travaille:${nom}:animateur: — ${plage}:plage: : ${jours}:jours: jour(s) travaillé(s) d'affilée`;
    case 'repos':
      return $localize`:@@repos.segment.repos:${nom}:animateur: — ${plage}:plage: : ${jours}:jours: jour(s) de repos`;
    case 'indisponible':
      return $localize`:@@repos.segment.indisponible:${nom}:animateur: — ${plage}:plage: : ${jours}:jours: jour(s) d'indisponibilité`;
  }
}

/**
 * How many people work, rest and are unavailable on each day. Computed over
 * whichever rows are handed in — the screen passes the *displayed* ones, so a
 * filtered grid never shows a footer counting rows that are not on it.
 */
export function totauxParJour(jours: ColonneJour[], lignes: LigneRepos[]): TotalJour[] {
  return jours.map((jour, index) => {
    const total: TotalJour = { jour: jour.jour, travaillent: 0, repos: 0, indisponibles: 0 };
    lignes.forEach((ligne) => {
      const statut = ligne.cellules[index]?.statut;
      if (statut === 'travaille') {
        total.travaillent += 1;
      } else if (statut === 'repos') {
        total.repos += 1;
      } else if (statut === 'indisponible') {
        total.indisponibles += 1;
      }
    });
    return total;
  });
}

/**
 * Rows matching the screen's two filters, in the grid's own order. The name
 * search goes through `correspondAuFiltre`, so it behaves like every other
 * quick filter of the application: accent- and case-insensitive, terms AND-ed.
 */
export function filtrerLignes(
  lignes: LigneRepos[],
  recherche: string,
  sansReposSeulement: boolean,
): LigneRepos[] {
  return lignes.filter(
    (ligne) =>
      correspondAuFiltre(recherche, [ligne.nom]) && (!sansReposSeulement || ligne.sansRepos),
  );
}

/**
 * The per-day rest tally turned into a histogram, over whichever rows are
 * handed in — the same ones the footer counts, so the bar and the number under
 * it never disagree.
 *
 * The height is a *share*, not a count: a day where four people out of six
 * rest and a day where four out of a hundred do are the same number and not
 * remotely the same day, and it is the second one this screen is opened for.
 */
export function histogrammeRepos(jours: ColonneJour[], lignes: LigneRepos[]): BarreJour[] {
  const effectif = lignes.length;
  return totauxParJour(jours, lignes).map((total, index) => {
    const colonne = jours[index];
    return {
      jour: colonne.jour,
      label: colonne.label,
      initiale: colonne.initiale,
      weekEnd: colonne.weekEnd,
      debutSemaine: colonne.debutSemaine,
      repos: total.repos,
      part: effectif === 0 ? 0 : Math.round((total.repos / effectif) * 100),
      tooltip: $localize`:@@repos.histogramme.tooltip:${colonne.titre}:jour: : ${total.repos}:repos: au repos sur ${effectif}:effectif:, ${total.indisponibles}:indisponibles: indisponible(s)`,
    };
  });
}

/** One line of the "most strained" panel: the row, and the two figures it is there for. */
export interface LigneTendue {
  ligne: LigneRepos;
  detail: string;
}

/**
 * The lines worth looking at first, capped: the grid is already sorted by
 * longest run then by load, so this is its head — minus anybody working no day
 * at all, who has nothing tense about them and would otherwise fill the list
 * of an edition that has just been created.
 */
export function lignesTendues(lignes: LigneRepos[], maximum = 5): LigneTendue[] {
  return lignes
    .filter((ligne) => ligne.serieMax > 0)
    .slice(0, maximum)
    .map((ligne) => ({
      ligne,
      detail: $localize`:@@repos.tendues.detail:${ligne.serieMax}:serie: j d'affilée, ${ligne.joursRepos}:repos: j de repos`,
    }));
}
