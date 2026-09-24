// The login page offers the doors /api/config says are open — Keycloak, the
// break-glass account, or both — and explains, rather than loops, when the
// visitor is signed in without the admin role. Rendered, because what the
// page offers is exactly the behaviour under test.

import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { provideZonelessChangeDetection } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { Router } from '@angular/router';
import { throwError } from 'rxjs';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { AdminApi } from '../../core/api/admin-api';
import { APP_CONFIG } from '../../core/app-config';
import { AppConfig, StatutSession } from '../../core/models';
import { LoginPage } from './login-page';

const CONFIG: AppConfig = {
  sentryDsn: '',
  sentryEnvironment: 'local',
  cloudflareWebAnalyticsToken: '',
  devMode: false,
  dragDropEnabled: false,
  version: '',
  authOidc: true,
  authSecours: false,
};

const ANONYME: StatutSession = { authentifie: false, nom: null, roles: [] };

describe('LoginPage', () => {
  let fixture: ComponentFixture<LoginPage>;
  const adminApi = {
    session: vi.fn(),
    logout: vi.fn(),
    oidcLoginUrl: vi.fn((retour: string) => `/api/auth/oidc/login?redirect=${retour}`),
  };
  const http = { post: vi.fn() };
  const router = { navigateByUrl: vi.fn(async () => true) };

  async function rendre(
    portes: Partial<Pick<AppConfig, 'authOidc' | 'authSecours'>>,
    session: StatutSession = ANONYME,
  ): Promise<void> {
    adminApi.session.mockReset();
    adminApi.session.mockResolvedValue(session);
    http.post.mockReset();
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      providers: [
        provideZonelessChangeDetection(),
        { provide: APP_CONFIG, useValue: { ...CONFIG, ...portes } },
        { provide: AdminApi, useValue: adminApi },
        { provide: HttpClient, useValue: http },
        { provide: Router, useValue: router },
      ],
    });
    fixture = TestBed.createComponent(LoginPage);
    await new Promise((resolve) => setTimeout(resolve, 0));
    await fixture.whenStable();
    fixture.detectChanges();
  }

  function element(): HTMLElement {
    return fixture.nativeElement as HTMLElement;
  }

  function texte(): string {
    return element().textContent!.replace(/\s+/g, ' ');
  }

  function bouton(libelle: string): HTMLButtonElement | undefined {
    return [...element().querySelectorAll('button')].find((b) =>
      b.textContent!.trim().replace(/\s+/g, ' ').endsWith(libelle),
    );
  }

  afterEach(() => {
    vi.restoreAllMocks();
  });

  it('avec Keycloak seul : un bouton, aucun champ mot de passe', async () => {
    const assign = vi.fn();
    vi.spyOn(window, 'location', 'get').mockReturnValue({ assign } as unknown as Location);
    await rendre({ authOidc: true, authSecours: false });

    expect(element().querySelector('input[type=password]')).toBeNull();
    expect(texte()).not.toContain('Compte de secours');

    bouton('Se connecter')!.click();

    expect(assign).toHaveBeenCalledExactlyOnceWith('/api/auth/oidc/login?redirect=/');
  });

  it('le compte de secours, ouvert, est présenté comme tel', async () => {
    await rendre({ authOidc: true, authSecours: true });

    expect(bouton('Se connecter')).toBeDefined();
    expect(texte()).toContain('Compte de secours');
    expect(element().querySelector('input[type=password]')).not.toBeNull();
    expect(bouton('Se connecter avec le compte de secours')).toBeDefined();
  });

  it('sans Keycloak (développement), le compte de secours est la seule porte', async () => {
    await rendre({ authOidc: false, authSecours: true });

    expect(bouton('Se connecter')).toBeUndefined();
    expect(element().querySelector('input[type=password]')).not.toBeNull();
  });

  /** Filled in and submitted the way a person would, through the real form. */
  async function soumettreSecours(): Promise<void> {
    const [utilisateur, motDePasse] = [...element().querySelectorAll('input')];
    utilisateur.value = 'admin';
    utilisateur.dispatchEvent(new Event('input'));
    motDePasse.value = 'secret';
    motDePasse.dispatchEvent(new Event('input'));
    fixture.detectChanges();
    element().querySelector('form')!.dispatchEvent(new Event('submit'));
    await new Promise((resolve) => setTimeout(resolve, 0));
    await fixture.whenStable();
    fixture.detectChanges();
  }

  it('un 409 du formulaire dit que la connexion par mot de passe est fermée', async () => {
    await rendre({ authOidc: true, authSecours: true });
    http.post.mockReturnValue(throwError(() => new HttpErrorResponse({ status: 409 })));

    await soumettreSecours();

    expect(texte()).toContain('La connexion par mot de passe est fermée sur ce serveur.');
    expect(texte()).not.toContain('Identifiants incorrects.');
  });

  it('un 401 du formulaire reste « identifiants incorrects »', async () => {
    await rendre({ authOidc: true, authSecours: true });
    http.post.mockReturnValue(throwError(() => new HttpErrorResponse({ status: 401 })));

    await soumettreSecours();

    expect(texte()).toContain('Identifiants incorrects.');
  });

  /**
   * Bounced here by a 403: offering "Se connecter" again would sign the same
   * account straight back into the same refusal.
   */
  it('connecté sans le rôle admin : le dit, et ne propose que la déconnexion', async () => {
    const assign = vi.fn();
    vi.spyOn(window, 'location', 'get').mockReturnValue({ assign } as unknown as Location);
    adminApi.logout.mockResolvedValue({ urlDeconnexion: '/api/auth/oidc/logout' });
    await rendre(
      { authOidc: true, authSecours: true },
      { authentifie: true, nom: 'marie', roles: ['animateur', 'user'] },
    );

    expect(texte()).toContain("ce compte n'a pas accès à l'administration");
    expect(bouton('Se connecter')).toBeUndefined();
    expect(element().querySelector('input[type=password]')).toBeNull();

    bouton('Se déconnecter')!.click();
    await new Promise((resolve) => setTimeout(resolve, 0));

    expect(assign).toHaveBeenCalledExactlyOnceWith('/api/auth/oidc/logout');
  });
});
