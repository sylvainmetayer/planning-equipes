import { describe, expect, it } from 'vitest';
import { signedInWithoutAdminRole } from './session';

describe('signedInWithoutAdminRole', () => {
  it('is true for a session without the admin role, false for an admin or an anonymous', () => {
    expect(
      signedInWithoutAdminRole({ authentifie: true, nom: 'a@b.fr', roles: [], compteId: null }),
    ).toBe(true);
    expect(
      signedInWithoutAdminRole({
        authentifie: true,
        nom: 'a@b.fr',
        roles: ['organisateur'],
        compteId: null,
      }),
    ).toBe(true);
    expect(
      signedInWithoutAdminRole({ authentifie: true, nom: 'a', roles: ['admin'], compteId: null }),
    ).toBe(false);
    expect(
      signedInWithoutAdminRole({ authentifie: false, nom: null, roles: [], compteId: null }),
    ).toBe(false);
  });
});
