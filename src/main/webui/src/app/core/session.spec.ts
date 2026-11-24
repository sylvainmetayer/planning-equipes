import { describe, expect, it } from 'vitest';
import { signedInWithoutAdminRole } from './session';

describe('signedInWithoutAdminRole', () => {
  it('is true for a session without the admin role, false for an admin or an anonymous', () => {
    expect(signedInWithoutAdminRole({ authentifie: true, nom: 'a@b.fr', roles: [] })).toBe(true);
    expect(
      signedInWithoutAdminRole({ authentifie: true, nom: 'a@b.fr', roles: ['organisateur'] }),
    ).toBe(true);
    expect(signedInWithoutAdminRole({ authentifie: true, nom: 'a', roles: ['admin'] })).toBe(false);
    expect(signedInWithoutAdminRole({ authentifie: false, nom: null, roles: [] })).toBe(false);
  });
});
