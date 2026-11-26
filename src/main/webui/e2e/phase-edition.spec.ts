// The phase of the edition (ADR 0115) from the home page: each change asks
// first, saying what the new phase freezes or opens, and the structure of a
// published edition is unblocked and frozen again with a banner while it is open.

import { APIRequestContext, expect, test } from '@playwright/test';
import { contexteAdmin, pageAdmin } from './support';
import { repartirDeLaReference } from './reference';

let admin: APIRequestContext;

test.beforeAll(async ({ playwright }, testInfo) => {
  admin = await contexteAdmin(playwright, testInfo.project.use.baseURL as string);
  await repartirDeLaReference(admin);
});

test.afterAll(async () => {
  // The other specs edit stands and timeslots: leave the edition in preparation.
  await admin.put('/api/editions/courant/phase', { data: { phase: 'PREPARATION' } });
  await admin.dispose();
});

test("la phase se change depuis l'accueil et la structure se débloque sous bandeau", async ({
  browser,
}) => {
  const page = await pageAdmin(browser, admin);
  await page.goto('/');
  const ligne = page.locator('li[data-ligne="phase"]');
  const dialogue = page.getByRole('dialog');
  const bandeau = page.locator('[data-bandeau="structure-debloquee"]');

  await expect(ligne).toContainText("Phase de l'édition : Préparation");

  await ligne.getByRole('button', { name: 'Passer en Calcul' }).click();
  await expect(dialogue).toContainText('sont figés');
  await dialogue.getByRole('button', { name: 'Passer en Calcul' }).click();
  await expect(ligne).toContainText("Phase de l'édition : Calcul");
  await expect(ligne).toContainText('Figé :');

  await ligne.getByRole('button', { name: 'Passer en Publiée' }).click();
  await dialogue.getByRole('button', { name: 'Passer en Publiée' }).click();
  await expect(ligne).toContainText("Phase de l'édition : Publiée");
  await expect(bandeau).toBeHidden();

  await ligne.getByRole('button', { name: 'Débloquer la structure' }).click();
  await dialogue.getByRole('button', { name: 'Débloquer la structure' }).click();
  await expect(bandeau).toBeVisible();

  await bandeau.getByRole('button', { name: 'Refiger' }).click();
  await dialogue.getByRole('button', { name: 'Refiger' }).click();
  await expect(bandeau).toBeHidden();
  await page.context().close();
});
