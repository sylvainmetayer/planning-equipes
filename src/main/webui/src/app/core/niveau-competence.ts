// How an appreciation level reads on screen: its word, and the letter the
// grids print in a cell. Never the enum's own name — `DEBUTANT` is a wire
// value, not a word anybody reads. Called from methods only: `$localize`
// resolves once `main.ts` has loaded the translations.

import { NiveauCompetence } from './models';

/** The three levels, in the order the keys 1 to 3 and the menus give them. */
export const NIVEAUX_COMPETENCE: readonly NiveauCompetence[] = ['DEBUTANT', 'AUTONOME', 'REFERENT'];

export function libelleNiveau(niveau: NiveauCompetence | null): string {
  switch (niveau) {
    case 'DEBUTANT':
      return $localize`:@@competences.niveau.debutant:Débutant`;
    case 'AUTONOME':
      return $localize`:@@competences.niveau.autonome:Autonome`;
    case 'REFERENT':
      return $localize`:@@competences.niveau.referent:Référent`;
    default:
      return $localize`:@@competences.niveau.aucun:Aucune appréciation`;
  }
}

export function lettreNiveau(niveau: NiveauCompetence | null): string {
  switch (niveau) {
    case 'DEBUTANT':
      return $localize`:@@competences.lettre.debutant:D`;
    case 'AUTONOME':
      return $localize`:@@competences.lettre.autonome:A`;
    case 'REFERENT':
      return $localize`:@@competences.lettre.referent:R`;
    default:
      return '';
  }
}
