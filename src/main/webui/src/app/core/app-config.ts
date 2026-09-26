import { InjectionToken, inject } from '@angular/core';
import { AppConfig } from './models';

/**
 * The `/api/config` answer, fetched once in `main.ts` before the application
 * bootstraps and provided here so any screen can read it synchronously.
 *
 * <p>It exists because some of that configuration decides what to *render* —
 * the Quarkus Dev UI link only makes sense against a server launched in dev
 * mode — and a component cannot wait for an HTTP round trip to know whether a
 * menu entry belongs on screen.</p>
 */
export const APP_CONFIG = new InjectionToken<AppConfig>('APP_CONFIG');

/**
 * What the application runs with when `/api/config` could not be read: every
 * optional feature off. Observability stays silent, and the drag and drop of
 * the day views is not offered — a gesture that writes to the plan is the last
 * thing to switch on by guesswork.
 */
export const DEFAULT_APP_CONFIG: AppConfig = {
  sentryDsn: '',
  sentryEnvironment: 'local',
  cloudflareWebAnalyticsToken: '',
  devMode: false,
  dragDropEnabled: false,
  version: '',
  // Production's defaults: Keycloak is the door and the break-glass form is
  // closed. A login page offering a password field no server accepts would be
  // worse than one offering the sign-in button every deployment has.
  authOidc: true,
  authSecours: false,
};

/**
 * The server's answer, or {@link DEFAULT_APP_CONFIG} where no one provided it
 * (a unit test mounting one component): the one place that decides what a
 * missing value means.
 */
export function injectAppConfig(): AppConfig {
  return inject(APP_CONFIG, { optional: true }) ?? DEFAULT_APP_CONFIG;
}
