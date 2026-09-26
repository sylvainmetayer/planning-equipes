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
import { SEED, contexteAdmin, idCree, pageAdmin, seedPlanning, EDITION_REFERENCE } from './support';
import { repartirDeLaReference } from './reference';

/**
 * Only the pre-existing pair of the last test is written straight into the
 * database, under fixture ids of its own; everything posted through the API
 * gets the id the application draws, read back from the answer.
 */
const INDISPO = 'E2E-ADHOC-INDISPO';
const FORCEE = 'E2E-ADHOC-FORCEE';

/** What the screen shows of each exception, and what marks it as this spec's. */
const RAISON_INDISPO = 'E2E : Alice en formation';
const RAISON_FORCEE = 'E2E : Alice imposée sur son stand';

let admin: APIRequestContext;

test.beforeAll(async ({ playwright }, testInfo) => {
  admin = await contexteAdmin(playwright, testInfo.project.use.baseURL as string);
  await repartirDeLaReference(admin);
  await seedPlanning(admin);
});

test.afterEach(async () => {
  // By their reason, not their id: the application numbers them.
  await admin.post('/api/database/import', {
    headers: { 'Content-Type': 'text/plain' },
    data: [
      `delete from contrainte_animateur where contrainte_id in (select id from contrainte_ad_hoc where raison like 'E2E : %');`,
      `delete from contrainte_ad_hoc where raison like 'E2E : %';`,
    ].join('\n'),
  });
});

/** The exception the screen and the API both accept. */
function indisponibilite() {
  return {
    type: 'INDISPONIBILITE_FORCEE',
    animateursConcernes: [{ id: SEED.demandeur }],
    creneau: { id: SEED.creneauId },
    stand: null,
    raison: RAISON_INDISPO,
  };
}

/** The one that cannot hold alongside it: same person, same créneau, forced. */
function affectationForcee() {
  return {
    type: 'AFFECTATION_FORCEE',
    animateursConcernes: [{ id: SEED.demandeur }],
    creneau: { id: SEED.creneauId },
    stand: { id: SEED.standDemandeur },
    raison: RAISON_FORCEE,
  };
}

test('une exception contradictoire est refusée à la saisie, en nommant les deux', async ({
  browser,
}) => {
  const indispo = await idCree(
    await admin.post('/api/contraintes-ad-hoc', { data: indisponibilite() }),
  );

  const refusee = await admin.post('/api/contraintes-ad-hoc', { data: affectationForcee() });
  expect(refusee.status(), 'a refused exception is a 400, never a 500').toBe(400);
  // The refused one has no id yet — nothing was written — so the sentence
  // names the exception it collides with.
  expect(await refusee.text()).toContain(indispo);

  // Refused means not written: the screen lists the first one and only it.
  const page = await pageAdmin(browser, admin);
  await page.goto('/consignes-solveur');
  await expect(page.locator('#contenu')).toContainText(RAISON_INDISPO);
  await expect(page.locator('#contenu')).not.toContainText(RAISON_FORCEE);
  await page.context().close();
});

test('deux affectations forcées simultanées sur le même animateur sont refusées', async () => {
  const forcee = await idCree(
    await admin.post('/api/contraintes-ad-hoc', { data: affectationForcee() }),
  );

  // Same créneau, another stand: two seats would be needed, and no one holds
  // two seats at the same hour.
  const seconde = await admin.post('/api/contraintes-ad-hoc', {
    data: { ...affectationForcee(), stand: { id: SEED.standCible } },
  });
  expect(seconde.status()).toBe(400);
  expect(await seconde.text()).toContain(forcee);
});

test('réenregistrer une exception sous son propre id reste possible', async () => {
  const id = await idCree(await admin.post('/api/contraintes-ad-hoc', { data: indisponibilite() }));

  // The saved version replaces the previous one instead of coexisting with it:
  // editing an exception is the only thing POST can mean here.
  const reecriture = await admin.post('/api/contraintes-ad-hoc', {
    data: { ...indisponibilite(), id, raison: 'E2E : motif corrigé' },
  });
  expect(await idCree(reecriture)).toBe(id);
});

test('un identifiant inconnu est refusé plutôt que créé', async () => {
  // An id is no longer something a caller chooses: one that names nothing is
  // a mistake, not a creation.
  const inconnu = await admin.post('/api/contraintes-ad-hoc', {
    data: { ...indisponibilite(), id: 'E2E-ADHOC-INCONNU' },
  });
  expect(inconnu.status()).toBe(400);
});

test('une contradiction déjà en base est signalée avant toute résolution', async ({ browser }) => {
  // Straight into the database: every write refuses this pair now, so this is
  // the only way to reproduce an edition that recorded it earlier.
  const insertion = await admin.post('/api/database/import', {
    headers: { 'Content-Type': 'text/plain' },
    data: [
      `insert into contrainte_ad_hoc (edition_id, id, type, creneau_id, raison) values ('${EDITION_REFERENCE}', '${INDISPO}', 'INDISPONIBILITE_FORCEE', ${SEED.creneauId}, 'E2E : Alice en formation');`,
      `insert into contrainte_animateur (edition_id, contrainte_id, animateur_id, position) values ('${EDITION_REFERENCE}', '${INDISPO}', '${SEED.demandeur}', 0);`,
      `insert into contrainte_ad_hoc (edition_id, id, type, creneau_id, stand_id, raison) values ('${EDITION_REFERENCE}', '${FORCEE}', 'AFFECTATION_FORCEE', ${SEED.creneauId}, '${SEED.standDemandeur}', 'E2E : Alice imposée');`,
      `insert into contrainte_animateur (edition_id, contrainte_id, animateur_id, position) values ('${EDITION_REFERENCE}', '${FORCEE}', '${SEED.demandeur}', 0);`,
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
  await page.goto('/consignes-solveur');
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

test('l’ancienne vue réseau mène à la liste filtrée sur la personne', async ({ browser }) => {
  await idCree(await admin.post('/api/contraintes-ad-hoc', { data: indisponibilite() }));

  const page = await pageAdmin(browser, admin);
  // Issue #719: the network is gone, its `?personne=` narrows the list instead.
  await page.goto(`/ad-hoc-constraints?vue=reseau&personne=${SEED.demandeur}`);
  await expect(page).toHaveURL(
    new RegExp(`/consignes-solveur\\?onglet=ajustements&personne=${SEED.demandeur}$`),
  );
  await expect(page.locator('#contenu')).toContainText(RAISON_INDISPO);
  await expect(page.locator('svg.reseau-paires')).toHaveCount(0);
  await page.context().close();
});
