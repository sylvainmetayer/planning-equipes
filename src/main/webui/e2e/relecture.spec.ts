// « Relu et accepté » through a browser: the prerequisites of a day are read
// out and never block, accepting records the reading and moves the banner, the
// lock is only laid down when it is asked for, and withdrawing puts the day
// back to read.
//
// What a unit test cannot see is exactly this: that the panel, the banner on
// three screens and the publication note all describe the same reading.

import { APIRequestContext, expect, test } from '@playwright/test';
import { contexteAdmin, pageAdmin, planningPersiste, seedPlanning } from './support';
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

test.afterEach(async () => {
  const validations = await (await admin.get('/api/validations')).json();
  for (const validation of validations as { id: string }[]) {
    await admin.delete(`/api/validations/${validation.id}`);
  }
  const verrous = await (await admin.get('/api/verrouillages')).json();
  for (const verrou of verrous as { id: string }[]) {
    await admin.delete(`/api/verrouillages/${verrou.id}`);
  }
});

test('accepter une journée la marque relue, sans rien figer', async ({ browser }) => {
  const page = await pageAdmin(browser, admin);
  await page.goto('/journee');

  const panneau = page.locator('.journee-validation');
  await expect(panneau).toContainText('Relecture de la journée');
  // The prerequisites are read out, and the button stays available whatever
  // they say: an unmet one is a warning, never a gate.
  await expect(panneau.locator('.journee-validation-prerequis li').first()).toBeVisible();
  const accepter = panneau.getByRole('button', { name: 'Marquer relu et accepté' });
  await expect(accepter).toBeEnabled();

  await accepter.click();

  await expect(panneau).toContainText('Relue et acceptée le');
  await expect(page.locator('.relecture-banniere')).toContainText('1');
  // Accepting says somebody read the day; it must not freeze it.
  expect(await (await admin.get('/api/verrouillages')).json()).toEqual([]);

  await panneau.getByRole('button', { name: 'Retirer la validation' }).click();
  await expect(panneau.locator('.journee-validation-prerequis li').first()).toBeVisible();

  await page.context().close();
});

test('filtrée sur un stand, la journée se valide en entier', async ({ browser }) => {
  const standId =
    (await planningPersiste(admin)).postes.find((poste) => poste.stand)?.stand?.id ?? '';
  expect(standId).not.toBe('');
  const page = await pageAdmin(browser, admin);
  await page.goto(`/journee?stand=${encodeURIComponent(standId)}`);

  const panneau = page.locator('.journee-validation');
  await expect(panneau).toContainText('La relecture porte sur la journée entière');
  await panneau.getByRole('button', { name: 'Marquer relu et accepté' }).click();
  await expect(panneau).toContainText('Relue et acceptée le');

  // The filter narrowed what was on screen, never what was accepted: the day
  // counts as read, and the banner's figure moved with it.
  const progression = await (await admin.get('/api/validations/progression')).json();
  expect(progression.journeesValidees).toBe(1);

  await page.context().close();
});

test('le verrouillage est posé seulement quand on le demande', async ({ browser }) => {
  const page = await pageAdmin(browser, admin);
  await page.goto('/journee');

  const panneau = page.locator('.journee-validation');
  await panneau.getByText('Verrouiller aussi cette journée').click();
  await panneau.getByRole('button', { name: 'Marquer relu et accepté' }).click();
  await expect(panneau).toContainText('Relue et acceptée le');

  const verrous = (await (await admin.get('/api/verrouillages')).json()) as { type: string }[];
  expect(verrous.map((verrou) => verrou.type)).toEqual(['JOUR']);

  await page.context().close();
});

test('la progression se lit aussi sur le calendrier et le solveur', async ({ browser }) => {
  const page = await pageAdmin(browser, admin);
  await page.goto('/journee');
  await page
    .locator('.journee-validation')
    .getByRole('button', { name: 'Marquer relu et accepté' })
    .click();
  await expect(page.locator('.relecture-banniere')).toBeVisible();

  await page.goto('/calendar');
  await expect(page.locator('.relecture-banniere')).toContainText('relues et acceptées');

  await page.goto('/solveur');
  await expect(page.locator('.relecture-banniere')).toContainText('relues et acceptées');

  await page.context().close();
});
