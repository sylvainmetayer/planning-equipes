import { afterEach, describe, expect, it, vi } from 'vitest';
import { initObservability, loadAppConfig, observabilityProviders } from './observability';
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
