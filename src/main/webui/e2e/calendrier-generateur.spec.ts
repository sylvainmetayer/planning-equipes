// L'écran Calendrier et son générateur unique (ADR 0126) vus du navigateur :
// un gabarit posé sur une date et appliqué écrit le créneau, le menu offre les
// trois sources, et retirer la date du calendrier ne supprime pas le créneau.

import { APIRequestContext, expect, test } from '@playwright/test';
import { contexteAdmin, dialogueOuvert, pageAdmin, shiftDate } from './support';
import { repartirDeLaReference } from './reference';

let admin: APIRequestContext;

/** Un jour que les autres suites n'utilisent pas. */
const JOUR = shiftDate('2027-09-14');

test.beforeAll(async ({ playwright }, testInfo) => {
  admin = await contexteAdmin(playwright, testInfo.project.use.baseURL as string);
  await repartirDeLaReference(admin);
});

test.afterAll(async () => {
  const etat = (await (await admin.get('/api/generateur-creneaux')).json()) as {
    gabarits: { id: number }[];
  };
  await admin.put('/api/generateur-creneaux/calendrier', { data: [] });
  for (const gabarit of etat.gabarits) {
    await admin.delete(`/api/generateur-creneaux/gabarits/${gabarit.id}`);
  }
  const creneaux = (await (await admin.get('/api/creneaux')).json()) as {
    id: number;
    date: string;
  }[];
  for (const creneau of creneaux.filter((candidat) => candidat.date === JOUR)) {
    await admin.delete(`/api/creneaux/${creneau.id}`).catch(() => undefined);
  }
});

test('un gabarit posé sur une date s’applique, et retirer la date garde le créneau', async ({
  browser,
}) => {
  const page = await pageAdmin(browser, admin);
  await page.goto('/creneaux');

  await page.getByRole('button', { name: 'Nouveau gabarit' }).click();
  let dialog = await dialogueOuvert(page);
  await dialog.getByLabel('Nom').fill('E2E Gabarit');
  await dialog.getByLabel('Vacations de la journée').fill('09:00-12:00');
  await dialog.getByRole('button', { name: 'Créer le gabarit' }).click();
  await expect(dialog).toBeHidden();

  const ajout = page.locator('.gabarits-ajout');
  await ajout.getByLabel('Du', { exact: true }).fill(JOUR);
  await ajout.getByLabel('Au', { exact: true }).fill(JOUR);
  await ajout.locator('mat-form-field').filter({ hasText: 'Gabarit' }).click();
  await page.getByRole('option', { name: 'E2E Gabarit' }).click();
  await page.getByRole('button', { name: 'Affecter 1 date(s)' }).click();
  await expect(page.locator('.gabarits-table tbody tr')).toHaveCount(1);

  // Le générateur : les trois sources et la reconnaissance, dans un seul menu.
  await page.getByRole('button', { name: 'Générer des créneaux' }).click();
  for (const source of [
    'Depuis les gabarits',
    'Depuis les horaires des stands',
    'Depuis un motif récurrent',
    'Reconnaître les gabarits depuis les créneaux',
  ]) {
    await expect(page.getByRole('menuitem', { name: source })).toBeVisible();
  }
  await page.getByRole('menuitem', { name: 'Depuis les gabarits' }).click();
  dialog = await dialogueOuvert(page);
  await expect(dialog).toContainText('créneau(x) créé(s)');
  await dialog.getByRole('button', { name: 'Appliquer' }).click();
  await expect(dialog).toBeHidden();
  await expect(page.locator('.creneaux-table tbody tr')).toHaveCount(1);

  // Le retrait : la date n'est plus gouvernée, le créneau reste.
  await page.getByRole('button', { name: 'Retirer cette date du calendrier' }).click();
  await expect(page.locator('.gabarits-table tbody tr')).toHaveCount(0);
  await expect(page.locator('.creneaux-table tbody tr')).toHaveCount(1);

  await page.context().close();
});
