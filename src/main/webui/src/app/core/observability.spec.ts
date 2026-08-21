import { afterEach, describe, expect, it, vi } from 'vitest';
import { initObservability, loadAppConfig, masquerJetonEspace, masquerJetonPartout, observabilityProviders } from './observability';
import { AppConfig } from './models';

const CONFIG: AppConfig = {
  sentryDsn: 'https://key@bugsink.example.com/1',
  sentryEnvironment: 'production',
  cloudflareWebAnalyticsToken: '987d563a0f264bbbb484df80ab2ab0f8',
  devMode: false
};

describe('loadAppConfig', () => {
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it('returns the fetched config on success', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue({ ok: true, json: () => Promise.resolve(CONFIG) })
    );
    await expect(loadAppConfig()).resolves.toEqual(CONFIG);
  });

  it('falls back to a disabled config on a non-OK response, instead of blocking bootstrap', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: false, status: 500 }));
    await expect(loadAppConfig()).resolves.toEqual({
      sentryDsn: '',
      sentryEnvironment: 'local',
      cloudflareWebAnalyticsToken: '',
      devMode: false
    });
  });

  it('falls back to a disabled config when the fetch itself rejects (offline, bad deploy)', async () => {
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new Error('network down')));
    await expect(loadAppConfig()).resolves.toEqual({
      sentryDsn: '',
      sentryEnvironment: 'local',
      cloudflareWebAnalyticsToken: '',
      devMode: false
    });
  });
});

describe('observabilityProviders', () => {
  it('registers no provider when Sentry has no DSN', () => {
    expect(observabilityProviders({ ...CONFIG, sentryDsn: '' })).toEqual([]);
  });

  it("registers Sentry's ErrorHandler when a DSN is configured", () => {
    expect(observabilityProviders(CONFIG)).toHaveLength(1);
  });
});


describe('initObservability', () => {
  afterEach(() => {
    document.head.innerHTML = '';
  });

  it('injects Cloudflare Web Analytics when a token is configured', () => {
    initObservability({ ...CONFIG, sentryDsn: '' });

    const script = document.head.querySelector('script[data-cf-beacon]');
    expect(script).not.toBeNull();
    expect(script?.getAttribute('type')).toBe('module');
    expect(script?.getAttribute('src')).toBe('https://static.cloudflareinsights.com/beacon.min.js');
    expect(script?.getAttribute('data-cf-beacon')).toBe(
      JSON.stringify({ token: CONFIG.cloudflareWebAnalyticsToken })
    );
  });

  it('does not inject duplicate Cloudflare scripts', () => {
    const config = { ...CONFIG, sentryDsn: '' };

    initObservability(config);
    initObservability(config);

    expect(document.head.querySelectorAll('script[data-cf-beacon]')).toHaveLength(1);
  });
});

describe('masquerJetonEspace', () => {
  it('remplace le jeton d’un lien d’espace, où qu’il apparaisse dans l’URL', () => {
    expect(masquerJetonEspace('https://planning.example.org/animateur/a1b2c3d4/echanges')).toBe(
      'https://planning.example.org/animateur/<jeton>/echanges'
    );
    // Les appels d'API portent le même jeton et atterrissent dans les fils
    // d'Ariane du rapport d'erreur : les masquer aussi, sinon le premier
    // masquage ne sert à rien.
    expect(masquerJetonEspace('/api/espace-animateur/a1b2c3d4/postes')).toBe(
      '/api/espace-animateur/<jeton>/postes'
    );
  });

  it('laisse intacte une URL qui ne porte aucun jeton', () => {
    expect(masquerJetonEspace('/mentions-legales')).toBe('/mentions-legales');
  });

  it('masque chaque occurrence d’un message qui en contient plusieurs', () => {
    // Un fil d'Ariane peut concaténer plusieurs URL : en laisser passer une
    // seule suffirait à identifier la personne.
    expect(masquerJetonEspace('/animateur/aaa -> /animateur/bbb?x=1')).toBe(
      '/animateur/<jeton> -> /animateur/<jeton>?x=1'
    );
  });
});

describe('masquerJetonPartout', () => {
  it('masque un fil d’Ariane de navigation, dont les champs ne s’appellent pas « url »', () => {
    // Une navigation interne à l'espace produit `data.from` / `data.to` : ne
    // masquer que `data.url` laissait passer le jeton à chaque changement de page.
    const breadcrumb = {
      category: 'navigation',
      data: { from: '/animateur/a1b2c3', to: '/animateur/a1b2c3/echanges' }
    };

    expect(masquerJetonPartout(breadcrumb).data).toEqual({
      from: '/animateur/<jeton>',
      to: '/animateur/<jeton>/echanges'
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
            value: 'Http failure response for /api/espace-animateur/a1b2c3/postes: 500 Server Error'
          }
        ]
      }
    };

    expect(masquerJetonPartout(event).exception.values[0].value).toBe(
      'Http failure response for /api/espace-animateur/<jeton>/postes: 500 Server Error'
    );
  });

  it('laisse le reste du rapport intact', () => {
    const event = { level: 'error', extra: { compteur: 3, actif: true, vide: null } };
    expect(masquerJetonPartout(event)).toEqual({
      level: 'error',
      extra: { compteur: 3, actif: true, vide: null }
    });
  });
});
