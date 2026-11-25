import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideZonelessChangeDetection } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { Router } from '@angular/router';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { AdminApi } from '../../core/api/admin-api';
import { APP_CONFIG, DEFAULT_APP_CONFIG } from '../../core/app-config';
import { LoginPage } from './login-page';

describe('LoginPage', () => {
  const api = {
    session: vi.fn(),
    localSignIn: vi.fn(),
    forgotPassword: vi.fn(),
    logout: vi.fn(),
  };
  const navigate = vi.fn();

  beforeEach(() => {
    Object.values(api).forEach((f) => f.mockReset());
    navigate.mockReset();
    api.session.mockResolvedValue({ authentifie: false, nom: null, roles: [] });
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      providers: [
        provideZonelessChangeDetection(),
        provideHttpClient(),
        provideHttpClientTesting(),
        {
          provide: AdminApi,
          useValue: { ...api, localFormPath: '/api/auth/connexion/j_security_check' },
        },
        { provide: Router, useValue: { navigateByUrl: navigate } },
        { provide: APP_CONFIG, useValue: { ...DEFAULT_APP_CONFIG } },
      ],
    });
  });

  type Page = {
    utilisateur: { set(v: string): void };
    password: { set(v: string): void };
    code: { set(v: string): void };
    defi(): unknown;
    echec(): { message: string | null } | null;
    connecter(e: Event): Promise<void>;
    submitCode(e: Event): Promise<void>;
    requestLink(e: Event): Promise<void>;
    oubliEnvoye(): boolean;
  };

  async function rendre() {
    const fixture = TestBed.createComponent(LoginPage);
    fixture.detectChanges();
    await fixture.whenStable();
    return fixture.componentInstance as unknown as Page;
  }

  it('waits for the mailed code after the right password, and posts the challenge with it', async () => {
    api.localSignIn.mockResolvedValue({
      codeRequired: true,
      challenge: 'D',
      maskedEmail: 'a•••@x.fr',
    });
    const page = await rendre();
    page.utilisateur.set('a@x.fr');
    page.password.set(crypto.randomUUID());
    await page.connecter(new Event('submit'));
    expect(page.defi()).not.toBeNull();

    api.session.mockResolvedValue({ authentifie: true, nom: 'a@x.fr', roles: ['admin'] });
    page.code.set('123456');
    const http = TestBed.inject(HttpTestingController);
    const fin = page.submitCode(new Event('submit'));
    const request = await vi.waitFor(() => http.expectOne('/api/auth/connexion/j_security_check'));
    expect(request.request.body).toContain('j_password=D%3A123456');
    request.flush('');
    await fin;
    expect(navigate).toHaveBeenCalledWith('/');
  });

  it('sends the configuration account straight to the form login', async () => {
    const page = await rendre();
    page.utilisateur.set('admin');
    page.password.set(crypto.randomUUID());
    const http = TestBed.inject(HttpTestingController);
    const fin = page.connecter(new Event('submit'));
    (await vi.waitFor(() => http.expectOne('/j_security_check'))).flush('');
    await fin;
    expect(api.localSignIn).not.toHaveBeenCalled();
  });

  it('shows the sentence of a refused password step', async () => {
    api.localSignIn.mockRejectedValue(new Error('Adresse ou mot de passe incorrect.'));
    const page = await rendre();
    page.utilisateur.set('a@x.fr');
    page.password.set(crypto.randomUUID());
    await page.connecter(new Event('submit'));
    expect(page.echec()?.message).toBe('Adresse ou mot de passe incorrect.');
  });

  it('answers « forgotten password » the same whatever the server did', async () => {
    api.forgotPassword.mockRejectedValue(new Error('boom'));
    const page = await rendre();
    page.utilisateur.set('a@x.fr');
    await page.requestLink(new Event('submit'));
    expect(page.oubliEnvoye()).toBe(true);
  });
});
