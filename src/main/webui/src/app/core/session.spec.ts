import { describe, expect, it } from 'vitest';
import { StatutSession } from './models';
import { responsableOnly, rhOnly, signedInWithoutAccess } from './session';

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
    expect(signedInWithoutAccess(statut(true, 'responsable_stand'))).toBe(false);
  });
});

describe('responsableOnly', () => {
  it('is true for a responsable de stand with no role of the administration', () => {
    expect(responsableOnly(statut(true, 'responsable_stand'))).toBe(true);
    expect(responsableOnly(statut(true, 'responsable_stand', 'organisateur'))).toBe(false);
    expect(responsableOnly(statut(true, 'admin'))).toBe(false);
    expect(responsableOnly(statut(false, 'responsable_stand'))).toBe(false);
  });
});

describe('rhOnly', () => {
  it('is true for an RH account with no other role, which also opens something', () => {
    expect(rhOnly(statut(true, 'rh'))).toBe(true);
    expect(rhOnly(statut(true, 'rh', 'organisateur'))).toBe(false);
    expect(rhOnly(statut(true, 'rh', 'responsable_stand'))).toBe(false);
    expect(rhOnly(statut(true, 'admin'))).toBe(false);
    expect(rhOnly(statut(false, 'rh'))).toBe(false);
    expect(signedInWithoutAccess(statut(true, 'rh'))).toBe(false);
  });
});
