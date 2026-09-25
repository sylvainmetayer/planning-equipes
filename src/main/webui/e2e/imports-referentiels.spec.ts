// Les trois référentiels remplis depuis un fichier, vus du navigateur : l'écran
// à onglets, l'aperçu qui n'écrit rien, et l'écriture qui crée les fiches.
//
// Ce que la suite tient au-delà du chemin heureux : une typologie qu'un stand
// cite sans qu'elle existe est créée et annoncée avant l'écriture, et une
// colonne que le fichier ne porte pas n'efface pas ce que la fiche tenait
// déjà — un fichier de trois colonnes ne doit pas emporter des horaires.

import { APIRequestContext, Page, expect, test } from '@playwright/test';
import { contexteAdmin, idCree, pageAdmin } from './support';
import { repartirDeLaReference } from './reference';

let admin: APIRequestContext;

/** The edition is found by its name: its id is drawn by the application. */
const EDITION_NOM = 'Imports E2E';
let EDITION = '';

async function supprimerEdition(): Promise<void> {
  const editions = (await (await admin.get('/api/editions')).json()) as {
    id: string;
    nom: string;
  }[];
  for (const edition of editions.filter((candidate) => candidate.nom === EDITION_NOM)) {
    await admin.delete(`/api/editions/${edition.id}`).catch(() => undefined);
  }
}

test.beforeAll(async ({ playwright }, testInfo) => {
  admin = await contexteAdmin(playwright, testInfo.project.use.baseURL as string);
  await repartirDeLaReference(admin);
  await supprimerEdition();
  EDITION = await idCree(await admin.post('/api/editions', { data: { nom: EDITION_NOM } }));
});

test.afterAll(async () => {
  await supprimerEdition();
  await admin.dispose();
});

const dansEdition = () => ({ headers: { 'X-Edition-Id': EDITION } });

/** A row of a referential as the assertions read it: found by its code, never by its id. */
interface Ligne {
  id: string;
  code: string | null;
  label?: string;
  nom?: string;
  effectifMin?: number;
  effectifMax?: number;
  horaires?: unknown[];
}

async function lire(page: Page, ressource: string): Promise<Ligne[]> {
  return (await (await page.request.get(`/api/${ressource}`, dansEdition())).json()) as Ligne[];
}

/** Dépose un contenu CSV sur l'onglet ouvert, sans passer par le disque. */
async function deposer(page: Page, nom: string, contenu: string): Promise<void> {
  await page.locator('input[type="file"]').setInputFiles({
    name: nom,
    mimeType: 'text/csv',
    buffer: Buffer.from(contenu, 'utf8'),
  });
}

test('les trois référentiels se remplissent depuis un fichier, sur un seul écran', async ({
  browser,
}) => {
  test.slow();
  const page = await pageAdmin(browser, admin);
  // L'édition se désigne avant la première navigation : `localStorage` n'existe
  // pas sur `about:blank`, d'où le script d'amorçage plutôt qu'un `evaluate`.
  await page.addInitScript(
    (id) => localStorage.setItem('planning-equipes.editionId', id as string),
    EDITION,
  );

  // 1. Typologies — l'onglet d'ouverture, puisque tout en part.
  await page.goto('/imports');
  await expect(page.locator('.imports-onglets')).toBeVisible();
  await deposer(
    page,
    'typologies.csv',
    'code;libelle\nE2EIMP-A;Jeux d’ambiance\nE2EIMP-B;Stratégie\n',
  );
  await expect(page.locator('#contenu')).toContainText('2 ligne(s)');
  await expect(page.locator('.import-ligne-creation')).toHaveCount(2);

  // L'aperçu n'écrit rien : le référentiel est encore vide.
  let typologies = await lire(page, 'typologies');
  expect(typologies.filter((t) => t.code?.startsWith('E2EIMP-'))).toHaveLength(0);

  await page.getByRole('button', { name: 'Importer', exact: true }).click();
  await page.getByRole('dialog').getByRole('button', { name: 'Confirmer' }).click();
  await expect(page.locator('#contenu')).toContainText('Import effectué');
  typologies = await lire(page, 'typologies');
  expect(typologies.find((t) => t.code === 'E2EIMP-A')?.label).toBe('Jeux d’ambiance');

  // 2. Emplacements — les coordonnées restent facultatives.
  await page.goto('/imports?onglet=emplacements');
  await deposer(
    page,
    'emplacements.csv',
    'code;nom;latitude;longitude\nE2EIMP-P;Pavillon;46,65;-0,24\nE2EIMP-E;Esplanade;;\n',
  );
  await expect(page.locator('.import-ligne-creation')).toHaveCount(2);
  await page.getByRole('button', { name: 'Importer', exact: true }).click();
  await page.getByRole('dialog').getByRole('button', { name: 'Confirmer' }).click();
  await expect(page.locator('#contenu')).toContainText('Import effectué');

  // 3. Stands — effectif facultatif, et une typologie inconnue annoncée puis créée.
  await page.goto('/imports?onglet=stands');
  await deposer(
    page,
    'stands.csv',
    'code;nom;typologies;effectifMin;effectifMax\n' +
      'E2EIMP-S1;Stand un;E2EIMP-A;2;3\n' +
      'E2EIMP-S2;Stand deux;E2EIMP-NOUVELLE;;\n',
  );
  await expect(page.locator('#contenu')).toContainText('E2EIMP-NOUVELLE');
  await expect(page.locator('#contenu')).toContainText('une personne');
  await page.getByRole('button', { name: 'Importer', exact: true }).click();
  await page.getByRole('dialog').getByRole('button', { name: 'Confirmer' }).click();
  await expect(page.locator('#contenu')).toContainText('Import effectué');

  const stands = await lire(page, 'stands');
  const standUn = stands.find((s) => s.code === 'E2EIMP-S1');
  expect(standUn?.effectifMin).toBe(2);
  // Sans effectif dans le fichier : une personne.
  expect(stands.find((s) => s.code === 'E2EIMP-S2')?.effectifMax).toBe(1);
  typologies = await lire(page, 'typologies');
  // A typologie a stand cited without it existing is created under that code,
  // and takes it for its label.
  expect(typologies.find((t) => t.code === 'E2EIMP-NOUVELLE')?.label).toBe('E2EIMP-NOUVELLE');

  // 4. Une colonne absente n'efface rien : on donne un horaire au stand, puis
  // on le renomme par un fichier de trois colonnes.
  // A PUT replaces the whole row: the code goes back with it, or the file
  // below would no longer designate the stand.
  const avecHoraire = await page.request.put(`/api/stands/${standUn?.id}`, {
    ...dansEdition(),
    data: {
      id: standUn?.id,
      code: 'E2EIMP-S1',
      nom: 'Stand un',
      typologiesProposees: ['E2EIMP-A'],
      effectifMin: 2,
      effectifMax: 3,
      horaires: [{ mode: 'OUVERTURE', jours: 'TOUS', fenetres: [{ heureDebut: '10:00:00' }] }],
    },
  });
  expect(avecHoraire.ok(), await avecHoraire.text()).toBe(true);

  await page.goto('/imports?onglet=stands');
  await deposer(page, 'renommage.csv', 'code;nom;typologies\nE2EIMP-S1;Stand renommé;E2EIMP-A\n');
  await expect(page.locator('.import-ligne-maj')).toHaveCount(1);
  await page.getByRole('button', { name: 'Importer', exact: true }).click();
  await page.getByRole('dialog').getByRole('button', { name: 'Confirmer' }).click();
  await expect(page.locator('#contenu')).toContainText('Import effectué');

  const apres = await lire(page, 'stands');
  const renomme = apres.find((s) => s.code === 'E2EIMP-S1');
  // Designated by its code, the stand was updated in place, not recreated.
  expect(renomme?.id).toBe(standUn?.id);
  expect(renomme?.nom).toBe('Stand renommé');
  expect(renomme?.effectifMin).toBe(2);
  expect(renomme?.horaires).toHaveLength(1);

  await page.context().close();
});
