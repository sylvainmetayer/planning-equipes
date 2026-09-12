// Les trois référentiels remplis depuis un fichier, vus du navigateur : l'écran
// à onglets, l'aperçu qui n'écrit rien, et l'écriture qui crée les fiches.
//
// Ce que la suite tient au-delà du chemin heureux : une typologie qu'un stand
// cite sans qu'elle existe est créée et annoncée avant l'écriture, et une
// colonne que le fichier ne porte pas n'efface pas ce que la fiche tenait
// déjà — un fichier de trois colonnes ne doit pas emporter des horaires.

import { APIRequestContext, Page, expect, test } from '@playwright/test';
import { contexteAdmin, pageAdmin } from './support';
import { repartirDeLaReference } from './reference';

let admin: APIRequestContext;

const EDITION = 'E2E-IMPORTS';

test.beforeAll(async ({ playwright }, testInfo) => {
  admin = await contexteAdmin(playwright, testInfo.project.use.baseURL as string);
  await repartirDeLaReference(admin);
  await admin.delete(`/api/editions/${EDITION}`).catch(() => undefined);
  const creation = await admin.post('/api/editions', {
    data: { id: EDITION, nom: 'Imports E2E' },
  });
  expect(creation.ok(), await creation.text()).toBe(true);
});

test.afterAll(async () => {
  await admin.delete(`/api/editions/${EDITION}`).catch(() => undefined);
  await admin.dispose();
});

const DANS_EDITION = { headers: { 'X-Edition-Id': EDITION } };

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
    'id;libelle\nE2EIMP-A;Jeux d’ambiance\nE2EIMP-B;Stratégie\n',
  );
  await expect(page.locator('#contenu')).toContainText('2 ligne(s)');
  await expect(page.locator('.import-ligne-creation')).toHaveCount(2);

  // L'aperçu n'écrit rien : le référentiel est encore vide.
  let typologies = (await (await page.request.get('/api/typologies', DANS_EDITION)).json()) as {
    id: string;
  }[];
  expect(typologies.filter((t) => t.id.startsWith('E2EIMP-'))).toHaveLength(0);

  await page.getByRole('button', { name: 'Importer', exact: true }).click();
  await page.getByRole('dialog').getByRole('button', { name: 'Confirmer' }).click();
  await expect(page.locator('#contenu')).toContainText('Import effectué');
  typologies = (await (await page.request.get('/api/typologies', DANS_EDITION)).json()) as {
    id: string;
    label: string;
  }[];
  expect(typologies.find((t) => t.id === 'E2EIMP-A')?.label).toBe('Jeux d’ambiance');

  // 2. Emplacements — les coordonnées restent facultatives.
  await page.goto('/imports?onglet=emplacements');
  await deposer(
    page,
    'emplacements.csv',
    'id;nom;latitude;longitude\nE2EIMP-P;Pavillon;46,65;-0,24\nE2EIMP-E;Esplanade;;\n',
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
    'id;nom;typologies;effectifMin;effectifMax\n' +
      'E2EIMP-S1;Stand un;E2EIMP-A;2;3\n' +
      'E2EIMP-S2;Stand deux;E2EIMP-NOUVELLE;;\n',
  );
  await expect(page.locator('#contenu')).toContainText('E2EIMP-NOUVELLE');
  await expect(page.locator('#contenu')).toContainText('une personne');
  await page.getByRole('button', { name: 'Importer', exact: true }).click();
  await page.getByRole('dialog').getByRole('button', { name: 'Confirmer' }).click();
  await expect(page.locator('#contenu')).toContainText('Import effectué');

  const stands = (await (await page.request.get('/api/stands', DANS_EDITION)).json()) as {
    id: string;
    nom: string;
    effectifMin: number;
    effectifMax: number;
    horaires: unknown[];
  }[];
  expect(stands.find((s) => s.id === 'E2EIMP-S1')?.effectifMin).toBe(2);
  // Sans effectif dans le fichier : une personne.
  expect(stands.find((s) => s.id === 'E2EIMP-S2')?.effectifMax).toBe(1);
  typologies = (await (await page.request.get('/api/typologies', DANS_EDITION)).json()) as {
    id: string;
    label: string;
  }[];
  expect(typologies.find((t) => t.id === 'E2EIMP-NOUVELLE')?.label).toBe('E2EIMP-NOUVELLE');

  // 4. Une colonne absente n'efface rien : on donne un horaire au stand, puis
  // on le renomme par un fichier de trois colonnes.
  const avecHoraire = await page.request.put('/api/stands/E2EIMP-S1', {
    ...DANS_EDITION,
    data: {
      id: 'E2EIMP-S1',
      nom: 'Stand un',
      typologiesProposees: ['E2EIMP-A'],
      effectifMin: 2,
      effectifMax: 3,
      horaires: [{ mode: 'OUVERTURE', jours: 'TOUS', fenetres: [{ heureDebut: '10:00:00' }] }],
    },
  });
  expect(avecHoraire.ok(), await avecHoraire.text()).toBe(true);

  await page.goto('/imports?onglet=stands');
  await deposer(page, 'renommage.csv', 'id;nom;typologies\nE2EIMP-S1;Stand renommé;E2EIMP-A\n');
  await expect(page.locator('.import-ligne-maj')).toHaveCount(1);
  await page.getByRole('button', { name: 'Importer', exact: true }).click();
  await page.getByRole('dialog').getByRole('button', { name: 'Confirmer' }).click();
  await expect(page.locator('#contenu')).toContainText('Import effectué');

  const apres = (await (await page.request.get('/api/stands', DANS_EDITION)).json()) as {
    id: string;
    nom: string;
    effectifMin: number;
    horaires: unknown[];
  }[];
  const renomme = apres.find((s) => s.id === 'E2EIMP-S1');
  expect(renomme?.nom).toBe('Stand renommé');
  expect(renomme?.effectifMin).toBe(2);
  expect(renomme?.horaires).toHaveLength(1);

  await page.context().close();
});
