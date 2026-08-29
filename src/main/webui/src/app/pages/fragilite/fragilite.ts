// Presentation rules of the « Fragilité du planning » screen, kept apart from
// the component so they are unit-tested without rendering — same split as
// `ouvertures.ts` next door.
//
// Nothing here recomputes fragility: `GET /api/fragilite` already derived it
// from the persisted seats and the competence referential, without a solve.
// This file only decides what to show and in which order.

import {
  AnimateurFragilite,
  CompetenceRare,
  RapportFragilite,
  SeveriteFragilite,
} from '../../core/models';
import { correspondAuFiltre } from '../../core/text-filter';

/** Which of the two questions the screen is showing. */
export type VueFragilite = 'ANIMATEURS' | 'COMPETENCES';

export function lireVue(valeur: string | null): VueFragilite {
  return valeur === 'COMPETENCES' ? 'COMPETENCES' : 'ANIMATEURS';
}

/** Rows kept: everything, or only what is worth acting on. */
export type FiltreFragilite = 'TOUS' | 'CRITIQUES';

export function lireFiltre(valeur: string | null): FiltreFragilite {
  return valeur === 'CRITIQUES' ? 'CRITIQUES' : 'TOUS';
}

/**
 * Ranking is done server-side and deliberately not redone here: the criticality
 * order is part of the answer, not a display choice. Filtering preserves it.
 */
export function filtrerAnimateurs(
  rapport: RapportFragilite | null,
  filtre: FiltreFragilite,
  recherche: string,
): AnimateurFragilite[] {
  return (rapport?.animateurs ?? []).filter(
    (ligne) =>
      (filtre === 'TOUS' || ligne.postesIrremplacables > 0) &&
      correspondAuFiltre(recherche, [ligne.animateurId, ligne.nom]),
  );
}

export function filtrerCompetences(
  rapport: RapportFragilite | null,
  filtre: FiltreFragilite,
  recherche: string,
): CompetenceRare[] {
  return (rapport?.competencesRares ?? []).filter(
    (ligne) =>
      (filtre === 'TOUS' || ligne.severite === 'CRITIQUE') &&
      correspondAuFiltre(recherche, [
        ligne.standId,
        ligne.standNom,
        ligne.nom,
        ligne.typologies.join(' '),
      ]),
  );
}

/** CSS class driving a row's colour, so severity reads without the label. */
export function classeSeverite(severite: SeveriteFragilite): string {
  switch (severite) {
    case 'CRITIQUE':
      return 'fragilite-critique';
    case 'ELEVEE':
      return 'fragilite-elevee';
    case 'MODEREE':
      return 'fragilite-moderee';
  }
}

/** Icon of a severity, so the table does not rely on colour alone. */
export function iconeSeverite(severite: SeveriteFragilite): string {
  switch (severite) {
    case 'CRITIQUE':
      return 'dangerous';
    case 'ELEVEE':
      return 'warning';
    case 'MODEREE':
      return 'info';
  }
}

export interface SyntheseFragilite {
  animateurs: number;
  irremplacables: number;
  competencesRares: number;
  sansSpecialiste: number;
  groupes: number;
  dejaSousEffectif: number;
  ninjaConfigure: boolean;
}

export function synthese(rapport: RapportFragilite): SyntheseFragilite {
  return {
    animateurs: rapport.animateurs.length,
    irremplacables: rapport.animateursIrremplacables,
    competencesRares: rapport.totalCompetencesRares,
    sansSpecialiste: rapport.groupesSansSpecialiste,
    groupes: rapport.groupesAnalyses,
    dejaSousEffectif: rapport.groupesDejaSousEffectif,
    ninjaConfigure: rapport.ninjaConfigure,
  };
}

/** `2026-07-08` → `08/07`, and `10:00:00` → `10:00`. */
export function libelleJour(date: string): string {
  const [, mois, jour] = date.split('-');
  return `${jour}/${mois}`;
}

export function heure(valeur: string): string {
  return valeur.slice(0, 5);
}
