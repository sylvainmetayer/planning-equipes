// Two sessions on the same fiche (issue #362).
//
// The server refuses a write whose `modifieLe` is older than the row's, and
// the screen turns that refusal into a choice: reload — nothing of mine is
// written, the form closes over the other session's version — or overwrite.
// What only a browser can check: that the choice is actually offered on the
// screen, that "Recharger" really writes nothing, and that "Écraser quand
// même" really wins. The per-entity server rule is covered by
// ConcurrentModificationGuardTest, far more cheaply than here.

import { APIRequestContext, Page, expect, test } from '@playwright/test';
import { contexteAdmin, pageAdmin, seedReferentielSolveur } from './support';
import { repartirDeLaReference } from './reference';

const STAND_ID = 'SOLV-CC1';

let admin: APIRequestContext;

test.beforeAll(async ({ playwright }, testInfo) => {
  admin = await contexteAdmin(playwright, testInfo.project.use.baseURL as string);
  await repartirDeLaReference(admin);
});

test.afterAll(async () => {
  await admin.dispose();
});

/** The other session: renames the stand through the API, with no precondition. */
async function autreSessionRenomme(nom: string): Promise<void> {
  const lecture = await admin.get('/api/stands');
  const stands = (await lecture.json()) as { id: string; nom: string }[];
  const stand = stands.find((candidat) => candidat.id === STAND_ID);
  expect(stand, `${STAND_ID} should be seeded`).toBeDefined();
  const ecriture = await admin.put(`/api/stands/${STAND_ID}`, { data: { ...stand, nom, modifieLe: null } });
  expect(ecriture.status(), await ecriture.text()).toBe(200);
}

async function nomEnBase(): Promise<string> {
  const lecture = await admin.get('/api/stands');
  const stands = (await lecture.json()) as { id: string; nom: string }[];
  return stands.find((candidat) => candidat.id === STAND_ID)?.nom ?? '';
}

/** Opens the stand's form, then lets the other session write before this one saves. */
async function ouvrirPuisSubirUneEcritureAilleurs(page: Page): Promise<void> {
  await page.goto('/stands');
  await page.getByLabel('Filtrer').fill(STAND_ID);
  const ligne = page.getByRole('row', { name: new RegExp(STAND_ID) });
  await expect(ligne).toBeVisible();
  await ligne.getByRole('button', { name: 'Modifier' }).click();
  const formulaire = page.getByRole('dialog').filter({ hasText: `Modifier le stand ${STAND_ID}` });
  await expect(formulaire).toBeVisible();

  await autreSessionRenomme('Renommé ailleurs');

  await formulaire.getByLabel('Nom', { exact: true }).fill('Renommé ici');
  await formulaire.getByRole('button', { name: 'Modifier le stand' }).click();
}

test.describe('modification concurrente', () => {
  test.beforeEach(async () => {
    await seedReferentielSolveur(admin, [], [{ id: STAND_ID, nom: 'Stand concurrent', effectif: 1 }], []);
  });

  test("« Recharger » n'écrit rien et referme le formulaire sur la version de l'autre session", async ({
    browser
  }) => {
    const page = await pageAdmin(browser, admin);
    try {
      await ouvrirPuisSubirUneEcritureAilleurs(page);

      const conflit = page.getByRole('dialog').filter({ hasText: 'Modifiée entre-temps' });
      await expect(conflit).toBeVisible();
      // The message no longer dates the conflict in the server's zone: it
      // says who, and the browser adds when (review of #362).
      await expect(conflit).toContainText('par une autre session');
      await expect(conflit).toContainText('dernière écriture le');
      await conflit.getByRole('button', { name: 'Recharger' }).click();

      await expect(page.getByRole('dialog')).toHaveCount(0);
      await expect(page.getByRole('row', { name: new RegExp(STAND_ID) })).toContainText('Renommé ailleurs');
      expect(await nomEnBase()).toBe('Renommé ailleurs');
    } finally {
      await page.context().close();
    }
  });

  test("« Écraser quand même » impose la saisie de cet écran", async ({ browser }) => {
    const page = await pageAdmin(browser, admin);
    try {
      await ouvrirPuisSubirUneEcritureAilleurs(page);

      const conflit = page.getByRole('dialog').filter({ hasText: 'Modifiée entre-temps' });
      await expect(conflit).toBeVisible();
      await conflit.getByRole('button', { name: 'Écraser quand même' }).click();

      await expect(page.getByRole('dialog')).toHaveCount(0);
      await expect(page.getByRole('row', { name: new RegExp(STAND_ID) })).toContainText('Renommé ici');
      expect(await nomEnBase()).toBe('Renommé ici');
    } finally {
      await page.context().close();
    }
  });

  test('sans écriture ailleurs, la modification passe sans question', async ({ browser }) => {
    const page = await pageAdmin(browser, admin);
    try {
      await page.goto('/stands');
      await page.getByLabel('Filtrer').fill(STAND_ID);
      await page.getByRole('row', { name: new RegExp(STAND_ID) }).getByRole('button', { name: 'Modifier' }).click();
      const formulaire = page.getByRole('dialog');
      await formulaire.getByLabel('Nom', { exact: true }).fill('Renommé tranquillement');
      await formulaire.getByRole('button', { name: 'Modifier le stand' }).click();

      await expect(page.getByRole('dialog')).toHaveCount(0);
      expect(await nomEnBase()).toBe('Renommé tranquillement');
    } finally {
      await page.context().close();
    }
  });
});
