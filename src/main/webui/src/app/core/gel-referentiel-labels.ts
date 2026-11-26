// How the screens name the four families the phase of the edition freezes
// (ADR 0052, 0115). Functions rather than constants: `$localize` must run after
// `main.ts` loaded the translations, never at module scope.

import { FreezeFamily } from './models';

/** The family, as a heading or a switch label names it. */
export function familyLabel(family: FreezeFamily): string {
  switch (family) {
    case 'STANDS':
      return $localize`:@@gel.famille.stands:Stands`;
    case 'CRENEAUX':
      return $localize`:@@gel.famille.creneaux:Créneaux`;
    case 'TYPOLOGIES_EMPLACEMENTS':
      return $localize`:@@gel.famille.typologiesEmplacements:Typologies et emplacements`;
    case 'COMPETENCES':
      return $localize`:@@gel.famille.competences:Compétences des animateurs`;
  }
}

/** « 12/07 », the day a family was frozen, in the reader's locale. */
export function frozenOn(instant: string | null, locale: string): string {
  if (!instant) {
    return '';
  }
  return new Date(instant).toLocaleDateString(locale, { day: '2-digit', month: '2-digit' });
}
