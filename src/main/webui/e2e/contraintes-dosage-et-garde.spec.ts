// « Règles du planning », where an organiser tunes what the solver optimises
// and where the rules of public order are guarded.
//
// Two things are only true in a browser, so only a browser can prove them.
// The confirmation asked before switching off a legal rule is one: the guard
// lives in a dialog service, and a unit test on that service proves the
// service, not that the screen calls it. The importance is the other: the
// value travels position → « Enregistrer » → HTTP → database → reload, and
// every link of that chain is somewhere the number could quietly fail to
// stick. Nothing is written before « Enregistrer » (issue #720): a switch or a
// position changed and not saved leaves the server as it was.

import { APIRequestContext, Page, expect, test } from '@playwright/test';
import { contexteAdmin, pageAdmin, seedPlanning } from './support';
import { repartirDeLaReference } from './reference';

/** MEDIUM, « Qualité d'organisation » — the family meant to be dosed. */
const DOSABLE = { name: 'equilibrerCharge', libelle: 'Équilibre de la charge' };

/** HARD, « Légal (mineurs) » — switching it off needs the confirmation. */
const PROTEGEE = {
  name: 'travailDeNuitInterditPourMineur',
  libelle: 'Pas de travail de nuit pour les mineurs',
};

/** SOFT, « Préférences » — neither protected nor part of that family. */
const NON_DOSABLE = { name: 'preserverBufferPolyvalents', libelle: 'Réserve de polyvalents' };

let admin: APIRequestContext;

test.beforeAll(async ({ playwright }, testInfo) => {
  admin = await contexteAdmin(playwright, testInfo.project.use.baseURL as string);
  await repartirDeLaReference(admin);
  await seedPlanning(admin);
});

/** Puts the rules back the way the suite found them, whatever the test did. */
async function reinitialiser(): Promise<void> {
  await admin.put(`/api/constraints/${DOSABLE.name}/poids`, { data: { poids: null } });
  await admin.put(`/api/constraints/${NON_DOSABLE.name}/poids`, { data: { poids: null } });
  await admin.put(`/api/constraints/${PROTEGEE.name}`, { data: { actif: true } });
  await admin.put(`/api/constraints/${DOSABLE.name}`, { data: { actif: true } });
}

test.beforeEach(async () => {
  await reinitialiser();
});

test.afterAll(async () => {
  // Reset BEFORE disposing the context: the other way round would run the
  // cleanup against an already-closed context.
  await reinitialiser();
  await admin.dispose();
});

/** The row of one rule, located by its short label — the technical name is never shown. */
function ligne(page: Page, libelle: string) {
  return page
    .locator('table.regles-table tbody tr')
    .filter({ has: page.getByRole('rowheader', { name: libelle, exact: true }) });
}

async function ouvrir(page: Page, onglet: 'legal' | 'qualite'): Promise<void> {
  await page.goto(`/regles?onglet=${onglet}`);
  await expect(page.locator('table.regles-table tbody tr').first()).toBeVisible();
}

async function enregistrer(page: Page): Promise<void> {
  await page
    .locator('.regles-save-bar')
    .getByRole('button', { name: /^Enregistrer/ })
    .click();
}

async function etat(name: string): Promise<{ actif: boolean; poids: number }> {
  const catalogue = await (await admin.get('/api/constraints')).json();
  return catalogue.contraintes.find((c: { name: string }) => c.name === name);
}

test('désactiver une règle légale demande une confirmation, et annuler ne change rien', async ({
  browser,
}) => {
  const page = await pageAdmin(browser, admin);
  await ouvrir(page, 'legal');

  await ligne(page, PROTEGEE.libelle).getByRole('switch').click();

  const dialogue = page.getByRole('dialog');
  await expect(dialogue).toBeVisible();
  // The dialog must name the rule, in the organiser's words: a generic
  // confirmation does not tell them what they are about to switch off.
  await expect(dialogue).toContainText(PROTEGEE.libelle);
  await expect(dialogue).not.toContainText(PROTEGEE.name);
  // And restate where the responsibility sits, as the conditions of use do.
  await expect(dialogue).toContainText("l'employeur ou le donneur d'ordre");

  await dialogue.getByRole('button', { name: 'Annuler' }).click();
  await expect(dialogue).toBeHidden();

  // Cancelling must really cancel, on screen as well as server side.
  await expect(ligne(page, PROTEGEE.libelle).getByRole('switch')).toBeChecked();
  expect((await etat(PROTEGEE.name)).actif).toBe(true);

  await page.close();
});

test("confirmer la modale n'éteint la règle qu'à l'enregistrement, et la réactiver ne redemande rien", async ({
  browser,
}) => {
  const page = await pageAdmin(browser, admin);
  await ouvrir(page, 'legal');

  await ligne(page, PROTEGEE.libelle).getByRole('switch').click();
  await page.getByRole('dialog').getByRole('button', { name: 'Désactiver quand même' }).click();
  await expect(page.getByRole('dialog')).toBeHidden();

  // Nothing written yet: the switch waits for « Enregistrer ».
  expect((await etat(PROTEGEE.name)).actif).toBe(true);
  await enregistrer(page);
  await expect.poll(async () => (await etat(PROTEGEE.name)).actif).toBe(false);

  // Switching back on goes the protective way: nothing to confirm.
  await ligne(page, PROTEGEE.libelle).getByRole('switch').click();
  await expect(page.getByRole('dialog')).toBeHidden();
  await enregistrer(page);
  await expect.poll(async () => (await etat(PROTEGEE.name)).actif).toBe(true);

  await page.close();
});

test("une règle de confort s'éteint sans confirmation", async ({ browser }) => {
  const page = await pageAdmin(browser, admin);
  await ouvrir(page, 'qualite');

  await ligne(page, DOSABLE.libelle).getByRole('switch').click();

  // Rules that arbitrate one comfort against another: putting them behind a
  // dialog would trivialise the one that matters.
  await expect(page.getByRole('dialog')).toBeHidden();
  await enregistrer(page);
  await expect.poll(async () => (await etat(DOSABLE.name)).actif).toBe(false);

  await page.close();
});

test("l'importance d'une règle s'enregistre en poids et survit à un rechargement", async ({
  browser,
}) => {
  const page = await pageAdmin(browser, admin);
  await ouvrir(page, 'qualite');

  const importance = ligne(page, DOSABLE.libelle).locator('mat-button-toggle-group');
  // The default of a quality rule is the middle position (ADR 0057).
  await expect(importance.getByRole('radio', { name: 'Normale' })).toBeChecked();

  await importance.getByRole('radio', { name: 'Forte' }).click();
  // The position alone writes nothing.
  expect((await etat(DOSABLE.name)).poids).toBe(5);

  await enregistrer(page);
  await expect.poll(async () => (await etat(DOSABLE.name)).poids).toBe(25);

  await page.reload();
  await expect(ligne(page, DOSABLE.libelle).getByRole('radio', { name: 'Forte' })).toBeChecked();

  await page.close();
});

test('le poids exact se règle dans le panneau, et reste ramené dans la plage acceptée', async ({
  browser,
}) => {
  const page = await pageAdmin(browser, admin);
  await page.goto(`/regles?regle=${NON_DOSABLE.name}`);

  // The address names the rule: its panel opens on its own tab.
  const panneau = page.locator('.regles-panneau');
  await expect(panneau.getByRole('heading', { name: NON_DOSABLE.libelle })).toBeVisible();

  const champ = panneau.locator('input[type="number"]');
  await champ.fill('12');
  await champ.blur();
  await expect(ligne(page, NON_DOSABLE.libelle).locator('.regles-personnalise')).toContainText(
    '12',
  );
  await enregistrer(page);
  await expect.poll(async () => (await etat(NON_DOSABLE.name)).poids).toBe(12);

  // Zero is refused server-side: switching a rule off goes through the switch.
  // The field must never send it, and must never keep showing it either.
  await champ.fill('0');
  await champ.blur();
  await expect(champ).toHaveValue('1');
  await enregistrer(page);
  await expect.poll(async () => (await etat(NON_DOSABLE.name)).poids).toBe(1);

  await page.close();
});

test('une règle protégée porte son pictogramme, et aucune règle dure ne se dose', async ({
  browser,
}) => {
  const page = await pageAdmin(browser, admin);
  await ouvrir(page, 'legal');

  // The pictogram warns before the click; the dialog only comes after.
  await expect(ligne(page, PROTEGEE.libelle).locator('.constraint-protegee-icon')).toBeVisible();
  // A hard breach is counted, never dosed: the legal table has no importance.
  await expect(page.locator('table.regles-table mat-button-toggle-group')).toHaveCount(0);

  await ouvrir(page, 'qualite');
  await expect(ligne(page, DOSABLE.libelle).locator('.constraint-protegee-icon')).toHaveCount(0);
  await expect(ligne(page, DOSABLE.libelle).locator('mat-button-toggle-group')).toBeVisible();

  await page.close();
});
