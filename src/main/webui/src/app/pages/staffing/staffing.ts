// Pure computation for the staffing-need calculator, kept separate from the
// component so it can be unit-tested without TestBed.
//
// Two lower bounds are combined, and the binding (larger) one wins:
//
// - "peak" — the busiest créneau's open seats (effectifMin summed over its
//   open stands). Assumes an animateur can freely rotate across every
//   créneau of the festival with no legal cap on hours.
// - "workload" — total person-hours demanded across every créneau, divided
//   by the legal weekly-hour cap times the number of ISO weeks the festival
//   spans. This is the bound the peak reading ignores: on the app's own
//   reference scenario (scenario-complet.yaml, 58 peak seats/créneau over 12
//   days / 2 ISO weeks), the peak bound alone is 58, but a Timefold solve
//   with only 58 animateurs leaves 920/2088 seats unfilled (hard score
//   -979) — nowhere close. The workload bound raises that to 102, closer but
//   still short: 102 animateurs solved to 221/2088 unfilled (hard score
//   -274), only the full 150-animateur roster reaches hard score 0.
//
// Both bounds stay optimistic: neither accounts for compétences (an
// animateur can only fill a seat matching one of their typologies) or the
// real clustering of individual disponibilités, which is exactly the
// remaining gap measured above. There is no principled universal fudge
// factor to close that gap from stands/créneaux alone — it depends on how
// concentrated competences and unavailability are in the actual roster. The
// only way to get an exact number is to enter real animateurs and run the
// solver/feasibility check (the "Constraints" page).

import { Creneau, Stand } from '../../core/models';

export interface CreneauStaffing {
  creneauId: string;
  jour: number;
  date: string;
  heureDebut: string;
  heureFin: string;
  standsOuverts: number;
  total: number;
  majeurs: number;
  mineurs: number;
  dureeHeures: number;
}

export interface StaffingSummary {
  parCreneau: CreneauStaffing[];
  peakTotal: number;
  creneauCritique: CreneauStaffing | null;
  totalDemandeHeures: number;
  nombreSemaines: number;
  capaciteHeuresParAnimateur: number;
  workloadTotal: number;
  minimumTotal: number;
  bindingBound: 'peak' | 'workload';
  minimumMajeurs: number;
  minimumMineurs: number;
}

const DUREE_HEBDOMADAIRE_MAX_MINUTES_PAR_DEFAUT = 48 * 60;

function seatsFor(stand: Stand): number {
  return Math.max(1, stand.effectifMin);
}

function splitMajeursMineurs(seats: number, reserveMajeurs: boolean): { majeurs: number; mineurs: number } {
  if (reserveMajeurs) {
    return { majeurs: seats, mineurs: 0 };
  }
  const majeurs = Math.ceil(seats / 2);
  return { majeurs, mineurs: seats - majeurs };
}

function openStandsFor(creneau: Creneau, stands: Stand[], standsById: Map<string, Stand>): Stand[] {
  if (creneau.standsOuvertsIds.length === 0) {
    return stands;
  }
  return creneau.standsOuvertsIds
    .map((id) => standsById.get(id))
    .filter((stand): stand is Stand => !!stand);
}

/** Mirrors the backend's `Creneau.getDureeMinutes()`: handles a slot crossing midnight. */
function dureeMinutes(creneau: Creneau): number {
  if (!creneau.heureDebut || !creneau.heureFin) {
    return 0;
  }
  const debut = toMinutesSinceMidnight(creneau.heureDebut);
  const fin = toMinutesSinceMidnight(creneau.heureFin);
  return fin > debut ? fin - debut : 24 * 60 - debut + fin;
}

function toMinutesSinceMidnight(hhmm: string): number {
  const [hours, minutes] = hhmm.split(':').map(Number);
  return hours * 60 + minutes;
}

/** ISO 8601 week key (e.g. "2026-W28"), mirroring the backend's `Creneau.semaineIso()`. */
function isoWeekKey(dateKey: string): string {
  const [year, month, day] = dateKey.split('-').map(Number);
  const date = new Date(year, month - 1, day);
  const dayNr = (date.getDay() + 6) % 7;
  date.setDate(date.getDate() - dayNr + 3);
  const isoYear = date.getFullYear();
  const jan4DayNr = (new Date(isoYear, 0, 4).getDay() + 6) % 7;
  const firstThursday = new Date(isoYear, 0, 4 - jan4DayNr + 3);
  const week = 1 + Math.round((date.getTime() - firstThursday.getTime()) / (7 * 24 * 60 * 60 * 1000));
  return `${isoYear}-W${String(week).padStart(2, '0')}`;
}

export function computeStaffingSummary(
  stands: Stand[],
  creneaux: Creneau[],
  dureeHebdomadaireMaxMinutes = DUREE_HEBDOMADAIRE_MAX_MINUTES_PAR_DEFAUT
): StaffingSummary {
  const standsById = new Map(stands.map((stand) => [stand.id, stand]));

  const parCreneau = creneaux
    .map((creneau) => {
      const openStands = openStandsFor(creneau, stands, standsById);
      let total = 0;
      let majeurs = 0;
      let mineurs = 0;
      for (const stand of openStands) {
        const seats = seatsFor(stand);
        const split = splitMajeursMineurs(seats, stand.reserveMajeurs);
        total += seats;
        majeurs += split.majeurs;
        mineurs += split.mineurs;
      }
      return {
        creneauId: creneau.id,
        jour: creneau.jour,
        date: creneau.date,
        heureDebut: creneau.heureDebut,
        heureFin: creneau.heureFin,
        standsOuverts: openStands.length,
        total,
        majeurs,
        mineurs,
        dureeHeures: dureeMinutes(creneau) / 60
      };
    })
    .sort((a, b) => (a.date === b.date ? a.heureDebut.localeCompare(b.heureDebut) : a.date.localeCompare(b.date)));

  const creneauCritique = parCreneau.reduce<CreneauStaffing | null>(
    (peak, current) => (!peak || current.total > peak.total ? current : peak),
    null
  );
  const peakTotal = creneauCritique?.total ?? 0;

  const totalDemandeHeures = parCreneau.reduce((sum, row) => sum + row.total * row.dureeHeures, 0);
  const nombreSemaines = new Set(parCreneau.map((row) => isoWeekKey(row.date))).size;
  const capaciteHeuresParAnimateur = nombreSemaines * (dureeHebdomadaireMaxMinutes / 60);
  const workloadTotal = capaciteHeuresParAnimateur > 0 ? Math.ceil(totalDemandeHeures / capaciteHeuresParAnimateur) : 0;

  const minimumTotal = Math.max(peakTotal, workloadTotal);
  const bindingBound: 'peak' | 'workload' = workloadTotal > peakTotal ? 'workload' : 'peak';

  const aggregateTotal = parCreneau.reduce((sum, row) => sum + row.total, 0);
  const aggregateMajeurs = parCreneau.reduce((sum, row) => sum + row.majeurs, 0);
  const majeursShare = aggregateTotal > 0 ? aggregateMajeurs / aggregateTotal : 0.5;
  const minimumMajeurs = Math.ceil(minimumTotal * majeursShare);
  const minimumMineurs = minimumTotal - minimumMajeurs;

  return {
    parCreneau,
    peakTotal,
    creneauCritique,
    totalDemandeHeures,
    nombreSemaines,
    capaciteHeuresParAnimateur,
    workloadTotal,
    minimumTotal,
    bindingBound,
    minimumMajeurs,
    minimumMineurs
  };
}
