// The Contraintes screen, where an organiser tunes what the solver optimises
// and where the rules of public order are guarded.
//
// Two things are only true in a browser, so only a browser can prove them.
// The confirmation asked before switching off a legal rule is one: the guard
// lives in a dialog service, and a unit test on that service proves the
// service, not that the screen calls it. The dosage is the other: the value
// travels field → HTTP → database → reload, and every link of that chain is
// somewhere the number could quietly fail to stick.
//
// Every rule carries the same control — one number field, protected rules
// included — so these tests also stand guard over that uniformity: a second
// shape of control coming back is what they fail on.

import { APIRequestContext, Page, expect, test } from '@playwright/test';
import { contexteAdmin, pageAdmin, seedPlanning } from './support';

/** MEDIUM, « Qualité d'organisation » — the family meant to be dosed. */
const DOSABLE = 'equilibrerCharge';

/** HARD, « Légal (mineurs) » — switching it off needs the confirmation. */
const PROTEGEE = 'travailDeNuitInterditPourMineur';

/** SOFT, « Préférences » — neither protected nor part of that family. */
const NON_DOSABLE = 'preserverBufferPolyvalents';

let admin: APIRequestContext;

test.beforeAll(async ({ playwright }, testInfo) => {
  admin = await contexteAdmin(playwright, testInfo.project.use.baseURL as string);
  await seedPlanning(admin);
});

/** Puts the rule back the way the suite found it, whatever the test did. */
async function reinitialiser(): Promise<void> {
  await admin.put(`/api/constraints/${DOSABLE}/poids`, { data: { poids: null } });
  await admin.put(`/api/constraints/${NON_DOSABLE}/poids`, { data: { poids: null } });
  await admin.put(`/api/constraints/${PROTEGEE}`, { data: { actif: true } });
  await admin.put(`/api/constraints/${DOSABLE}`, { data: { actif: true } });
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

/** The card of one constraint, located by the technical name it displays. */
function carte(page: Page, nom: string) {
  return page.locator('.constraint-card').filter({ has: page.getByText(nom, { exact: true }) });
}

/** The one weight control of a rule: the same number field for all of them. */
function champPoids(page: Page, nom: string) {
  return carte(page, nom).locator('input[type="number"]');
}

async function ouvrirContraintes(page: Page): Promise<void> {
  await page.goto('/constraints');
  await expect(carte(page, DOSABLE)).toBeVisible();
}

test('désactiver une règle légale demande une confirmation, et annuler ne change rien', async ({
  browser
}) => {
  const page = await pageAdmin(browser, admin);
  await ouvrirContraintes(page);

  await carte(page, PROTEGEE).getByRole('switch').click();

  const dialogue = page.getByRole('dialog');
  await expect(dialogue).toBeVisible();
  // The dialog must name the rule: a generic confirmation does not tell the
  // organiser what they are about to switch off.
  await expect(dialogue).toContainText(PROTEGEE);
  // And restate where the responsibility sits, as the conditions of use do.
  await expect(dialogue).toContainText("l'employeur ou le donneur d'ordre");

  await dialogue.getByRole('button', { name: 'Annuler' }).click();
  await expect(dialogue).toBeHidden();

  // Cancelling must really cancel, on screen as well as server side: a switch
  // showing « désactivée » over a rule that is still active is the same lie,
  // and only the reload used to reveal it.
  await expect(carte(page, PROTEGEE).getByRole('switch')).toBeChecked();
  const apres = await (await admin.get('/api/constraints')).json();
  expect(apres.contraintes.find((c: { name: string }) => c.name === PROTEGEE).actif).toBe(true);

  await page.close();
});

test('confirmer la modale éteint bien la règle, et la réactiver ne redemande rien', async ({
  browser
}) => {
  const page = await pageAdmin(browser, admin);
  await ouvrirContraintes(page);

  await carte(page, PROTEGEE).getByRole('switch').click();
  await page.getByRole('dialog').getByRole('button', { name: 'Désactiver quand même' }).click();
  await expect(page.getByRole('dialog')).toBeHidden();

  await expect
    .poll(async () => {
      const etat = await (await admin.get('/api/constraints')).json();
      return etat.contraintes.find((c: { name: string }) => c.name === PROTEGEE).actif;
    })
    .toBe(false);

  // Switching back on goes the protective way: nothing to confirm.
  await carte(page, PROTEGEE).getByRole('switch').click();
  await expect(page.getByRole('dialog')).toBeHidden();

  await expect
    .poll(async () => {
      const etat = await (await admin.get('/api/constraints')).json();
      return etat.contraintes.find((c: { name: string }) => c.name === PROTEGEE).actif;
    })
    .toBe(true);

  await page.close();
});

test("une règle de confort s'éteint sans confirmation", async ({ browser }) => {
  const page = await pageAdmin(browser, admin);
  await ouvrirContraintes(page);

  await carte(page, DOSABLE).getByRole('switch').click();

  // Twenty-four rules arbitrate one comfort against another: putting them
  // behind a dialog would trivialise the one that matters.
  await expect(page.getByRole('dialog')).toBeHidden();
  await expect
    .poll(async () => {
      const etat = await (await admin.get('/api/constraints')).json();
      return etat.contraintes.find((c: { name: string }) => c.name === DOSABLE).actif;
    })
    .toBe(false);

  await page.close();
});

test("le poids d'une règle dosable survit à un rechargement", async ({ browser }) => {
  const page = await pageAdmin(browser, admin);
  await ouvrirContraintes(page);

  const champ = champPoids(page, DOSABLE);
  await expect(champ).toHaveValue('1');

  // What is checked here is the whole path — field, HTTP, database, back after
  // a reload — every link being somewhere the number could fail to stick.
  await champ.fill('2');
  await champ.blur();

  await expect
    .poll(async () => {
      const etat = await (await admin.get('/api/constraints')).json();
      return etat.contraintes.find((c: { name: string }) => c.name === DOSABLE).poids;
    })
    .toBe(2);

  await page.reload();
  await expect(champPoids(page, DOSABLE)).toHaveValue('2');

  await page.close();
});

test("le poids d'une règle non dosable survit à un rechargement", async ({ browser }) => {
  const page = await pageAdmin(browser, admin);
  await ouvrirContraintes(page);

  // Same control, same path: the rules outside « Qualité d'organisation » are
  // not arbitrated by feel, but they stay weightable exactly the same way.
  const champ = champPoids(page, NON_DOSABLE);
  await champ.fill('12');
  await champ.blur();

  await expect
    .poll(async () => {
      const etat = await (await admin.get('/api/constraints')).json();
      return etat.contraintes.find((c: { name: string }) => c.name === NON_DOSABLE).poids;
    })
    .toBe(12);

  await page.reload();
  await expect(champPoids(page, NON_DOSABLE)).toHaveValue('12');

  await page.close();
});

test('une règle protégée porte son pictogramme et le même champ de poids que les autres', async ({
  browser
}) => {
  const page = await pageAdmin(browser, admin);
  await ouvrirContraintes(page);

  // The pictogram warns before the click; the dialog only comes after. It is
  // what singles a rule of public order out — not a different way of weighting
  // it.
  await expect(carte(page, PROTEGEE).locator('.constraint-protegee-icon')).toBeVisible();
  await expect(carte(page, DOSABLE).locator('.constraint-protegee-icon')).toHaveCount(0);

  // One control for every rule: a number field, and no slider left anywhere.
  await expect(champPoids(page, PROTEGEE)).toBeVisible();
  await expect(champPoids(page, DOSABLE)).toBeVisible();
  await expect(champPoids(page, NON_DOSABLE)).toBeVisible();
  await expect(page.locator('.constraint-card input[type="range"]')).toHaveCount(0);

  await page.close();
});

test('le poids hors bornes est ramené dans la plage acceptée', async ({ browser }) => {
  const page = await pageAdmin(browser, admin);
  await ouvrirContraintes(page);

  // Zero is refused server-side: switching a rule off goes through the switch,
  // and for a legal rule through the confirmation. The field must never send
  // it, and must never keep showing it either.
  const champ = champPoids(page, DOSABLE);
  await champ.fill('5');
  await champ.blur();
  await expect
    .poll(async () => {
      const etat = await (await admin.get('/api/constraints')).json();
      return etat.contraintes.find((c: { name: string }) => c.name === DOSABLE).poids;
    })
    .toBe(5);

  await champ.fill('0');
  await champ.blur();

  await expect(champ).toHaveValue('1');
  await expect
    .poll(async () => {
      const etat = await (await admin.get('/api/constraints')).json();
      return etat.contraintes.find((c: { name: string }) => c.name === DOSABLE).poids;
    })
    .toBe(1);

  await page.close();
});
