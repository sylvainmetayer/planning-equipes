// The read-only views over the seeded persisted planning: the Planning page
// per person and per stand, staffing, stand openings and the animateur's
// planning actually show the seeded data —
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

test('le planning par personne liste les animateurs du planning enregistré', async ({
  browser,
}) => {
  const page = await pageAdmin(browser, admin);
  // The former Heures address lands on the axis that absorbed it.
  await page.goto('/hours');
  await expect(page).toHaveURL(/\/journee\?.*axe=personne/);
  await expect(page.locator('#contenu')).toContainText('Heures pour la paie (CSV)');
  await expect(page.locator('#contenu')).toContainText('E2E');
  await page.context().close();
});

test('le planning par stand dit ce qui est pourvu, stand par stand et jour par jour', async ({
  browser,
}) => {
  const page = await pageAdmin(browser, admin);
  await page.goto('/journee?axe=stand');
  await expect(page.locator('#contenu')).toContainText('Stand E2E un');
  await expect(page.locator('#contenu')).toContainText('Total du jour');
  // A cell of the grid opens the Siège panel, as a cell of the day does.
  await page.locator('.planning-grille-case-active').first().click();
  await expect(page).toHaveURL(/siege=/);
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

test('les jours de repos se lisent au pied du planning par personne', async ({ browser }) => {
  const page = await pageAdmin(browser, admin);
  await page.goto('/repos');
  await expect(page).toHaveURL(/\/journee\?.*axe=personne/);
  await expect(page.locator('#contenu')).toContainText('Alice E2E');
  await expect(page.locator('#contenu')).toContainText('Au repos ce jour-là');
  await page.context().close();
});

test("le planning d'un animateur, sur sa fiche, montre ses stands à couvrir", async ({
  browser,
}) => {
  const page = await pageAdmin(browser, admin);
  await page.goto('/animateurs/E2E-A');
  const planning = page.locator('#fiche-section-timeline');
  await expect(planning).toContainText('stand(s) au total');
  await expect(planning).toContainText(/Stand E2E (un|deux)/);
  await page.context().close();
});

test("l'envoi des plannings par e-mail rend compte, individuellement et pour tous", async ({
  browser,
}) => {
  test.slow();
  const page = await pageAdmin(browser, admin);
  await page.goto('/animateurs/E2E-A');

  // Individual send, from the fiche: Alice has an address, the mail leaves
  // (mock SMTP) once the confirmation is accepted.
  await page.getByRole('button', { name: 'Envoyer son planning' }).click();
  await page.getByRole('dialog').getByRole('button', { name: 'Envoyer' }).click();
  await expect(page.getByText('Planning envoyé — Alice E2E')).toBeVisible();

  // Le pendant collectif vit sur la page Publication : « Publier ». Le seed
  // vient de publier, donc il n'y a plus personne à prévenir et le bouton le
  // dit plutôt que d'inviter à un clic qui serait refusé. Le parcours complet
  // (déplacer un siège, relire la liste, publier) est dans publication.spec.ts.
  await page.goto('/publication');
  await expect(page.getByText('Tout le monde est à jour')).toBeVisible();
  await expect(page.getByText(/Dernière publication le/)).toBeVisible();
  await page.context().close();
});
