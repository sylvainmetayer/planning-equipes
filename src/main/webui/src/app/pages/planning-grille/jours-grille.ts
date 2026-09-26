// The day columns of the Planning page's grids, read from the page's own days:
// the weekday initial, the week-end and the week bands that turn a row of
// « J1 J2 J3 … » back into a calendar. The Jours de repos grid computed them
// for itself; both axes read them from here now.

import { parseDateKey } from '../../core/date-utils';
import { intlLocale } from '../../core/locale';
import { JourEvenement } from '../journee/journee';
import { JourGrille } from './planning-grille';

/**
 * One column per day of the plan, in order. Dates make the three marks exact;
 * without them the band is cut every seventh column so the eye still has a
 * step to count by, and the initial stays empty rather than being guessed.
 */
export function joursGrille(jours: readonly JourEvenement[]): JourGrille[] {
  const initiales = new Intl.DateTimeFormat(intlLocale(), { weekday: 'narrow' });
  return jours.map((jour, index) => {
    const jourSemaine = jour.date ? parseDateKey(jour.date).getDay() : null;
    return {
      key: jour.key,
      label: $localize`:@@heatmap.dayColumn:J${jour.jour}:jour:`,
      initiale: jour.date ? initiales.format(parseDateKey(jour.date)) : '',
      titre: jour.title,
      weekEnd: jourSemaine === 0 || jourSemaine === 6,
      // Never on the first column: the grid's own edge is already there.
      debutSemaine: index > 0 && (jourSemaine === null ? index % 7 === 0 : jourSemaine === 1),
    };
  });
}

/** `Prénom N.`: a cell of the grid holds several names in a day column's width. */
export function nomCourt(
  prenom: string | null | undefined,
  nom: string | null | undefined,
  id: string,
): string {
  const premier = (prenom ?? '').trim();
  const dernier = (nom ?? '').trim();
  if (!premier && !dernier) {
    return id;
  }
  if (!dernier) {
    return premier;
  }
  const initiale = `${dernier.charAt(0)}.`;
  return premier ? `${premier} ${initiale}` : dernier;
}
