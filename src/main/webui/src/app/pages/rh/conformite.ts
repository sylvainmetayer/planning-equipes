// The pure side of the compliance report screen.

import { PersonneConformite } from '../../core/models';

/** « 38,5 h »: hours with a comma, like the exports. */
export function hoursLabel(hours: number): string {
  return `${hours.toLocaleString('fr-FR', { minimumFractionDigits: 0, maximumFractionDigits: 2 })} h`;
}

/** The minors of the report: the section an inspector reads first. */
export function minorsOf(people: readonly PersonneConformite[]): PersonneConformite[] {
  return people.filter((person) => person.mineur);
}

/** Whether a person has anything to report: a week over its ceiling or a breach of a rule. */
export function hasFindings(person: PersonneConformite): boolean {
  return person.semainesEnDepassement.length > 0 || person.ecartsDetail.length > 0;
}

/** The shortest rest between two worked days, or a dash when fewer than two days are worked. */
export function restLabel(person: PersonneConformite): string {
  return person.reposQuotidienMinHeures === null || person.reposQuotidienMinHeures === undefined
    ? '—'
    : hoursLabel(person.reposQuotidienMinHeures);
}
