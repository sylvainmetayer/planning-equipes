// Error tracking (Sentry-protocol, typically a Bugsink instance) and audience
// analytics (Cloudflare Web Analytics) for the frontend. Both stay off unless
// the server-side env vars are set: see docs/observabilite.md.

import { ErrorHandler, Provider } from '@angular/core';

import { APP_VERSION } from '../version';
import { DEFAULT_APP_CONFIG } from './app-config';
import { AppConfig } from './models';

/**
 * Fetched once before `bootstrapApplication()`, same pattern as the i18n
 * catalog in `main.ts`: a failed fetch must not block startup, it just leaves
 * the session on {@link DEFAULT_APP_CONFIG} — observability disabled, and no
 * drag and drop in the day views, until the page is reloaded.
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
      'Could not load /api/config: error tracking, analytics and drag and drop stay disabled until the page is reloaded.',
      error,
    );
    return DEFAULT_APP_CONFIG;
  }
}

/**
 * Two public pages carry a credential in their URL path: the espace animateur
 * (`/animateur/<token>`) and the wall display (`/mural/<token>`). The first is
 * a unique, stable identifier of one named person — often a minor — so any
 * telemetry that reports "which page was viewed" would be reporting who was
 * viewing it, to a third party, with no basis for doing so. The second opens
 * the whole day's staffing, names included, to whoever holds it: handing it to
 * an analytics or error-tracking provider would hand that provider the screen.
 *
 * <p>Hence the two guards below: audience measurement is not loaded at all on
 * those pages, and error reports have the token replaced before they leave the
 * browser. Checking `location.pathname` once at bootstrap is enough — both
 * pages are entered by their URL and no in-app navigation crosses into them.</p>
 */
const TOKEN_PAGE_PREFIXES = ['/animateur/', '/mural/'];

/**
 * Replaces the token of an espace or wall-display URL with a placeholder,
 * leaving the rest readable. Covers both shapes each takes: the page the
 * browser is on (`/animateur/…`, `/mural/…`) and the calls it makes
 * (`/api/espace-animateur/…`, `/api/mural/…`), which land in breadcrumbs. The
 * admin routes `/api/affichage-mural/<id>` carry an id, not a token, and are
 * left alone (the segment before `mural` is not a slash there).
 */
export function maskUrlToken(value: string): string {
  return value.replace(
    /\/(api\/espace-animateur|animateur|api\/mural|mural)\/[^/?#\s"']+/g,
    '/$1/<jeton>',
  );
}

/**
 * Applies {@link maskUrlToken} to every string of a report, however deep.
 *
 * <p>Masking a handful of named fields was not enough, and the misses were the
 * likely ones: a route change inside the espace lands in a navigation
 * breadcrumb as `data.from` / `data.to`, and an HTTP failure reads
 * "Http failure response for /api/espace-animateur/…" inside
 * `exception.values[].value` — the single most common error there is. Walking
 * the whole payload removes the question of whether the next SDK version puts
 * the URL somewhere new; reports are rare, so the cost is nil.</p>
 */
export function maskTokensEverywhere<T>(value: T): T {
  if (typeof value === 'string') {
    return maskUrlToken(value) as T;
  }
  if (Array.isArray(value)) {
    value.forEach((element, index) => {
      value[index] = maskTokensEverywhere(element);
    });
    return value;
  }
  if (value && typeof value === 'object') {
    const record = value as Record<string, unknown>;
    for (const key of Object.keys(record)) {
      record[key] = maskTokensEverywhere(record[key]);
    }
    return value;
  }
  return value;
}

function isTokenPage(): boolean {
  return (
    typeof location !== 'undefined' &&
    TOKEN_PAGE_PREFIXES.some((prefix) => location.pathname.startsWith(prefix))
  );
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
      // calls as breadcrumbs — both carry the espace or wall token here.
      beforeSend: (event) => maskTokensEverywhere(event),
      beforeBreadcrumb: (breadcrumb) => maskTokensEverywhere(breadcrumb),
    });
    providers.push({ provide: ErrorHandler, useValue: Sentry.createErrorHandler() });
  }
  if (
    config.cloudflareWebAnalyticsToken &&
    !isTokenPage() &&
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
