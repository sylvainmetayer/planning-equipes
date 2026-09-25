// Contradictory ad hoc exceptions, end to end (issue #84).
//
// Two halves that only a full stack can prove. The refusal is one: it reads
// the exceptions *and the créneaux* already persisted, so a unit test on the
// rules proves the rules, not that the entry point looks the recorded set up.
// The pre-solve report is the other: a contradiction that predates this check
// is the only kind that survives in the database, and nothing but the
// Problèmes screen and the row badges would ever point at it.
//
// That second half is seeded through `/api/database/import`, deliberately —
// it is the only way in, now that every write refuses the combination, and it
// is exactly the shape of an edition that carried the pair before the check
// shipped.

import { APIRequestContext, expect, test } from '@playwright/test';
import { SEED, contexteAdmin, pageAdmin, seedPlanning } from './support';
import { repartirDeLaReference } from './reference';

const INDISPO = 'E2E-ADHOC-INDISPO';
const FORCEE = 'E2E-ADHOC-FORCEE';
const SECONDE_FORCEE = 'E2E-ADHOC-FORCEE-2';

let admin: APIRequestContext;

test.beforeAll(async ({ playwright }, testInfo) => {
  admin = await contexteAdmin(playwright, testInfo.project.use.baseURL as string);
  await repartirDeLaReference(admin);
  await seedPlanning(admin);
});

test.afterEach(async () => {
  await admin.post('/api/database/import', {
    headers: { 'Content-Type': 'text/plain' },
    data: [
      `delete from contrainte_animateur where contrainte_id like 'E2E-ADHOC-%';`,
      `delete from contrainte_ad_hoc where id like 'E2E-ADHOC-%';`,
    ].join('\n'),
  });
});

/** The exception the screen and the API both accept. */
function indisponibilite() {
  return {
    id: INDISPO,
    type: 'INDISPONIBILITE_FORCEE',
    animateursConcernes: [{ id: SEED.demandeur }],
    creneau: { id: SEED.creneauId },
    stand: null,
    raison: 'E2E : Alice en formation',
  };
}

/** The one that cannot hold alongside it: same person, same créneau, forced. */
function affectationForcee(id = FORCEE) {
  return {
    id,
    type: 'AFFECTATION_FORCEE',
    animateursConcernes: [{ id: SEED.demandeur }],
    creneau: { id: SEED.creneauId },
    stand: { id: SEED.standDemandeur },
    raison: 'E2E : Alice imposée sur son stand',
  };
}

test('une exception contradictoire est refusée à la saisie, en nommant les deux', async ({
  browser,
}) => {
  const premiere = await admin.post('/api/contraintes-ad-hoc', { data: indisponibilite() });
  expect(premiere.ok(), await premiere.text()).toBe(true);

  const refusee = await admin.post('/api/contraintes-ad-hoc', { data: affectationForcee() });
  expect(refusee.status(), 'a refused exception is a 400, never a 500').toBe(400);
  const message = await refusee.text();
  expect(message).toContain(INDISPO);
  expect(message).toContain(FORCEE);

  // Refused means not written: the screen lists the first one and only it.
  const page = await pageAdmin(browser, admin);
  await page.goto('/ad-hoc-constraints');
  await expect(page.locator('#contenu')).toContainText(INDISPO);
  await expect(page.locator('#contenu')).not.toContainText(FORCEE);
  await page.context().close();
});

test('deux affectations forcées simultanées sur le même animateur sont refusées', async () => {
  const premiere = await admin.post('/api/contraintes-ad-hoc', { data: affectationForcee() });
  expect(premiere.ok(), await premiere.text()).toBe(true);

  // Same créneau, another stand: two seats would be needed, and no one holds
  // two seats at the same hour.
  const seconde = await admin.post('/api/contraintes-ad-hoc', {
    data: { ...affectationForcee(SECONDE_FORCEE), stand: { id: SEED.standCible } },
  });
  expect(seconde.status()).toBe(400);
  expect(await seconde.text()).toContain(FORCEE);
});

test('réenregistrer une exception sous son propre id reste possible', async () => {
  const creation = await admin.post('/api/contraintes-ad-hoc', { data: indisponibilite() });
  expect(creation.ok(), await creation.text()).toBe(true);

  // The saved version replaces the previous one instead of coexisting with it:
  // editing an exception is the only thing POST can mean here.
  const reecriture = await admin.post('/api/contraintes-ad-hoc', {
    data: { ...indisponibilite(), raison: 'E2E : motif corrigé' },
  });
  expect(reecriture.ok(), await reecriture.text()).toBe(true);
});

test('une contradiction déjà en base est signalée avant toute résolution', async ({ browser }) => {
  // Straight into the database: every write refuses this pair now, so this is
  // the only way to reproduce an edition that recorded it earlier.
  const insertion = await admin.post('/api/database/import', {
    headers: { 'Content-Type': 'text/plain' },
    data: [
      `insert into contrainte_ad_hoc (edition_id, id, type, creneau_id, raison) values ('DEFAUT', '${INDISPO}', 'INDISPONIBILITE_FORCEE', ${SEED.creneauId}, 'E2E : Alice en formation');`,
      `insert into contrainte_animateur (edition_id, contrainte_id, animateur_id, position) values ('DEFAUT', '${INDISPO}', '${SEED.demandeur}', 0);`,
      `insert into contrainte_ad_hoc (edition_id, id, type, creneau_id, stand_id, raison) values ('DEFAUT', '${FORCEE}', 'AFFECTATION_FORCEE', ${SEED.creneauId}, '${SEED.standDemandeur}', 'E2E : Alice imposée');`,
      `insert into contrainte_animateur (edition_id, contrainte_id, animateur_id, position) values ('DEFAUT', '${FORCEE}', '${SEED.demandeur}', 0);`,
    ].join('\n'),
  });
  expect(insertion.ok(), await insertion.text()).toBe(true);

  const faisabilite = (await (await admin.get('/api/feasibility')).json()) as {
    feasible: boolean;
    causes: { type: string; severite: string; contrainteIds: string[] }[];
  };
  const contradiction = faisabilite.causes.find((cause) => cause.contrainteIds?.includes(FORCEE));
  expect(contradiction, 'the pre-solve report must name the two exceptions').toBeDefined();
  expect(contradiction?.type).toBe('CONTRAINTES_AD_HOC_CONTRADICTOIRES');
  expect(contradiction?.severite).toBe('CRITIQUE');
  expect(contradiction?.contrainteIds).toContain(INDISPO);

  const page = await pageAdmin(browser, admin);
  await page.goto('/diagnostic');
  await expect(page.locator('#contenu')).toContainText(INDISPO);
  await expect(page.locator('#contenu')).toContainText(FORCEE);

  // And the rows themselves are badged, on the screen that owns them.
  await page.goto('/ad-hoc-constraints');
  // Anchored on the Id column, not a substring of the row's accessible name:
  // since issue #39 the badge is exposed to assistive tech, and its name is the
  // contradiction sentence — which names *both* exceptions. A bare
  // `new RegExp(FORCEE)` therefore matches the INDISPO row too, and would match
  // `E2E-ADHOC-FORCEE-2` as well; `(?!\S)` closes both.
  const ligneForcee = page.getByRole('row', { name: new RegExp(String.raw`^${FORCEE}(?!\S)`) });
  await expect(ligneForcee).toHaveCount(1);
  await expect(ligneForcee.getByText('warning')).toBeVisible();
  // The badge says what it means, rather than only showing a tooltip on hover.
  await expect(ligneForcee.getByRole('img', { name: new RegExp(INDISPO) })).toBeVisible();
  await page.context().close();
});
