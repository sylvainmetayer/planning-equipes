// « Le passé est figé » (ADR 0044) from the screen: the scenario of
// FrozenPastAcceptanceTest, replayed through a browser on the packaged
// application. The clock is set on the Débogage page — the e2e-lourd stack
// is launched with HORLOGE_SIMULEE_AUTORISEE=true, a staging server's
// configuration — first before the event, then on the Wednesday at 13:30;
// three solves follow, and the days already worked come out of each one
// exactly as they went in. The recap says how many seats it froze, and a move
// on a day already worked is refused with the rule's own sentence.
//
// What a unit test cannot see is the chain: that the date typed on the
// Débogage page is the one the solver reads, that the recap of a solve
// launched from the button carries the count, and that the day view shows
// the refusal instead of swallowing it.
//
// The ordinary e2e stack refuses the simulated clock (jour-j.spec.ts checks
// exactly that), hence the @lourd tag: e2e-lourd.yml is the workflow that
// stands up the stack allowing it.

import { APIRequestContext, Page, expect, test } from '@playwright/test';
import { contexteAdmin, pageAdmin } from './support';
import { repartirDeLaReference } from './reference';

/**
 * The twelfth rung of the ladder: a civil week, Monday 12 to Sunday 18 July
 * 2027, a morning (10:00–13:00) and an afternoon a day. Its dates need no
 * shift: the clock the solver reads is the one this spec sets.
 */
const SCENARIO = 'gamme-12-7j-10stands-24animateurs-semaine-complete-ferie.yaml';
const AVANT_L_EVENEMENT = '2027-07-01';
const J1 = '2027-07-12';
const J2 = '2027-07-13';
const J3 = '2027-07-14';
const MATIN = '10:00:00';

/** Short solves: the week reaches zero hard in a few seconds. */
const DUREE_SECONDES = 10;

/** The fields of `/api/planning/persisted` a seat is compared on. */
interface Siege {
  id: string;
  stand: { id: string } | null;
  creneau: { date: string | null; heureDebut: string; heureFin: string } | null;
  animateur: { id: string } | null;
  heureDebutEffective?: string | null;
  heureFinEffective?: string | null;
}

let admin: APIRequestContext;

test.beforeAll(async ({ playwright }, testInfo) => {
  admin = await contexteAdmin(playwright, testInfo.project.use.baseURL as string);
  await repartirDeLaReference(admin);
  const reset = await admin.post('/api/planning/reset');
  expect(reset.ok(), await reset.text()).toBe(true);
  const importation = await admin.post(`/api/reference-data/import-scenario?name=${SCENARIO}`);
  expect(importation.ok(), await importation.text()).toBe(true);
  const parametres = await admin.put('/api/parametres-solveur', {
    data: { dureeResolutionSecondes: DUREE_SECONDES },
  });
  expect(parametres.ok(), await parametres.text()).toBe(true);
});

test.afterAll(async () => {
  // The clock lives outside the tables the reference restores
  // (`horloge_jour_j`): handed back explicitly, or every later spec would
  // read July 2027 as today.
  await admin.put('/api/debug/date-du-jour', { data: { dateDuJour: null } });
  await repartirDeLaReference(admin);
  await admin.dispose();
});

/** Sets the server's clock from the Débogage page, the way a tester would. */
async function figerHorloge(page: Page, date: string, heure?: string): Promise<void> {
  await page.goto('/debug?onglet=verifications');
  const champDate = page.locator('#date-du-jour');
  await champDate.fill(date);
  await expect.poll(async () => (await horloge()).dateDuJour).toBe(date);
  if (heure) {
    const champHeure = page.locator('#heure-du-jour');
    await expect(champHeure).toBeEnabled();
    await champHeure.fill(heure);
    await expect.poll(async () => (await horloge()).heureDuJour?.slice(0, 5)).toBe(heure);
  }
  await expect(page.locator('#contenu')).toContainText('Date figée');
}

async function horloge(): Promise<{ dateDuJour: string | null; heureDuJour?: string | null }> {
  const reponse = await admin.get('/api/debug/date-du-jour');
  expect(reponse.ok()).toBe(true);
  return (await reponse.json()) as { dateDuJour: string | null; heureDuJour?: string | null };
}

/** Launches a full solve with the page's button and waits for the job to end. */
async function calculerDepuisLEcran(page: Page): Promise<void> {
  await page.goto('/solveur');
  await page.getByRole('button', { name: 'Calculer le planning' }).click();
  await expect
    .poll(async () => (await page.request.get('/api/jobs/active')).status(), { timeout: 15_000 })
    .toBe(200);
  await expect
    .poll(async () => (await page.request.get('/api/jobs/active')).status(), {
      timeout: (DUREE_SECONDES + 80) * 1000,
    })
    .toBe(204);
}

async function sieges(): Promise<Siege[]> {
  const reponse = await admin.get('/api/planning/persisted');
  expect(reponse.ok()).toBe(true);
  return ((await reponse.json()) as { postes: Siege[] }).postes;
}

/** « stand@début|fin|titulaire », as FrozenPastAcceptanceTest compares seats. */
function forme(siege: Siege): string {
  const debut = siege.heureDebutEffective ?? siege.creneau?.heureDebut;
  const fin = siege.heureFinEffective ?? siege.creneau?.heureFin;
  return `${siege.stand?.id}@${debut}|${fin}|${siege.animateur?.id ?? '-'}`;
}

/**
 * The seats already worked on the Wednesday at 13:30 — Monday and Tuesday
 * whole, and the Wednesday morning — each group sorted, so two plans compare
 * as the multisets they are.
 */
async function siegesPasses(): Promise<Record<string, string[]>> {
  const tous = await sieges();
  const du = (date: string, filtre: (siege: Siege) => boolean = () => true) =>
    tous
      .filter((siege) => siege.creneau?.date === date && filtre(siege))
      .map(forme)
      .sort();
  return {
    [J1]: du(J1),
    [J2]: du(J2),
    [`${J3} matin`]: du(J3, (siege) => siege.creneau?.heureDebut === MATIN),
  };
}

/** Hard level of the analysis the Contraintes screen reads: the persisted plan, re-scored. */
async function scoreDurPersiste(): Promise<number> {
  const reponse = await admin.get('/api/constraints');
  expect(reponse.ok()).toBe(true);
  return ((await reponse.json()) as { hardScore: number }).hardScore;
}

/** Adds a day off to an animateur, keeping the rest of the fiche as it is. */
async function rendreIndisponible(animateurId: string, jour: string): Promise<void> {
  const fiches = (await (await admin.get('/api/animateurs')).json()) as Record<string, unknown>[];
  const fiche = fiches.find((candidat) => candidat['id'] === animateurId);
  expect(fiche, `animateur ${animateurId}`).toBeTruthy();
  const jours = [...((fiche!['joursIndisponibles'] as string[] | undefined) ?? []), jour];
  const reponse = await admin.put(`/api/animateurs/${animateurId}`, {
    data: { ...fiche, joursIndisponibles: jours },
  });
  expect(reponse.ok(), await reponse.text()).toBe(true);
}

async function nomComplet(animateurId: string): Promise<string> {
  const fiches = (await (await admin.get('/api/animateurs')).json()) as {
    id: string;
    prenom: string;
    nom: string;
  }[];
  const fiche = fiches.find((candidat) => candidat.id === animateurId);
  expect(fiche, `animateur ${animateurId}`).toBeTruthy();
  return `${fiche!.prenom} ${fiche!.nom}`;
}

// @lourd in the title is a Playwright tag: e2e.yml leaves it out, e2e-lourd.yml
// plays it on the stack that lets the clock be set.
test('les journées déjà travaillées sortent de chaque résolution telles qu’elles y sont entrées @lourd', async ({
  browser,
}) => {
  test.slow();
  test.setTimeout(8 * 60_000);
  const page = await pageAdmin(browser, admin);
  try {
    // 1. Before the event: the whole week ahead, nothing frozen, zero hard.
    await figerHorloge(page, AVANT_L_EVENEMENT);
    await calculerDepuisLEcran(page);
    await expect(page.locator('#contenu')).not.toContainText('postes déjà commencés');
    expect(await scoreDurPersiste()).toBe(0);
    const passeAttendu = await siegesPasses();
    const postesPasses = Object.values(passeAttendu).reduce(
      (total, each) => total + each.length,
      0,
    );
    expect(postesPasses).toBeGreaterThan(0);

    // 2. Wednesday 13:30, and a holder of the Monday declares that day off
    //    after the fact: they were there all the same.
    await figerHorloge(page, J3, '13:30');
    const temoin = (await sieges()).find((siege) => siege.creneau?.date === J1 && siege.animateur)!
      .animateur!.id;
    await rendreIndisponible(temoin, J1);

    // 3. A full solve from the button: the recap names the frozen seats, the
    //    past is identical, and it costs nothing.
    await calculerDepuisLEcran(page);
    await expect(page.locator('#contenu')).toContainText(
      `${postesPasses} postes déjà commencés, figés tels que travaillés.`,
    );
    expect(await siegesPasses()).toEqual(passeAttendu);
    expect(await scoreDurPersiste()).toBe(0);

    // 4. An incremental solve re-opening the Monday: nothing to re-open.
    const incremental = await admin.post(`/api/solve/incremental/async?seconds=${DUREE_SECONDES}`, {
      data: { animateurIds: [], jours: [J1], standIds: [] },
    });
    expect(incremental.status(), await incremental.text()).toBe(202);
    const { id } = (await incremental.json()) as { id: string };
    await expect
      .poll(
        async () =>
          ((await (await admin.get(`/api/jobs/${id}`)).json()) as { status: string }).status,
        { timeout: (DUREE_SECONDES + 80) * 1000 },
      )
      .toMatch(/COMPLETED|FAILED|CANCELLED/);
    const job = (await (await admin.get(`/api/jobs/${id}`)).json()) as {
      status: string;
      error: string | null;
      result: { statistiques: { postesPasses: number }; changements: unknown[] };
    };
    expect(job.status, job.error ?? '').toBe('COMPLETED');
    expect(job.result.statistiques.postesPasses).toBe(postesPasses);
    expect(job.result.changements).toEqual([]);
    expect(await siegesPasses()).toEqual(passeAttendu);

    // 5. On the Monday's day view, handing a seat to someone else is refused
    //    with the rule's sentence, and the plan does not move. The move handle
    //    exists because the e2e stack turns GLISSER_DEPOSER_ACTIF on.
    const lundi = (await sieges()).filter((siege) => siege.creneau?.date === J1 && siege.animateur);
    const titulaire = await nomComplet(lundi[0].animateur!.id);
    const autre = lundi.find((siege) => siege.animateur!.id !== lundi[0].animateur!.id)!;
    const remplacant = await nomComplet(autre.animateur!.id);

    await page.goto(`/journee?vue=calendrier&date=${J1}`);
    const poignee = page
      .getByRole('button', { name: new RegExp(`^Déplacer ${titulaire}`) })
      .first();
    await poignee.focus();
    await page.keyboard.press('Enter');
    const dialogue = page.getByRole('dialog', { name: `Déplacer ${titulaire}` });
    await expect(dialogue.getByRole('combobox')).toBeFocused();
    await page.keyboard.type(remplacant);
    await expect(page.getByRole('option', { name: new RegExp(remplacant) }).first()).toBeVisible();
    await page.keyboard.press('ArrowDown');
    await page.keyboard.press('Enter');
    const deplacer = dialogue.getByRole('button', { name: 'Déplacer' });
    await expect(deplacer).toBeEnabled();
    await deplacer.click();

    await expect(page.locator('mat-snack-bar-container')).toContainText(
      'Ce créneau est déjà commencé',
    );
    expect(await siegesPasses()).toEqual(passeAttendu);
  } finally {
    await page.context().close();
  }
});
