// The « a newer release exists » check: asked of GitHub once, only from a
// tagged build, and never loud — the hint is a courtesy, and an unreachable
// GitHub is not this application's problem to report.

import { TestBed } from '@angular/core/testing';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { latestReleaseEndpoint, UpdateCheckService } from './update-check.service';

describe('UpdateCheckService', () => {
  let service: UpdateCheckService;

  function repond(body: unknown, ok = true): ReturnType<typeof vi.fn> {
    const fetchMock = vi
      .fn()
      .mockResolvedValue({ ok, status: ok ? 200 : 403, json: async () => body });
    vi.stubGlobal('fetch', fetchMock);
    return fetchMock;
  }

  /** Lets the promise chain behind `check()` settle. */
  async function attendre(): Promise<void> {
    await new Promise((resolve) => setTimeout(resolve, 0));
  }

  beforeEach(() => {
    TestBed.resetTestingModule();
    service = TestBed.inject(UpdateCheckService);
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
    await attendre();

    expect(fetchMock).toHaveBeenCalledOnce();
    expect(fetchMock.mock.calls[0][0]).toMatch(
      /^https:\/\/api\.github\.com\/repos\/.+\/releases\/latest$/,
    );
  });

  it('signale une release plus récente, avec son lien', async () => {
    repond({ tag_name: 'v1.1.0', html_url: 'https://github.com/x/y/releases/tag/v1.1.0' });

    service.check('v1.0.0');
    await attendre();

    expect(service.available()).toEqual({
      version: 'v1.1.0',
      url: 'https://github.com/x/y/releases/tag/v1.1.0',
    });
  });

  it('ne signale rien quand la version qui tourne est la dernière, ou plus récente', async () => {
    repond({ tag_name: 'v1.0.0', html_url: 'https://github.com/x/y/releases/tag/v1.0.0' });

    service.check('v1.0.0');
    await attendre();
    expect(service.available()).toBeNull();

    // A maintenance line deployed ahead of GitHub's « latest » (versioning.md § 5).
    TestBed.resetTestingModule();
    service = TestBed.inject(UpdateCheckService);
    repond({ tag_name: 'v1.0.0', html_url: '' });
    service.check('v1.0.1');
    await attendre();
    expect(service.available()).toBeNull();
  });

  it('ne demande rien pour un build sur un commit', async () => {
    const fetchMock = repond({ tag_name: 'v9.9.9' });

    service.check('2f7a1c3');
    await attendre();

    expect(fetchMock).not.toHaveBeenCalled();
    expect(service.available()).toBeNull();
  });

  it('ne demande qu’une fois, quel que soit le nombre de composants qui le lancent', async () => {
    const fetchMock = repond({ tag_name: 'v1.1.0' });

    service.check('v1.0.0');
    service.check('v1.0.0');
    await attendre();

    expect(fetchMock).toHaveBeenCalledOnce();
  });

  it('reste muet quand GitHub refuse, répond n’importe quoi, ou est injoignable', async () => {
    repond({ message: 'API rate limit exceeded' }, false);
    service.check('v1.0.0');
    await attendre();
    expect(service.available()).toBeNull();

    TestBed.resetTestingModule();
    service = TestBed.inject(UpdateCheckService);
    repond({ tag_name: 'nightly' });
    service.check('v1.0.0');
    await attendre();
    expect(service.available()).toBeNull();

    TestBed.resetTestingModule();
    service = TestBed.inject(UpdateCheckService);
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new Error('offline')));
    service.check('v1.0.0');
    await attendre();
    expect(service.available()).toBeNull();
  });

  it('rebâtit le lien de la release quand GitHub n’en donne pas un du sien', async () => {
    repond({ tag_name: 'v1.1.0', html_url: 'javascript:alert(1)' });

    service.check('v1.0.0');
    await attendre();

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
