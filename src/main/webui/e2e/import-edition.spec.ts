// A scenario file carrying an `edition:` section routes its import into that
// edition — and the operator can no longer miss it: the confirmation dialog
// names the target BEFORE anything is written, and once the import is done an
// unmissable dialog offers to switch onto the freshly written edition. This
// spec drives the whole flow through the real UI: file picker, both dialogs,
// the switch, and the fact that the ambient edition was never touched.

import { APIRequestContext, expect, test } from '@playwright/test';
import { contexteAdmin, pageAdmin } from './support';
import { repartirDeLaReference } from './reference';

const EDITION_IMPORT = { id: 'E2E-IMPORT-CIBLE', nom: 'Édition import e2e' };

const SCENARIO = `edition:
  id: ${EDITION_IMPORT.id}
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

test.beforeAll(async ({ playwright }, testInfo) => {
  admin = await contexteAdmin(playwright, testInfo.project.use.baseURL as string);
  await repartirDeLaReference(admin);
  // Leftover from a crashed previous run; a 404 here is fine.
  await admin.delete(`/api/editions/${EDITION_IMPORT.id}`);
});

test.afterAll(async () => {
  await admin.delete(`/api/editions/${EDITION_IMPORT.id}`);
  await admin.dispose();
});

test("l'import d'un fichier à section edition annonce la cible, importe ailleurs et propose la bascule", async ({ browser }) => {
  test.slow();
  const editionCourante = (await (await admin.get('/api/editions/courant')).json()) as { id: string; nom: string };

  const page = await pageAdmin(browser, admin);
  await page.goto('/parametres');
  await expect(page.getByRole('button', { name: 'Importer un fichier' })).toBeEnabled();

  const fileChooserPromise = page.waitForEvent('filechooser');
  await page.getByRole('button', { name: 'Importer un fichier' }).click();
  const fileChooser = await fileChooserPromise;
  await fileChooser.setFiles({
    name: 'scenario-edition.yaml',
    mimeType: 'application/x-yaml',
    buffer: Buffer.from(SCENARIO, 'utf8')
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
  await expect(recap.getByRole('button', { name: `Basculer sur « ${EDITION_IMPORT.nom} »` })).toBeVisible();

  // The ambient edition was never touched by the import.
  const standsCourants = (await (await page.request.get('/api/stands')).json()) as { id: string }[];
  expect(standsCourants.map((stand) => stand.id)).not.toContain('E2EIMP-S1');
  const standsCible = (await (
    await page.request.get('/api/stands', { headers: { 'X-Edition-Id': EDITION_IMPORT.id } })
  ).json()) as { id: string }[];
  expect(standsCible.map((stand) => stand.id)).toContain('E2EIMP-S1');

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
