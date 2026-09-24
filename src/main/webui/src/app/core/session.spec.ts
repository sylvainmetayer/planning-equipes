import { afterEach, describe, expect, it, vi } from 'vitest';
import { opensAdministration, signOut, signedInWithoutAdminRole } from './session';

describe('session', () => {
  describe('opensAdministration / signedInWithoutAdminRole', () => {
    it("n'ouvre l'administration qu'à une session portant le rôle admin", () => {
      const admin = { authentifie: true, nom: 'admin', roles: ['admin'] };
      const animatrice = { authentifie: true, nom: 'marie', roles: ['animateur', 'user'] };
      const anonyme = { authentifie: false, nom: null, roles: [] };

      expect(opensAdministration(admin)).toBe(true);
      expect(opensAdministration(animatrice)).toBe(false);
      expect(opensAdministration(anonyme)).toBe(false);

      // Only a signed-in visitor without the role is "without access": an
      // anonymous one simply has not signed in yet.
      expect(signedInWithoutAdminRole(admin)).toBe(false);
      expect(signedInWithoutAdminRole(animatrice)).toBe(true);
      expect(signedInWithoutAdminRole(anonyme)).toBe(false);
    });
  });

  describe('signOut', () => {
    afterEach(() => {
      vi.restoreAllMocks();
    });

    function espionnerNavigation(): ReturnType<typeof vi.fn> {
      const assign = vi.fn();
      vi.spyOn(window, 'location', 'get').mockReturnValue({ assign } as unknown as Location);
      return assign;
    }

    it("suit la déconnexion du fournisseur d'identité quand le serveur la nomme", async () => {
      const assign = espionnerNavigation();
      const logout = vi.fn().mockResolvedValue({ urlDeconnexion: '/api/auth/oidc/logout' });

      await signOut({ logout });

      expect(logout).toHaveBeenCalledOnce();
      expect(assign).toHaveBeenCalledExactlyOnceWith('/api/auth/oidc/logout');
    });

    it('retombe sur la destination de repli sans session Keycloak à fermer', async () => {
      const assign = espionnerNavigation();

      await signOut({ logout: vi.fn().mockResolvedValue({ urlDeconnexion: null }) }, '/ailleurs');

      expect(assign).toHaveBeenCalledExactlyOnceWith('/ailleurs');
    });

    it('part quand même si le serveur refuse la déconnexion', async () => {
      const assign = espionnerNavigation();

      await expect(
        signOut({ logout: vi.fn().mockRejectedValue(new Error('indisponible')) }),
      ).rejects.toThrow();

      expect(assign).toHaveBeenCalledExactlyOnceWith('/login');
    });
  });
});
