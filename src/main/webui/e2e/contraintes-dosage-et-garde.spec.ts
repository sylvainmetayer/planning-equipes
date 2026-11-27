// « Règles du planning », where an organiser tunes what the solver optimises
// and where the rules of public order are guarded: three blocks — the legal
// obligations, read only and linked to Paramètres; the organisation's policy,
// each rule with a level; the rest, folded.
//
// Two things are only true in a browser, so only a browser can prove them.
// The confirmation asked before switching off a protected rule is one: the
// guard lives in a dialog service, and a unit test on that service proves the
// service, not that the screen calls it. The level is the other: the value
// travels choice → « Enregistrer » → HTTP → database → reload, and every link
// of that chain is somewhere it could quietly fail to stick. Nothing is
// written before « Enregistrer » (issue #720): a switch or a level changed and
// not saved leaves the server as it was.

import { APIRequestContext, Page, expect, test } from '@playwright/test';
import { contexteAdmin, pageAdmin, seedPlanning } from './support';
import { repartirDeLaReference } from './reference';

/** MEDIUM, « Qualité d'organisation » — the family meant to be dosed. */
const DOSABLE = { name: 'equilibrerCharge', libelle: 'Équilibre de la charge' };

/** « Organisation (repas) » — protected, and a level the edition chooses: switching it off needs the confirmation. */
const PROTEGEE = { name: 'coupureRepasObligatoire', libelle: '' };

/** HARD, « Légal (mineurs) » — read only on this screen. */
const LEGALE = {
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
  // The label the screen shows is the catalogue's, not a copy of it here.
  const catalogue = await (await admin.get('/api/constraints')).json();
  PROTEGEE.libelle = catalogue.contraintes.find(
    (c: { name: string }) => c.name === PROTEGEE.name,
  ).libelleCourt;
});

/** Puts the rules back the way the suite found them, whatever the test did. */
async function reinitialiser(): Promise<void> {
  await admin.put(`/api/constraints/${DOSABLE.name}/niveau`, {
    data: { niveau: 'PREFERENCE_NORMALE' },
  });
  await admin.put(`/api/constraints/${DOSABLE.name}/poids`, { data: { poids: null } });
  await admin.put(`/api/constraints/${NON_DOSABLE.name}/poids`, { data: { poids: null } });
  await admin.put(`/api/constraints/${PROTEGEE.name}`, { data: { actif: true } });
  await admin.put(`/api/constraints/${PROTEGEE.name}/niveau`, {
    data: { niveau: 'PREFERENCE_NORMALE' },
  });
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

async function ouvrir(page: Page): Promise<void> {
  await page.goto('/regles?onglet=regles');
  await expect(page.locator('table.regles-table tbody tr').first()).toBeVisible();
}

async function enregistrer(page: Page): Promise<void> {
  await page
    .locator('.regles-save-bar')
    .getByRole('button', { name: /^Enregistrer/ })
    .click();
}

async function etat(
  name: string,
): Promise<{ actif: boolean; poids: number; niveauChoisi: string | null }> {
  const catalogue = await (await admin.get('/api/constraints')).json();
  return catalogue.contraintes.find((c: { name: string }) => c.name === name);
}

test('désactiver une règle protégée demande une confirmation, et annuler ne change rien', async ({
  browser,
}) => {
  const page = await pageAdmin(browser, admin);
  await ouvrir(page);

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
  await ouvrir(page);

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
  await ouvrir(page);

  await ligne(page, DOSABLE.libelle).getByRole('switch').click();

  // Rules that arbitrate one comfort against another: putting them behind a
  // dialog would trivialise the one that matters.
  await expect(page.getByRole('dialog')).toBeHidden();
  await enregistrer(page);
  await expect.poll(async () => (await etat(DOSABLE.name)).actif).toBe(false);

  await page.close();
});

test("le niveau d'une règle s'enregistre en poids et survit à un rechargement", async ({
  browser,
}) => {
  const page = await pageAdmin(browser, admin);
  await ouvrir(page);

  const niveau = ligne(page, DOSABLE.libelle).locator('mat-button-toggle-group');
  // The default of an organisation rule is the middle position (ADR 0057).
  await expect(niveau.getByRole('radio', { name: 'Normale' })).toBeChecked();

  await niveau.getByRole('radio', { name: 'Forte' }).click();
  // The choice alone writes nothing.
  expect((await etat(DOSABLE.name)).poids).toBe(5);

  await enregistrer(page);
  await expect.poll(async () => (await etat(DOSABLE.name)).poids).toBe(25);

  await page.reload();
  await expect(ligne(page, DOSABLE.libelle).getByRole('radio', { name: 'Forte' })).toBeChecked();

  await page.close();
});

test("« Obligatoire » se lit dans le catalogue, et le panneau n'offre alors plus de poids", async ({
  browser,
}) => {
  const page = await pageAdmin(browser, admin);
  await page.goto(`/regles?regle=${DOSABLE.name}`);
  const panneau = page.locator('.regles-panneau');
  await expect(panneau.locator('input[type="number"]')).toBeVisible();

  await ligne(page, DOSABLE.libelle).getByRole('radio', { name: 'Obligatoire' }).click();
  expect((await etat(DOSABLE.name)).niveauChoisi).toBe('PREFERENCE_NORMALE');
  // A mandatory rule counts, it is not weighed: no field that would do nothing.
  await expect(panneau.locator('input[type="number"]')).toHaveCount(0);

  await enregistrer(page);
  await expect.poll(async () => (await etat(DOSABLE.name)).niveauChoisi).toBe('OBLIGATOIRE');

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
  // The toast comes after the write and before the catalogue is read again: the field is disabled
  // from there until the save is over. Typing into it earlier is locked mid-edit, and the stored 12
  // is written back with no `change` ever fired.
  await expect(page.getByText(/Règles enregistrées/).first()).toBeVisible();
  await expect.poll(async () => (await etat(NON_DOSABLE.name)).poids).toBe(12);
  await expect(champ).toBeEnabled();
  await expect(champ).toHaveValue('12');

  // Zero is refused server-side: switching a rule off goes through the switch.
  // The field must never send it, and must never keep showing it either.
  await champ.fill('0');
  await champ.blur();
  await expect(champ).toHaveValue('1');
  await enregistrer(page);
  await expect.poll(async () => (await etat(NON_DOSABLE.name)).poids).toBe(1);

  await page.close();
});

test("une obligation légale est en lecture seule et mène à Paramètres, une règle d'organisation porte son niveau", async ({
  browser,
}) => {
  const page = await pageAdmin(browser, admin);
  await ouvrir(page);

  const legal = page.locator('section[aria-labelledby="regles-bloc-legal"]');
  const ligneLegale = legal
    .locator('tbody tr')
    .filter({ has: page.getByRole('rowheader', { name: LEGALE.libelle, exact: true }) });
  // Read only: no switch, no level, no threshold field — one place edits them.
  await expect(ligneLegale).toBeVisible();
  await expect(ligneLegale.getByRole('switch')).toHaveCount(0);
  await expect(legal.locator('mat-button-toggle-group, input')).toHaveCount(0);
  await ligneLegale.getByRole('link', { name: /Régler dans Paramètres/ }).click();
  await expect(page).toHaveURL(/\/parametres\?onglet=legal/);

  await ouvrir(page);
  // The pictogram warns before the click; the dialog only comes after.
  await expect(ligne(page, PROTEGEE.libelle).locator('.constraint-protegee-icon')).toBeVisible();
  await expect(ligne(page, DOSABLE.libelle).locator('.constraint-protegee-icon')).toHaveCount(0);
  await expect(ligne(page, DOSABLE.libelle).getByRole('radio')).toHaveCount(4);

  await page.close();
});
