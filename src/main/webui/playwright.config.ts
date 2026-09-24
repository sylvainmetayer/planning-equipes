import { defineConfig, devices, type VideoMode } from '@playwright/test';

/**
 * End-to-end perimeter tests (issue #165), run on every push and every pull
 * request by .github/workflows/e2e.yml, which stands up the full stack the
 * suite needs (app + PostgreSQL + Mailpit + Keycloak) as disposable
 * containers — see docs/developpement.md § "Tests de bout en bout (Playwright)".
 *
 * That stack runs Keycloak (OIDC_ENABLED=true) with the break-glass account
 * open (ADMIN_SECOURS_ENABLED=true): the admin side signs in through the
 * latter's form, the espace animateur through Keycloak (e2e/keycloak.ts). A
 * stack without Keycloak (OIDC_ENABLED=false) still runs every spec that
 * opens no espace — the @lourd selection is one.
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
 * - E2E_KEYCLOAK_URL    (default http://keycloak:8081, same URL for browser and app)
 * - E2E_KEYCLOAK_ADMIN / E2E_KEYCLOAK_ADMIN_PASSWORD  Keycloak's own
 *                       administrator, which creates the animateur accounts
 *                       the espace specs sign in with
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
  // Les suites Keycloak « de production » visent une pile où le compte de
  // secours est fermé (ADMIN_SECOURS_ENABLED=false) — y compris le formulaire
  // par lequel le globalSetup ci-dessous photographie la base. Elles ont leur
  // propre configuration (playwright.oidc.config.ts) et leur propre job de CI.
  //
  // Les DEUX sont nommées ici, et la paire doit rester en regard de celle du
  // `testMatch` d'en face : une spec absente des deux tourne dans cette
  // suite-ci, où le compte de secours est ouvert, et y échoue.
  testIgnore: /authentification-(keycloak|methodes)\.spec\.ts/,
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
       * rejoue que cette suite-là, et le balayage axe de ses quatre écrans —
       * l'interface d'administration assume, elle, d'être une interface de
       * bureau.
       */
      name: 'mobile',
      testMatch: /(espace-animateur|accessibilite)\.spec\.ts/,
      use: {
        ...devices['Pixel 7'],
        ...chromiumInstalle,
      },
    },
  ],
});
