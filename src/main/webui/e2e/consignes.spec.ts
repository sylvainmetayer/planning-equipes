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
  dialogueOuvert,
  ouvrirSelect,
  pageAdmin,
  seedPlanning,
} from './support';
import { repartirDeLaReference } from './reference';

let admin: APIRequestContext;

/** A créneau of the reserved 987xxx range, on a day strictly ahead of the real clock. */
const CRENEAU_A_VENIR = 987020;
const MOTIF = 'Arrêté canicule E2E';

/** Thirty days ahead of the real clock, as `AAAA-MM-JJ` — the seeded 2026-07-10 is behind it. */
function dateAVenir(): string {
  const date = new Date();
  date.setDate(date.getDate() + 30);
  return [
    date.getFullYear(),
    String(date.getMonth() + 1).padStart(2, '0'),
    String(date.getDate()).padStart(2, '0'),
  ].join('-');
}

/** `Vendredi 10/07`: the date as the page's selectors and table say it. */
function libelleDate(date: string): string {
  const jours = ['Dimanche', 'Lundi', 'Mardi', 'Mercredi', 'Jeudi', 'Vendredi', 'Samedi'];
  const [annee, mois, jour] = date.split('-').map(Number);
  return `${jours[new Date(annee, mois - 1, jour).getDay()]} ${String(jour).padStart(2, '0')}/${String(mois).padStart(2, '0')}`;
}

const DATE = dateAVenir();

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
  // The seeded grid lives in 2026-07, behind the real clock: a day ahead of it
  // is added, open all day on both seeded stands (no schedule = open on every créneau).
  await sql(
    `delete from poste_affectation where creneau_id = ${CRENEAU_A_VENIR};\n` +
      `delete from creneau where id = ${CRENEAU_A_VENIR};\n` +
      `insert into creneau (edition_id, id, date_creneau, heure_debut, heure_fin) ` +
      `values ('DEFAUT', ${CRENEAU_A_VENIR}, '${DATE}', '10:00', '20:00');`,
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

  // Only the day ahead is offered: the seeded 2026-07-10 has begun long ago.
  await ouvrirSelect(page, 'Dates');
  await expect(page.getByRole('option', { name: /10\/07/ })).toHaveCount(0);
  await page.getByRole('option', { name: libelleDate(DATE) }).click();
  await page.keyboard.press('Escape');

  await dialog.getByLabel('Motif').fill(MOTIF);
  // One default window, the evening: what the ticked stands reopen on.
  await dialog.getByRole('button', { name: 'Ajouter une fenêtre' }).first().click();
  const fenetre = dialog.locator('.consigne-form-fenetres .consigne-fenetre').first();
  await fenetre.locator('input[type="time"]').nth(0).fill('18:00');
  await fenetre.locator('input[type="time"]').nth(1).fill('22:00');

  // The stands, read from the server for the date and the band 12h–18h:
  // both seeded stands lose the whole band and arrive ticked.
  const stands = dialog.locator('.consigne-stand');
  await expect(stands).toHaveCount(2);
  await expect(dialog).toContainText('Stand E2E un');
  await expect(dialog).toContainText('360 min perdues');
  await expect(dialog).toContainText('2 coché(s) sur 2');

  await dialog.getByRole('button', { name: 'Aperçu' }).click();
  await expect(dialog).toContainText('Sièges :');
  await expect(dialog).toContainText('2 stand(s) rouvert(s)');

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
  expect(consigne?.ouvertures.map((ouverture) => ouverture.standId).sort()).toEqual([
    SEED.standCible,
    SEED.standDemandeur,
  ]);
  expect(consigne?.ouvertures[0].debut).toBe('18:00:00');

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
  await expect(levee).toContainText(libelleDate(DATE));
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
  await form.getByRole('button', { name: 'Annuler' }).click();

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
  await expect(dialog.locator('mat-select[name="dates"]')).toContainText(libelleDate(DATE));
  // Obeyed once: the address no longer asks for a new consigne.
  await expect(page).not.toHaveURL(/nouvelle=/);
  await expect(page).toHaveURL(new RegExp(`date=${DATE}`));

  await page.context().close();
});
