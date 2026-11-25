// The rules of the Comptes tab, kept out of the component so they are tested
// without rendering. Every wording is built on call, never at module scope:
// `$localize` only resolves once `main.ts` has loaded the translations.

import { Compte, Edition, Habilitation, RoleHabilitation, Stand } from '../../core/models';

/** The roles, in the order the grant form offers them. */
export const ROLES: readonly RoleHabilitation[] = ['ORGANISATEUR', 'RESPONSABLE_STAND', 'ADMIN'];

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
  switch (role) {
    case 'ADMIN':
      return $localize`:@@comptes.role.admin:Administrateur`;
    case 'RESPONSABLE_STAND':
      return $localize`:@@comptes.role.responsable:Responsable de stand (lecture de son stand)`;
    case 'ORGANISATEUR':
      return $localize`:@@comptes.role.organisateur:Organisateur`;
  }
}

/** The edition a right is scoped to, by name; its id when the edition has since been deleted. */
export function editionLabel(editionId: string | null, editions: readonly Edition[]): string {
  if (editionId === null) {
    return $localize`:@@comptes.edition.toutes:Toutes les éditions`;
  }
  return editions.find((edition) => edition.id === editionId)?.nom ?? editionId;
}

/** One right in a sentence: « Organisateur — Année 2026 ». */
export function rightLabel(
  habilitation: Habilitation,
  editions: readonly Edition[],
  stands: readonly Pick<Stand, 'id' | 'nom'>[] = [],
): string {
  const role = roleLabel(habilitation.role);
  const edition = editionLabel(habilitation.editionId ?? null, editions);
  if (habilitation.role === 'ADMIN') {
    return role;
  }
  if (habilitation.role === 'RESPONSABLE_STAND') {
    // The names are known only for the stands this browser holds (its edition's): else a count.
    const noms = habilitation.standIds.map((id) => stands.find((s) => s.id === id)?.nom);
    if (noms.every((nom) => nom !== undefined)) {
      const liste = noms.join(', ');
      return $localize`:@@comptes.droit.responsableStands:${role}:role: — ${edition}:edition: (${liste}:stands:)`;
    }
    const n = habilitation.standIds.length;
    return $localize`:@@comptes.droit.responsable:${role}:role: — ${edition}:edition: (${n}:stands: stand(s))`;
  }
  return $localize`:@@comptes.droit:${role}:role: — ${edition}:edition:`;
}

/** Active accounts first, then by address: the ones that can still sign in are the ones looked for. */
export function sortAccounts(comptes: readonly Compte[]): Compte[] {
  return [...comptes].sort(
    (a, b) => Number(!!a.desactiveLe) - Number(!!b.desactiveLe) || a.email.localeCompare(b.email),
  );
}
