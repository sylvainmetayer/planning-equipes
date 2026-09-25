// Une édition saisie de bout en bout depuis l'écran, dans l'ordre de la prise
// en main (ADR 0032) : l'édition, ses typologies et emplacements, ses journées
// types posées sur un calendrier puis appliquées, ses stands et leur effectif
// dans la grille des ouvertures — dont un stand aux heures particulières —,
// ses animateurs, un solve court réel, et le planning relu sur l'axe du temps
// et la page Problèmes. Trois jours, trois stands, trois animateurs : le plus
// petit jeu qui passe par toutes les capacités de l'outil.

import { APIRequestContext, Page, expect, test } from '@playwright/test';
import {
  choisirOption,
  contexteAdmin,
  shiftDate,
  dialogueOuvert,
  ouvrirSelect,
  pageAdmin,
} from './support';
import { repartirDeLaReference } from './reference';

/**
 * The edition is found by its name: its id (`E2`, `E3`…) is drawn by the
 * application, like every id below. What the spec types is a name or a code.
 */
const EDITION_NOM = 'E2E Journées types';
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

async function creerJourneeType(page: Page, nom: string, vacations: string): Promise<void> {
  await page.getByRole('button', { name: 'Nouvelle journée type' }).click();
  const dialog = await dialogueOuvert(page);
  await dialog.getByLabel('Nom').fill(nom);
  await dialog.getByLabel('Vacations de la journée').fill(vacations);
  await dialog.getByRole('button', { name: 'Créer la journée type' }).click();
  await expect(dialog).toBeHidden();
}

async function affecterDates(
  page: Page,
  du: string,
  au: string,
  journeeType: string,
  attendu: number,
) {
  const ajout = page.locator('.journees-types-ajout');
  await ajout.getByLabel('Du', { exact: true }).fill(du);
  await ajout.getByLabel('Au', { exact: true }).fill(au);
  await ajout.locator('mat-form-field').filter({ hasText: 'Journée type' }).click();
  await page.getByRole('option', { name: journeeType }).click();
  await page.getByRole('button', { name: `Affecter ${attendu} date(s)` }).click();
}

async function creerStand(page: Page, code: string, nom: string): Promise<void> {
  await page.getByRole('button', { name: 'Ajouter' }).click();
  const dialog = await dialogueOuvert(page);
  await dialog.getByLabel('Nom', { exact: true }).fill(nom);
  await dialog.getByLabel('Code', { exact: true }).fill(code);
  await dialog.getByLabel('Effectif minimum').fill('1');
  await dialog.getByLabel('Effectif maximum').fill('1');
  await ouvrirSelect(dialog, 'Typologies de jeu');
  await page.getByRole('option', { name: 'Jeux E2E' }).click();
  await page.keyboard.press('Escape');
  await expect(page.locator('.cdk-overlay-transparent-backdrop')).toHaveCount(0);
  await dialog.getByRole('button', { name: 'Créer le stand' }).click();
  await expect(dialog).toBeHidden();
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
  await dialog.getByRole('button', { name: 'Ajouter une appréciation' }).click();
  await choisirOption(dialog, 'Typologie', 'Jeux E2E');
  await choisirOption(dialog, 'Niveau', 'AUTONOME');
  await dialog.getByRole('button', { name: "Créer l'animateur" }).click();
  await expect(dialog).toBeHidden();
}

/**
 * L'effectif du stand un sur les deux matins de « Jour normal » : ce qu'une
 * seule case de la grille par journée type doit avoir écrit sur les deux dates.
 */
async function effectifsDesMatins(): Promise<(number | null)[]> {
  const reponse = await admin.get('/api/ouvertures-stands', DANS_EDITION);
  expect(reponse.ok()).toBeTruthy();
  const rapport = (await reponse.json()) as {
    jours: { date: string; creneaux: { id: number; heureDebut: string }[] }[];
    stands: {
      standId: string;
      jours: { date: string; creneaux: { creneauId: number; effectif: number | null }[] }[];
    }[];
  };
  const matins = new Set(
    rapport.jours
      .filter((jour) => jour.date === JOUR1 || jour.date === JOUR2)
      .flatMap((jour) =>
        jour.creneaux.filter((creneau) => creneau.heureDebut.startsWith('09:')).map((c) => c.id),
      ),
  );
  expect(matins.size).toBe(2);
  const stands = (await (await admin.get('/api/stands', DANS_EDITION)).json()) as {
    id: string;
    code: string | null;
  }[];
  const standUnId = stands.find((stand) => stand.code === STANDS[0].code)?.id;
  const standUn = rapport.stands.find((ligne) => ligne.standId === standUnId)!;
  return standUn.jours
    .flatMap((jour) => jour.creneaux)
    .filter((cellule) => matins.has(cellule.creneauId))
    .map((cellule) => cellule.effectif);
}

/** Une case de la grille de saisie, désignée comme son aria-label la nomme : « Stand · JJ/MM colonne ». */
function cellule(page: Page, stand: string, jour: string, colonne: string) {
  const [, mois, jj] = jour.split('-');
  return page.getByLabel(`${stand} · ${jj}/${mois} ${colonne}`);
}

test('une édition saisie de bout en bout, résolue, et relue sur l’axe du temps', async ({
  browser,
}) => {
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
  await page.goto('/typologies');
  await page.getByRole('button', { name: 'Ajouter' }).click();
  let dialog = await dialogueOuvert(page);
  await dialog.getByLabel('Libellé').fill('Jeux E2E');
  await dialog.getByLabel('Code', { exact: true }).fill(TYPOLOGIE);
  await dialog.getByRole('button', { name: /Créer/ }).click();
  await expect(dialog).toBeHidden();
  await expect(page.getByRole('row', { name: /Jeux E2E/ })).toBeVisible();

  await page.goto('/emplacements');
  await page.getByRole('button', { name: 'Ajouter' }).click();
  dialog = await dialogueOuvert(page);
  await dialog.getByLabel('Nom', { exact: true }).fill('Pavillon E2E');
  await dialog.getByLabel('Code', { exact: true }).fill('E2E-JT-PAV');
  await dialog.getByRole('button', { name: /Créer/ }).click();
  await expect(dialog).toBeHidden();

  // 3. Les journées types et leur calendrier : c'est là que l'édition prend ses dates.
  await page.goto('/creneaux');
  await creerJourneeType(
    page,
    'Jour normal',
    '09:00-12:00, 12:00-13:00 R, 13:00-14:00 R, 14:00-18:00',
  );
  // La nocturne : ses vacations se coupent à 19 h, l'ouverture de la fenêtre
  // repas du soir — une vacation 18-22 d'un bloc ne laisserait à personne
  // l'heure de coupure que la règle exige.
  await creerJourneeType(page, 'Nocturne', '14:00-19:00, 19:00-23:00');
  await expect(page.locator('.journee-type-item')).toHaveCount(2);
  await expect(page.locator('.vacation-chip-relais')).toHaveCount(2);

  await affecterDates(page, JOUR1, JOUR2, 'Jour normal', 2);
  await expect(page.locator('.journees-types-table tbody tr')).toHaveCount(2);
  await affecterDates(page, JOUR3, JOUR3, 'Nocturne', 1);
  await expect(page.locator('.journees-types-table tbody tr')).toHaveCount(3);
  await expect(page.locator('.journees-types-bornes')).toContainText(`du ${JOUR1} au ${JOUR3}`);
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
  // Le relais repas se lit sur la liste des créneaux.
  await expect(page.locator('.creneaux-table .relais-repas-icon')).toHaveCount(4);

  // 4. Les stands, puis leur effectif par créneau dans la grille des ouvertures.
  await page.goto('/stands');
  for (const stand of STANDS) {
    await creerStand(page, stand.code, stand.nom);
  }

  await page.goto('/ouvertures?vue=saisie');
  await expect(page.locator('.grille-saisie')).toBeVisible();
  // Stand deux : le matin seulement. Stand trois : l'après-midi seulement, et
  // fermé le premier jour.
  for (const jour of [JOUR1, JOUR2]) {
    for (const colonne of ['12-13', '13-14', '14-18']) {
      await cellule(page, 'Stand deux E2E', jour, colonne).fill('-');
    }
  }
  for (const colonne of ['14-19', '19-23']) {
    await cellule(page, 'Stand deux E2E', JOUR3, colonne).fill('-');
  }
  for (const colonne of ['09-12', '12-13', '13-14', '14-18']) {
    await cellule(page, 'Stand trois E2E', JOUR1, colonne).fill('-');
  }
  for (const colonne of ['09-12', '12-13', '13-14']) {
    await cellule(page, 'Stand trois E2E', JOUR2, colonne).fill('-');
  }
  await cellule(page, 'Stand trois E2E', JOUR3, '19-23').fill('-');
  await page.getByRole('button', { name: /^Enregistrer/ }).click();
  await expect(page.getByText(/enregistré/i).last()).toBeVisible();

  // 4 bis. La même grille lue par journée type (ADR 0033) : six colonnes au
  // lieu de dix, et une case qui vaut pour les deux jours normaux d'un coup.
  await page.goto('/ouvertures?vue=journees-types');
  await expect(page.locator('.grille-journees-types')).toBeVisible();
  // Six colonnes — quatre vacations de « Jour normal », deux de « Nocturne » —
  // là où la grille par date en aligne dix.
  await expect(page.locator('.grille-journees-types .entete-creneau')).toHaveCount(6);
  const matinJourNormal = page.getByLabel('Stand un E2E · Jour normal 09:00-12:00');
  const effectifInitial = await matinJourNormal.inputValue();

  await matinJourNormal.fill('2');
  await page.getByRole('button', { name: /^Enregistrer/ }).click();
  await expect(page.getByText(/enregistré/i).last()).toBeVisible();
  await expect.poll(effectifsDesMatins).toEqual([2, 2]);

  // Et retour : la même case rend les deux jours à leur effectif de départ, pour
  // que la suite de la spec lise l'édition qu'elle a saisie.
  await matinJourNormal.fill(effectifInitial);
  await page.getByRole('button', { name: /^Enregistrer/ }).click();
  await expect(page.getByText(/enregistré/i).last()).toBeVisible();
  await expect.poll(effectifsDesMatins).toEqual([Number(effectifInitial), Number(effectifInitial)]);

  // Un stand aux heures particulières, déclaré après la grille — une case ne
  // sait pas dire 07:00-08:00, et enregistrer la grille réécrit tout l'horaire
  // du stand : ouvert 07:00-08:00 le soir de nocturne, une heure qu'aucune
  // vacation ne couvre. Sur un jour où la grille a écrit une ouverture (l'après-
  // midi), une ouverture de plus est permise ; sur le premier jour, fermé en
  // entier, elle serait refusée — un jour ne porte pas les deux modes.
  // Enregistrée, et signalée sans effet.
  await page.goto('/stands');
  await page.getByLabel('Filtrer').fill('E2E-JT-S3');
  await page
    .getByRole('row', { name: /E2E-JT-S3/ })
    .getByRole('button', { name: 'Modifier' })
    .click();
  const fiche = await dialogueOuvert(page);
  await fiche.getByRole('button', { name: 'Ajouter une ouverture' }).click();
  // Le fieldset propre aux ouvertures : celui dont c'est la légende, pas le
  // formulaire entier qui la contient aussi.
  const ouvertures = fiche.locator('legend', { hasText: 'Ouvertures ponctuelles' }).locator('..');
  // La grille vient d'écrire l'horaire du stand en ouvertures datées : la
  // ligne ajoutée est la dernière.
  await ouvertures.getByLabel('Date', { exact: true }).last().fill(JOUR3);
  await ouvertures.getByLabel('Début', { exact: true }).last().fill('07:00');
  await ouvertures.getByLabel('Fin (vide = fermeture)').last().fill('08:00');
  await fiche.getByRole('button', { name: 'Modifier le stand' }).click();
  await expect(fiche).toBeHidden();
  await expect(page.getByText(/ne recoupe aucun créneau/)).toBeVisible();

  // 5. La journée sur l'axe du temps : les blocs, le relais, la fenêtre hors de toute vacation.
  await page.goto('/ouvertures?vue=journee');
  await expect(page.locator('.axe-table')).toBeVisible();
  await expect(page.locator('.axe-ligne')).toHaveCount(3);
  await expect(page.locator('.axe-bande')).toHaveCount(4);
  await expect(page.locator('.axe-bande-relais')).toHaveCount(2);
  const ligneDe = (stand: string) => page.locator('.axe-ligne', { hasText: stand });
  await expect(ligneDe('Stand un E2E').locator('.axe-bloc')).toHaveCount(4);
  await expect(ligneDe('Stand un E2E').locator('.axe-bloc-relais')).toHaveCount(2);
  await expect(ligneDe('Stand deux E2E').locator('.axe-bloc')).toHaveCount(1);
  await expect(ligneDe('Stand trois E2E').locator('.axe-bloc')).toHaveCount(0);
  await expect(page.locator('.axe-hors')).toHaveCount(0);
  // Le jour suivant, le stand trois ouvre l'après-midi.
  await page.getByRole('button', { name: 'Jour suivant' }).click();
  await expect(ligneDe('Stand trois E2E').locator('.axe-bloc')).toHaveCount(1);
  // La nocturne : deux bandes, et l'ouverture de 07:00 hachurée là où elle tombe.
  await page.getByRole('button', { name: 'Jour suivant' }).click();
  await expect(page.locator('.axe-bande')).toHaveCount(2);
  await expect(page.locator('.axe-hors')).toHaveCount(1);
  await expect(ligneDe('Stand trois E2E').locator('.axe-bloc')).toHaveCount(1);
  await expect(ligneDe('Stand deux E2E').locator('.axe-bloc')).toHaveCount(0);
  await expect(page.getByRole('button', { name: 'Jour suivant' })).toBeDisabled();

  // La fiche du stand porte son anomalie, l'accueil la compte à part.
  await page.goto('/stands');
  await page.getByLabel('Filtrer').fill('E2E-JT-S3');
  await page
    .getByRole('row', { name: /E2E-JT-S3/ })
    .getByRole('button', { name: 'Consulter le détail' })
    .click();
  await expect(page.getByRole('dialog')).toContainText('Ouvertures effectives');
  await expect(page.getByRole('dialog')).toContainText('ne recoupe aucun créneau');
  await page.keyboard.press('Escape');
  await page.goto('/');
  await expect(page.locator('li[data-ligne="ouvertures"]')).toContainText('hors de toute vacation');

  // 6. Les animateurs.
  await page.goto('/animateurs');
  for (const animateur of ANIMATEURS) {
    await creerAnimateur(page, animateur.email, animateur.prenom, animateur.nom);
  }
  await expect(page.getByRole('row', { name: /Journee/ })).toHaveCount(3);

  // 7. Un solve court, depuis la page Solveur.
  const parametres = await page.request.put('/api/parametres-solveur', {
    ...DANS_EDITION,
    data: { dureeResolutionSecondes: 8 },
  });
  expect(parametres.ok(), await parametres.text()).toBe(true);
  await page.goto('/solveur');
  await page.getByRole('button', { name: 'Calculer le planning' }).click();
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
  await page.goto('/ouvertures?vue=journee');
  await expect(page.locator('.axe-ligne')).toHaveCount(3);

  await page.evaluate(() => localStorage.removeItem('planning-equipes.editionId'));
  await page.context().close();
});
