// How the screens name the four phases of an edition and what each one does
// to the referential (ADR 0115). Functions rather than constants: `$localize`
// must run after `main.ts` loaded the translations, never at module scope.

import { EditionPhase } from './models';

/** The phases in the order of the cycle. */
export const EDITION_PHASES: readonly EditionPhase[] = [
  'PREPARATION',
  'CALCUL',
  'PUBLIEE',
  'TERMINEE',
];

export function phaseLabel(phase: EditionPhase): string {
  switch (phase) {
    case 'PREPARATION':
      return $localize`:@@phase.preparation:Préparation`;
    case 'CALCUL':
      return $localize`:@@phase.calcul:Calcul`;
    case 'PUBLIEE':
      return $localize`:@@phase.publiee:Publiée`;
    case 'TERMINEE':
      return $localize`:@@phase.terminee:Terminée`;
  }
}

/** What entering the phase freezes or opens — the confirmation says it before the click. */
export function phaseEffect(phase: EditionPhase): string {
  switch (phase) {
    case 'PREPARATION':
      return $localize`:@@phase.effet.preparation:Tout est modifiable : stands, créneaux, typologies, emplacements et compétences des animateurs.`;
    case 'CALCUL':
      return $localize`:@@phase.effet.calcul:Stands, créneaux, typologies, emplacements et compétences des animateurs sont figés. Disponibilités, souhaits, ajustements, verrous et consignes restent ouverts.`;
    case 'PUBLIEE':
      return $localize`:@@phase.effet.publiee:Structure figée (stands, créneaux, typologies, emplacements), compétences des animateurs modifiables. « Débloquer la structure » la rouvre jusqu'à la prochaine publication.`;
    case 'TERMINEE':
      return $localize`:@@phase.effet.terminee:L'événement est fini : tout est figé, comme en Calcul.`;
  }
}

/** « Depuis le 12/07 », the day the edition entered its phase, in the reader's locale. */
export function phaseSince(instant: string | undefined, locale: string): string {
  return instant
    ? new Date(instant).toLocaleDateString(locale, { day: '2-digit', month: '2-digit' })
    : '';
}
