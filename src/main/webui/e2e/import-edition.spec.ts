// A scenario file carrying an `edition:` section routes its import into that
// edition — and the operator can no longer miss it: the confirmation dialog
// names the target BEFORE anything is written, and once the import is done an
// unmissable dialog offers to switch onto the freshly written edition. This
// spec drives the whole flow through the real UI: file picker, both dialogs,
// the switch, and the fact that the ambient edition was never touched.

import { APIRequestContext, expect, test } from '@playwright/test';
import { contexteAdmin, pageAdmin } from './support';
import { repartirDeLaReference } from './reference';

/**
 * Named, never numbered: an edition the section does not designate by an
 * existing id is created under its name, with an id the application draws.
 */
const EDITION_IMPORT = { nom: 'Édition import e2e' };

/**
 * The ids of the file are references local to it; the created rows get ids
 * of their own. The stand carries a code — the readable key that survives the
 * import — which is what the assertions find it by.
 */
const SCENARIO = `edition:
  nom: ${EDITION_IMPORT.nom}

festival:
  dateDebut: 2026-07-15

creneaux:
  - id: E2EIMP-J1
    jour: 1
    date: 2026-07-15
    heureDebut: "10:00"
    heureFin: "12:00"

stands:
  - id: E2EIMP-S1
    code: E2EIMP-S1
    nom: Stand import edition
    typologiesProposees:
      - STRATEGIE
    effectifMin: 1
    effectifMax: 1
    reserveMajeurs: false

animateurs:
  - id: E2EIMP-A
    prenom: Edna
    nom: Import
    dateNaissance: 1990-01-01
    manager: false
    competences:
      STRATEGIE: DEBUTANT
`;

let admin: APIRequestContext;

/** The id the application gave the edition of that name, `undefined` when none exists. */
async function editionImportee(): Promise<string | undefined> {
  const editions = (await (await admin.get('/api/editions')).json()) as {
    id: string;
    nom: string;
  }[];
  return editions.find((edition) => edition.nom === EDITION_IMPORT.nom)?.id;
}

async function supprimerEdition(): Promise<void> {
  const id = await editionImportee();
  if (id) {
    await admin.delete(`/api/editions/${id}`);
  }
}

test.beforeAll(async ({ playwright }, testInfo) => {
  admin = await contexteAdmin(playwright, testInfo.project.use.baseURL as string);
  await repartirDeLaReference(admin);
  // Leftover from a crashed previous run.
  await supprimerEdition();
});

test.afterAll(async () => {
  await supprimerEdition();
  await admin.dispose();
});

test("l'import d'un fichier à section edition annonce la cible, importe ailleurs et propose la bascule", async ({
  browser,
}) => {
  test.slow();
  const editionCourante = (await (await admin.get('/api/editions/courant')).json()) as {
    id: string;
    nom: string;
  };

  const page = await pageAdmin(browser, admin);
  await page.goto('/imports?onglet=scenario');
  await expect(page.getByRole('button', { name: 'Importer un fichier' })).toBeEnabled();

  const fileChooserPromise = page.waitForEvent('filechooser');
  await page.getByRole('button', { name: 'Importer un fichier' }).click();
  const fileChooser = await fileChooserPromise;
  await fileChooser.setFiles({
    name: 'scenario-edition.yaml',
    mimeType: 'application/x-yaml',
    buffer: Buffer.from(SCENARIO, 'utf8'),
  });

  // BEFORE anything is written: the confirmation names the target edition,
  // says it will be created, and promises the current edition stays intact.
  const confirmation = page.getByRole('dialog');
  await expect(confirmation).toContainText(`« ${EDITION_IMPORT.nom} »`);
  await expect(confirmation).toContainText('CRÉÉE');
  await expect(confirmation).toContainText(`« ${editionCourante.nom} » ne sera pas modifiée`);
  await confirmation.getByRole('button', { name: 'Importer' }).click();

  // AFTER: the unmissable recap dialog offers to switch onto the target.
  const recap = page.getByRole('dialog');
  await expect(recap).toContainText(`Édition « ${EDITION_IMPORT.nom} » créée`);
  await expect(
    recap.getByRole('button', { name: `Basculer sur « ${EDITION_IMPORT.nom} »` }),
  ).toBeVisible();

  // The ambient edition was never touched by the import.
  const standsCourants = (await (await page.request.get('/api/stands')).json()) as {
    code: string | null;
  }[];
  expect(standsCourants.map((stand) => stand.code)).not.toContain('E2EIMP-S1');
  const editionCible = await editionImportee();
  expect(editionCible, 'the import must have created the edition').toBeTruthy();
  const standsCible = (await (
    await page.request.get('/api/stands', { headers: { 'X-Edition-Id': editionCible as string } })
  ).json()) as { code: string | null }[];
  expect(standsCible.map((stand) => stand.code)).toContain('E2EIMP-S1');

  // Switching reloads the page onto the freshly written edition. The waiter is
  // armed BEFORE the click on purpose: `waitForLoadState('load')` resolves
  // straight away on the document that is already loaded, so it never waited
  // for the reload at all. The assertions below then raced it, and the
  // localStorage cleanup landed in a context the reload had destroyed —
  // "Execution context was destroyed", one night out of two.
  const rechargement = page.waitForEvent('load');
  await recap.getByRole('button', { name: `Basculer sur « ${EDITION_IMPORT.nom} »` }).click();
  await rechargement;
  await expect(page.locator('body')).toContainText(EDITION_IMPORT.nom);

  // Housekeeping: the switch persisted in localStorage; forget it so the
  // browser context (and any later test reusing the storage state) does not
  // stay pointed at an edition the afterAll hook deletes.
  await page.evaluate(() => localStorage.removeItem('planning-equipes.editionId'));
  await page.context().close();
});
