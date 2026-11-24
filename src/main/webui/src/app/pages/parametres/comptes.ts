// The rules of the Comptes tab, kept out of the component so they are tested
// without rendering. Every wording is built on call, never at module scope:
// `$localize` only resolves once `main.ts` has loaded the translations.

import { Compte, Edition, Habilitation, RoleHabilitation } from '../../core/models';

/** The roles, in the order the grant form offers them. */
export const ROLES: readonly RoleHabilitation[] = ['ORGANISATEUR', 'ADMIN'];

/** Where a right stands at a given instant. */
export type RightState = 'active' | 'expired' | 'withdrawn';

/** Withdrawn wins over expired: withdrawing is the act somebody performed, expiring only happened. */
export function rightState(habilitation: Habilitation, now: Date): RightState {
  if (habilitation.retireeLe) {
    return 'withdrawn';
  }
  if (habilitation.expireLe && new Date(habilitation.expireLe).getTime() <= now.getTime()) {
    return 'expired';
  }
  return 'active';
}

export function roleLabel(role: RoleHabilitation): string {
  return role === 'ADMIN'
    ? $localize`:@@comptes.role.admin:Administrateur`
    : $localize`:@@comptes.role.organisateur:Organisateur`;
}

/** The edition a right is scoped to, by name; its id when the edition has since been deleted. */
export function editionLabel(editionId: string | null, editions: readonly Edition[]): string {
  if (editionId === null) {
    return $localize`:@@comptes.edition.toutes:Toutes les éditions`;
  }
  return editions.find((edition) => edition.id === editionId)?.nom ?? editionId;
}

/** One right in a sentence: « Organisateur — Année 2026 ». */
export function rightLabel(habilitation: Habilitation, editions: readonly Edition[]): string {
  const role = roleLabel(habilitation.role);
  const edition = editionLabel(habilitation.editionId ?? null, editions);
  return habilitation.role === 'ADMIN'
    ? role
    : $localize`:@@comptes.droit:${role}:role: — ${edition}:edition:`;
}

/** Active accounts first, then by address: the ones that can still sign in are the ones looked for. */
export function sortAccounts(comptes: readonly Compte[]): Compte[] {
  return [...comptes].sort(
    (a, b) => Number(!!a.desactiveLe) - Number(!!b.desactiveLe) || a.email.localeCompare(b.email),
  );
}
