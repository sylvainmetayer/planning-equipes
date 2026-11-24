import { describe, expect, it } from 'vitest';
import { StatutSession } from './models';
import { signedInWithoutAccess } from './session';

function statut(authentifie: boolean, ...roles: string[]): StatutSession {
  return { authentifie, nom: authentifie ? 'a@b.fr' : null, roles, compteId: null };
}

describe('signedInWithoutAccess', () => {
  it('is true only for a signed-in person holding neither role', () => {
    expect(signedInWithoutAccess(statut(true))).toBe(true);
    expect(signedInWithoutAccess(statut(true, 'mcp'))).toBe(true);
    expect(signedInWithoutAccess(statut(true, 'organisateur'))).toBe(false);
    expect(signedInWithoutAccess(statut(true, 'admin'))).toBe(false);
    expect(signedInWithoutAccess(statut(false))).toBe(false);
  });
});
