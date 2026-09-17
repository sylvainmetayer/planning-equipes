// The read-only views over the seeded persisted planning: hours, staffing,
// stand openings, rest days and the animateur timeline actually show the
// seeded data —
// not just a rendered shell.

import { APIRequestContext, expect, test } from '@playwright/test';
import { contexteAdmin, pageAdmin, seedPlanning } from './support';
import { repartirDeLaReference } from './reference';

let admin: APIRequestContext;

test.beforeAll(async ({ playwright }, testInfo) => {
  admin = await contexteAdmin(playwright, testInfo.project.use.baseURL as string);
  await repartirDeLaReference(admin);
  await seedPlanning(admin);
});

test.afterAll(async () => {
  await admin.dispose();
});

test('les heures planifiées listent les animateurs du planning enregistré', async ({ browser }) => {
  const page = await pageAdmin(browser, admin);
  await page.goto('/hours');
  await expect(page.locator('#contenu')).toContainText('Heures planifiées par animateur');
  await expect(page.locator('#contenu')).toContainText('E2E');
  await page.context().close();
});

test('le besoin en effectif se calcule sur les stands et créneaux ensemencés', async ({
  browser,
}) => {
  const page = await pageAdmin(browser, admin);
  await page.goto('/diagnostic?onglet=besoin');
  await expect(page.locator('#contenu')).toContainText('Minimum retenu');
  await expect(page.locator('#contenu')).toContainText('Pic simultané');
  await page.context().close();
});

test('la grille des ouvertures montre les stands ensemencés', async ({ browser }) => {
  const page = await pageAdmin(browser, admin);
  await page.goto('/ouvertures');
  await expect(page.locator('#contenu')).toContainText('Stand E2E un');
  await expect(page.locator('#contenu')).toContainText('Stand E2E deux');
  await page.context().close();
});

test('la grille des jours de repos montre les animateurs du planning enregistré', async ({
  browser,
}) => {
  const page = await pageAdmin(browser, admin);
  await page.goto('/repos');
  await expect(page.locator('#contenu')).toContainText('Jours de repos');
  await expect(page.locator('#contenu')).toContainText('Alice E2E');
  await expect(page.locator('#contenu')).toContainText('Au repos ce jour-là');
  await page.context().close();
});

test("la timeline d'un animateur montre ses stands à couvrir", async ({ browser }) => {
  const page = await pageAdmin(browser, admin);
  await page.goto('/timeline');
  // The selector is a Material autocomplete: once open, its listbox shares the
  // "Animateur" label with the input, so target the combobox role explicitly.
  const champ = page.getByRole('combobox', { name: 'Animateur' });
  await champ.click();
  await champ.fill('Alice');
  await page.getByRole('option', { name: /Alice E2E/ }).click();
  await expect(page.locator('#contenu')).toContainText('Stands à couvrir');
  await expect(page.locator('#contenu')).toContainText(/Stand E2E (un|deux)/);
  await page.context().close();
});

test("l'envoi des plannings par e-mail rend compte, individuellement et pour tous", async ({
  browser,
}) => {
  test.slow();
  const page = await pageAdmin(browser, admin);
  await page.goto('/timeline');
  const champ = page.getByRole('combobox', { name: 'Animateur' });
  await champ.click();
  await champ.fill('Alice');
  await page.getByRole('option', { name: /Alice E2E/ }).click();

  // Individual send, from the timeline: Alice has an address, the mail
  // leaves (mock SMTP).
  await page.getByRole('button', { name: 'Envoyer par e-mail' }).click();
  await expect(page.getByText('Planning envoyé à Alice E2E')).toBeVisible();

  // Le pendant collectif vit sur la page Publication : « Publier ». Le seed
  // vient de publier, donc il n'y a plus personne à prévenir et le bouton le
  // dit plutôt que d'inviter à un clic qui serait refusé. Le parcours complet
  // (déplacer un siège, relire la liste, publier) est dans publication.spec.ts.
  await page.goto('/publication');
  await expect(page.getByText('Tout le monde est à jour')).toBeVisible();
  await expect(page.getByText(/Dernière publication le/)).toBeVisible();
  await page.context().close();
});
