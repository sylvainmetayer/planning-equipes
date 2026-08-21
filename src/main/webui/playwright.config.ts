import { defineConfig, devices } from '@playwright/test';

/**
 * End-to-end perimeter tests (issue #165), deliberately OUT of CI: they need
 * the full stack running (app + PostgreSQL), which no CI job provides — see
 * docs/developpement.md § "Tests de bout en bout (Playwright)".
 *
 * Target a DISPOSABLE local stack: the suite seeds data through the admin API
 * (`/api/database/import`) and decides real demandes d'échange.
 *
 * Environment knobs:
 * - E2E_BASE_URL        (default http://localhost:8080)
 * - E2E_ADMIN_PASSWORD  (default admin, must match the app's ADMIN_PASSWORD)
 * - E2E_CHROMIUM        optional Chromium executable, for sandboxes that ship
 *                       a browser without letting Playwright download its own
 *
 * Deux projets : `chromium` (bureau, toute la suite) et `mobile`, qui rejoue la
 * seule suite de l'espace animateur sur un viewport de téléphone — c'est de là
 * que la plupart des animateurs ouvrent leur lien.
 */
/** Chromium déjà présent sur la machine, pour un environnement qui interdit son téléchargement. */
const chromiumInstalle = process.env['E2E_CHROMIUM']
  ? { launchOptions: { executablePath: process.env['E2E_CHROMIUM'] } }
  : {};

export default defineConfig({
  testDir: './e2e',
  // The specs share one database and one seeded dataset: keep them ordered.
  fullyParallel: false,
  workers: 1,
  reporter: [['list']],
  timeout: 30_000,
  // A cold SPA load (fresh context, lazy chunks) can take >10s on a modest
  // machine; the default 5s expect budget flakes right after a full reload.
  expect: { timeout: 15_000 },
  use: {
    baseURL: process.env['E2E_BASE_URL'] ?? 'http://localhost:8080',
    // The UI's source language; the specs assert on French labels.
    locale: 'fr-FR',
    trace: 'retain-on-failure'
  },
  projects: [
    {
      name: 'chromium',
      use: {
        ...devices['Desktop Chrome'],
        ...chromiumInstalle
      }
    },
    {
      /*
       * L'espace animateur sur un téléphone : c'est ainsi que la majorité des
       * animateurs l'ouvrent, le lien leur arrivant par e-mail. Ce projet ne
       * rejoue que cette suite-là — l'interface d'administration assume, elle,
       * d'être une interface de bureau.
       */
      name: 'mobile',
      testMatch: /espace-animateur\.spec\.ts/,
      use: {
        ...devices['Pixel 7'],
        ...chromiumInstalle
      }
    }
  ]
});
