// The links from a symptom to the screen that fixes it (issue #489), driven
// through the browser. A unit test can render an `href`; what it cannot see is
// the other end: that landing on a référentiel with `?edit=<id>` really opens
// that fiche, once, and that the address then forgets the instruction — a
// refresh shows the list, not the dialog again.

import { APIRequestContext, expect, test, type Page } from '@playwright/test';
import { contexteAdmin, pageAdmin, SEED, seedPlanning, typologieId } from './support';
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

/** The fiche opened by a deep link, once the page has landed. */
async function ficheOuverte(page: Page, url: string, titre: RegExp): Promise<void> {
  await page.goto(url, { waitUntil: 'domcontentloaded' });
  const dialog = page.getByRole('dialog');
  await expect(dialog).toBeVisible();
  // The title is the first heading; the form's sections carry their own.
  await expect(dialog.getByRole('heading').first()).toHaveText(titre);
  // Obeyed once: the address no longer carries the instruction.
  await expect(page).not.toHaveURL(/edit=/);
  await dialog
    .getByRole('button', { name: /Annuler|Fermer/ })
    .first()
    .click();
  await expect(dialog).toBeHidden();
  // And a refresh shows the list, not the dialog a second time.
  await page.reload({ waitUntil: 'domcontentloaded' });
  await expect(page.locator('#contenu table')).toBeVisible();
  await expect(page.getByRole('dialog')).toHaveCount(0);
}

test('« ?edit= » opens the named animateur’s form, once only', async ({ browser }) => {
  const page = await pageAdmin(browser, admin);
  await ficheOuverte(page, `/animateurs?edit=${SEED.demandeur}`, /Modifier l'animateur Alice E2E/);
  await page.context().close();
});

test('« ?edit= » opens the named stand’s form', async ({ browser }) => {
  const page = await pageAdmin(browser, admin);
  await ficheOuverte(page, `/stands?edit=${SEED.standDemandeur}`, /Modifier le stand Stand E2E un/);
  await page.context().close();
});

test('« ?edit= » ouvre la fiche du créneau nommé', async ({ browser }) => {
  const page = await pageAdmin(browser, admin);
  await ficheOuverte(
    page,
    `/creneaux?edit=${SEED.creneauId}`,
    new RegExp(`Modifier le créneau du ${SEED.jour}`),
  );
  await page.context().close();
});

test("un id inconnu n'ouvre rien et laisse la liste", async ({ browser }) => {
  const page = await pageAdmin(browser, admin);
  await page.goto('/stands?edit=PERSONNE', { waitUntil: 'domcontentloaded' });
  await expect(page.locator('#contenu table')).toBeVisible();
  await expect(page.getByRole('dialog')).toHaveCount(0);
  await expect(page).not.toHaveURL(/edit=/);
  await page.context().close();
});

/**
 * The typologie filter the staffing bottleneck and the scarce competences
 * land on: named in the address, shown as a chip, dropped with its cross. The
 * seeded animateurs hold no appreciation, so the filtered list is empty — and
 * says so, rather than showing everyone.
 */
test('« ?typologie= » filtre les animateurs sur la typologie nommée, et la puce le retire', async ({
  browser,
}) => {
  // Named by its id, as the screens that link here write it: the code is for files.
  const strategie = await typologieId(admin, 'STRATEGIE');
  const page = await pageAdmin(browser, admin);
  await page.goto(`/animateurs?typologie=${strategie}`, { waitUntil: 'domcontentloaded' });

  const puce = page.locator('.animateurs-typologie-filtre');
  await expect(puce).toContainText('Typologie :');
  await expect(page.locator('#contenu')).toContainText('Aucune ligne ne correspond au filtre.');

  await puce.getByRole('button', { name: 'Retirer le filtre par typologie' }).click();
  await expect(puce).toHaveCount(0);
  await expect(page).not.toHaveURL(/typologie=/);
  await expect(page.locator('table tbody tr').filter({ hasText: 'E2E-A' })).toHaveCount(1);
  await page.context().close();
});

/** The bench, reached from a break nobody can relay on the Journée page, is the same bench as ever. */
test('le banc de touche répond à un créneau et un stand nommés dans l’adresse', async ({
  browser,
}) => {
  const page = await pageAdmin(browser, admin);
  await page.goto(
    `/diagnostic?onglet=banc&creneau=${SEED.creneauId}&stand=${SEED.standDemandeur}`,
    { waitUntil: 'domcontentloaded' },
  );
  await expect(page.locator('#contenu')).toContainText('Banc de touche');
  await expect(page.locator('#contenu')).toContainText('Siège évalué');
  // The seat is named by its stand's name, not its id.
  await expect(page.locator('#contenu')).toContainText('Stand E2E un');
  await page.context().close();
});
