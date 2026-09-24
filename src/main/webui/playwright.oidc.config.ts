import { defineConfig, devices, type VideoMode } from '@playwright/test';

/**
 * La suite Keycloak de production, et elle seule.
 *
 * Pourquoi une configuration à part plutôt qu'un projet de plus dans
 * `playwright.config.ts` : cette suite vise une pile **différente**, montée
 * comme la production — OIDC_ENABLED=true et ADMIN_SECOURS_ENABLED=false —,
 * où le formulaire du compte de secours répond 409. Or le `globalSetup` de la
 * configuration principale (`e2e/reference.ts`) commence par s'y connecter
 * pour photographier la base : contre cette pile, il échouerait avant le
 * premier test. Un projet Playwright ne peut pas avoir son propre
 * `globalSetup` ; un fichier de configuration, si.
 *
 * L'autre moitié de la réponse est que cette suite n'a pas besoin de cette
 * photographie : elle crée ses propres fiches, préfixées `KC-`, et ne touche
 * ni au planning ni aux échanges des autres specs.
 *
 * La suite principale, elle, tourne contre une pile Keycloak où le compte de
 * secours est ouvert (ADMIN_SECOURS_ENABLED=true) : l'administration s'y
 * connecte par le formulaire, l'espace animateur par Keycloak.
 *
 * Lancement :
 *   npm run e2e:oidc
 * contre une pile démarrée avec OIDC_ENABLED=true et ADMIN_SECOURS_ENABLED=false
 * — voir docs/keycloak.md.
 *
 * Variables :
 * - E2E_BASE_URL       (défaut http://localhost:8080)
 * - E2E_KEYCLOAK_URL   (défaut http://keycloak:8081, la même URL des deux côtés)
 * - E2E_KC_*           comptes et clients, si le realm visé n'est pas celui de
 *                      docker/keycloak/realm-planning.json
 * - E2E_ADMIN_PASSWORD le mot de passe du compte de secours de la pile, que la
 *                      suite poste pour vérifier qu'il n'ouvre plus rien
 * - E2E_CHROMIUM       navigateur déjà installé, pour un bac à sable qui
 *                      interdit son téléchargement
 * - E2E_VIDEO          mode vidéo Playwright ; non défini, rien n'est filmé
 */
const chromiumInstalle = process.env['E2E_CHROMIUM']
  ? { launchOptions: { executablePath: process.env['E2E_CHROMIUM'] } }
  : {};

const videoDemandee = process.env['E2E_VIDEO']
  ? { video: process.env['E2E_VIDEO'] as VideoMode }
  : {};

export default defineConfig({
  testDir: './e2e',
  // En regard du `testIgnore` de playwright.config.ts : ce que celui-ci écarte,
  // celui-là doit le reprendre, sans quoi la spec ne tourne nulle part.
  testMatch: /authentification-(keycloak|methodes)\.spec\.ts/,
  // Les specs partagent un realm et une édition : elles restent ordonnées.
  fullyParallel: false,
  workers: 1,
  reporter: [['list']],
  // Plus large que les 30 s de la suite principale : un aller-retour complet
  // vers l'authorization server traverse deux applications, et le rattrapage
  // d'une fenêtre TOTP coûte à lui seul jusqu'à trente secondes.
  timeout: 90_000,
  expect: { timeout: 15_000 },
  use: {
    baseURL: process.env['E2E_BASE_URL'] ?? 'http://localhost:8080',
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
  ],
});
