import { defineConfig, devices } from '@playwright/test';

/**
 * The real-provider OIDC trial (.github/workflows/oidc-keycloak.yml): one spec,
 * `e2e/oidc-keycloak.oidc.ts`, against a Keycloak the workflow starts. Apart
 * from `playwright.config.ts` on purpose: no database snapshot (`globalSetup`),
 * and the suffix keeps the ordinary suite from picking the spec up.
 *
 * Environment: E2E_BASE_URL, E2E_ADMIN_PASSWORD (the break-glass account that
 * seeds the application's accounts), E2E_KEYCLOAK_URL, E2E_OIDC_USER_PASSWORD
 * (the password the realm import gave both test users).
 */
const chromiumInstalle = process.env['E2E_CHROMIUM']
  ? { launchOptions: { executablePath: process.env['E2E_CHROMIUM'] } }
  : {};

export default defineConfig({
  testDir: './e2e',
  testMatch: '**/*.oidc.ts',
  workers: 1,
  reporter: [['list']],
  timeout: 60_000,
  expect: { timeout: 15_000 },
  use: {
    baseURL: process.env['E2E_BASE_URL'] ?? 'http://localhost:8080',
    locale: 'fr-FR',
    trace: 'retain-on-failure',
  },
  projects: [{ name: 'chromium', use: { ...devices['Desktop Chrome'], ...chromiumInstalle } }],
});
