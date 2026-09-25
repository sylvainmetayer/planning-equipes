// A consigne through a browser (issue #4): laid down from the form on a day to
// come, with its preview, its stands read from the server and its default
// window; shown as such on the Créneaux page; then lifted. Plus the presets
// and the deep link the Journée page uses.
//
// What a unit test cannot see is exactly this: that the dates offered are the
// grid's days still ahead of the REAL clock (the packaged application freezes
// no date), and that the same request the preview read is the one written.

import { APIRequestContext, expect, test } from '@playwright/test';
import {
  SEED,
  contexteAdmin,
  daysFromToday,
  dialogueOuvert,
  dayLabel,
  ouvrirSelect,
  pageAdmin,
  seedPlanning,
  EDITION_REFERENCE,
} from './support';
import { repartirDeLaReference } from './reference';

let admin: APIRequestContext;

/** A créneau of the reserved 987xxx range, on a day strictly ahead of the real clock. */
const CRENEAU_A_VENIR = 987020;
/** And one on a day behind it: the form must not offer that day. */
const CRENEAU_PASSE = 987021;
const MOTIF = 'Arrêté canicule E2E';

const DATE = daysFromToday(30);
const DATE_PASSEE = daysFromToday(-30);

async function sql(script: string): Promise<void> {
  const reponse = await admin.post('/api/database/import', {
    headers: { 'Content-Type': 'text/plain' },
    data: script,
  });
  expect(reponse.ok(), await reponse.text()).toBe(true);
}

/** Lifts whatever the spec laid on the day ahead, so the next test starts clean. */
async function leverTout(): Promise<void> {
  const etat = (await (await admin.get('/api/consignes')).json()) as {
    consignes: { date: string }[];
    prereglages: { id: string; nom: string }[];
  };
  const dates = etat.consignes.map((consigne) => consigne.date).filter((date) => date === DATE);
  if (dates.length > 0) {
    await admin.post('/api/consignes/levee', { data: { dates } });
  }
  for (const prereglage of etat.prereglages.filter((each) => each.nom.includes('E2E'))) {
    await admin.delete(`/api/consignes/prereglages/${prereglage.id}`);
  }
}

test.beforeAll(async ({ playwright }, testInfo) => {
  admin = await contexteAdmin(playwright, testInfo.project.use.baseURL as string);
  await repartirDeLaReference(admin);
  await seedPlanning(admin);
  // A day thirty days ahead, open all day on both seeded stands (no schedule =
  // open on every créneau), and one thirty days behind, already begun.
  await sql(
    `delete from poste_affectation where creneau_id in (${CRENEAU_A_VENIR}, ${CRENEAU_PASSE});\n` +
      `delete from creneau where id in (${CRENEAU_A_VENIR}, ${CRENEAU_PASSE});\n` +
      `insert into creneau (edition_id, id, date_creneau, heure_debut, heure_fin) ` +
      `values ('${EDITION_REFERENCE}', ${CRENEAU_A_VENIR}, '${DATE}', '10:00', '20:00');\n` +
      `insert into creneau (edition_id, id, date_creneau, heure_debut, heure_fin) ` +
      `values ('${EDITION_REFERENCE}', ${CRENEAU_PASSE}, '${DATE_PASSEE}', '10:00', '20:00');`,
  );
});

test.afterEach(async () => {
  await leverTout();
});

test.afterAll(async () => {
  await admin.dispose();
});

test('poser une consigne sur un jour à venir, la voir sur la grille, la lever', async ({
  browser,
}) => {
  test.slow();
  const page = await pageAdmin(browser, admin);
  await page.goto('/consignes');
  await expect(page.locator('#contenu')).toContainText('Aucune journée sous consigne');

  await page.getByRole('button', { name: 'Poser une consigne' }).click();
  const dialog = await dialogueOuvert(page);
  await expect(dialog).toContainText('Poser une consigne');

  // Only the days ahead are offered: the one a month behind has begun long ago.
  await ouvrirSelect(page, 'Dates');
  await expect(page.getByRole('option', { name: dayLabel(DATE_PASSEE) })).toHaveCount(0);
  await page.getByRole('option', { name: dayLabel(DATE) }).click();
  await page.keyboard.press('Escape');

  await dialog.getByLabel('Motif').fill(MOTIF);
  // One default window, the evening: what the ticked stands reopen on.
  await dialog.getByRole('button', { name: 'Ajouter une fenêtre' }).first().click();
  const fenetre = dialog.locator('.consigne-form-fenetres .consigne-fenetre').first();
  await fenetre.locator('input[type="time"]').nth(0).fill('18:00');
  await fenetre.locator('input[type="time"]').nth(1).fill('22:00');

  // The stands, read from the server for the date and the band 12h–18h: the
  // whole edition's — the reference base carries stands beside the two seeded
  // ones — with the seeded ones, open all day, losing the whole band and
  // arriving ticked. What the screen lists is what the API pre-selects.
  const preselection = (await (
    await admin.post('/api/consignes/preselection', {
      data: { date: DATE, fermetureDebut: '12:00', fermetureFin: '18:00' },
    })
  ).json()) as { stands: { standId: string; preCoche: boolean }[] };
  const coches = preselection.stands
    .filter((stand) => stand.preCoche)
    .map((stand) => stand.standId);
  expect(coches).toEqual(expect.arrayContaining([SEED.standDemandeur, SEED.standCible]));
  const stands = dialog.locator('.consigne-stand');
  await expect(stands).toHaveCount(preselection.stands.length);
  await expect(dialog).toContainText('Stand E2E un');
  await expect(dialog).toContainText('360 min perdues');
  await expect(dialog).toContainText(`${coches.length} coché(s) sur ${preselection.stands.length}`);

  await dialog.getByRole('button', { name: 'Aperçu' }).click();
  await expect(dialog).toContainText('Sièges :');
  await expect(dialog).toContainText(`${coches.length} stand(s) rouvert(s)`);

  await dialog.getByRole('button', { name: 'Enregistrer' }).click();
  await expect(page.getByText('Consigne enregistrée sur 1 date(s).')).toBeVisible();

  const ligne = page.locator(`tr[data-date="${DATE}"]`);
  await expect(ligne).toContainText(MOTIF);
  await expect(ligne).toContainText('12h–18h');
  await expect(ligne).toContainText('à venir');

  // What was written is what the preview read: one opening per stand, on the window typed.
  const etat = (await (await admin.get('/api/consignes')).json()) as {
    consignes: { date: string; motif: string; ouvertures: { standId: string; debut: string }[] }[];
  };
  const consigne = etat.consignes.find((each) => each.date === DATE);
  expect(consigne?.motif).toBe(MOTIF);
  expect(
    consigne?.ouvertures.map((ouverture) => ouverture.standId).sort((a, b) => a.localeCompare(b)),
  ).toEqual([...coches].sort((a, b) => a.localeCompare(b)));
  expect(consigne?.ouvertures.every((ouverture) => ouverture.debut === '18:00:00')).toBe(true);

  // The grid says which date is under consigne.
  await page.goto('/creneaux');
  await expect(page.getByRole('row', { name: new RegExp(DATE) }).first()).toContainText(
    'sous consigne',
  );

  // Lifted from the row, after its preview.
  await page.goto('/consignes');
  await page.locator(`tr[data-date="${DATE}"]`).getByRole('button', { name: 'Lever' }).click();
  const levee = await dialogueOuvert(page);
  await levee.getByRole('button', { name: 'Aperçu' }).click();
  await expect(levee).toContainText(dayLabel(DATE));
  await levee.getByRole('button', { name: 'Lever', exact: true }).click();
  await expect(page.getByText('Consigne levée.')).toBeVisible();
  await expect(page.locator(`tr[data-date="${DATE}"]`)).toHaveCount(0);

  await page.context().close();
});

test('un préréglage se crée, remplit le formulaire, et se supprime', async ({ browser }) => {
  const page = await pageAdmin(browser, admin);
  await page.goto('/consignes');

  await page.getByRole('button', { name: 'Nouveau préréglage' }).click();
  const dialog = await dialogueOuvert(page);
  await dialog.getByLabel('Nom').fill('Plan canicule E2E');
  await dialog.getByLabel('Motif').fill('Canicule');
  await dialog.getByLabel('Fenêtres de compensation par défaut').fill('18:00-22:00');
  await dialog.getByRole('button', { name: 'Créer le préréglage' }).click();

  const carte = page.locator('.consignes-prereglages');
  await expect(carte).toContainText('Plan canicule E2E');
  await expect(carte).toContainText('12h–18h');
  await expect(carte).toContainText('18h–22h');

  // Picking it in the form fills the band, the motif and the windows.
  await page.getByRole('button', { name: 'Poser une consigne' }).click();
  const form = await dialogueOuvert(page);
  await ouvrirSelect(page, 'Préréglage');
  await page.getByRole('option', { name: 'Plan canicule E2E' }).click();
  await expect(form.getByLabel('Motif')).toHaveValue('Canicule');
  await expect(form.locator('.consigne-form-fenetres .consigne-fenetre')).toHaveCount(1);
  // Picking a preset modified the form: leaving it asks before throwing the
  // entry away, and « Abandonner » drops its draft too.
  await form.getByRole('button', { name: 'Annuler' }).click();
  const abandon = page.getByRole('dialog').filter({ hasText: 'Abandonner les modifications ?' });
  await abandon.getByRole('button', { name: 'Abandonner' }).click();
  await expect(page.getByRole('dialog')).toHaveCount(0);

  await carte.getByRole('button', { name: 'Supprimer' }).click();
  await page.getByRole('dialog').getByRole('button', { name: 'Supprimer' }).click();
  await expect(carte).toContainText('Aucun préréglage');

  await page.context().close();
});

test('le lien profond de la Journée ouvre le formulaire sur la date demandée', async ({
  browser,
}) => {
  const page = await pageAdmin(browser, admin);
  await page.goto(`/consignes?date=${DATE}&nouvelle=1`);

  const dialog = await dialogueOuvert(page);
  await expect(dialog).toContainText('Poser une consigne');
  await expect(dialog.locator('mat-select[name="dates"]')).toContainText(dayLabel(DATE));
  // Obeyed once: the address no longer asks for a new consigne.
  await expect(page).not.toHaveURL(/nouvelle=/);
  await expect(page).toHaveURL(new RegExp(`date=${DATE}`));

  await page.context().close();
});
