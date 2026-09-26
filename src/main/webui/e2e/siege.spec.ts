// The Siège panel of the Journée (#711), driven through the browser: the bench
// became its « Qui peut tenir ce siège ? », and a hole is now filled from the
// screen that shows it.
//
// The bench shipped with a bug no unit test could have seen, because it lived
// in the gap between two sources: the referential holds more timeslots than
// the saved plan does, and a timeslot the plan holds no seat on opened the
// screen on « Créneau inconnu ». Its addresses still land somewhere that says
// so rather than failing; the rest pins the new gesture, « Placer ».

import { APIRequestContext, expect, Page, test } from '@playwright/test';
import {
  contexteAdmin,
  shiftDate,
  pageAdmin,
  SEED,
  seedPlanning,
  EDITION_REFERENCE,
} from './support';
import { repartirDeLaReference } from './reference';

/** A créneau of the referential that no seat of the saved plan points at. */
const CRENEAU_SANS_SIEGE = 987003;

const DATE_SANS_SIEGE = shiftDate('2026-07-12');

let admin: APIRequestContext;

test.beforeAll(async ({ playwright }, testInfo) => {
  admin = await contexteAdmin(playwright, testInfo.project.use.baseURL as string);
  await repartirDeLaReference(admin);
  await semer();
});

test.afterAll(async () => {
  await admin.dispose();
});

async function sql(script: string): Promise<void> {
  const reponse = await admin.post('/api/database/import', {
    headers: { 'Content-Type': 'text/plain' },
    data: script,
  });
  expect(reponse.ok(), await reponse.text()).toBe(true);
}

/**
 * The seeded planning, plus one seatless créneau. Chloé is unavailable on the
 * seeded day and Denis is free: two different verdicts on the same seat.
 */
async function semer(): Promise<void> {
  await seedPlanning(admin, { avecCollegueIndisponible: true, avecCollegueLibre: true });
  await sql(
    `delete from verrouillage_planning where animateur_id like 'E2E-%';\n` +
      `delete from poste_affectation where creneau_id = ${CRENEAU_SANS_SIEGE};\n` +
      `delete from creneau where id = ${CRENEAU_SANS_SIEGE};\n` +
      `insert into creneau (edition_id, id, date_creneau, heure_debut, heure_fin) ` +
      `values ('${EDITION_REFERENCE}', ${CRENEAU_SANS_SIEGE}, '${DATE_SANS_SIEGE}', '09:00', '11:00');`,
  );
}

/** The panel of the page: a labelled side panel, not a dialog. */
function panneau(page: Page) {
  return page.getByRole('complementary', { name: /Siège/ });
}

test("l'ancienne adresse du banc ouvre la Journée sur le siège, panneau ouvert", async ({
  browser,
}) => {
  const page = await pageAdmin(browser, admin);

  await page.goto(
    `/diagnostic?onglet=banc&creneau=${SEED.creneauId}&stand=${SEED.standDemandeur}`,
    { waitUntil: 'domcontentloaded' },
  );

  await expect(page).toHaveURL(/\/journee\?.*siege=E2E-P1/);
  await expect(page).not.toHaveURL(/onglet=banc/);
  // The seat is named by its stand's name, not its id.
  await expect(panneau(page)).toContainText('Stand E2E un');
  await expect(panneau(page)).toContainText('Alice');
  await page.context().close();
});

test("un créneau sans siège dans le plan s'explique au lieu d'échouer", async ({ browser }) => {
  const page = await pageAdmin(browser, admin);

  await page.goto(`/banc-de-touche?creneau=${CRENEAU_SANS_SIEGE}`, {
    waitUntil: 'domcontentloaded',
  });

  const contenu = page.locator('#contenu');
  await expect(contenu).toContainText("Ce siège n'est pas dans le planning enregistré");
  await expect(contenu).not.toContainText('Erreur :');
  await expect(panneau(page)).toHaveCount(0);
  await page.context().close();
});

test('un siège vide se remplit depuis la Journée : « Placer » affecte et verrouille', async ({
  browser,
}) => {
  const page = await pageAdmin(browser, admin);
  try {
    // Alice's seat freed: a hole on a timeslot still ahead.
    const liberation = await admin.post('/api/postes/E2E-P1/affectation');
    expect(liberation.status(), await liberation.text()).toBe(204);

    await page.goto(`/journee?date=${SEED.jour}&siege=E2E-P1`, {
      waitUntil: 'domcontentloaded',
    });
    await expect(panneau(page)).toContainText('Place vide');
    await panneau(page).getByRole('button', { name: 'Qui peut tenir ce siège ?' }).click();

    const dialogue = page.getByRole('dialog', { name: 'Qui peut tenir ce siège ?' });
    // Chloé is unavailable that day: folded behind the others, and the rule
    // that keeps her out is named in words — never `animateurDisponible`.
    await dialogue.getByRole('button', { name: /autres, qu'une règle dure écarte/ }).click();
    const chloe = dialogue.locator('li').filter({ hasText: 'Chloé' }).first();
    await expect(chloe).toContainText('Impossible');
    await expect(chloe).toContainText('Respect des indisponibilités');
    await expect(dialogue).not.toContainText('animateurDisponible');
    // Everyone on duty on that créneau is off the bench.
    await expect(dialogue.locator('li').filter({ hasText: 'Bruno' })).toHaveCount(0);

    // « La garder au prochain calcul » is ticked by default.
    await expect(
      dialogue.getByRole('checkbox', { name: 'La garder au prochain calcul' }),
    ).toBeChecked();
    await dialogue.getByRole('button', { name: /^Placer Denis/ }).click();

    await expect(panneau(page)).toContainText('y restera au prochain calcul');
    await expect(panneau(page).getByRole('link', { name: 'Prévenir' })).toHaveAttribute(
      'href',
      '/publication',
    );
    const plan = await (await admin.get('/api/planning/persisted')).json();
    const siege = plan.postes.find((poste: { id: string }) => poste.id === 'E2E-P1');
    expect(siege.animateur.id).toBe(SEED.collegueLibre);
    const verrous = await (await admin.get('/api/verrouillages')).json();
    expect(
      verrous.some(
        (verrou: { type: string; animateurId: string; creneauId: number }) =>
          verrou.type === 'ANIMATEUR_CRENEAU' &&
          verrou.animateurId === SEED.collegueLibre &&
          verrou.creneauId === SEED.creneauId,
      ),
    ).toBe(true);
  } finally {
    await page.context().close();
    await semer();
  }
});

test('« Libérer » vide le siège et tient la personne à l’écart de ce créneau', async ({
  browser,
}) => {
  const page = await pageAdmin(browser, admin);
  try {
    await page.goto(`/journee?date=${SEED.jour}&siege=E2E-P1`, {
      waitUntil: 'domcontentloaded',
    });
    await expect(panneau(page)).toContainText('Alice');
    await panneau(page).getByRole('button', { name: 'Libérer' }).click();

    const confirmation = page.getByRole('dialog', { name: 'Libérer ce siège ?' });
    // Ticked by default: otherwise « Corriger le reste » puts her straight back.
    await expect(
      confirmation.getByRole('checkbox', {
        name: "La tenir à l'écart de ce créneau au prochain calcul",
      }),
    ).toBeChecked();
    await confirmation.getByRole('button', { name: 'Libérer' }).click();

    await expect(panneau(page)).toContainText("restera à l'écart de ce créneau");
    const plan = await (await admin.get('/api/planning/persisted')).json();
    const siege = plan.postes.find((poste: { id: string }) => poste.id === 'E2E-P1');
    expect(siege.animateur).toBeNull();
    const verrous = await (await admin.get('/api/verrouillages')).json();
    expect(
      verrous.some(
        (verrou: { type: string; animateurId: string; creneauId: number }) =>
          verrou.type === 'ANIMATEUR_CRENEAU' &&
          verrou.animateurId === SEED.demandeur &&
          verrou.creneauId === SEED.creneauId,
      ),
    ).toBe(true);
  } finally {
    await page.context().close();
    await semer();
  }
});

/**
 * The state a new user is in: nothing has been solved, so there is no seat to
 * open. Re-seeds on the way out so the suite carries on from where it started.
 */
test("sans planning enregistré, l'adresse du banc dit quoi faire", async ({ browser }) => {
  const page = await pageAdmin(browser, admin);
  try {
    await sql(`delete from poste_affectation where edition_id = '${EDITION_REFERENCE}';`);

    await page.goto(`/diagnostic?onglet=banc&creneau=${SEED.creneauId}`, {
      waitUntil: 'domcontentloaded',
    });

    const contenu = page.locator('#contenu');
    await expect(page).toHaveURL(/\/journee/);
    await expect(contenu).not.toContainText('Erreur :');
    await expect(panneau(page)).toHaveCount(0);
  } finally {
    await page.context().close();
    await semer();
  }
});
