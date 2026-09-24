// The « a newer release exists » check: asked of GitHub once, only from a
// tagged build, only once an admin session is confirmed, and never loud — the
// hint is a courtesy, and an unreachable GitHub is not this application's
// problem to report.

import { TestBed } from '@angular/core/testing';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { AdminApi } from './api/admin-api';
import { latestReleaseEndpoint, UpdateCheckService } from './update-check.service';

/** Lets the promise chain behind `check()` settle. */
async function settle(): Promise<void> {
  await new Promise((resolve) => setTimeout(resolve, 0));
}

describe('UpdateCheckService', () => {
  let service: UpdateCheckService;
  const session = vi.fn();

  function repond(body: unknown, ok = true): ReturnType<typeof vi.fn> {
    const fetchMock = vi
      .fn()
      .mockResolvedValue({ ok, status: ok ? 200 : 403, json: async () => body });
    vi.stubGlobal('fetch', fetchMock);
    return fetchMock;
  }

  /** A fresh service over a session probe that answers « logged in ». */
  function reconstruire(authentifie = true): void {
    session.mockReset();
    session.mockResolvedValue({
      authentifie,
      nom: authentifie ? 'admin' : null,
      roles: authentifie ? ['admin'] : [],
    });
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({ providers: [{ provide: AdminApi, useValue: { session } }] });
    service = TestBed.inject(UpdateCheckService);
  }

  beforeEach(() => {
    reconstruire();
  });

  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it('interroge la dernière release publiée du dépôt', async () => {
    const fetchMock = repond({
      tag_name: 'v1.1.0',
      html_url: 'https://github.com/x/y/releases/tag/v1.1.0',
    });

    service.check('v1.0.0');
    await settle();

    expect(fetchMock).toHaveBeenCalledOnce();
    expect(fetchMock.mock.calls[0][0]).toMatch(
      /^https:\/\/api\.github\.com\/repos\/.+\/releases\/latest$/,
    );
  });

  it('signale une release plus récente, avec son lien', async () => {
    repond({ tag_name: 'v1.1.0', html_url: 'https://github.com/x/y/releases/tag/v1.1.0' });

    service.check('v1.0.0');
    await settle();

    expect(service.available()).toEqual({
      version: 'v1.1.0',
      url: 'https://github.com/x/y/releases/tag/v1.1.0',
    });
  });

  it('ne signale rien quand la version qui tourne est la dernière, ou plus récente', async () => {
    repond({ tag_name: 'v1.0.0', html_url: 'https://github.com/x/y/releases/tag/v1.0.0' });

    service.check('v1.0.0');
    await settle();
    expect(service.available()).toBeNull();

    // A maintenance line deployed ahead of GitHub's « latest » (versioning.md § 5).
    reconstruire();
    repond({ tag_name: 'v1.0.0', html_url: '' });
    service.check('v1.0.1');
    await settle();
    expect(service.available()).toBeNull();
  });

  it('ne demande rien pour un build sur un commit', async () => {
    const fetchMock = repond({ tag_name: 'v9.9.9' });

    service.check('2f7a1c3');
    await settle();

    expect(fetchMock).not.toHaveBeenCalled();
    expect(session).not.toHaveBeenCalled();
    expect(service.available()).toBeNull();
  });

  // The admin shell has no route guard: it renders for anybody who opens the
  // application and only redirects once an API call answers 401. Without this
  // gate, an animateur's — often a minor's — IP address would reach GitHub on
  // the way to the login page, and the anonymous per-IP quota it burns is the
  // one the real administrators need.
  it('ne demande rien à GitHub sans session administrateur', async () => {
    reconstruire(false);
    const fetchMock = repond({ tag_name: 'v9.9.9' });

    service.check('v1.0.0');
    await settle();

    expect(session).toHaveBeenCalledOnce();
    expect(fetchMock).not.toHaveBeenCalled();
    expect(service.available()).toBeNull();
  });

  // The ordinary way in: the shell renders without a session, gets refused,
  // and the login navigates back to it without reloading the page. A check
  // that latched on the first refusal would hide the hint for the whole
  // working session.
  it('redemande une fois la connexion faite', async () => {
    reconstruire(false);
    const fetchMock = repond({ tag_name: 'v1.1.0' });

    service.check('v1.0.0');
    await settle();
    expect(fetchMock).not.toHaveBeenCalled();

    session.mockResolvedValue({ authentifie: true, nom: 'admin', roles: ['admin'] });
    service.check('v1.0.0');
    await settle();

    expect(fetchMock).toHaveBeenCalledOnce();
    expect(service.available()?.version).toBe('v1.1.0');
  });

  // Under Keycloak an animateur holds a session too: signed in is not admin.
  it('ne demande rien à GitHub pour une session sans le rôle admin', async () => {
    reconstruire();
    session.mockResolvedValue({ authentifie: true, nom: 'marie', roles: ['animateur', 'user'] });
    const fetchMock = repond({ tag_name: 'v9.9.9' });

    service.check('v1.0.0');
    await settle();

    expect(fetchMock).not.toHaveBeenCalled();
    expect(service.available()).toBeNull();
  });

  it('ne demande rien à GitHub quand la sonde de session échoue', async () => {
    reconstruire();
    session.mockRejectedValue(new Error('401'));
    const fetchMock = repond({ tag_name: 'v9.9.9' });

    service.check('v1.0.0');
    await settle();

    expect(fetchMock).not.toHaveBeenCalled();
    expect(service.available()).toBeNull();
  });

  it('ne demande qu’une fois, quel que soit le nombre de composants qui le lancent', async () => {
    const fetchMock = repond({ tag_name: 'v1.1.0' });

    service.check('v1.0.0');
    service.check('v1.0.0');
    await settle();

    expect(session).toHaveBeenCalledOnce();
    expect(fetchMock).toHaveBeenCalledOnce();
  });

  it('reste muet quand GitHub refuse, répond n’importe quoi, ou est injoignable', async () => {
    repond({ message: 'API rate limit exceeded' }, false);
    service.check('v1.0.0');
    await settle();
    expect(service.available()).toBeNull();

    reconstruire();
    repond({ tag_name: 'nightly' });
    service.check('v1.0.0');
    await settle();
    expect(service.available()).toBeNull();

    reconstruire();
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new Error('offline')));
    service.check('v1.0.0');
    await settle();
    expect(service.available()).toBeNull();
  });

  it('rebâtit le lien de la release quand GitHub n’en donne pas un du sien', async () => {
    repond({ tag_name: 'v1.1.0', html_url: 'javascript:alert(1)' });

    service.check('v1.0.0');
    await settle();

    expect(service.available()?.url).toMatch(
      /^https:\/\/github\.com\/.+\/releases\/tag\/v1\.1\.0$/,
    );
  });

  it('dérive l’API de l’URL du dépôt', () => {
    expect(latestReleaseEndpoint('https://github.com/o/r')).toBe(
      'https://api.github.com/repos/o/r/releases/latest',
    );
  });
});
