// Tabular import of the animateurs (issue #307), through the interface only:
// drop a CSV, map the columns, read the preview, confirm, read the report.
//
// Two files, and the second one matters more than the first: a deliberately
// dirty roster, where what has to be proved is not that the bad rows are
// refused but that the good ones still go in — a row-by-row import that gives
// up on the whole file at the first mistake is a scenario import with extra
// steps.

import { APIRequestContext, Page, expect, test } from '@playwright/test';
import { contexteAdmin, pageAdmin, seedPlanning } from './support';
import { repartirDeLaReference } from './reference';

let admin: APIRequestContext;

/** Ids the file hands out itself, so the cleanup is deterministic. */
const IDS = ['E2E-CSV-1', 'E2E-CSV-2', 'E2E-CSV-3'] as const;

const JOUR_EVENEMENT = '10/07/2026';

test.beforeAll(async ({ playwright }, testInfo) => {
  admin = await contexteAdmin(playwright, testInfo.project.use.baseURL as string);
  await repartirDeLaReference(admin);
  await seedPlanning(admin);
});

test.afterEach(async () => {
  await nettoyer();
});

test.afterAll(async () => {
  await admin.dispose();
});

async function nettoyer(): Promise<void> {
  const script = [
    `delete from poste_affectation where animateur_id like 'E2E-CSV-%';`,
    `delete from animateur where id like 'E2E-CSV-%';`
  ].join('\n');
  const reponse = await admin.post('/api/database/import', {
    headers: { 'Content-Type': 'text/plain' },
    data: script
  });
  expect(reponse.ok(), await reponse.text()).toBe(true);
}

async function deposer(page: Page, nom: string, contenu: string): Promise<void> {
  await page.locator('input[type="file"]').setInputFiles({
    name: nom,
    mimeType: 'text/csv',
    buffer: Buffer.from(contenu, 'utf-8')
  });
}

function ligne(page: Page, numero: number) {
  return page.locator(`tr[data-ligne="${numero}"]`);
}

/**
 * What the screen has to say *before* a file is picked. An explanation shown
 * only once the mapping editor appears comes too late: the operator has
 * already exported the wrong thing from their spreadsheet.
 */
test("le format attendu et l'aide se lisent avant tout choix de fichier", async ({ browser }) => {
  const page = await pageAdmin(browser, admin);
  await page.goto('/import-animateurs');

  const contenu = page.locator('#contenu');
  await expect(contenu).toContainText('La date de naissance est obligatoire pour créer une fiche');
  await expect(contenu).toContainText('séparées par « | »');
  await expect(contenu).toContainText('« typologie » ou « typologie:REFERENT »');
  // Said before the picker, not after it: the paragraph precedes the button in
  // the document, and nothing has been loaded yet.
  await expect(contenu).not.toContainText('Fichier chargé');
  const ordre = await contenu.evaluate((racine) => {
    const texte = [...racine.querySelectorAll('p')].find((noeud) =>
      noeud.textContent?.includes('La date de naissance est obligatoire')
    );
    const bouton = [...racine.querySelectorAll('button')].find((noeud) =>
      noeud.textContent?.includes('Choisir un fichier CSV')
    );
    return texte && bouton
      ? texte.compareDocumentPosition(bouton) & Node.DOCUMENT_POSITION_FOLLOWING
      : 0;
  });
  expect(ordre).toBeGreaterThan(0);

  // And the deep link lands on the import passage of the guide, not on its top.
  await page.getByRole('link', { name: "Tous les détails dans l'aide" }).click();
  await expect(page).toHaveURL(/\/aide#import-csv-animateurs$/);
  const section = page.locator('#import-csv-animateurs');
  await expect(section).toContainText('Import CSV des animateurs');
  await expect(section).toBeInViewport();

  // A deep link that does not survive a refresh is not a deep link.
  await page.reload();
  await expect(page.locator('#import-csv-animateurs')).toBeInViewport();

  await page.context().close();
});

test('un fichier propre : aperçu, validation, rapport, et les fiches en base', async ({ browser }) => {
  const page = await pageAdmin(browser, admin);
  await page.goto('/import-animateurs');
  await expect(page.locator('#contenu')).toContainText('Import des animateurs');

  await deposer(
    page,
    'benevoles.csv',
    [
      'id;prenom;nom;date de naissance;jours indisponibles',
      `${IDS[0]};Amélie;Duranteau;12/03/1990;${JOUR_EVENEMENT}`,
      `${IDS[1]};Bruno;Lefèvreau;04/06/1988;`,
      ''
    ].join('\n')
  );

  // The preview: shown, counted, and — the invariant — written nowhere.
  await expect(page.locator('#contenu')).toContainText("Ce que l'import ferait");
  await expect(page.locator('#contenu')).toContainText("Aucune écriture n'a eu lieu");
  await expect(ligne(page, 2)).toContainText('Amélie Duranteau');
  await expect(ligne(page, 2)).toContainText('Création');
  await expect(ligne(page, 2)).toContainText('2026-07-10');
  await expect(ligne(page, 3)).toContainText('Création');
  const avantImport = await admin.get('/api/animateurs');
  expect(await avantImport.text()).not.toContain('Duranteau');

  await page.getByRole('button', { name: 'Importer', exact: true }).click();
  await page.getByRole('button', { name: 'Confirmer' }).click();

  await expect(page.locator('#contenu')).toContainText('Ce qui a été importé');
  await expect(page.locator('#contenu')).toContainText('une seule transaction');

  // And the fiches are really there, with the off day the file carried.
  const apres = await admin.get('/api/animateurs');
  const roster = (await apres.json()) as { id: string; nom: string; joursIndisponibles: string[] }[];
  const amelie = roster.find((animateur) => animateur.id === IDS[0]);
  expect(amelie?.nom).toBe('Duranteau');
  expect(amelie?.joursIndisponibles).toEqual(['2026-07-10']);
  expect(roster.some((animateur) => animateur.id === IDS[1])).toBe(true);

  await page.context().close();
});

test('un fichier sale : les lignes fautives sont rejetées, les bonnes passent quand même', async ({
  browser
}) => {
  const page = await pageAdmin(browser, admin);
  await page.goto('/import-animateurs');

  await deposer(
    page,
    'benevoles-sales.csv',
    [
      'id;prenom;nom;date de naissance;email;competences;jours indisponibles',
      `${IDS[0]};Amélie;Duranteau;12/03/1990;amelie@example.org;;${JOUR_EVENEMENT}`,
      'E2E-CSV-BAD1;Carla;Moreau;32/13/1990;carla@example.org;;',
      'E2E-CSV-BAD2;Diego;Santos;01/01/1990;pas-une-adresse;;',
      'E2E-CSV-BAD3;Elena;Rossi;01/01/1990;elena@example.org;typologie-inexistante;',
      'E2E-CSV-BAD4;Farid;Belkacem;01/01/1990;farid@example.org;;01/01/2031',
      `${IDS[1]};Bruno;Lefèvreau;04/06/1988;bruno@example.org;;`,
      ''
    ].join('\n')
  );

  await expect(ligne(page, 2)).toContainText('Création');
  await expect(ligne(page, 3)).toContainText('Date de naissance illisible');
  await expect(ligne(page, 4)).toContainText('Adresse e-mail invalide');
  await expect(ligne(page, 5)).toContainText('Typologie');
  await expect(ligne(page, 6)).toContainText("hors des dates de l'événement");
  await expect(ligne(page, 7)).toContainText('Création');

  await page.getByRole('button', { name: 'Importer', exact: true }).click();
  await page.getByRole('button', { name: 'Confirmer' }).click();
  await expect(page.locator('#contenu')).toContainText('Ce qui a été importé');

  // The point of a row-by-row import: four refusals cost four rows, not the file.
  const apres = await admin.get('/api/animateurs');
  const roster = (await apres.json()) as { id: string }[];
  const ids = roster.map((animateur) => animateur.id);
  expect(ids).toContain(IDS[0]);
  expect(ids).toContain(IDS[1]);
  expect(ids.filter((id) => id.startsWith('E2E-CSV-BAD'))).toEqual([]);

  await page.context().close();
});

test('un fichier sans en-tête reconnaissable se mappe à la main', async ({ browser }) => {
  const page = await pageAdmin(browser, admin);
  await page.goto('/import-animateurs');

  await deposer(
    page,
    'sans-entete.csv',
    ['colonne A;colonne B;colonne C', `Duranteau;Amélie;12/03/1990`, ''].join('\n')
  );

  // Nothing recognised: the screen still draws its mapping editor, and says why.
  await expect(page.locator('#contenu')).toContainText("Aucune colonne n'est associée");
  await expect(page.getByRole('button', { name: 'Importer', exact: true })).toBeDisabled();

  await choisirColonne(page, 'Nom', 'colonne A (1)');
  await choisirColonne(page, 'Prénom', 'colonne B (2)');
  await choisirColonne(page, 'Date de naissance', 'colonne C (3)');

  await expect(ligne(page, 2)).toContainText('Amélie Duranteau');
  await expect(ligne(page, 2)).toContainText('Création');

  await page.context().close();
});

/**
 * Picks a column in one field's mat-select.
 *
 * Two traps, both met on the way. An unfilled Material select puts its label
 * in the middle of the control, so a click aimed at the centre lands on the
 * `<mat-label>` and is swallowed — the arrow is the one part of the trigger
 * nothing covers. And the field is centred in the viewport first: the sticky
 * edition banner covers whatever Playwright's own scroll brings just under the
 * top of it.
 */
async function choisirColonne(page: Page, champ: string, option: string): Promise<void> {
  const champField = page
    .locator('mat-form-field')
    .filter({ has: page.getByText(champ, { exact: true }) });
  const select = champField.locator('mat-select');
  await select.evaluate((element) => element.scrollIntoView({ block: 'center' }));
  await champField.locator('.mat-mdc-select-arrow-wrapper').click();
  await page.getByRole('option', { name: option, exact: true }).click();
}

/**
 * The likeliest slip of the whole screen, and the one the preview exists for:
 * a free-text column dropped on « Nom ». It used to preview all green and then
 * die on the column width — a 500, with the whole file rolled back.
 */
test('une colonne mal mappée est refusée à l\'aperçu, pas à l\'écriture', async ({ browser }) => {
  const page = await pageAdmin(browser, admin);
  await page.goto('/import-animateurs');

  const commentaire = 'Disponible surtout le week-end et volontiers en soirée '.repeat(6);
  await deposer(
    page,
    'benevoles-commentes.csv',
    [
      'id;prenom;nom;date de naissance;commentaires',
      `${IDS[0]};Amélie;Duranteau;12/03/1990;${commentaire}`,
      ''
    ].join('\n')
  );

  await expect(ligne(page, 2)).toContainText('Création');

  await choisirColonne(page, 'Nom', 'commentaires (5)');

  await expect(ligne(page, 2)).toContainText('Nom trop long');
  await expect(ligne(page, 2)).toContainText('128 au maximum');
  await expect(page.getByRole('button', { name: 'Importer', exact: true })).toBeDisabled();

  const apres = await admin.get('/api/animateurs');
  expect(await apres.text()).not.toContain('Duranteau');

  await page.context().close();
});

test('un classeur .xlsx est refusé avec la marche à suivre', async ({ browser }) => {
  const page = await pageAdmin(browser, admin);
  await page.goto('/import-animateurs');

  await page.locator('input[type="file"]').setInputFiles({
    name: 'benevoles.xlsx',
    mimeType: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
    buffer: Buffer.from('PK not really a workbook', 'utf-8')
  });

  await expect(page.getByRole('alert')).toContainText('seul le CSV est lu');
  await expect(page.locator('#contenu')).not.toContainText("Ce que l'import ferait");

  await page.context().close();
});
