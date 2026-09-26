// « Relu et accepté » through a browser: the relecture bar of the Planning page
// counts what to look at and narrows the rendering on it, the prerequisites of
// a day are read out in its menu's dialog and never block, accepting records
// the reading and marks the day, the lock is only laid down when it is asked
// for, and withdrawing puts the day back to read.
//
// What a unit test cannot see is exactly this: that the bar, the day selector,
// the banner of the Solveur and the publication note all describe the same
// reading.

import { APIRequestContext, expect, Page, test } from '@playwright/test';
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

/** Opens « Relu et accepté… » (or its twin on a day already read) from the relecture bar's menu. */
async function ouvrirRelecture(page: Page) {
  await page.locator('.relecture-menu').click();
  await page.getByRole('menuitem', { name: /Relu et accepté…|Relecture et commentaire…/ }).click();
  const dialogue = page.getByRole('dialog');
  await expect(dialogue).toContainText('Relecture du');
  return dialogue;
}

test('accepter une journée la marque relue, sans rien figer', async ({ browser }) => {
  const page = await pageAdmin(browser, admin);
  await page.goto('/journee');

  const barre = page.locator('.relecture-barre');
  await expect(barre.locator('.relecture-pastille')).toHaveCount(4);
  await expect(barre).toContainText('À relire');

  const dialogue = await ouvrirRelecture(page);
  // The prerequisites are read out, and the button stays available whatever
  // they say: an unmet one is a warning, never a gate.
  await expect(dialogue.locator('.journee-validation-prerequis li').first()).toBeVisible();
  const accepter = dialogue.getByRole('button', { name: 'Marquer relu et accepté' });
  await expect(accepter).toBeEnabled();

  await accepter.click();

  await expect(dialogue).toBeHidden();
  await expect(barre).toContainText('Relue le');
  const progression = await (await admin.get('/api/validations/progression')).json();
  expect(progression.journeesValidees).toBe(1);
  // Accepting says somebody read the day; it must not freeze it.
  expect(await (await admin.get('/api/verrouillages')).json()).toEqual([]);

  const retrait = await ouvrirRelecture(page);
  await retrait.getByRole('button', { name: 'Retirer la validation' }).click();
  await expect(barre).toContainText('À relire');

  await page.context().close();
});

test('filtrée sur un stand, la journée se valide en entier', async ({ browser }) => {
  const standId =
    (await planningPersiste(admin)).postes.find((poste) => poste.stand)?.stand?.id ?? '';
  expect(standId).not.toBe('');
  const page = await pageAdmin(browser, admin);
  await page.goto(`/journee?stand=${encodeURIComponent(standId)}`);

  const dialogue = await ouvrirRelecture(page);
  await expect(dialogue).toContainText('La relecture porte sur la journée entière');
  await dialogue.getByRole('button', { name: 'Marquer relu et accepté' }).click();
  await expect(page.locator('.relecture-barre')).toContainText('Relue le');

  // The filter narrowed what was on screen, never what was accepted: the day
  // counts as read, and the banner's figure moved with it.
  const progression = await (await admin.get('/api/validations/progression')).json();
  expect(progression.journeesValidees).toBe(1);

  await page.context().close();
});

test('le verrouillage est posé seulement quand on le demande', async ({ browser }) => {
  const page = await pageAdmin(browser, admin);
  await page.goto('/journee');

  const dialogue = await ouvrirRelecture(page);
  await dialogue.getByText('Verrouiller aussi cette journée').click();
  await dialogue.getByRole('button', { name: 'Marquer relu et accepté' }).click();
  await expect(page.locator('.relecture-barre')).toContainText('Relue le');

  const verrous = (await (await admin.get('/api/verrouillages')).json()) as { type: string }[];
  expect(verrous.map((verrou) => verrou.type)).toEqual(['JOUR']);

  await page.context().close();
});

test('la progression se lit sur le sélecteur de jour et sur le solveur', async ({ browser }) => {
  const page = await pageAdmin(browser, admin);
  await page.goto('/journee');
  const dialogue = await ouvrirRelecture(page);
  await dialogue.getByRole('button', { name: 'Marquer relu et accepté' }).click();
  await expect(page.locator('.relecture-barre')).toContainText('Relue le');

  // The day selector marks the day read, where the month calendar used to.
  await page.locator('.mini-mois-bascule').click();
  await expect(page.locator('.mini-mois-courant')).toContainText('task_alt');

  await page.goto('/solveur');
  await expect(page.locator('.relecture-banniere')).toContainText('relues et acceptées');

  await page.context().close();
});

test("une pastille de relecture filtre le rendu sur ce qu'elle compte", async ({ browser }) => {
  const page = await pageAdmin(browser, admin);
  await page.goto('/journee?vue=rail');

  const pastille = page.locator('.relecture-pastille', { hasText: 'vide(s)' });
  await pastille.click();
  await expect(page).toHaveURL(/sieges=vides/);
  await expect(page).not.toHaveURL(/vue=rail/);
  await expect(pastille).toHaveAttribute('aria-pressed', 'true');

  // Pressed again, the chip gives the whole day back.
  await pastille.click();
  await expect(page).not.toHaveURL(/sieges=/);

  await page.context().close();
});
