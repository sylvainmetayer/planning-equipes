// Error tracking (Sentry-protocol, typically a Bugsink instance) and audience
// analytics (Cloudflare Web Analytics) for the frontend. Both stay off unless
// the server-side env vars are set: see docs/observabilite.md.

import { ErrorHandler, Provider } from '@angular/core';
import { createErrorHandler, init as initSentry } from '@sentry/angular';

import { APP_VERSION } from '../version';
import { AppConfig } from './models';

const DISABLED_CONFIG: AppConfig = {
  sentryDsn: '',
  sentryEnvironment: 'local',
  cloudflareWebAnalyticsToken: '',
  devMode: false
};

/**
 * Fetched once before `bootstrapApplication()`, same pattern as the i18n
 * catalog in `main.ts`: a failed fetch must not block startup, it just leaves
 * observability disabled for that session.
 */
export async function loadAppConfig(): Promise<AppConfig> {
  try {
    const response = await fetch('/api/config');
    if (!response.ok) {
      throw new Error(`HTTP ${response.status}`);
    }
    return (await response.json()) as AppConfig;
  } catch (error) {
    console.error('Could not load the observability config, error tracking and analytics stay disabled.', error);
    return DISABLED_CONFIG;
  }
}

/**
 * The espace animateur carries its access token in the URL path. That token is
 * a unique, stable identifier of one named person — often a minor — so any
 * telemetry that reports "which page was viewed" would be reporting who was
 * viewing it, to a third party, with no basis for doing so.
 *
 * <p>Hence the two guards below: audience measurement is not loaded at all on
 * those pages, and error reports have the token replaced before they leave the
 * browser. Checking `location.pathname` once at bootstrap is enough — the
 * espace is entered by its URL and no in-app navigation crosses into it.</p>
 */
const PREFIXE_ESPACE = '/animateur/';

/**
 * Replaces the access token of an espace URL with a placeholder, leaving the
 * rest readable. Covers both shapes it takes: the page the browser is on
 * (`/animateur/…`) and the calls it makes (`/api/espace-animateur/…`), which
 * land in breadcrumbs.
 */
export function masquerJetonEspace(valeur: string): string {
  return valeur.replace(/\/(api\/espace-animateur|animateur)\/[^/?#\s"']+/g, '/$1/<jeton>');
}

function estPageEspaceAnimateur(): boolean {
  return typeof location !== 'undefined' && location.pathname.startsWith(PREFIXE_ESPACE);
}

/** No-ops on whichever half of `config` is blank (dsn / token unset server-side). */
export function initObservability(config: AppConfig): void {
  if (config.sentryDsn) {
    initSentry({
      dsn: config.sentryDsn,
      environment: config.sentryEnvironment,
      release: APP_VERSION,
      // The SDK always attaches the full request URL, and records fetch/xhr
      // calls as breadcrumbs — both carry the access token here.
      beforeSend: (event) => {
        if (event.request?.url) {
          event.request.url = masquerJetonEspace(event.request.url);
        }
        if (event.message) {
          event.message = masquerJetonEspace(event.message);
        }
        return event;
      },
      beforeBreadcrumb: (breadcrumb) => {
        if (typeof breadcrumb.data?.['url'] === 'string') {
          breadcrumb.data['url'] = masquerJetonEspace(breadcrumb.data['url'] as string);
        }
        if (breadcrumb.message) {
          breadcrumb.message = masquerJetonEspace(breadcrumb.message);
        }
        return breadcrumb;
      }
    });
  }
  if (
    config.cloudflareWebAnalyticsToken &&
    !estPageEspaceAnimateur() &&
    !document.querySelector('script[data-cf-beacon]')
  ) {
    const script = document.createElement('script');
    script.type = 'module';
    script.src = 'https://static.cloudflareinsights.com/beacon.min.js';
    script.dataset['cfBeacon'] = JSON.stringify({ token: config.cloudflareWebAnalyticsToken });
    document.head.append(script);
  }
}

/** Swaps in Sentry's `ErrorHandler` — only meaningful once `initObservability` actually called `Sentry.init`. */
export function observabilityProviders(config: AppConfig): Provider[] {
  return config.sentryDsn ? [{ provide: ErrorHandler, useValue: createErrorHandler() }] : [];
}
