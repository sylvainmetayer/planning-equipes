// The day map (issue #306) over a persisted plan: the day selector, the time
// cursor, and the fact that the state of the stands really changes when the
// cursor moves — which no unit test on the builders can prove, since it is the
// binding between the slider and the map that carries the whole screen.
//
// The fixture is its own: `seedPlanning` puts both its stands on the same
// two-hour créneau and geolocates neither, so it would show one closed map and
// prove nothing. Here two locations, two opening windows and a seat left empty
// give the three states the screen exists to tell apart.

import { APIRequestContext, Page, expect, test } from '@playwright/test';
import { contexteAdmin, shiftDate, pageAdmin, EDITION_REFERENCE, typologieSql } from './support';
import { repartirDeLaReference } from './reference';

const SEED = {
  matin: 'CJ-S-MATIN',
  soir: 'CJ-S-SOIR',
  nomade: 'CJ-S-NOMADE',
  animateur: 'CJ-A1',
  // 987500-987501: a free sub-range of the reserved 987xxx block. 987301+ are
  // taken by `solve-degradation` and `referentiel-pendant-solve`, the latter on
  // this very date — sharing them only holds while Playwright runs one worker.
  creneauMatin: 987500,
  creneauSoir: 987501,
  lieuMatin: 'CJ-L-MATIN',
  lieuSoir: 'CJ-L-SOIR',
  jour: shiftDate('2026-07-20'),
} as const;

let admin: APIRequestContext;

/** Everything this fixture owns, removed — replayed before and after the suite. */
function nettoyage(): string {
  return [
    `delete from poste_affectation where stand_id like 'CJ-%' or animateur_id like 'CJ-%';`,
    `delete from poste_affectation where creneau_id in (${SEED.creneauMatin}, ${SEED.creneauSoir});`,
    `delete from creneau where id in (${SEED.creneauMatin}, ${SEED.creneauSoir});`,
    `delete from animateur where id like 'CJ-%';`,
    `delete from stand_typologie where stand_id like 'CJ-%';`,
    `delete from stand where id like 'CJ-%';`,
    `delete from emplacement where id like 'CJ-%';`,
  ].join('\n');
}

/**
 * Two located stands opening at different hours, plus one tied to no
 * emplacement at all: the morning stand is fully staffed, the evening one is
 * open with nobody, and the third is never on the map.
 */
async function amorcer(): Promise<void> {
  const script = [
    nettoyage(),
    `insert into emplacement (edition_id, id, nom, latitude, longitude) values ('${EDITION_REFERENCE}', '${SEED.lieuMatin}', 'Halle du matin', 46.6513, 2.2492);`,
    `insert into emplacement (edition_id, id, nom, latitude, longitude) values ('${EDITION_REFERENCE}', '${SEED.lieuSoir}', 'Halle du soir', 46.6490, 2.2547);`,
    `insert into stand (edition_id, id, nom, effectif_min, effectif_max, reserve_majeurs, emplacement_id) values ('${EDITION_REFERENCE}', '${SEED.matin}', 'Stand du matin', 1, 1, false, '${SEED.lieuMatin}');`,
    `insert into stand (edition_id, id, nom, effectif_min, effectif_max, reserve_majeurs, emplacement_id) values ('${EDITION_REFERENCE}', '${SEED.soir}', 'Stand du soir', 1, 1, false, '${SEED.lieuSoir}');`,
    `insert into stand (edition_id, id, nom, effectif_min, effectif_max, reserve_majeurs) values ('${EDITION_REFERENCE}', '${SEED.nomade}', 'Stand nomade', 1, 1, false);`,
    `insert into stand_typologie (edition_id, stand_id, typologie) values ('${EDITION_REFERENCE}', '${SEED.matin}', ${typologieSql('STRATEGIE')});`,
    `insert into stand_typologie (edition_id, stand_id, typologie) values ('${EDITION_REFERENCE}', '${SEED.soir}', ${typologieSql('STRATEGIE')});`,
    `insert into stand_typologie (edition_id, stand_id, typologie) values ('${EDITION_REFERENCE}', '${SEED.nomade}', ${typologieSql('STRATEGIE')});`,
    `insert into animateur (edition_id, id, prenom, nom, date_naissance, manager) values ('${EDITION_REFERENCE}', '${SEED.animateur}', 'Carte', 'Jour', '1990-01-01', false);`,
    `insert into creneau (edition_id, id, date_creneau, heure_debut, heure_fin) values ('${EDITION_REFERENCE}', ${SEED.creneauMatin}, '${SEED.jour}', '09:00', '11:00');`,
    `insert into creneau (edition_id, id, date_creneau, heure_debut, heure_fin) values ('${EDITION_REFERENCE}', ${SEED.creneauSoir}, '${SEED.jour}', '17:00', '19:00');`,
    // Morning: staffed. Evening: open with nobody — the case the screen exists
    // for. Nomade: staffed but nowhere to draw it.
    `insert into poste_affectation (edition_id, id, stand_id, creneau_id, animateur_id) values ('${EDITION_REFERENCE}', 'CJ-P1', '${SEED.matin}', ${SEED.creneauMatin}, '${SEED.animateur}');`,
    `insert into poste_affectation (edition_id, id, stand_id, creneau_id, animateur_id) values ('${EDITION_REFERENCE}', 'CJ-P2', '${SEED.soir}', ${SEED.creneauSoir}, null);`,
    `insert into poste_affectation (edition_id, id, stand_id, creneau_id, animateur_id) values ('${EDITION_REFERENCE}', 'CJ-P3', '${SEED.nomade}', ${SEED.creneauMatin}, '${SEED.animateur}');`,
  ].join('\n');
  const reponse = await admin.post('/api/database/import', {
    headers: { 'Content-Type': 'text/plain' },
    data: script,
  });
  expect(reponse.ok(), await reponse.text()).toBe(true);
}

test.beforeAll(async ({ playwright }, testInfo) => {
  admin = await contexteAdmin(playwright, testInfo.project.use.baseURL as string);
  await repartirDeLaReference(admin);
  await amorcer();
});

test.afterAll(async () => {
  // This fixture is the only one carrying its own emplacements, and its
  // créneaux live in the reserved 987xxx range the solver specs wipe. Cleaning
  // up keeps a stand nobody seeded out of the next spec's problem.
  await admin.post('/api/database/import', {
    headers: { 'Content-Type': 'text/plain' },
    data: nettoyage(),
  });
  await admin.dispose();
});

/**
 * Opens the screen on *this* fixture's day. Never the default one: the
 * database is shared, and another spec's seeding may well own day 1.
 */
async function ouvrirJourDeLaFixture(page: Page): Promise<void> {
  await page.goto('/journee?vue=carte');
  await page.getByRole('combobox', { name: 'Journée' }).click();
  await page.getByRole('option', { name: new RegExp(SEED.jour) }).click();
  await expect(page.locator('[data-test="carte-jour-heure"]')).toHaveText('09:00');
}

/**
 * Moves the time cursor. The control is the native range input Material wraps,
 * and an `input` event is exactly what a drag or an arrow key produces on it.
 */
async function reglerCurseur(page: Page, minutes: number): Promise<void> {
  await page.evaluate((valeur) => {
    const entree = document.querySelector('[data-test="carte-jour-curseur"]') as HTMLInputElement;
    entree.value = String(valeur);
    entree.dispatchEvent(new Event('input', { bubbles: true }));
  }, minutes);
}

test("la carte de la journée change d'état quand le curseur se déplace", async ({ browser }) => {
  const page = await pageAdmin(browser, admin);
  const contenu = page.locator('#contenu');
  // The day opens at 09:00 — its first opening, rounded to the hour.
  await ouvrirJourDeLaFixture(page);
  await expect(contenu).toContainText('Emplacements à cette heure-là');

  // 10:00: the morning stand and the unlocated one are open and staffed, the
  // evening one is closed — but only one of the two has a place on the map.
  await reglerCurseur(page, 10 * 60);
  await expect(page.locator('[data-test="carte-jour-heure"]')).toHaveText('10:00');
  await expect(contenu).toContainText('Halle du matin');
  const compteurs = page.locator('[data-test="carte-jour-compteurs"]');
  await expect(compteurs).toContainText('2 stand(s) ouvert(s) sur 3');
  await expect(page.locator('.carte-jour-liste .carte-jour-pastille.etat-pourvu')).toHaveCount(1);

  // 18:00: the morning stand has closed and the evening one is open with nobody.
  await reglerCurseur(page, 18 * 60);
  await expect(page.locator('[data-test="carte-jour-heure"]')).toHaveText('18:00');
  await expect(compteurs).toContainText('1 stand(s) ouvert(s) sur 3');
  await expect(page.locator('.carte-jour-liste .carte-jour-pastille.etat-decouvert')).toHaveCount(
    1,
  );
  await expect(contenu).toContainText('sans personne');

  // 13:00, between the two: nothing is open anywhere. The badge of a closed
  // place says « 0 », like its tooltip and like the page's hint — not the
  // number of stands attached to it.
  await reglerCurseur(page, 13 * 60);
  await expect(compteurs).toContainText('0 stand(s) ouvert(s) sur 3');
  await expect(
    page.locator('.leaflet-marker-icon[aria-label="Halle du matin"] .carte-jour-pastille'),
  ).toHaveText('0');

  await page.context().close();
});

test('la carte dessine les emplacements et garde les stands non situés à côté', async ({
  browser,
}) => {
  const page = await pageAdmin(browser, admin);
  await ouvrirJourDeLaFixture(page);

  // One marker per emplacement — Leaflet really instantiated the map.
  await expect(page.locator('.leaflet-container')).toBeVisible();
  await expect(page.locator('.leaflet-marker-icon .carte-jour-pastille').first()).toBeVisible();

  // One tooltip per marker, not two: the Leaflet one, never a native `title`
  // stacking the same text on top of it.
  const icone = page.locator('.leaflet-marker-icon[aria-label="Halle du matin"]');
  await expect(icone).toHaveCount(1);
  await expect(icone).not.toHaveAttribute('title');

  // The stand tied to no emplacement is listed, with the reason.
  const nonSitues = page.locator('[data-test="carte-jour-non-situes"]');
  await expect(nonSitues).toContainText('Stand nomade');
  await expect(nonSitues).toContainText('rattaché à aucun emplacement');

  await page.context().close();
});

test("l'instant du curseur survit à un rafraîchissement et se réinitialise", async ({
  browser,
}) => {
  const page = await pageAdmin(browser, admin);
  await ouvrirJourDeLaFixture(page);

  await reglerCurseur(page, 18 * 60);
  await expect(page).toHaveURL(/t=1080/);

  await page.reload();
  await expect(page.locator('[data-test="carte-jour-heure"]')).toHaveText('18:00');

  await page.getByRole('button', { name: 'Réinitialiser la vue' }).click();
  await expect(page.locator('[data-test="carte-jour-heure"]')).toHaveText('09:00');
  await expect(page).not.toHaveURL(/t=/);

  await page.context().close();
});

// The « Lieu » column of the Stands table leads to the Lieux tab of the same
// page: the router keeps the page, which has to follow its address.
test('un lieu de la colonne « Lieu » ouvre l’onglet Lieux filtré sur lui, sans recharger', async ({
  browser,
}) => {
  const page = await pageAdmin(browser, admin);
  await page.goto('/stands');
  await page.getByLabel('Filtrer').fill('Stand du matin');
  await page
    .getByRole('row', { name: /Stand du matin/ })
    .getByRole('link', { name: 'Halle du matin' })
    .click();

  await expect(page).toHaveURL(/\/stands\?(.*&)?onglet=lieux/);
  await expect(page.locator('app-lieux-map .leaflet-container')).toBeVisible();
  await expect(page.getByRole('columnheader', { name: 'Stands rattachés' })).toBeVisible();
  await expect(page.locator('#contenu table tbody tr')).toHaveCount(1);
  await expect(page.locator('#contenu table tbody')).toContainText('Halle du matin');
  await page.context().close();
});
