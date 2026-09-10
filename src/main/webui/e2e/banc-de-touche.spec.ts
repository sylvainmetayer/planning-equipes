// The banc de touche (issue #303), driven through the browser.
//
// It shipped with a bug no unit test could have seen, because it lived in the
// gap between two sources: the créneau selector is fed by the *referential*,
// the answer is computed from the *saved plan*, and the referential
// legitimately holds more créneaux than the plan does — a stand closed then, a
// découpage run after the last solve. The screen opened on
// « Erreur : Créneau inconnu dans le planning: 865 » before the user had
// touched anything, with nothing to click to get out of it.
//
// Hence these three: the two states where the screen has nothing to show must
// still be usable screens, and the state where it has something must really
// show it.

import { APIRequestContext, expect, Page, test } from '@playwright/test';
import { contexteAdmin, ouvrirSelect, pageAdmin, SEED, seedPlanning } from './support';
import { repartirDeLaReference } from './reference';

/** A créneau of the referential that no seat of the saved plan points at. */
const CRENEAU_SANS_SIEGE = 987003;

/** Its date, the one the selector must never offer. */
const DATE_SANS_SIEGE = '2026-07-12';

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
 * The seeded planning, plus one seatless créneau — the case the screen used to
 * fail on. Chloé is unavailable on the seeded day and Denis is free: two
 * different verdicts on the same seat.
 */
async function semer(): Promise<void> {
  await seedPlanning(admin, { avecCollegueIndisponible: true, avecCollegueLibre: true });
  await sql(
    `delete from poste_affectation where creneau_id = ${CRENEAU_SANS_SIEGE};\n` +
      `delete from creneau where id = ${CRENEAU_SANS_SIEGE};\n` +
      `insert into creneau (edition_id, id, date_creneau, heure_debut, heure_fin) ` +
      `values ('DEFAUT', ${CRENEAU_SANS_SIEGE}, '${DATE_SANS_SIEGE}', '09:00', '11:00');`,
  );
}

/** The banc for one créneau, named in the URL so the test never depends on which one is first. */
async function ouvrirBanc(page: Page, creneauId: number): Promise<void> {
  await page.goto(`/banc-de-touche?creneau=${creneauId}`, { waitUntil: 'domcontentloaded' });
  await expect(page.locator('#contenu')).toContainText('Banc de touche');
}

test("un créneau sans siège dans le plan s'explique au lieu d'échouer", async ({ browser }) => {
  const page = await pageAdmin(browser, admin);

  await ouvrirBanc(page, CRENEAU_SANS_SIEGE);

  const contenu = page.locator('#contenu');
  // The regression itself: a real créneau answered « Créneau inconnu », as an error.
  await expect(contenu).not.toContainText('Erreur :');
  await expect(contenu).not.toContainText('inconnu');
  // A bookmark can still name it, so the answer explains rather than fails.
  await expect(contenu).toContainText('Aucun siège sur ce créneau');
  await expect(contenu).toContainText('Choisissez un créneau qui porte des sièges');
  await page.context().close();
});

/**
 * The screen ships under « En cours de développement », and says so: it reads
 * and changes nothing, so the banner is the read-only wording, not the one the
 * jour-J screen uses.
 */
test("l'écran s'annonce comme livré à l'essai", async ({ browser }) => {
  const page = await pageAdmin(browser, admin);

  await ouvrirBanc(page, SEED.creneauId);

  await expect(page.locator('#contenu')).toContainText("Cet écran est livré à l'essai");
  await expect(page.getByRole('navigation', { name: 'Navigation principale' })).toContainText(
    'En cours de développement',
  );
  await page.context().close();
});

/**
 * What the user asked for after the previous round: a créneau the saved plan
 * staffs nothing on is not merely marked, it is not offered at all. The
 * selector is built from the answer, so this also proves the page no longer
 * reads the créneau referential to fill it.
 */
test('le sélecteur ne propose pas un créneau que le plan ne pourvoit pas', async ({ browser }) => {
  // Guard against a vacuous assertion: the créneau really is in the
  // référentiel — it is only absent from the plan — so leaving it out of the
  // selector is a decision, not an accident of the fixture.
  const referentiel = await (await admin.get('/api/creneaux')).json();
  expect(referentiel.map((creneau: { date: string }) => creneau.date)).toContain(DATE_SANS_SIEGE);

  const page = await pageAdmin(browser, admin);
  await ouvrirBanc(page, SEED.creneauId);
  await ouvrirSelect(page, 'Créneau');

  const options = page.locator('mat-option');
  await expect(options.filter({ hasText: SEED.jour })).toHaveCount(1);
  await expect(options.filter({ hasText: DATE_SANS_SIEGE })).toHaveCount(0);
  await page.context().close();
});

test('un créneau porteur de sièges liste le banc et les motifs de chacun', async ({ browser }) => {
  const page = await pageAdmin(browser, admin);

  await ouvrirBanc(page, SEED.creneauId);

  const contenu = page.locator('#contenu');
  await expect(contenu).not.toContainText('Erreur :');
  await expect(contenu).toContainText('Siège évalué');
  // Both seats of that créneau are taken, so the seat probed is an occupied one
  // and the question becomes « qui pourrait le remplacer ? ».
  await expect(contenu).toContainText('Ce siège est tenu par');

  // Chloé is unavailable that day: refused, and the rule that refuses is named.
  const chloe = page.locator('tbody tr').filter({ hasText: 'Chloé' }).first();
  await expect(chloe).toContainText('Impossible');
  await expect(chloe).toContainText('animateurDisponible');

  // Denis holds a seat the day after only: nothing stands in his way.
  const denis = page.locator('tbody tr').filter({ hasText: 'Denis' }).first();
  await expect(denis).toContainText('Disponible');

  // Everyone on duty on that créneau is off the bench.
  await expect(page.locator('tbody tr').filter({ hasText: 'Alice' })).toHaveCount(0);
  await expect(page.locator('tbody tr').filter({ hasText: 'Bruno' })).toHaveCount(0);
  await page.context().close();
});

/**
 * The state a new user is in, and the one the screen must survive: nothing has
 * been solved, so there is no plan to be absent from. Re-seeds on the way out
 * so the suite carries on from the state it started in.
 */
test("sans planning enregistré, l'écran dit quoi faire", async ({ browser }) => {
  const page = await pageAdmin(browser, admin);
  try {
    await sql(`delete from poste_affectation where edition_id = 'DEFAUT';`);

    await ouvrirBanc(page, CRENEAU_SANS_SIEGE);

    const contenu = page.locator('#contenu');
    await expect(contenu).toContainText('Aucun planning enregistré');
    await expect(contenu).not.toContainText('Erreur :');
    // Nothing staffed means nothing to offer: the selectors stand down rather
    // than showing an empty dropdown the user would have to interpret.
    await expect(contenu.getByLabel('Créneau')).toHaveCount(0);
  } finally {
    await page.context().close();
    await semer();
  }
});
