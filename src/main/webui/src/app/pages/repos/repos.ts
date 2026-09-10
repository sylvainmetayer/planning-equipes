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

export interface LigneRepos {
  animateurId: string;
  nom: string;
  cellules: CelluleJour[];
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

  const jours: ColonneJour[] = Array.from(dates.entries())
    .sort((left, right) => left[0] - right[0])
    .map(([jour, date]) => ({
      jour,
      date,
      label: $localize`:@@heatmap.dayColumn:J${jour}:jour:`,
      titre: date
        ? $localize`:@@calendarDay.dayTitleWithDate:Jour ${jour}:jour: — ${date}:date:`
        : $localize`:@@calendarDay.dayTitle:Jour ${jour}:jour:`,
    }));

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
