// Drag-and-drop of one assignment on the day views (issue #308).
//
// What only a browser can check: that a name can actually be picked up and
// dropped, that a drop on a held seat swaps the two people and a drop on a
// free seat moves one, that the rail hands a vacation to another person, and
// that a drop the server refuses leaves the plan as it was and says why. The
// per-gesture rules are covered by DeplacementResourceTest, far more cheaply.

import { APIRequestContext, Locator, Page, expect, test } from '@playwright/test';
import { contexteAdmin, decaler, pageAdmin, planningPersiste } from './support';
import { repartirDeLaReference } from './reference';

const C1 = 987401;
/** C1's day: ahead of the real clock, where a drop is still the operator's to make. */
const JOUR = decaler('2026-07-22');
const ANIMATEURS = [
  { id: 'SOLV-DD-A', prenom: 'Anna', nom: 'Glisse', dateNaissance: '1990-01-01' },
  { id: 'SOLV-DD-B', prenom: 'Boris', nom: 'Glisse', dateNaissance: '1991-02-02' },
  { id: 'SOLV-DD-C', prenom: 'Cléo', nom: 'Glisse', dateNaissance: '1992-03-03' },
];
const STANDS = [
  { id: 'SOLV-DD-S1', nom: 'Stand Glisse un' },
  { id: 'SOLV-DD-S2', nom: 'Stand Glisse deux' },
  { id: 'SOLV-DD-S3', nom: 'Stand Glisse trois' },
];

/**
 * Three seats on one créneau, held by Anna, Boris and Cléo in that order —
 * written straight into the plan rather than solved: what this spec checks is
 * the gesture, and a solve over the other specs' leftovers does not always
 * fill every seat. Every SOLV-DD row is wiped first, so a crashed run leaves
 * nothing behind.
 */
interface SeedOptions {
  /** The first stand takes adults only. */
  premierStandReserveMajeurs?: boolean;
  /** Cléo is a minor, and holds no seat. */
  cleoMineureEtLibre?: boolean;
}

async function seedPlan(options: SeedOptions = {}): Promise<void> {
  const script = [
    `delete from poste_affectation where stand_id like 'SOLV-DD-%' or animateur_id like 'SOLV-DD-%' or creneau_id = ${C1};`,
    `delete from contrainte_ad_hoc where id like 'SOLV-DD-%';`,
    `delete from verrouillage_planning where creneau_id = ${C1};`,
    `delete from creneau where id = ${C1};`,
    `delete from animateur where id like 'SOLV-DD-%';`,
    `delete from stand_typologie where stand_id like 'SOLV-DD-%';`,
    `delete from stand where id like 'SOLV-DD-%';`,
    ...STANDS.map(
      (stand, index) =>
        `insert into stand (edition_id, id, nom, effectif_min, effectif_max, reserve_majeurs) values ('DEFAUT', '${stand.id}', '${stand.nom}', 1, 1, ${index === 0 && options.premierStandReserveMajeurs ? 'true' : 'false'});`,
    ),
    ...STANDS.map(
      (stand) =>
        `insert into stand_typologie (edition_id, stand_id, typologie) values ('DEFAUT', '${stand.id}', 'STRATEGIE');`,
    ),
    ...ANIMATEURS.map(
      (animateur) =>
        `insert into animateur (edition_id, id, prenom, nom, date_naissance, manager) values ('DEFAUT', '${animateur.id}', '${animateur.prenom}', '${animateur.nom}', '${animateur.id === 'SOLV-DD-C' && options.cleoMineureEtLibre ? decaler('2012-01-01') : animateur.dateNaissance}', false);`,
    ),
    `insert into creneau (edition_id, id, date_creneau, heure_debut, heure_fin) values ('DEFAUT', ${C1}, '${JOUR}', '10:00', '12:00');`,
    ...STANDS.map((stand, index) =>
      index === 2 && options.cleoMineureEtLibre
        ? `insert into poste_affectation (edition_id, id, stand_id, creneau_id) values ('DEFAUT', 'SOLV-DD-P3', '${stand.id}', ${C1});`
        : `insert into poste_affectation (edition_id, id, stand_id, creneau_id, animateur_id) values ('DEFAUT', 'SOLV-DD-P${index + 1}', '${stand.id}', ${C1}, '${ANIMATEURS[index].id}');`,
    ),
  ].join('\n');
  const reponse = await admin.post('/api/database/import', {
    headers: { 'Content-Type': 'text/plain' },
    data: script,
  });
  expect(reponse.ok(), await reponse.text()).toBe(true);
}

let admin: APIRequestContext;

test.beforeAll(async ({ playwright }, testInfo) => {
  admin = await contexteAdmin(playwright, testInfo.project.use.baseURL as string);
  // The gesture is off unless the server is started with it: say so, instead
  // of four locator timeouts on a handle that is never rendered.
  const config = (await (await admin.get('/api/config')).json()) as { dragDropEnabled?: boolean };
  expect(
    config.dragDropEnabled,
    'drag and drop is off on this server: start it with GLISSER_DEPOSER_ACTIF=true',
  ).toBe(true);
  await repartirDeLaReference(admin);
});

test.afterAll(async () => {
  await admin.dispose();
});

/** Who holds which of this spec's seats, by stand id. */
async function occupants(): Promise<Record<string, string | null>> {
  const planning = await planningPersiste(admin);
  const resultat: Record<string, string | null> = {};
  for (const poste of planning.postes) {
    if (poste.stand?.id.startsWith('SOLV-DD-')) {
      resultat[poste.stand.id] = poste.animateur?.id ?? null;
    }
  }
  return resultat;
}

/**
 * The CDK starts a drag after a few pixels of movement, and lands it where the
 * pointer is released: a mousedown, a couple of moves, then a mouseup on the
 * target's centre. `dragTo` skips the intermediate moves the CDK needs.
 */
async function glisser(page: Page, source: Locator, cible: Locator): Promise<void> {
  // Les deux extrémités sont amenées à l'écran, PUIS mesurées, PUIS seulement
  // le glissement commence. L'ordre est ce qui compte, et il a demandé deux
  // essais.
  //
  // Le CDK fige le rectangle de chaque liste au démarrage du glissement
  // (`DropListRef._cacheParentPositions`, dans le gestionnaire du premier
  // mousemove). Une coordonnée lue *après* ce moment appartient donc à un autre
  // repère que celui du CDK, et `_canReceive` — qui compare son rectangle figé
  // à un `elementFromPoint` vivant — refuse le dépôt. Pire, faire défiler
  // pendant le glissement décale le conteneur sans que le CDK le sache : aucun
  // élément ne porte `cdkScrollable` ici, donc rien ne compense.
  //
  // Ma première correction lisait la cible après le `mouse.down()`. Elle
  // marchait, mais pas pour la raison que j'avais écrite : je croyais que
  // l'aperçu du CDK décalait les lignes, ce qu'il ne fait pas — `.rail-bloc`
  // est en `position: absolute` et l'aperçu en `position: fixed`. Ce qui
  // corrigeait vraiment, c'est l'attente de stabilité que
  // `scrollIntoViewIfNeeded` impose : le défaut d'origine était une mise en
  // page pas encore posée au moment de la mesure, d'amplitude proportionnelle
  // au rang de la ligne — d'où « plus la cible est basse, plus ça rate ».
  await source.scrollIntoViewIfNeeded();
  await cible.scrollIntoViewIfNeeded();
  const depart = await source.boundingBox();
  const arrivee = await cible.boundingBox();
  expect(depart, 'the dragged element should be on screen').not.toBeNull();
  expect(arrivee, 'the drop target should be on screen').not.toBeNull();

  await page.mouse.move(depart!.x + depart!.width / 2, depart!.y + depart!.height / 2);
  await page.mouse.down();
  // Le seuil du CDK, franchi avant de viser : c'est ce mouvement-là qui fige
  // les rectangles, et il doit partir de la source.
  await page.mouse.move(depart!.x + depart!.width / 2 + 8, depart!.y + depart!.height / 2 + 8);
  await page.mouse.move(arrivee!.x + arrivee!.width / 2, arrivee!.y + arrivee!.height / 2, {
    steps: 12,
  });
  await page.mouse.up();
}

test.describe('glisser-déposer', () => {
  test.beforeEach(async () => {
    await seedPlan();
    expect(await occupants()).toEqual({
      'SOLV-DD-S1': 'SOLV-DD-A',
      'SOLV-DD-S2': 'SOLV-DD-B',
      'SOLV-DD-S3': 'SOLV-DD-C',
    });
  });

  test('sur le calendrier, déposer un nom sur une personne échange les deux sièges', async ({
    browser,
  }) => {
    const avant = await occupants();
    const page = await pageAdmin(browser, admin);
    try {
      await ouvrirLaJournee(page, 'calendrier');
      const ligneUn = page.locator('.day-stand', { hasText: 'Stand Glisse un' });
      const ligneDeux = page.locator('.day-stand', { hasText: 'Stand Glisse deux' });
      await expect(ligneUn).toBeVisible();

      // The drag is taken by the handle, not by the name (the name stays
      // selectable and, on touch, scrollable — see the review of #308).
      await glisser(
        page,
        ligneUn.locator('.affectation-poignee'),
        ligneDeux.locator('.affectation-link'),
      );

      await expect(page.locator('mat-snack-bar-container')).toContainText(
        'ont échangé leurs sièges',
      );
      await expect.poll(async () => (await occupants())['SOLV-DD-S1']).toBe(avant['SOLV-DD-S2']);
      expect((await occupants())['SOLV-DD-S2']).toBe(avant['SOLV-DD-S1']);
      // The screen re-read the plan: the names swapped on it too.
      await expect(ligneUn).toContainText(await nomDe(avant['SOLV-DD-S2']!));
    } finally {
      await page.context().close();
    }
  });

  test('sur le calendrier, déposer un nom sur un siège libre le déplace et libère son siège', async ({
    browser,
  }) => {
    // Free one seat first: the third stand's holder is taken off it.
    const avant = await occupants();
    const liberation = await admin.post('/api/postes/SOLV-DD-P3/affectation');
    expect(liberation.status(), await liberation.text()).toBe(204);

    const page = await pageAdmin(browser, admin);
    try {
      await ouvrirLaJournee(page, 'calendrier');
      const ligneUn = page.locator('.day-stand', { hasText: 'Stand Glisse un' });
      const ligneTrois = page.locator('.day-stand', { hasText: 'Stand Glisse trois' });
      await expect(ligneTrois.locator('.siege-libre')).toBeVisible();

      await glisser(
        page,
        ligneUn.locator('.affectation-poignee'),
        ligneTrois.locator('.siege-libre'),
      );

      await expect(page.locator('mat-snack-bar-container')).toContainText('a changé de siège');
      await expect.poll(async () => (await occupants())['SOLV-DD-S3']).toBe(avant['SOLV-DD-S1']);
      expect((await occupants())['SOLV-DD-S1']).toBeNull();
    } finally {
      await page.context().close();
    }
  });

  test("sur le rail, déposer une vacation sur une autre personne l'échange", async ({
    browser,
  }) => {
    const avant = await occupants();
    const page = await pageAdmin(browser, admin);
    try {
      await ouvrirLaJournee(page, 'rail');
      const ligneA = page.locator('.rail-ligne', { hasText: 'Anna Glisse' });
      const ligneB = page.locator('.rail-ligne', { hasText: 'Boris Glisse' });
      await expect(ligneA.locator('.rail-bloc')).toBeVisible();

      await glisser(page, ligneA.locator('.rail-bloc-poignee'), ligneB.locator('.rail-cell'));

      await expect(page.locator('mat-snack-bar-container')).toContainText(
        'ont échangé leurs sièges',
      );
      const apres = await occupants();
      const standDeA = Object.keys(avant).find((stand) => avant[stand] === 'SOLV-DD-A')!;
      const standDeB = Object.keys(avant).find((stand) => avant[stand] === 'SOLV-DD-B')!;
      expect(apres[standDeA]).toBe('SOLV-DD-B');
      expect(apres[standDeB]).toBe('SOLV-DD-A');
    } finally {
      await page.context().close();
    }
  });

  test('un dépôt qui casserait une règle dure est refusé, et le plan ne bouge pas', async ({
    browser,
  }) => {
    // Cléo becomes a minor and leaves her seat; the first stand is adults-only.
    // Handing her Anna's seat there is a hard violation, and the rail is where
    // a free person receives a vacation.
    await seedPlan({ premierStandReserveMajeurs: true, cleoMineureEtLibre: true });
    const avant = await occupants();
    expect(avant['SOLV-DD-S3']).toBeNull();
    const page = await pageAdmin(browser, admin);
    try {
      await ouvrirLaJournee(page, 'rail');
      const ligneA = page.locator('.rail-ligne', { hasText: 'Anna Glisse' });
      const ligneC = page.locator('.rail-ligne', { hasText: 'Cléo Glisse' });
      await expect(ligneA.locator('.rail-bloc')).toBeVisible();

      await glisser(page, ligneA.locator('.rail-bloc-poignee'), ligneC.locator('.rail-cell'));

      await expect(page.locator('mat-snack-bar-container')).toContainText('Déplacement refusé');
      expect(await occupants()).toEqual(avant);
    } finally {
      await page.context().close();
    }
  });
});

/*
 * The keyboard twin of the drag (RGAA 7.3, WCAG 2.1.1 and 2.5.7): the same
 * moves, without ever calling `page.mouse`, `click` or `dragTo` — a focus, keys,
 * and nothing else. The day is named in the URL rather than picked in the
 * select, which would take a click.
 */
test.describe('déplacer au clavier', () => {
  test.beforeEach(async () => {
    await seedPlan();
  });

  test('sur le calendrier, la poignée ouvre le déplacement et échange deux personnes', async ({
    browser,
  }) => {
    const avant = await occupants();
    const page = await pageAdmin(browser, admin);
    try {
      await page.goto(`/journee?vue=calendrier&date=${JOUR}`);
      const ligneUn = page.locator('.day-stand', { hasText: 'Stand Glisse un' });
      const poignee = ligneUn.getByRole('button', { name: /^Déplacer Anna/ });
      await poignee.focus();
      await page.keyboard.press('Enter');

      const dialogue = page.getByRole('dialog', { name: 'Déplacer Anna Glisse' });
      // The dialog hands the focus to its search field: keys typed before
      // that lands would still reach the page underneath.
      await expect(dialogue.getByRole('combobox')).toBeFocused();
      await page.keyboard.type('Boris');
      // The filtered option has to be on screen before the arrow can reach it:
      // on a slow runner the keys otherwise land on an empty panel.
      await expect(page.getByRole('option', { name: /Boris Glisse/ })).toBeVisible();
      await page.keyboard.press('ArrowDown');
      await page.keyboard.press('Enter');
      const deplacer = dialogue.getByRole('button', { name: 'Déplacer' });
      await expect(deplacer).toBeEnabled();
      await deplacer.focus();
      await page.keyboard.press('Enter');

      await expect(page.locator('mat-snack-bar-container')).toContainText(
        'ont échangé leurs sièges',
      );
      await expect.poll(async () => (await occupants())['SOLV-DD-S1']).toBe(avant['SOLV-DD-S2']);
      expect((await occupants())['SOLV-DD-S2']).toBe(avant['SOLV-DD-S1']);
    } finally {
      await page.context().close();
    }
  });

  test('sur le rail, Entrée sur une ligne confie une vacation à une autre personne', async ({
    browser,
  }) => {
    const avant = await occupants();
    const page = await pageAdmin(browser, admin);
    try {
      await page.goto(`/journee?vue=rail&date=${JOUR}`);
      const ligneA = page.locator('.rail-ligne', { hasText: 'Anna Glisse' }).locator('.rail-cell');
      await ligneA.focus();
      await page.keyboard.press('Enter');

      const dialogue = page.getByRole('dialog');
      await expect(dialogue).toContainText('Déplacer une vacation de Anna Glisse');
      await expect(dialogue.getByRole('combobox')).toBeFocused();
      await page.keyboard.type('Cléo');
      // The filtered option has to be on screen before the arrow can reach it:
      // on a slow runner the keys otherwise land on an empty panel.
      await expect(page.getByRole('option', { name: /Cléo Glisse/ })).toBeVisible();
      await page.keyboard.press('ArrowDown');
      await page.keyboard.press('Enter');
      const deplacer = dialogue.getByRole('button', { name: 'Déplacer' });
      await expect(deplacer).toBeEnabled();
      await deplacer.focus();
      await page.keyboard.press('Enter');

      await expect(page.locator('mat-snack-bar-container')).toContainText(
        'ont échangé leurs sièges',
      );
      await expect.poll(async () => (await occupants())['SOLV-DD-S1']).toBe(avant['SOLV-DD-S3']);
    } finally {
      await page.context().close();
    }
  });
});

/** The page opens on the event's first day, which other specs' créneaux may own: pick this spec's date. */
async function ouvrirLaJournee(page: Page, vue: 'calendrier' | 'rail'): Promise<void> {
  await page.goto(`/journee?vue=${vue}`);
  await page.getByRole('combobox', { name: 'Journée' }).click();
  await page.getByRole('option', { name: new RegExp(JOUR) }).click();
  // The select's backdrop outlives the click by an animation frame, and a
  // mousedown landing on it would start no drag at all.
  await expect(page.locator('.cdk-overlay-backdrop')).toHaveCount(0);
  await expect(page.getByRole('listbox')).toHaveCount(0);
}

async function nomDe(animateurId: string): Promise<string> {
  return ANIMATEURS.find((animateur) => animateur.id === animateurId)!.prenom;
}
