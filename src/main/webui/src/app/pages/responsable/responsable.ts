// What the « Mon stand » screen says about a shift, kept out of the component so it is
// tested without rendering. Wordings are built on call, never at module scope.

import { StandResponsable, VacationResponsable } from '../../core/models';

/** « 10:00 – 12:00 », read off the two local date-times of a shift. */
export function windowLabel(vacation: Pick<VacationResponsable, 'debut' | 'fin'>): string {
  return `${vacation.debut.slice(11, 16)} – ${vacation.fin.slice(11, 16)}`;
}

/** The staffing of a shift: its seats, and how many are still to fill. */
export function staffingLabel(vacation: Pick<VacationResponsable, 'pourvus' | 'vides'>): string {
  const places = vacation.pourvus + vacation.vides;
  const base =
    places === 1
      ? $localize`:@@responsable.effectif.une:1 place`
      : $localize`:@@responsable.effectif.n:${places}:places: places`;
  return vacation.vides > 0
    ? $localize`:@@responsable.effectif.vides:${base}:base: — ${vacation.vides}:vides: à pourvoir`
    : base;
}

/** « Prénom Nom », the only thing a name is. */
export function personLabel(personne: { prenom: string; nom: string }): string {
  return `${personne.prenom} ${personne.nom}`.trim();
}

/** The names holding a shift, comma separated. */
export function personLabels(vacation: Pick<VacationResponsable, 'personnes'>): string {
  return vacation.personnes.map(personLabel).join(', ');
}

/** The shifts of a stand grouped by day, in the order they start. */
export function daysOf(stand: Pick<StandResponsable, 'vacations'>): {
  jour: string;
  vacations: VacationResponsable[];
}[] {
  const byDay = new Map<string, VacationResponsable[]>();
  for (const vacation of stand.vacations) {
    const jour = vacation.debut.slice(0, 10);
    byDay.set(jour, [...(byDay.get(jour) ?? []), vacation]);
  }
  return [...byDay].map(([jour, vacations]) => ({ jour, vacations }));
}
