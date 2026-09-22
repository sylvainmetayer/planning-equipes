import { defineConfig, devices, type VideoMode } from '@playwright/test';

/**
 * End-to-end perimeter tests (issue #165), run on every push and every pull
 * request by the `e2e` job of .github/workflows/tests.yml, which stands up the
 * full stack the suite needs (app + PostgreSQL + Mailpit) as disposable
 * service containers —
 * see docs/developpement.md § "Tests de bout en bout (Playwright)".
 *
 * They cost minutes where the unit suites answer in seconds, and that is the
 * point: three interface defects of the #331..#338 stack were found by a human
 * clicking, none of them visible to a unit test.
 *
 * Locally, target a DISPOSABLE stack: the suite seeds data through the admin API
 * (`/api/database/import`) and decides real demandes d'échange.
 *
 * Environment knobs:
 * - E2E_BASE_URL        (default http://localhost:8080)
 * - E2E_ADMIN_PASSWORD  (default admin, must match the app's ADMIN_PASSWORD)
 * - E2E_CHROMIUM        optional Chromium executable, for sandboxes that ship
 *                       a browser without letting Playwright download its own
 * - E2E_VIDEO           optional Playwright video mode ('on',
 *                       'retain-on-failure', …); unset records nothing
 *
 * Deux projets : `chromium` (bureau, toute la suite) et `mobile`, qui rejoue la
 * seule suite de l'espace animateur sur un viewport de téléphone — c'est de là
 * que la plupart des animateurs ouvrent leur lien.
 */
/** Chromium déjà présent sur la machine, pour un environnement qui interdit son téléchargement. */
const chromiumInstalle = process.env['E2E_CHROMIUM']
  ? { launchOptions: { executablePath: process.env['E2E_CHROMIUM'] } }
  : {};

/*
 * Vidéo à la demande, et jamais par défaut : 57 tests filmés pèsent lourd pour
 * un signal que la trace donne déjà en mieux (pellicule, DOM, réseau, console).
 * Elle sert à montrer un parcours à qui ne lancera pas Playwright.
 */
const videoDemandee = process.env['E2E_VIDEO']
  ? { video: process.env['E2E_VIDEO'] as VideoMode }
  : {};

export default defineConfig({
  testDir: './e2e',
  // Photographie l'état de la base avant la première spec : chacune y revient
  // ensuite, au lieu de nettoyer les préfixes d'identifiant de ses voisines.
  // Voir e2e/reference.ts.
  globalSetup: './e2e/reference.ts',
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
    trace: 'retain-on-failure',
    ...videoDemandee,
  },
  projects: [
    {
      name: 'chromium',
      use: {
        ...devices['Desktop Chrome'],
        ...chromiumInstalle,
      },
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
        ...chromiumInstalle,
      },
    },
  ],
});
