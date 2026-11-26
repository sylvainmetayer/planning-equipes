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
const INCOMPAT = 'E2E-ADHOC-INCOMPAT';
const AFFINITE = 'E2E-ADHOC-AFFINITE';

/** What the screen shows of each exception, and what marks it as this spec's. */
const RAISON_INCOMPAT = 'E2E : Alice et Bruno ne travaillent pas ensemble';
const RAISON_AFFINITE = 'E2E : Alice et Bruno font binôme';

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
function incompatibilite() {
  return {
    type: 'INCOMPATIBILITE',
    animateursConcernes: [{ id: SEED.demandeur }, { id: SEED.cible }],
    creneau: null,
    stand: null,
    raison: RAISON_INCOMPAT,
  };
}

/** The one that cannot hold alongside it: the same pair, in affinity. */
function affinite() {
  return {
    type: 'AFFINITE',
    animateursConcernes: [{ id: SEED.demandeur }, { id: SEED.cible }],
    creneau: null,
    stand: null,
    raison: RAISON_AFFINITE,
  };
}

test('une exception contradictoire est refusée à la saisie, en nommant les deux', async ({
  browser,
}) => {
  const incompat = await idCree(
    await admin.post('/api/contraintes-ad-hoc', { data: incompatibilite() }),
  );

  const refusee = await admin.post('/api/contraintes-ad-hoc', { data: affinite() });
  expect(refusee.status(), 'a refused exception is a 400, never a 500').toBe(400);
  // The refused one has no id yet — nothing was written — so the sentence
  // names the exception it collides with.
  expect(await refusee.text()).toContain(incompat);

  // Refused means not written: the screen lists the first one and only it.
  const page = await pageAdmin(browser, admin);
  await page.goto('/regles');
  await expect(page.locator('#contenu')).toContainText(RAISON_INCOMPAT);
  await expect(page.locator('#contenu')).not.toContainText(RAISON_AFFINITE);
  await page.context().close();
});

test('réenregistrer une exception sous son propre id reste possible', async () => {
  const id = await idCree(await admin.post('/api/contraintes-ad-hoc', { data: incompatibilite() }));

  // The saved version replaces the previous one instead of coexisting with it:
  // editing an exception is the only thing POST can mean here.
  const reecriture = await admin.post('/api/contraintes-ad-hoc', {
    data: { ...incompatibilite(), id, raison: 'E2E : motif corrigé' },
  });
  expect(await idCree(reecriture)).toBe(id);
});

test('un identifiant inconnu est refusé plutôt que créé', async () => {
  // An id is no longer something a caller chooses: one that names nothing is
  // a mistake, not a creation.
  const inconnu = await admin.post('/api/contraintes-ad-hoc', {
    data: { ...incompatibilite(), id: 'E2E-ADHOC-INCONNU' },
  });
  expect(inconnu.status()).toBe(400);
});

test('flags a contradiction already in the database before any solve', async ({ browser }) => {
  // Straight into the database: every write refuses this pair now, so this is
  // the only way to reproduce an edition that recorded it earlier.
  const insertion = await admin.post('/api/database/import', {
    headers: { 'Content-Type': 'text/plain' },
    data: [
      `insert into contrainte_ad_hoc (edition_id, id, type, raison) values ('${EDITION_REFERENCE}', '${INCOMPAT}', 'INCOMPATIBILITE', 'E2E : Alice et Bruno incompatibles');`,
      `insert into contrainte_animateur (edition_id, contrainte_id, animateur_id, position) values ('${EDITION_REFERENCE}', '${INCOMPAT}', '${SEED.demandeur}', 0), ('${EDITION_REFERENCE}', '${INCOMPAT}', '${SEED.cible}', 1);`,
      `insert into contrainte_ad_hoc (edition_id, id, type, raison) values ('${EDITION_REFERENCE}', '${AFFINITE}', 'AFFINITE', 'E2E : Alice et Bruno en affinité');`,
      `insert into contrainte_animateur (edition_id, contrainte_id, animateur_id, position) values ('${EDITION_REFERENCE}', '${AFFINITE}', '${SEED.demandeur}', 0), ('${EDITION_REFERENCE}', '${AFFINITE}', '${SEED.cible}', 1);`,
    ].join('\n'),
  });
  expect(insertion.ok(), await insertion.text()).toBe(true);

  const faisabilite = (await (await admin.get('/api/feasibility')).json()) as {
    feasible: boolean;
    causes: { type: string; severite: string; contrainteIds: string[] }[];
  };
  const contradiction = faisabilite.causes.find((cause) => cause.contrainteIds?.includes(AFFINITE));
  expect(contradiction, 'the pre-solve report must name the two exceptions').toBeDefined();
  expect(contradiction?.type).toBe('CONTRAINTES_AD_HOC_CONTRADICTOIRES');
  expect(contradiction?.severite).toBe('CRITIQUE');
  expect(contradiction?.contrainteIds).toContain(INCOMPAT);

  const page = await pageAdmin(browser, admin);
  await page.goto('/diagnostic');
  await expect(page.locator('#contenu')).toContainText(INCOMPAT);
  await expect(page.locator('#contenu')).toContainText(AFFINITE);

  // And the rows themselves are badged, on the screen that owns them.
  await page.goto('/regles');
  // Anchored on the Id cell, not a substring of the row's accessible name:
  // since issue #39 the badge is exposed to assistive tech, and its name is the
  // contradiction sentence — which names *both* exceptions. A bare
  // `new RegExp(AFFINITE)` therefore matches the INCOMPAT row too, and would match
  // `E2E-ADHOC-AFFINITE-2` as well; `(?!\S)` closes both. The cell, not the row:
  // the row's name now opens on its selection checkbox.
  const ligneIncompat = page.getByRole('row').filter({
    has: page.getByRole('cell', { name: new RegExp(String.raw`^${INCOMPAT}(?!\S)`) }),
  });
  await expect(ligneIncompat).toHaveCount(1);
  await expect(ligneIncompat.getByText('warning')).toBeVisible();
  // The badge says what it means, rather than only showing a tooltip on hover.
  await expect(ligneIncompat.getByRole('img', { name: new RegExp(AFFINITE) })).toBeVisible();
  await page.context().close();
});

test('the former network view leads to the list filtered on the person', async ({ browser }) => {
  await idCree(await admin.post('/api/contraintes-ad-hoc', { data: incompatibilite() }));

  const page = await pageAdmin(browser, admin);
  // Issue #719: the network is gone, its `?personne=` narrows the list instead.
  // « Ajustements » is the page's default tab, which the URL does not spell out.
  await page.goto(`/ad-hoc-constraints?vue=reseau&personne=${SEED.demandeur}`);
  await expect(page).toHaveURL(new RegExp(String.raw`/regles\?personne=${SEED.demandeur}$`));
  await expect(page.locator('#contenu')).toContainText(RAISON_INCOMPAT);
  await expect(page.locator('svg.reseau-paires')).toHaveCount(0);
  await page.context().close();
});
