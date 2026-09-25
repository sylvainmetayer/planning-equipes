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
  masquerJetonEspace,
  masquerJetonPartout,
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

  it('does not inject duplicate Cloudflare scripts', async () => {
    const config = { ...CONFIG, sentryDsn: '' };

    await initObservability(config);
    await initObservability(config);

    expect(document.head.querySelectorAll('script[data-cf-beacon]')).toHaveLength(1);
  });
});

describe('masquerJetonEspace', () => {
  it('remplace le jeton d’un lien d’espace, où qu’il apparaisse dans l’URL', () => {
    expect(masquerJetonEspace('https://planning.example.org/animateur/a1b2c3d4/echanges')).toBe(
      'https://planning.example.org/animateur/<jeton>/echanges',
    );
    // Les appels d'API portent le même jeton et atterrissent dans les fils
    // d'Ariane du rapport d'erreur : les masquer aussi, sinon le premier
    // masquage ne sert à rien.
    expect(masquerJetonEspace('/api/espace-animateur/a1b2c3d4/postes')).toBe(
      '/api/espace-animateur/<jeton>/postes',
    );
  });

  it('laisse intacte une URL qui ne porte aucun jeton', () => {
    expect(masquerJetonEspace('/mentions-legales')).toBe('/mentions-legales');
  });

  it('masque chaque occurrence d’un message qui en contient plusieurs', () => {
    // Un fil d'Ariane peut concaténer plusieurs URL : en laisser passer une
    // seule suffirait à identifier la personne.
    expect(masquerJetonEspace('/animateur/aaa -> /animateur/bbb?x=1')).toBe(
      '/animateur/<jeton> -> /animateur/<jeton>?x=1',
    );
  });
});

describe('masquerJetonPartout', () => {
  it('masque un fil d’Ariane de navigation, dont les champs ne s’appellent pas « url »', () => {
    // Une navigation interne à l'espace produit `data.from` / `data.to` : ne
    // masquer que `data.url` laissait passer le jeton à chaque changement de page.
    const breadcrumb = {
      category: 'navigation',
      data: { from: '/animateur/a1b2c3', to: '/animateur/a1b2c3/echanges' },
    };

    expect(masquerJetonPartout(breadcrumb).data).toEqual({
      from: '/animateur/<jeton>',
      to: '/animateur/<jeton>/echanges',
    });
  });

  it('masque le message d’exception, là où atterrit l’erreur la plus fréquente', () => {
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

    expect(masquerJetonPartout(event).exception.values[0].value).toBe(
      'Http failure response for /api/espace-animateur/<jeton>/postes: 500 Server Error',
    );
  });

  it('laisse le reste du rapport intact', () => {
    const event = { level: 'error', extra: { compteur: 3, actif: true, vide: null } };
    expect(masquerJetonPartout(event)).toEqual({
      level: 'error',
      extra: { compteur: 3, actif: true, vide: null },
    });
  });
});
