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

  async function rendre() {
    const fixture = TestBed.createComponent(LoginPage);
    fixture.detectChanges();
    await fixture.whenStable();
    return fixture.componentInstance;
  }

  it('waits for the mailed code after the right password, and posts the challenge with it', async () => {
    api.localSignIn.mockResolvedValue({
      codeRequired: true,
      challenge: 'D',
      maskedEmail: 'a•••@x.fr',
    });
    const page = await rendre();
    page['utilisateur'].set('a@x.fr');
    page['password'].set(crypto.randomUUID());
    await page['connecter'](new Event('submit'));
    expect(page['defi']()).not.toBeNull();

    api.session.mockResolvedValue({ authentifie: true, nom: 'a@x.fr', roles: ['admin'] });
    page['code'].set('123456');
    const http = TestBed.inject(HttpTestingController);
    const fin = page['submitCode'](new Event('submit'));
    const request = await vi.waitFor(() => http.expectOne('/api/auth/connexion/j_security_check'));
    expect(request.request.body).toContain('j_password=D%3A123456');
    request.flush('');
    await fin;
    expect(navigate).toHaveBeenCalledWith('/');
  });

  it('reads the configuration again once signed in: a visitor was not given its admin half', async () => {
    const fetchMock = vi
      .fn()
      .mockResolvedValue(
        new Response(JSON.stringify({ ...DEFAULT_APP_CONFIG, mailMock: true, version: '4.1.0' })),
      );
    vi.stubGlobal('fetch', fetchMock);
    try {
      api.localSignIn.mockResolvedValue({
        codeRequired: true,
        challenge: 'D',
        maskedEmail: 'a•••@x.fr',
      });
      const page = await rendre();
      page['utilisateur'].set('a@x.fr');
      page['password'].set(crypto.randomUUID());
      await page['connecter'](new Event('submit'));
      api.session.mockResolvedValue({ authentifie: true, nom: 'a@x.fr', roles: ['admin'] });
      page['code'].set('123456');
      const http = TestBed.inject(HttpTestingController);
      const fin = page['submitCode'](new Event('submit'));
      (await vi.waitFor(() => http.expectOne('/api/auth/connexion/j_security_check'))).flush('');
      await fin;
      expect(fetchMock).toHaveBeenCalledWith('/api/config');
      expect(TestBed.inject(APP_CONFIG)).toMatchObject({ mailMock: true, version: '4.1.0' });
    } finally {
      vi.unstubAllGlobals();
    }
  });

  it('sends the configuration account straight to the form login', async () => {
    const page = await rendre();
    page['utilisateur'].set('admin');
    page['password'].set(crypto.randomUUID());
    const http = TestBed.inject(HttpTestingController);
    const fin = page['connecter'](new Event('submit'));
    (await vi.waitFor(() => http.expectOne('/j_security_check'))).flush('');
    await fin;
    expect(api.localSignIn).not.toHaveBeenCalled();
  });

  it('shows the sentence of a refused password step', async () => {
    api.localSignIn.mockRejectedValue(new Error('Adresse ou mot de passe incorrect.'));
    const page = await rendre();
    page['utilisateur'].set('a@x.fr');
    page['password'].set(crypto.randomUUID());
    await page['connecter'](new Event('submit'));
    expect(page['echec']()?.message).toBe('Adresse ou mot de passe incorrect.');
  });

  it('answers « forgotten password » the same whatever the server did', async () => {
    api.forgotPassword.mockRejectedValue(new Error('boom'));
    const page = await rendre();
    page['utilisateur'].set('a@x.fr');
    await page['requestLink'](new Event('submit'));
    expect(page['oubliEnvoye']()).toBe(true);
  });
});

describe('LoginPage, through the real API layer', () => {
  beforeEach(() => {
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      providers: [
        provideZonelessChangeDetection(),
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: Router, useValue: { navigateByUrl: vi.fn() } },
        { provide: APP_CONFIG, useValue: { ...DEFAULT_APP_CONFIG } },
      ],
    });
  });

  async function rendre() {
    const fixture = TestBed.createComponent(LoginPage);
    fixture.detectChanges();
    const http = TestBed.inject(HttpTestingController);
    http
      .match('/api/auth/me')
      .forEach((r) => r.flush({ authentifie: false, nom: null, roles: [] }));
    await fixture.whenStable();
    return { page: fixture.componentInstance, http };
  }

  async function signIn(status: number, body: object) {
    const { page, http } = await rendre();
    page['utilisateur'].set('a@x.fr');
    page['password'].set(crypto.randomUUID());
    const fin = page['connecter'](new Event('submit'));
    const request = await vi.waitFor(() => http.expectOne('/api/auth/connexion'));
    request.flush(body, { status, statusText: 'x' });
    await fin;
    return page;
  }

  it('reads a wrong password as wrong credentials, not as an expired session', async () => {
    const page = await signIn(401, { message: 'Adresse ou mot de passe incorrect.' });
    expect(page['echec']()).toEqual({ fermee: false, message: null });
  });

  it('says a lockout in its own words', async () => {
    const page = await signIn(429, { message: 'Trop de tentatives' });
    expect(page['echec']()?.message).toContain('Trop de tentatives de connexion');
  });

  it('says the door is closed when the break-glass form answers 409', async () => {
    const { page, http } = await rendre();
    page['utilisateur'].set('admin');
    page['password'].set(crypto.randomUUID());
    const fin = page['connecter'](new Event('submit'));
    const request = await vi.waitFor(() => http.expectOne('/j_security_check'));
    request.flush({ message: 'fermée' }, { status: 409, statusText: 'Conflict' });
    await fin;
    expect(page['echec']()).toEqual({ fermee: true, message: null });
  });

  it('says a lockout of the form post too, and forgets the challenge on restart', async () => {
    const { page, http } = await rendre();
    page['utilisateur'].set('a@x.fr');
    page['defi'].set({ codeRequired: true, challenge: 'D', maskedEmail: null });
    page['code'].set('123456');
    const fin = page['submitCode'](new Event('submit'));
    const request = await vi.waitFor(() => http.expectOne('/api/auth/connexion/j_security_check'));
    request.flush({ message: 'x' }, { status: 429, statusText: 'Too Many' });
    await fin;
    expect(page['echec']()?.message).toContain('Trop de tentatives de connexion');
    page['recommencer']();
    expect(page['defi']()).toBeNull();
    expect(page['echec']()).toBeNull();
  });
});
