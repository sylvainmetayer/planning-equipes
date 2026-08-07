import { afterEach, describe, expect, it, vi } from 'vitest';
import { loadObservabilityConfig, observabilityProviders } from './observability';
import { ObservabilityConfig } from './models';

const CONFIG: ObservabilityConfig = {
  sentryDsn: 'https://key@bugsink.example.com/1',
  sentryEnvironment: 'production',
  posthogApiKey: 'phc_test',
  posthogHost: 'https://eu.i.posthog.com'
};

describe('loadObservabilityConfig', () => {
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it('returns the fetched config on success', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue({ ok: true, json: () => Promise.resolve(CONFIG) })
    );
    await expect(loadObservabilityConfig()).resolves.toEqual(CONFIG);
  });

  it('falls back to a disabled config on a non-OK response, instead of blocking bootstrap', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: false, status: 500 }));
    await expect(loadObservabilityConfig()).resolves.toEqual({
      sentryDsn: '',
      sentryEnvironment: 'local',
      posthogApiKey: '',
      posthogHost: ''
    });
  });

  it('falls back to a disabled config when the fetch itself rejects (offline, bad deploy)', async () => {
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new Error('network down')));
    await expect(loadObservabilityConfig()).resolves.toEqual({
      sentryDsn: '',
      sentryEnvironment: 'local',
      posthogApiKey: '',
      posthogHost: ''
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
