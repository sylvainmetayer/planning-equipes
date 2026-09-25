import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

/**
 * The SDK is now loaded by a dynamic `import()` inside the DSN branch, so it is
 * mocked at module level: that is also how these tests can assert it is never
 * reached at all when no DSN is configured.
 */
const sentry = vi.hoisted(() => ({
  init: vi.fn(),
  createErrorHandler: vi.fn(() => ({ handleError: vi.fn() })),
}));
vi.mock('@sentry/angular', () => sentry);
import {
  initObservability,
  loadAppConfig,
  maskUrlToken,
  maskTokensEverywhere,
} from './observability';
import { AppConfig } from './models';

const CONFIG: AppConfig = {
  sentryDsn: 'https://key@bugsink.example.com/1',
  sentryEnvironment: 'production',
  cloudflareWebAnalyticsToken: 'token-de-test',
  devMode: false,
  dragDropEnabled: false,
  version: '',
};

describe('loadAppConfig', () => {
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it('returns the fetched config on success', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue({ ok: true, json: () => Promise.resolve(CONFIG) }),
    );
    await expect(loadAppConfig()).resolves.toEqual(CONFIG);
  });

  it('falls back to a disabled config on a non-OK response, instead of blocking bootstrap', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: false, status: 500 }));
    await expect(loadAppConfig()).resolves.toEqual({
      sentryDsn: '',
      sentryEnvironment: 'local',
      cloudflareWebAnalyticsToken: '',
      devMode: false,
      dragDropEnabled: false,
      version: '',
    });
  });

  it('falls back to a disabled config when the fetch itself rejects (offline, bad deploy)', async () => {
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new Error('network down')));
    await expect(loadAppConfig()).resolves.toEqual({
      sentryDsn: '',
      sentryEnvironment: 'local',
      cloudflareWebAnalyticsToken: '',
      devMode: false,
      dragDropEnabled: false,
      version: '',
    });
  });
});

describe('initObservability', () => {
  beforeEach(() => {
    sentry.init.mockClear();
    sentry.createErrorHandler.mockClear();
  });

  afterEach(() => {
    document.head.innerHTML = '';
  });

  it('registers no provider — and never loads the SDK — when Sentry has no DSN', async () => {
    await expect(initObservability({ ...CONFIG, sentryDsn: '' })).resolves.toEqual([]);
    expect(sentry.init).not.toHaveBeenCalled();
    expect(sentry.createErrorHandler).not.toHaveBeenCalled();
  });

  it("registers Sentry's ErrorHandler when a DSN is configured", async () => {
    await expect(
      initObservability({ ...CONFIG, cloudflareWebAnalyticsToken: '' }),
    ).resolves.toHaveLength(1);
  });

  it('initialises the SDK with the release and both token-masking hooks', async () => {
    await initObservability({ ...CONFIG, cloudflareWebAnalyticsToken: '' });

    expect(sentry.init).toHaveBeenCalledTimes(1);
    const options = sentry.init.mock.calls[0][0];
    expect(options.dsn).toBe(CONFIG.sentryDsn);
    expect(options.environment).toBe(CONFIG.sentryEnvironment);
    // The two guards keeping the espace animateur token out of a third party.
    expect(options.beforeSend).toBeTypeOf('function');
    expect(options.beforeBreadcrumb).toBeTypeOf('function');
  });

  it('masks the espace animateur token in what the SDK is about to send', async () => {
    await initObservability({ ...CONFIG, cloudflareWebAnalyticsToken: '' });

    const { beforeSend, beforeBreadcrumb } = sentry.init.mock.calls[0][0];
    const event = beforeSend({ request: { url: 'https://app.example.com/animateur/abc123' } });
    const breadcrumb = beforeBreadcrumb({ data: { to: '/api/espace-animateur/abc123/planning' } });

    expect(event.request.url).toBe('https://app.example.com/animateur/<jeton>');
    expect(breadcrumb.data.to).toBe('/api/espace-animateur/<jeton>/planning');
  });

  it('injects Cloudflare Web Analytics when a token is configured', async () => {
    await initObservability({ ...CONFIG, sentryDsn: '' });

    const script = document.head.querySelector<HTMLScriptElement>('script[data-cf-beacon]');
    expect(script).not.toBeNull();
    expect(script?.getAttribute('type')).toBe('module');
    expect(script?.getAttribute('src')).toBe('https://static.cloudflareinsights.com/beacon.min.js');
    expect(script?.dataset['cfBeacon']).toBe(
      JSON.stringify({ token: CONFIG.cloudflareWebAnalyticsToken }),
    );
  });

  it('never injects Cloudflare Web Analytics on a page carrying a token', async () => {
    for (const path of ['/animateur/abc123', '/mural/abc123']) {
      history.replaceState(null, '', path);
      await initObservability({ ...CONFIG, sentryDsn: '' });
      expect(document.head.querySelector('script[data-cf-beacon]')).toBeNull();
    }
    history.replaceState(null, '', '/');
  });

  it('does not inject duplicate Cloudflare scripts', async () => {
    const config = { ...CONFIG, sentryDsn: '' };

    await initObservability(config);
    await initObservability(config);

    expect(document.head.querySelectorAll('script[data-cf-beacon]')).toHaveLength(1);
  });
});

describe('maskUrlToken', () => {
  it('replaces the token of an espace link, wherever it appears in the URL', () => {
    expect(maskUrlToken('https://planning.example.org/animateur/a1b2c3d4/echanges')).toBe(
      'https://planning.example.org/animateur/<jeton>/echanges',
    );
    // Les appels d'API portent le même jeton et atterrissent dans les fils
    // d'Ariane du rapport d'erreur : les masquer aussi, sinon le premier
    // masquage ne sert à rien.
    expect(maskUrlToken('/api/espace-animateur/a1b2c3d4/postes')).toBe(
      '/api/espace-animateur/<jeton>/postes',
    );
  });

  it('leaves a URL carrying no token untouched', () => {
    expect(maskUrlToken('/mentions-legales')).toBe('/mentions-legales');
    // The admin routes of the wall links carry an id, not a token.
    expect(maskUrlToken('/api/affichage-mural/12')).toBe('/api/affichage-mural/12');
  });

  it('replaces the token of a wall-display link, page and API call alike', () => {
    expect(maskUrlToken('https://planning.example.org/mural/Zx9-tok')).toBe(
      'https://planning.example.org/mural/<jeton>',
    );
    expect(maskUrlToken('GET /api/mural/Zx9-tok?x=1 failed')).toBe(
      'GET /api/mural/<jeton>?x=1 failed',
    );
  });

  it('masks every occurrence of a message carrying several', () => {
    // Un fil d'Ariane peut concaténer plusieurs URL : en laisser passer une
    // seule suffirait à identifier la personne.
    expect(maskUrlToken('/animateur/aaa -> /animateur/bbb?x=1')).toBe(
      '/animateur/<jeton> -> /animateur/<jeton>?x=1',
    );
  });
});

describe('maskTokensEverywhere', () => {
  it('masks a navigation breadcrumb, whose fields are not called « url »', () => {
    // Une navigation interne à l'espace produit `data.from` / `data.to` : ne
    // masquer que `data.url` laissait passer le jeton à chaque changement de page.
    const breadcrumb = {
      category: 'navigation',
      data: { from: '/animateur/a1b2c3', to: '/animateur/a1b2c3/echanges' },
    };

    expect(maskTokensEverywhere(breadcrumb).data).toEqual({
      from: '/animateur/<jeton>',
      to: '/animateur/<jeton>/echanges',
    });
  });

  it('masks the exception message, where the most frequent error lands', () => {
    // Angular formule ses échecs HTTP ainsi : c'est l'erreur la plus probable
    // dans l'espace animateur, et elle porte l'URL appelée.
    const event = {
      exception: {
        values: [
          {
            type: 'HttpErrorResponse',
            value:
              'Http failure response for /api/espace-animateur/a1b2c3/postes: 500 Server Error',
          },
        ],
      },
    };

    expect(maskTokensEverywhere(event).exception.values[0].value).toBe(
      'Http failure response for /api/espace-animateur/<jeton>/postes: 500 Server Error',
    );
  });

  it('leaves the rest of the report untouched', () => {
    const event = { level: 'error', extra: { compteur: 3, actif: true, vide: null } };
    expect(maskTokensEverywhere(event)).toEqual({
      level: 'error',
      extra: { compteur: 3, actif: true, vide: null },
    });
  });
});
