// Les imports qui restent, vus du navigateur : le YAML est le classeur complet
// de l'édition, le CSV ne sert plus qu'aux animateurs. Les anciennes routes
// d'import (un CSV par référentiel, la grille des stands) répondent 410 en
// montrant la sortie, et un signet sur leur carte tombe sur l'onglet Importer.

import { APIRequestContext, expect, test } from '@playwright/test';
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

const ROUTES_RETIREES = [
  '/api/typologies/import-csv',
  '/api/emplacements/import-csv',
  '/api/stands/import-csv',
  '/api/creneaux/import-csv',
  '/api/journees-types/import-csv',
  '/api/stands/import-grille',
];

test('les imports CSV retirés répondent 410 et montrent le YAML', async () => {
  for (const route of ROUTES_RETIREES) {
    for (const chemin of [route, `${route}/analyse`]) {
      const reponse = await admin.post(chemin, { ...dansEdition(), data: {} });
      expect(reponse.status(), chemin).toBe(410);
      const { message } = (await reponse.json()) as { message: string };
      expect(message, chemin).toContain('YAML');
    }
    const exemple = await admin.get(`${route}/exemple`, dansEdition());
    expect(exemple.status(), `${route}/exemple`).toBe(410);
  }
});

test("un signet sur une carte d'import retirée tombe sur le scénario, et il ne reste que quatre cartes", async ({
  browser,
}) => {
  const page = await pageAdmin(browser, admin);
  await page.addInitScript(
    (id) => localStorage.setItem('planning-equipes.editionId', id as string),
    EDITION,
  );
  for (const carte of ['typologies', 'stands', 'creneaux', 'journees-types', 'grille-stands']) {
    await page.goto(`/fichiers?cible=${carte}`);
    await expect(page.locator('#contenu')).toContainText('Un fichier scénario');
  }
  await expect(page.locator('.imports-onglets mat-button-toggle')).toHaveCount(4);
  // L'écran des stands ne propose plus d'importer la grille.
  await page.goto('/stands');
  await page.getByRole('button', { name: 'Plus' }).click();
  await expect(page.getByRole('menuitem', { name: 'Importer la grille' })).toHaveCount(0);
  await page.context().close();
});

/**
 * Le YAML remplit tout d'un coup : typologies, stands et horaires, là où il
 * fallait un fichier CSV par référentiel.
 */
test("le YAML porte le référentiel d'une édition : typologies, stands et horaires", async ({
  browser,
}) => {
  test.slow();
  const yaml = [
    'schemaVersion: 2',
    'festival:',
    '  dateDebut: 2027-07-03',
    'typologies:',
    '  - id: E2EIMP-A',
    '    label: Jeux d’ambiance',
    'creneaux:',
    '  - id: C1',
    '    jour: 1',
    '    date: 2027-07-03',
    '    heureDebut: "10:00"',
    '    heureFin: "12:00"',
    'stands:',
    '  - id: E2EIMP-S1',
    '    nom: Stand un',
    '    typologiesProposees: [E2EIMP-A]',
    '    effectifMin: 2',
    '    effectifMax: 3',
    'animateurs: []',
    '',
  ].join('\n');
  const reponse = await admin.post('/api/reference-data/import-scenario-fichier', {
    ...dansEdition(),
    headers: { ...dansEdition().headers, 'Content-Type': 'text/plain' },
    data: yaml,
  });
  expect(reponse.ok(), await reponse.text()).toBe(true);

  const page = await pageAdmin(browser, admin);
  const stands = (await (await page.request.get('/api/stands', dansEdition())).json()) as {
    code: string | null;
    effectifMin: number;
  }[];
  expect(stands.find((s) => s.code === 'E2EIMP-S1')?.effectifMin).toBe(2);
  await page.context().close();
});

/**
 * A referential screen fills from a file or a paste without leaving it: its
 * « Importer » button opens the very card of Fichiers in a dialog.
 */
test("« Importer » ouvre la carte d'import sans quitter la page", async ({ browser }) => {
  const page = await pageAdmin(browser, admin);
  await page.addInitScript(
    (id) => localStorage.setItem('planning-equipes.editionId', id as string),
    EDITION,
  );
  await page.goto('/animateurs');
  await page.getByRole('button', { name: 'Importer', exact: true }).click();

  const dialog = page.getByRole('dialog');
  await expect(dialog.getByRole('heading', { name: 'Importer des animateurs' })).toBeVisible();
  await expect(dialog).toContainText('date de naissance est obligatoire');
  await expect(dialog.getByRole('button', { name: 'Coller depuis un tableur' })).toBeVisible();
  await expect(page).toHaveURL(/\/animateurs$/);

  await dialog.getByRole('button', { name: 'Fermer' }).click();
  await expect(page.getByRole('dialog')).toHaveCount(0);
  await page.context().close();
});
