// Error tracking (Sentry-protocol, typically a Bugsink instance) and audience
// analytics (Cloudflare Web Analytics) for the frontend. Both stay off unless
// the server-side env vars are set: see docs/observabilite.md.

import { ErrorHandler, Provider } from '@angular/core';

import { APP_VERSION } from '../version';
import { AppConfig } from './models';

const DISABLED_CONFIG: AppConfig = {
  sentryDsn: '',
  sentryEnvironment: 'local',
  cloudflareWebAnalyticsToken: '',
  devMode: false,
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
    console.error(
      'Could not load the observability config, error tracking and analytics stay disabled.',
      error,
    );
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

/**
 * Applies {@link masquerJetonEspace} to every string of a report, however deep.
 *
 * <p>Masking a handful of named fields was not enough, and the misses were the
 * likely ones: a route change inside the espace lands in a navigation
 * breadcrumb as `data.from` / `data.to`, and an HTTP failure reads
 * "Http failure response for /api/espace-animateur/…" inside
 * `exception.values[].value` — the single most common error there is. Walking
 * the whole payload removes the question of whether the next SDK version puts
 * the URL somewhere new; reports are rare, so the cost is nil.</p>
 */
export function masquerJetonPartout<T>(valeur: T): T {
  if (typeof valeur === 'string') {
    return masquerJetonEspace(valeur) as T;
  }
  if (Array.isArray(valeur)) {
    valeur.forEach((element, index) => {
      valeur[index] = masquerJetonPartout(element);
    });
    return valeur;
  }
  if (valeur && typeof valeur === 'object') {
    const objet = valeur as Record<string, unknown>;
    for (const key of Object.keys(objet)) {
      objet[key] = masquerJetonPartout(objet[key]);
    }
    return valeur;
  }
  return valeur;
}

function estPageEspaceAnimateur(): boolean {
  return typeof location !== 'undefined' && location.pathname.startsWith(PREFIXE_ESPACE);
}

/**
 * No-ops on whichever half of `config` is blank (dsn / token unset
 * server-side), and returns the providers the app must be bootstrapped with —
 * today, Sentry's `ErrorHandler`, or nothing at all.
 *
 * <p>The SDK is imported dynamically, inside the branch that needs it. A static
 * import put it in the initial bundle of *every* visitor, including the ones on
 * `/animateur/:jeton` — a public page, often on a phone, often a minor — and
 * including deployments where `sentryDsn` is blank and this function does
 * nothing at all. Returning the providers rather than exposing a second
 * function keeps that import in one place: a caller cannot ask for the error
 * handler without having gone through the branch that loaded the SDK.</p>
 */
export async function initObservability(config: AppConfig): Promise<Provider[]> {
  const providers: Provider[] = [];
  if (config.sentryDsn) {
    const Sentry = await import('@sentry/angular');
    Sentry.init({
      dsn: config.sentryDsn,
      environment: config.sentryEnvironment,
      release: APP_VERSION,
      // The SDK always attaches the full request URL, and records fetch/xhr
      // calls as breadcrumbs — both carry the access token here.
      beforeSend: (event) => masquerJetonPartout(event),
      beforeBreadcrumb: (breadcrumb) => masquerJetonPartout(breadcrumb),
    });
    providers.push({ provide: ErrorHandler, useValue: Sentry.createErrorHandler() });
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
  return providers;
}
