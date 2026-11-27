// Links that several sections of the guide point to: one definition each, so
// a renamed tab or label changes in one place. Functions, never constants:
// `$localize` only resolves once `main.ts` has loaded the translations.

import { HelpLink } from '../help-section';

export const lienVerrouillages = (): HelpLink => ({
  route: '/regles',
  queryParams: { onglet: 'verrouillages' },
  label: $localize`:@@consignesSolveur.onglet.verrouillages:Verrouillages`,
});

export const lienConsignes = (): HelpLink => ({
  route: '/regles',
  queryParams: { onglet: 'consignes' },
  label: $localize`:@@consignesSolveur.onglet.consignes:Consignes`,
});

export const lienAjustements = (): HelpLink => ({
  route: '/regles',
  queryParams: { onglet: 'ajustements' },
  label: $localize`:@@consignesSolveur.onglet.ajustements:Ajustements`,
});

export const lienSolve = (): HelpLink => ({
  route: '/journee',
  queryParams: { onglet: 'calculer' },
  label: $localize`:@@aide.link.calculer:Planning › Calculer`,
});

export const lienVersions = (): HelpLink => ({
  route: '/journee',
  queryParams: { onglet: 'versions' },
  label: $localize`:@@nav.link.versions:Versions du plan`,
});

export const lienStaffing = (): HelpLink => ({
  route: '/diagnostic',
  queryParams: { onglet: 'besoin' },
  label: $localize`:@@aide.link.staffing:Besoin en animateurs`,
});

export const lienFragilite = (): HelpLink => ({
  route: '/diagnostic',
  queryParams: { onglet: 'risques', section: 'fragilite' },
  label: $localize`:@@aide.link.fragilite:Fragilité du planning`,
});

export const lienPauses = (): HelpLink => ({
  route: '/diagnostic',
  queryParams: { onglet: 'problemes' },
  label: $localize`:@@aide.link.pausesProblemes:Pauses sans relais (Problèmes)`,
});

export const lienTypologies = (): HelpLink => ({
  route: '/stands',
  queryParams: { onglet: 'typologies' },
  label: $localize`:@@nav.link.typologies:Typologies`,
});

export const lienDisponibilites = (): HelpLink => ({
  route: '/animateurs',
  queryParams: { onglet: 'disponibilites' },
  label: $localize`:@@nav.link.disponibilites:Disponibilités`,
});

export const lienHistorique = (): HelpLink => ({
  route: '/parametres',
  queryParams: { onglet: 'historique' },
  label: $localize`:@@nav.link.historique:Historique`,
});
