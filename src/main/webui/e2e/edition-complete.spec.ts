// Une édition saisie de bout en bout depuis l'écran, dans l'ordre de la prise
// en main (ADR 0032) : l'édition, ses typologies et emplacements, ses journées
// types posées sur un calendrier puis appliquées, ses stands et leurs plages
// d'ouverture — dont un stand aux heures particulières —,
// ses animateurs, un solve court réel, et le planning relu sur l'axe du temps
// et la page Problèmes. Trois jours, trois stands, trois animateurs : le plus
// petit jeu qui passe par toutes les capacités de l'outil.

import { APIRequestContext, Page, expect, test } from '@playwright/test';
import {
  calculerLePlanning,
  contexteAdmin,
  shiftDate,
  dialogueOuvert,
  pageAdmin,
  PAGE_CALCULER,
} from './support';
import { repartirDeLaReference } from './reference';

/**
 * The edition is found by its name: its id (`E2`, `E3`…) is drawn by the
 * application, like every id below. What the spec types is a name or a code.
 */
const EDITION_NOM = 'E2E Gabarits';
/** Codes: the readable keys the spec gives, and finds its rows by. */
const TYPOLOGIE = 'E2E-JT-JEU';
const STANDS = [
  { code: 'E2E-JT-S1', nom: 'Stand un E2E' },
  { code: 'E2E-JT-S2', nom: 'Stand deux E2E' },
  { code: 'E2E-JT-S3', nom: 'Stand trois E2E' },
] as const;
const ANIMATEURS = [
  { email: 'e2e-jt-a@example.org', prenom: 'Alix', nom: 'Journee' },
  { email: 'e2e-jt-b@example.org', prenom: 'Bao', nom: 'Journee' },
  { email: 'e2e-jt-c@example.org', prenom: 'Cléo', nom: 'Journee' },
] as const;
const JOUR1 = shiftDate('2026-07-13');
const JOUR2 = shiftDate('2026-07-14');
const JOUR3 = shiftDate('2026-07-15');

let admin: APIRequestContext;

test.beforeAll(async ({ playwright }, testInfo) => {
  admin = await contexteAdmin(playwright, testInfo.project.use.baseURL as string);
  await repartirDeLaReference(admin);
  await supprimerEdition();
});

test.afterAll(async () => {
  await supprimerEdition();
  await admin.dispose();
});

/** The id the application gave the edition this spec creates, `undefined` before. */
async function editionCreee(): Promise<string | undefined> {
  const editions = (await (await admin.get('/api/editions')).json()) as {
    id: string;
    nom: string;
  }[];
  return editions.find((edition) => edition.nom === EDITION_NOM)?.id;
}

async function supprimerEdition(): Promise<void> {
  const id = await editionCreee();
  if (id) {
    await admin.delete(`/api/editions/${id}`).catch(() => undefined);
  }
}

/** Set once the edition exists: the header that aims the API reads at it. */
let DANS_EDITION: { headers: Record<string, string> } = { headers: {} };

async function creerGabarit(page: Page, nom: string, vacations: string): Promise<void> {
  await page.getByRole('button', { name: 'Nouveau gabarit' }).click();
  const dialog = await dialogueOuvert(page);
  await dialog.getByLabel('Nom').fill(nom);
  await dialog.getByLabel('Vacations de la journée').fill(vacations);
  await dialog.getByRole('button', { name: 'Créer le gabarit' }).click();
  await expect(dialog).toBeHidden();
}

async function affecterDates(page: Page, du: string, au: string, gabarit: string, attendu: number) {
  const ajout = page.locator('.gabarits-ajout');
  await ajout.getByLabel('Du', { exact: true }).fill(du);
  await ajout.getByLabel('Au', { exact: true }).fill(au);
  await ajout.locator('mat-form-field').filter({ hasText: 'Gabarit' }).click();
  await page.getByRole('option', { name: gabarit }).click();
  await page.getByRole('button', { name: `Affecter ${attendu} date(s)` }).click();
}

/**
 * « Ajouter un stand », guided: identity, game categories, location, hours —
 * the hours left as offered (open everywhere), the grid below sets them. The
 * creation lands on the new stand's fiche.
 */
async function creerStand(page: Page, code: string, nom: string): Promise<void> {
  await page.goto('/stands');
  await page.getByRole('button', { name: 'Ajouter' }).click();
  const dialog = await dialogueOuvert(page);
  await dialog.getByLabel('Nom', { exact: true }).fill(nom);
  await dialog.getByLabel('Code', { exact: true }).fill(code);
  await dialog.getByLabel('Effectif minimum').fill('1');
  await dialog.getByLabel('Effectif maximum').fill('1');
  await dialog.getByRole('button', { name: 'Suivant' }).first().click();
  await dialog.getByRole('option', { name: 'Jeux E2E' }).click();
  await dialog.getByRole('button', { name: 'Créer le stand' }).click();
  await expect(dialog).toBeHidden();
  await expect(page).toHaveURL(/\/stands\/[^/?]+/);
}

async function creerAnimateur(
  page: Page,
  email: string,
  prenom: string,
  nom: string,
): Promise<void> {
  await page.getByRole('button', { name: 'Ajouter' }).click();
  const dialog = await dialogueOuvert(page);
  await dialog.getByLabel('Prénom').fill(prenom);
  await dialog.getByLabel('Nom', { exact: true }).fill(nom);
  await dialog.getByLabel('Date de naissance').fill('1990-01-01');
  await dialog.getByLabel('E-mail').fill(email);
  await dialog.getByRole('button', { name: "Créer l'animateur" }).click();
  await expect(dialog).toBeHidden();
}

test('an edition entered end to end, solved, and read back', async ({ browser }) => {
  test.slow();
  const page = await pageAdmin(browser, admin);

  // 1. L'édition.
  await page.goto('/editions');
  // By placeholder: every listed edition also carries a « Nom » field, the
  // inline rename, and the list may land before or after this line.
  await page.getByPlaceholder('Année 2026').fill(EDITION_NOM);
  await page.getByRole('button', { name: 'Ajouter' }).click();
  const ligneEdition = page.getByRole('row', { name: new RegExp(EDITION_NOM) });
  await expect(ligneEdition).toBeVisible();
  const editionId = await editionCreee();
  expect(editionId, 'the edition must have been created').toBeTruthy();
  DANS_EDITION = { headers: { 'X-Edition-Id': editionId as string } };
  const rechargement = page.waitForEvent('load');
  await ligneEdition.getByRole('button', { name: 'Travailler dans cette édition' }).click();
  await rechargement;
  await expect(page.locator('body')).toContainText(EDITION_NOM);

  // 2. Typologies et emplacements.
  await page.goto('/stands?onglet=typologies');
  await page.getByRole('button', { name: 'Ajouter' }).click();
  let dialog = await dialogueOuvert(page);
  await dialog.getByLabel('Libellé').fill('Jeux E2E');
  await dialog.getByLabel('Code', { exact: true }).fill(TYPOLOGIE);
  await dialog.getByRole('button', { name: /Créer/ }).click();
  await expect(dialog).toBeHidden();
  await expect(page.getByRole('row', { name: /Jeux E2E/ })).toBeVisible();

  // Les lieux sont l'onglet « Lieux » de la page Stands.
  await page.goto('/stands?onglet=lieux');
  await page.getByRole('button', { name: 'Ajouter' }).click();
  dialog = await dialogueOuvert(page);
  await dialog.getByLabel('Nom', { exact: true }).fill('Pavillon E2E');
  await dialog.getByLabel('Code', { exact: true }).fill('E2E-JT-PAV');
  await dialog.getByRole('button', { name: /Créer/ }).click();
  await expect(dialog).toBeHidden();

  // 3. Les gabarits et leur calendrier : c'est là que l'édition prend ses dates.
  await page.goto('/creneaux');
  await creerGabarit(page, 'Jour normal', '09:00-12:00, 12:00-13:00, 13:00-14:00, 14:00-18:00');
  // La nocturne : ses vacations se coupent à 19 h, l'ouverture de la fenêtre
  // repas du soir — une vacation 18-22 d'un bloc ne laisserait à personne
  // l'heure de coupure que la règle exige.
  await creerGabarit(page, 'Nocturne', '14:00-19:00, 19:00-23:00');
  await expect(page.locator('.gabarit-item')).toHaveCount(2);
  await expect(page.locator('.vacation-chip')).toHaveCount(6);

  await affecterDates(page, JOUR1, JOUR2, 'Jour normal', 2);
  await expect(page.locator('.gabarits-table tbody tr')).toHaveCount(2);
  await affecterDates(page, JOUR3, JOUR3, 'Nocturne', 1);
  await expect(page.locator('.gabarits-table tbody tr')).toHaveCount(3);
  await expect(page.locator('.gabarits-bornes')).toContainText(`du ${JOUR1} au ${JOUR3}`);
  await expect(page.locator('.journee-ecart')).toHaveCount(3);

  await page.getByRole('button', { name: 'Appliquer le calendrier' }).click();
  dialog = await dialogueOuvert(page);
  await expect(dialog).toContainText('10');
  await expect(dialog).toContainText('créneau(x) créé(s)');
  await dialog.getByRole('button', { name: 'Appliquer' }).click();
  await expect(dialog).toBeHidden();
  await expect(page.getByText(/Calendrier appliqué : 10 créneau\(x\) créé\(s\)/)).toBeVisible();
  await expect(page.locator('.creneaux-liste-card h2')).toContainText('Créneaux (10)');
  await expect(page.locator('.journee-conforme')).toHaveCount(3);

  // 4. Les stands, puis leurs plages d'ouverture, posées par l'API comme la
  // fiche les poserait : stand deux le matin seulement, stand trois l'après-midi
  // seulement et fermé le premier jour.
  for (const stand of STANDS) {
    await creerStand(page, stand.code, stand.nom);
  }
  const stands = (await (await admin.get('/api/stands', DANS_EDITION)).json()) as {
    id: string;
    code: string | null;
  }[];
  const poser = async (
    code: string,
    date: string,
    mode: 'OUVERTURE' | 'FERMETURE',
    heureDebut: string,
    heureFin: string | null,
  ) => {
    const stand = stands.find((each) => each.code === code)?.id;
    expect(stand, `stand ${code}`).toBeTruthy();
    const reponse = await admin.post(`/api/stands/${stand}/plages`, {
      ...DANS_EDITION,
      data: { id: null, mode, date, heureDebut, heureFin, motif: null, effectif: null },
    });
    expect(reponse.ok(), `plage ${code} ${date}`).toBeTruthy();
  };
  await poser('E2E-JT-S2', JOUR1, 'OUVERTURE', '09:00', '12:00');
  await poser('E2E-JT-S2', JOUR2, 'OUVERTURE', '09:00', '12:00');
  await poser('E2E-JT-S2', JOUR3, 'FERMETURE', '00:00', null);
  await poser('E2E-JT-S3', JOUR1, 'FERMETURE', '00:00', null);
  await poser('E2E-JT-S3', JOUR2, 'OUVERTURE', '14:00', '18:00');
  await poser('E2E-JT-S3', JOUR3, 'OUVERTURE', '14:00', '19:00');
  // Une heure qu'aucune vacation ne couvre : enregistrée, et signalée sans effet.
  await poser('E2E-JT-S3', JOUR3, 'OUVERTURE', '07:00', '08:00');

  // 5. L'accueil compte la fenêtre sans effet, et les anciennes adresses des
  // horaires mènent à la fiche du stand.
  await page.goto('/');
  await expect(page.locator('li[data-ligne="ouvertures"]')).toContainText('hors de toute vacation');
  await page.goto('/creneaux?onglet=horaires&vue=saisie');
  await expect(page).toHaveURL(/\/stands$/);

  // 6. Les animateurs.
  await page.goto('/animateurs');
  for (const animateur of ANIMATEURS) {
    await creerAnimateur(page, animateur.email, animateur.prenom, animateur.nom);
  }
  await expect(page.getByRole('row', { name: /Journee/ })).toHaveCount(3);
  // Their levels go in the skills grid, the only place they are entered: the
  // edition has one game category, so each row has one cell, autonome (2).
  await page.goto('/animateurs?onglet=competences');
  await page.getByLabel('Filtrer par nom').fill('Journee');
  await page.getByLabel('Filtrer par nom').press('ArrowDown');
  for (const _animateur of ANIMATEURS) {
    await page.keyboard.press('2');
    await page.keyboard.press('Enter');
  }
  await page.getByRole('button', { name: /Enregistrer/ }).click();
  await expect(page.getByText('Compétences enregistrées')).toBeVisible();

  // 7. Un solve court, depuis Planning › Calculer.
  await page.goto(PAGE_CALCULER);
  await calculerLePlanning(page);
  await expect
    .poll(async () => (await page.request.get('/api/jobs/active')).status(), { timeout: 15_000 })
    .toBe(200);
  await expect
    .poll(async () => (await page.request.get('/api/jobs/active')).status(), { timeout: 90_000 })
    .toBe(204);

  const planning = (await (
    await page.request.get('/api/planning/persisted', DANS_EDITION)
  ).json()) as {
    postes: { stand?: { id: string }; animateur: unknown }[];
  };
  // 4 + 1 sièges le jour 1, 4 + 1 + 1 le jour 2, 2 + 1 la nocturne.
  expect(planning.postes).toHaveLength(14);
  expect(planning.postes.every((poste) => poste.animateur !== null)).toBe(true);

  // 8. Le planning relu : l'accueil, la page Problèmes, l'axe du temps.
  await page.goto('/');
  await expect(page.locator('li[data-ligne="resolution"]')).toContainText('Résolue le');
  await expect(page.locator('li[data-ligne="referentiels"]')).toContainText(
    '3 stands · 3 animateurs · 10 créneaux',
  );
  await page.goto('/diagnostic?onglet=problemes');
  await expect(page.getByRole('heading', { name: 'Diagnostic' })).toBeVisible();
  await expect(page.locator('app-problemes-page')).toBeVisible();
  await expect(page.locator('body')).not.toContainText('bloquant(s)');
  await page.evaluate(() => localStorage.removeItem('planning-equipes.editionId'));
  await page.context().close();
});
