// The rules of the Comptes screen, kept out of the component so they are
// tested without rendering: what the quick filter matches, how an account's
// rights are summed up in one cell, when a right is in force, and the checks a
// grant is refused on — mirrored from `CompteService.grant` so the dialog says
// it before the server does.
//
// Every wording is built on call, never at module scope: `$localize` only
// resolves once `main.ts` has loaded the translation catalogue.

import {
  Compte,
  Edition,
  Habilitation,
  NouvelleHabilitation,
  RoleHabilitation,
} from '../../core/models';
import { correspondAuFiltre } from '../../core/text-filter';

/** The roles, in the order the grant dialog offers them. */
export const ROLES: readonly RoleHabilitation[] = ['RH', 'RESPONSABLE_STAND'];

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
    case 'RH':
      return $localize`:@@comptes.role.rh:RH (lecture seule)`;
    case 'RESPONSABLE_STAND':
      return $localize`:@@comptes.role.responsableStand:Responsable de stand`;
  }
}

/** The edition a right is scoped to, by name; its id when the edition has since been deleted. */
export function editionLabel(editionId: string | null, editions: readonly Edition[]): string {
  if (editionId === null) {
    return $localize`:@@comptes.edition.toutes:Toutes les éditions`;
  }
  return editions.find((edition) => edition.id === editionId)?.nom ?? editionId;
}

/**
 * One line per account in the table: the rights in force, role and edition,
 * with the size of a stand scope. Expired and withdrawn rights are left out —
 * they open nothing, and the rights panel still lists them.
 */
export function rightsSummary(compte: Compte, editions: readonly Edition[], now: Date): string {
  const inForce = compte.habilitations.filter(
    (habilitation) => rightState(habilitation, now) === 'active',
  );
  if (inForce.length === 0) {
    return $localize`:@@comptes.summary.none:Aucun droit délégué`;
  }
  return inForce
    .map((habilitation) => {
      const role = roleLabel(habilitation.role);
      const edition = editionLabel(habilitation.editionId, editions);
      if (habilitation.standIds.length === 0) {
        return $localize`:@@comptes.summary.right:${role}:role: — ${edition}:edition:`;
      }
      const count = habilitation.standIds.length;
      return $localize`:@@comptes.summary.rightWithStands:${role}:role: — ${edition}:edition:, ${count}:count: stand(s)`;
    })
    .join(' ; ');
}

/**
 * The quick filter: every typed term must appear in the address, the name or
 * the rights summary — so « responsable » lists the stand managers and
 * « 2026 » whoever holds a right in that edition.
 */
export function filterAccounts(
  comptes: readonly Compte[],
  query: string,
  editions: readonly Edition[],
  now: Date,
): Compte[] {
  return comptes.filter((compte) =>
    correspondAuFiltre(query, [compte.email, compte.nom, rightsSummary(compte, editions, now)]),
  );
}

/** Active accounts first, then by address: the ones that can still sign in are the ones looked for. */
export function sortAccounts(comptes: readonly Compte[]): Compte[] {
  return [...comptes].sort(
    (a, b) =>
      Number(a.desactiveLe !== null) - Number(b.desactiveLe !== null) ||
      a.email.localeCompare(b.email),
  );
}

/**
 * Whether this account is the one signed in. `/api/auth/me` names a Keycloak
 * session by its e-mail, which is the account's key, compared the way the
 * server compares addresses: trimmed and case-insensitively. The break-glass
 * session is named `admin` and matches no account, which is right — it is not
 * one.
 */
export function isOwnAccount(compte: Compte, sessionName: string | null): boolean {
  if (!sessionName) {
    return false;
  }
  return compte.email.trim().toLowerCase() === sessionName.trim().toLowerCase();
}

/** The address check the server makes (`CompteService.create`), and its refusal worded ahead of it. */
export function emailError(email: string): string | null {
  const trimmed = email.trim();
  if (!trimmed) {
    return $localize`:@@comptes.error.emailMissing:Adresse e-mail manquante.`;
  }
  if (!/^[^@\s]+@[^@\s]+$/.test(trimmed)) {
    return $localize`:@@comptes.error.emailInvalid:Adresse e-mail invalide.`;
  }
  return null;
}

/** What the grant dialog holds while it is being filled. */
export interface GrantDraft {
  role: RoleHabilitation;
  /** `null`: every edition — offered to RH only. */
  editionId: string | null;
  /** `yyyy-mm-dd` from the date field, `''` for no expiry. */
  expiryDate: string;
  standIds: string[];
}

export function initialGrantDraft(): GrantDraft {
  return { role: 'RH', editionId: null, expiryDate: '', standIds: [] };
}

/**
 * The draft once the role changes. A stand manager is one in an edition, so
 * « every edition » gives way to the current one; any other role has no stand
 * scope, so the stands picked are dropped rather than sent to a refusal.
 */
export function withRole(
  draft: GrantDraft,
  role: RoleHabilitation,
  currentEditionId: string | null,
): GrantDraft {
  if (role === 'RESPONSABLE_STAND') {
    return { ...draft, role, editionId: draft.editionId ?? currentEditionId };
  }
  return { ...draft, role, standIds: [] };
}

/** The draft once the edition changes: stands belong to one edition, so the picked ones go. */
export function withEdition(draft: GrantDraft, editionId: string | null): GrantDraft {
  return draft.editionId === editionId ? draft : { ...draft, editionId, standIds: [] };
}

/**
 * The instant a right chosen « valid until » a date expires: the end of that
 * day, local time — the day picked is included. `null` for no date, or for a
 * value the date field should never produce.
 */
export function expiryInstant(date: string): string | null {
  const match = /^(\d{4})-(\d{2})-(\d{2})$/.exec(date);
  if (!match) {
    return null;
  }
  const [year, month, day] = [Number(match[1]), Number(match[2]), Number(match[3])];
  return new Date(year, month - 1, day + 1).toISOString();
}

/**
 * Why the server would refuse this grant — `CompteService.grant`'s rules, in
 * its order, so the dialog refuses first and in the same words. An unknown
 * stand is the one check left to the server: the dialog only offers stands it
 * just read.
 */
export function grantErrors(draft: GrantDraft, now: Date): string[] {
  const errors: string[] = [];
  if (
    draft.role === 'RESPONSABLE_STAND' &&
    (draft.editionId === null || draft.standIds.length === 0)
  ) {
    errors.push(
      $localize`:@@comptes.error.responsableSansStand:Un responsable de stand l'est dans une édition, pour au moins un de ses stands.`,
    );
  }
  if (draft.role !== 'RESPONSABLE_STAND' && draft.standIds.length > 0) {
    errors.push(
      $localize`:@@comptes.error.standsHorsResponsable:Seul un responsable de stand a un périmètre de stands.`,
    );
  }
  if (draft.expiryDate) {
    const expiry = expiryInstant(draft.expiryDate);
    if (expiry === null || new Date(expiry).getTime() <= now.getTime()) {
      errors.push($localize`:@@comptes.error.expiryPast:La date d'expiration est déjà passée.`);
    }
  }
  return errors;
}

/** The request body; only called on a draft {@link grantErrors} accepts. */
export function toGrantRequest(draft: GrantDraft): NouvelleHabilitation {
  return {
    role: draft.role,
    editionId: draft.editionId,
    expireLe: draft.expiryDate ? expiryInstant(draft.expiryDate) : null,
    standIds: draft.role === 'RESPONSABLE_STAND' ? [...draft.standIds] : [],
  };
}

/** The editions whose stands the rights of an account name, for their labels. */
export function editionsWithStands(compte: Compte | null): string[] {
  if (!compte) {
    return [];
  }
  return [
    ...new Set(
      compte.habilitations
        .filter((habilitation) => habilitation.editionId !== null && habilitation.standIds.length)
        .map((habilitation) => habilitation.editionId as string),
    ),
  ].sort();
}
