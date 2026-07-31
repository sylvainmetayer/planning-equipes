// Pure computation for the staffing-need calculator, kept separate from the
// component so it can be unit-tested without TestBed.
//
// Minimum headcount follows the "peak load" reading: an animateur can work
// several créneaux across the festival, so the minimum team size is bounded
// by the busiest créneau (sum of effectifMin over its open stands), not the
// sum across every créneau. Per open stand, the majeur/mineur split targets
// majeurs >= mineurs (ceil/floor), which satisfies both the hard constraint
// (>=1 majeur as soon as a mineur is present) and the medium "repartition"
// target — a stand reserved to majeurs is 100% majeurs. This is a lower
// bound: it ignores compétences, disponibilités and legal rest constraints,
// which only the solver enforces.

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
}

export interface StaffingSummary {
  parCreneau: CreneauStaffing[];
  minimumTotal: number;
  minimumMajeurs: number;
  minimumMineurs: number;
  creneauCritique: CreneauStaffing | null;
}

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

export function computeStaffingSummary(stands: Stand[], creneaux: Creneau[]): StaffingSummary {
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
        mineurs
      };
    })
    .sort((a, b) => (a.date === b.date ? a.heureDebut.localeCompare(b.heureDebut) : a.date.localeCompare(b.date)));

  const creneauCritique = parCreneau.reduce<CreneauStaffing | null>(
    (peak, current) => (!peak || current.total > peak.total ? current : peak),
    null
  );

  return {
    parCreneau,
    minimumTotal: creneauCritique?.total ?? 0,
    minimumMajeurs: creneauCritique?.majeurs ?? 0,
    minimumMineurs: creneauCritique?.mineurs ?? 0,
    creneauCritique
  };
}
