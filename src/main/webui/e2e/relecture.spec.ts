// The state of a day through a browser: the relecture bar of the Planning page
// counts what to look at, the prerequisites of a day are read out in the
// control its state button opens and never block, reading a day records the
// reading, « Figée » asks first and is the only state that lays the day lock,
// and going back to « À relire » puts the day back to read.
//
// What a unit test cannot see is exactly this: that the bar, the day selector,
// the banner of the Solveur and the publication note all describe the same
// state.

import { APIRequestContext, expect, Locator, Page, test } from '@playwright/test';
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
  const etats = await (await admin.get('/api/etats-journee')).json();
  for (const etat of etats as { jour: string; etat: string }[]) {
    if (etat.etat !== 'A_RELIRE') {
      await admin.put(`/api/etats-journee/${etat.jour}`, { data: { etat: 'A_RELIRE' } });
    }
  }
  const verrous = await (await admin.get('/api/verrouillages')).json();
  for (const verrou of verrous as { id: string }[]) {
    await admin.delete(`/api/verrouillages/${verrou.id}`);
  }
});

/** Opens the day's control from the state button of the relecture bar. */
async function ouvrirRelecture(page: Page) {
  await page.locator('.relecture-menu').click();
  const dialogue = page.getByRole('dialog');
  await expect(dialogue).toContainText('Relecture du');
  return dialogue;
}

/** Points the control at a state; the toggle settles after the click, so the state is awaited. */
async function choisir(dialogue: Locator, etat: 'À relire' | 'Relue' | 'Figée') {
  const bouton = dialogue.getByRole('radio', { name: etat });
  await bouton.click();
  await expect(bouton).toHaveAttribute('aria-checked', 'true');
}

/** Chooses a state in the control and saves it. */
async function enregistrer(dialogue: Locator, etat: 'À relire' | 'Relue' | 'Figée') {
  await choisir(dialogue, etat);
  await dialogue.getByRole('button', { name: "Enregistrer l'état" }).click();
}

test('relire une journée la marque relue, sans rien figer', async ({ browser }) => {
  const page = await pageAdmin(browser, admin);
  await page.goto('/journee');

  const barre = page.locator('.relecture-barre');
  await expect(barre.getByRole('link', { name: /à relire/ })).toHaveAttribute(
    'href',
    '/diagnostic?onglet=problemes',
  );
  await expect(barre).toContainText('À relire');

  const dialogue = await ouvrirRelecture(page);
  // The prerequisites are read out, and the button stays available whatever
  // they say: an unmet one is a warning, never a gate.
  await expect(dialogue.locator('.journee-validation-prerequis li').first()).toBeVisible();
  await choisir(dialogue, 'Relue');
  await expect(dialogue.getByRole('button', { name: "Enregistrer l'état" })).toBeEnabled();
  await dialogue.getByRole('button', { name: "Enregistrer l'état" }).click();

  await expect(dialogue).toBeHidden();
  await expect(barre).toContainText('Relue le');
  const progression = await (await admin.get('/api/validations/progression')).json();
  expect(progression.journeesValidees).toBe(1);
  // Reading says somebody read the day; it must not freeze it.
  expect(await (await admin.get('/api/verrouillages')).json()).toEqual([]);

  const retrait = await ouvrirRelecture(page);
  await enregistrer(retrait, 'À relire');
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
  await enregistrer(dialogue, 'Relue');
  await expect(page.locator('.relecture-barre')).toContainText('Relue le');

  // The filter narrowed what was on screen, never what was accepted: the day
  // counts as read, and the banner's figure moved with it.
  const progression = await (await admin.get('/api/validations/progression')).json();
  expect(progression.journeesValidees).toBe(1);

  await page.context().close();
});

test('figer demande confirmation et pose le verrou, relire le retire', async ({ browser }) => {
  const page = await pageAdmin(browser, admin);
  await page.goto('/journee');

  const dialogue = await ouvrirRelecture(page);
  await choisir(dialogue, 'Figée');
  await dialogue.getByRole('button', { name: "Enregistrer l'état" }).click();
  // Declined: nothing is frozen.
  const confirmation = page.getByRole('dialog', { name: 'Figer cette journée ?' });
  await expect(confirmation).toContainText('Le calcul ne touchera plus');
  await confirmation.getByRole('button', { name: 'Annuler' }).click();
  await expect(confirmation).toBeHidden();
  expect(await (await admin.get('/api/verrouillages')).json()).toEqual([]);

  await dialogue.getByRole('button', { name: "Enregistrer l'état" }).click();
  await page
    .getByRole('dialog', { name: 'Figer cette journée ?' })
    .getByRole('button', { name: 'Figer la journée' })
    .click();
  await expect(page.locator('.relecture-barre')).toContainText('Figée');

  const verrous = (await (await admin.get('/api/verrouillages')).json()) as { type: string }[];
  expect(verrous.map((verrou) => verrou.type)).toEqual(['JOUR']);

  const relue = await ouvrirRelecture(page);
  await enregistrer(relue, 'Relue');
  await expect(page.locator('.relecture-barre')).toContainText('Relue le');
  expect(await (await admin.get('/api/verrouillages')).json()).toEqual([]);

  await page.context().close();
});

test('la progression se lit sur le sélecteur de jour et sur le solveur', async ({ browser }) => {
  const page = await pageAdmin(browser, admin);
  await page.goto('/journee');
  const dialogue = await ouvrirRelecture(page);
  await enregistrer(dialogue, 'Relue');
  await expect(page.locator('.relecture-barre')).toContainText('Relue le');

  // The day selector marks the day read, where the month calendar used to.
  await page.locator('.mini-mois-bascule').click();
  await expect(page.locator('.mini-mois-courant')).toContainText('task_alt');

  await page.goto('/solveur');
  await expect(page.locator('.relecture-banniere')).toContainText('relues ou figées');

  await page.context().close();
});

test('un seul compteur dit ce qui reste à relire, et mène aux problèmes', async ({ browser }) => {
  const page = await pageAdmin(browser, admin);
  await page.goto('/journee?vue=rail');

  const compteur = page.locator('.relecture-barre').getByRole('link', { name: /à relire/ });
  await compteur.click();
  // « Problèmes » is the Diagnostic's default tab, which the URL does not spell out.
  await expect(page).toHaveURL(/\/diagnostic(\?onglet=problemes)?$/);

  await page.context().close();
});
