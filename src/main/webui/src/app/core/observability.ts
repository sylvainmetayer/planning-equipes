// Error tracking (Sentry-protocol, typically a Bugsink instance) and audience
// analytics (Cloudflare Web Analytics) for the frontend. Both stay off unless
// the server-side env vars are set: see docs/observabilite.md.

import { ErrorHandler, Provider } from '@angular/core';
import { createErrorHandler, init as initSentry } from '@sentry/angular';

import { APP_VERSION } from '../version';
import { ObservabilityConfig } from './models';

const DISABLED_CONFIG: ObservabilityConfig = {
  sentryDsn: '',
  sentryEnvironment: 'local',
  cloudflareWebAnalyticsToken: ''
};

/**
 * Fetched once before `bootstrapApplication()`, same pattern as the i18n
 * catalog in `main.ts`: a failed fetch must not block startup, it just leaves
 * observability disabled for that session.
 */
export async function loadObservabilityConfig(): Promise<ObservabilityConfig> {
  try {
    const response = await fetch('/api/config');
    if (!response.ok) {
      throw new Error(`HTTP ${response.status}`);
    }
    return (await response.json()) as ObservabilityConfig;
  } catch (error) {
    console.error('Could not load the observability config, error tracking and analytics stay disabled.', error);
    return DISABLED_CONFIG;
  }
}

/** No-ops on whichever half of `config` is blank (dsn / token unset server-side). */
export function initObservability(config: ObservabilityConfig): void {
  if (config.sentryDsn) {
    initSentry({
      dsn: config.sentryDsn,
      environment: config.sentryEnvironment,
      release: APP_VERSION
    });
  }
  if (config.cloudflareWebAnalyticsToken && !document.querySelector('script[data-cf-beacon]')) {
    const script = document.createElement('script');
    script.type = 'module';
    script.src = 'https://static.cloudflareinsights.com/beacon.min.js';
    script.dataset['cfBeacon'] = JSON.stringify({ token: config.cloudflareWebAnalyticsToken });
    document.head.append(script);
  }
}

/** Swaps in Sentry's `ErrorHandler` — only meaningful once `initObservability` actually called `Sentry.init`. */
export function observabilityProviders(config: ObservabilityConfig): Provider[] {
  return config.sentryDsn ? [{ provide: ErrorHandler, useValue: createErrorHandler() }] : [];
}
