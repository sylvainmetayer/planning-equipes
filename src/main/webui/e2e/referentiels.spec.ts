// Reference-data CRUD through the real UI, focused on the animateur record —
// the one the foire au planning extended (email, espace link) — plus the
// lightest referential (typologies) for the create/delete round trip, and the
// copy of a stand's schedule into another (from its form) and into a whole
// selection (from the bulk edit).

import { APIRequestContext, expect, test } from '@playwright/test';
import {
  SEED,
  contexteAdmin,
  dialogueOuvert,
  jourMois,
  ouvrirSelect,
  pageAdmin,
  seedPlanning,
} from './support';
import { repartirDeLaReference } from './reference';

let admin: APIRequestContext;

test.beforeAll(async ({ playwright }, testInfo) => {
  admin = await contexteAdmin(playwright, testInfo.project.use.baseURL as string);
  await repartirDeLaReference(admin);
  await seedPlanning(admin);
  // Idempotence across runs: drop what this spec creates through the UI.
  await admin.delete('/api/animateurs/E2E-UI').catch(() => undefined);
  await admin.delete('/api/typologies/E2E-TYPO').catch(() => undefined);
  await admin.delete('/api/typologies/E2E-TYPO-CAP').catch(() => undefined);
  await admin.delete('/api/typologies/E2E-TYPO-NOTE').catch(() => undefined);
  await admin.delete(`/api/stands/${STAND_MODELE}`).catch(() => undefined);
});

test.afterAll(async () => {
  await admin.dispose();
});

test.describe('fiche animateur', () => {
  test('créer, retrouver, consulter, puis supprimer un animateur avec e-mail', async ({
    browser,
  }) => {
    test.slow();
    const page = await pageAdmin(browser, admin);
    await page.goto('/animateurs');

    // Create, with the new email field.
    await page.getByRole('button', { name: 'Ajouter' }).click();
    const dialog = await dialogueOuvert(page);
    await dialog.getByLabel('Identifiant').fill('E2E-UI');
    await dialog.getByLabel('Prénom').fill('Uma');
    await dialog.getByLabel('Nom', { exact: true }).fill('E2E');
    await dialog.getByLabel('Date de naissance').fill('1995-05-05');
    await dialog.getByLabel('E-mail').fill('uma@example.org');
    await dialog.getByRole('button', { name: "Créer l'animateur" }).click();
    await expect(dialog).toBeHidden();

    // The quick filter narrows the table to the new row.
    await page.getByLabel('Filtrer').fill('E2E-UI');
    const ligne = page.getByRole('row', { name: /E2E-UI/ });
    await expect(ligne).toBeVisible();

    // The espace link exists right away: the store reload brought the
    // database-generated token back, so the copy button is enabled.
    await expect(
      ligne.getByRole('button', { name: 'Copier le lien de son espace animateur' }),
    ).toBeEnabled();

    // The consultation dialog shows the new fields.
    await ligne.getByRole('button', { name: 'Consulter le détail' }).click();
    await expect(page.getByRole('dialog')).toContainText('uma@example.org');
    await expect(page.getByRole('dialog')).toContainText('Lien espace animateur');
    await page.keyboard.press('Escape');
    await expect(page.getByRole('dialog')).toBeHidden();

    // Regenerating the token rotates the espace link.
    await ligne.getByRole('button', { name: 'Régénérer le lien de son espace' }).click();
    await page.getByRole('dialog').getByRole('button', { name: 'Régénérer' }).click();
    await expect(page.getByText('Nouveau lien généré.')).toBeVisible();

    // Delete, behind its confirmation.
    await ligne.getByRole('button', { name: 'Supprimer' }).click();
    await page.getByRole('dialog').getByRole('button', { name: 'Supprimer' }).click();
    await page.getByLabel('Filtrer').fill('');
    await expect(page.getByRole('row', { name: /E2E-UI/ })).toHaveCount(0);
    await page.context().close();
  });

  test("l'aide sous la date de naissance ne recouvre pas la case Manager", async ({ browser }) => {
    // Défaut signalé sur une capture d'écran : la zone de sous-titre d'un
    // `mat-form-field` ne réserve par défaut la place que d'une ligne, et cette
    // aide-là en tient trois — le texte passait par-dessus la case à cocher de
    // la ligne suivante. Une géométrie, parce que rien d'autre ne le voit : le
    // DOM est correct, seul le rendu se superpose.
    const page = await pageAdmin(browser, admin);
    // Étroit à dessein : c'est là que l'aide déborde le plus.
    await page.setViewportSize({ width: 760, height: 900 });
    await page.goto('/animateurs');
    await page.getByRole('button', { name: 'Ajouter' }).click();
    const dialog = page.getByRole('dialog');
    await expect(dialog).toBeVisible();

    const chevauchements = await dialog.evaluate((racine) => {
      const rectangles = Array.from(racine.querySelectorAll('mat-hint')).map((hint) => ({
        texte: (hint.textContent ?? '').trim().slice(0, 30),
        boite: hint.getBoundingClientRect(),
      }));
      const voisins = Array.from(
        racine.querySelectorAll('mat-checkbox, .subform, .form-warning'),
      ).map((element) => ({
        texte: (element.textContent ?? '').trim().slice(0, 30),
        boite: element.getBoundingClientRect(),
      }));
      const croise = (a: DOMRect, b: DOMRect) =>
        a.left < b.right && b.left < a.right && a.top < b.bottom && b.top < a.bottom;
      return rectangles
        .flatMap((hint) => voisins.map((voisin) => ({ hint, voisin })))
        .filter(({ hint, voisin }) => croise(hint.boite, voisin.boite))
        .map(({ hint, voisin }) => `« ${hint.texte} » sur « ${voisin.texte} »`);
    });

    expect(chevauchements).toEqual([]);
    await page.context().close();
  });
});

test.describe('typologies', () => {
  test('créer puis supprimer une typologie', async ({ browser }) => {
    const page = await pageAdmin(browser, admin);
    await page.goto('/typologies');

    await page.getByRole('button', { name: 'Ajouter' }).click();
    const dialog = await dialogueOuvert(page);
    await dialog.getByLabel('Identifiant').fill('E2E-TYPO');
    await dialog.getByLabel('Libellé').fill('Typologie E2E');
    await dialog.getByRole('button', { name: /Créer/ }).click();
    await expect(dialog).toBeHidden();
    const ligne = page.getByRole('row', { name: /E2E-TYPO/ });
    await expect(ligne).toBeVisible();

    await ligne.getByRole('button', { name: 'Supprimer' }).click();
    await page.getByRole('dialog').getByRole('button', { name: 'Supprimer' }).click();
    await expect(page.getByRole('row', { name: /E2E-TYPO/ })).toHaveCount(0);
    await page.context().close();
  });

  // The cap of issue #594 was droppable on its way to the database: the form
  // sent it, the service rebuilt the item without it, and the response came
  // back empty. A round trip through the real screen is what catches that —
  // the unit tests on both ends were green while the middle lost the value.
  test('le plafond de créneaux saisi dans le formulaire est bien enregistré', async ({
    browser,
  }) => {
    const page = await pageAdmin(browser, admin);
    await page.goto('/typologies');

    await page.getByRole('button', { name: 'Ajouter' }).click();
    const dialog = await dialogueOuvert(page);
    await dialog.getByLabel('Identifiant').fill('E2E-TYPO-CAP');
    await dialog.getByLabel('Libellé').fill('Typologie plafonnée');
    await dialog.getByLabel('Créneaux maximum par animateur').fill('4');
    await dialog.getByRole('button', { name: /Créer/ }).click();
    await expect(dialog).toBeHidden();

    // Read back from the server, not from the screen: the bug was that the
    // value never reached it.
    const reponse = await admin.get('/api/typologies');
    expect(reponse.ok()).toBe(true);
    const typologies = (await reponse.json()) as {
      id: string;
      maxCreneauxParAnimateur: number | null;
    }[];
    expect(typologies.find((each) => each.id === 'E2E-TYPO-CAP')?.maxCreneauxParAnimateur).toBe(4);

    // And the edit round trip: reopening the form shows it, and saving again
    // does not silently clear it.
    await page.reload();
    const ligne = page.getByRole('row', { name: /E2E-TYPO-CAP/ });
    await ligne.getByRole('button', { name: 'Modifier' }).click();
    const edition = await dialogueOuvert(page);
    await expect(edition.getByLabel('Créneaux maximum par animateur')).toHaveValue('4');
    await edition.getByLabel('Créneaux maximum par animateur').fill('2');
    await edition.getByRole('button', { name: /Modifier la typologie/ }).click();
    await expect(edition).toBeHidden();

    const apres = await admin.get('/api/typologies');
    const relues = (await apres.json()) as { id: string; maxCreneauxParAnimateur: number | null }[];
    expect(relues.find((each) => each.id === 'E2E-TYPO-CAP')?.maxCreneauxParAnimateur).toBe(2);
    await page.context().close();
  });

  // The organiser's own note, the same round trip as the cap above: written in
  // the form, read back from the server, and shown again where the plan is read
  // by typologie — « cette typologie nécessite d'apprendre 45 jeux ».
  test('la description saisie dans le formulaire se relit, et se retrouve dans la vue', async ({
    browser,
  }) => {
    const page = await pageAdmin(browser, admin);
    await page.goto('/typologies');

    await page.getByRole('button', { name: 'Ajouter' }).click();
    const dialog = await dialogueOuvert(page);
    await dialog.getByLabel('Identifiant').fill('E2E-TYPO-NOTE');
    await dialog.getByLabel('Libellé').fill('Typologie annotée');
    await dialog.getByLabel('Description').fill("Nécessite d'apprendre 45 jeux");
    await dialog.getByRole('button', { name: /Créer/ }).click();
    await expect(dialog).toBeHidden();

    const reponse = await admin.get('/api/typologies');
    const typologies = (await reponse.json()) as { id: string; description: string | null }[];
    expect(typologies.find((each) => each.id === 'E2E-TYPO-NOTE')?.description).toBe(
      "Nécessite d'apprendre 45 jeux",
    );

    // Reopening the form shows it, and a save that touches nothing else keeps it.
    await page.reload();
    const ligne = page.getByRole('row', { name: /E2E-TYPO-NOTE/ });
    await ligne.getByRole('button', { name: 'Modifier' }).click();
    const edition = await dialogueOuvert(page);
    await expect(edition.getByLabel('Description')).toHaveValue("Nécessite d'apprendre 45 jeux");
    await edition.getByRole('button', { name: /Modifier la typologie/ }).click();
    await expect(edition).toBeHidden();

    await page.goto('/typologies-planning');
    await expect(page.getByText("Nécessite d'apprendre 45 jeux").first()).toBeVisible();
    await page.context().close();
  });

  // The four renderings, the filters and the links out: the screen is the whole
  // point of the rework, and a tab that throws on an empty axis would only show
  // up here.
  test('la vue par typologie propose ses quatre rendus, ses filtres et ses liens', async ({
    browser,
  }) => {
    const page = await pageAdmin(browser, admin);
    await page.goto('/typologies-planning');

    await expect(page.getByRole('heading', { name: 'Planning par typologie' })).toBeVisible();

    // `mat-button-toggle` renders radios in a radiogroup, not buttons — same
    // reading as the Diagnostic tabs. Each rendering is asserted on what only
    // it draws, so a tab that switches without rendering fails here.
    const onglets = page.getByRole('radiogroup', { name: 'Rendu du planning par typologie' });
    await onglets.getByText('Barres comparées').click();
    await expect(page.locator('.typologies-barres')).toBeVisible();

    await onglets.getByText('Typologie × jour').click();
    // Une édition sans jour tenu dit pourquoi elle ne dessine rien plutôt que
    // de montrer une grille vide : les deux sont des succès.
    await expect(page.locator('.typologies-heatmap, .empty-hint').first()).toBeVisible();

    await onglets.getByText('Cartes').click();
    await expect(page.locator('.typologies-cartes')).toBeVisible();

    await onglets.getByText('Tableau').click();
    await expect(page.locator('.typologies-table')).toBeVisible();

    // A search nothing matches empties the table and says so, rather than
    // looking like an edition with no typologies at all.
    await page.getByLabel('Rechercher').fill('zzz-aucune-typologie');
    await expect(page.getByText('Aucune ligne ne correspond au filtre.')).toBeVisible();
    await page.getByRole('button', { name: 'Tout afficher' }).click();

    // Every animateur name is a link to that person's timeline.
    const lien = page.locator('a.typologies-lien').first();
    if (await lien.count()) {
      await expect(lien).toHaveAttribute('href', /\/timeline\?animateur=/);
    }
    await page.context().close();
  });
});

/** The stand whose typical day the two tests below copy. */
const STAND_MODELE = 'E2E-HOR';

/** One stand of the edition as `GET /api/stands` returns it — only what the assertions read. */
interface StandLu {
  id: string;
  horaires: { id: number | null; fenetres: { heureDebut: string }[] }[];
  ouvertures: { id: number | null; date: string; heureDebut: string }[];
}

async function lireStand(id: string): Promise<StandLu> {
  const reponse = await admin.get('/api/stands');
  expect(reponse.ok()).toBe(true);
  const stand = ((await reponse.json()) as StandLu[]).find((each) => each.id === id);
  expect(stand, `stand ${id} absent`).toBeDefined();
  return stand as StandLu;
}

test.describe('horaires de stand', () => {
  test.beforeAll(async () => {
    // The model: open from 10:00 every day, and a dated opening on the seeded
    // day that narrows it to 11:00-12:00 — one row of each kind, so the copy
    // of the rules and of the exceptions are both observable, without an
    // effectif the one-seat seeded stands could not hold.
    const modele = await admin.post('/api/stands', {
      data: {
        id: STAND_MODELE,
        nom: 'Stand modèle E2E',
        typologiesProposees: ['STRATEGIE'],
        effectifMin: 1,
        effectifMax: 1,
        reserveMajeurs: false,
        horaires: [{ mode: 'OUVERTURE', jours: 'TOUS', fenetres: [{ heureDebut: '10:00:00' }] }],
        ouvertures: [
          { date: SEED.jour, heureDebut: '11:00:00', heureFin: '12:00:00', motif: 'Inauguration' },
        ],
      },
    });
    expect(modele.ok(), await modele.text()).toBe(true);
  });

  test.afterAll(async () => {
    await admin.delete(`/api/stands/${STAND_MODELE}`).catch(() => undefined);
  });

  test('la fiche copie les horaires d’un autre stand, l’aperçu puis la page Ouvertures les reflètent', async ({
    browser,
  }) => {
    const page = await pageAdmin(browser, admin);
    await page.goto('/stands');
    await page.getByLabel('Filtrer').fill(SEED.standCible);
    const ligne = page.getByRole('row', { name: new RegExp(SEED.standCible) });
    await expect(ligne).toBeVisible();
    await ligne.getByRole('button', { name: 'Modifier' }).click();
    const formulaire = page
      .getByRole('dialog')
      .filter({ hasText: `Modifier le stand ${SEED.standCible}` });
    await expect(formulaire).toBeVisible();
    // The preview cell of the seeded day — open all day so far: no rule, no exception.
    const jourSeme = formulaire.locator('.apercu-jour').filter({ hasText: jourMois(SEED.jour) });
    await expect(jourSeme).toContainText('Ouvert toute la journée');

    await ouvrirSelect(page, 'Copier les horaires de');
    await page.getByRole('option', { name: 'Stand modèle E2E' }).click();

    // The draft took the copy — the rule on its line, the exception winning
    // on its day in the preview — and nothing is written yet.
    await expect(formulaire.getByLabel('Fenêtres de la journée')).toHaveValue('10:00-');
    await expect(jourSeme).toContainText('Ouvert 11:00 → 12:00');
    await expect(formulaire.getByText(/Horaires de « Stand modèle E2E » copiés/)).toBeVisible();
    expect((await lireStand(SEED.standCible)).horaires).toHaveLength(0);

    await formulaire.getByRole('button', { name: 'Modifier le stand' }).click();
    await expect(formulaire).toBeHidden();

    // Saved as new rows of the target stand, not as the model's.
    const modele = await lireStand(STAND_MODELE);
    const cible = await lireStand(SEED.standCible);
    expect(cible.horaires).toHaveLength(1);
    expect(cible.horaires[0].fenetres[0].heureDebut).toMatch(/^10:00/);
    expect(cible.horaires[0].id).not.toBe(modele.horaires[0].id);
    expect(cible.ouvertures).toHaveLength(1);
    expect(cible.ouvertures[0].date).toBe(SEED.jour);
    expect(cible.ouvertures[0].id).not.toBe(modele.ouvertures[0].id);

    // The Ouvertures page reads the store afresh: the seeded day now opens at 11:00.
    await page.goto('/ouvertures');
    await expect(page.getByRole('button', { name: 'Actualiser' })).toBeVisible();
    const ligneOuvertures = page.getByRole('row', { name: /Stand E2E deux/ });
    await expect(ligneOuvertures).toBeVisible();
    await expect(ligneOuvertures).toContainText('11:00');
    await page.context().close();
  });

  test('la modification en masse applique les horaires d’un stand modèle à la sélection', async ({
    browser,
  }) => {
    const page = await pageAdmin(browser, admin);
    await page.goto('/stands');
    // The two seeded stands, and only them: the filter is what the "select all" ticks.
    await page.getByLabel('Filtrer').fill('E2E-S');
    await expect(page.getByRole('row', { name: /E2E-S/ })).toHaveCount(2);
    await page.getByRole('checkbox', { name: 'Tout sélectionner' }).check();
    await page.getByRole('button', { name: 'Modifier la sélection' }).click();
    const dialog = page.getByRole('dialog').filter({ hasText: 'Modifier 2 stands' });
    await expect(dialog).toBeVisible();

    await ouvrirSelect(page, 'Que faire des horaires');
    await page.getByRole('option', { name: "Remplacer par ceux d'un stand" }).click();
    // Naming no model stand yet changes nothing: the submit stays disabled.
    const appliquer = dialog.getByRole('button', { name: 'Appliquer à la sélection' });
    await expect(appliquer).toBeDisabled();
    await ouvrirSelect(page, 'Stand modèle');
    await page.getByRole('option', { name: 'Stand modèle E2E' }).click();
    await expect(dialog.getByText(/1 règle\(s\) et 1 exception\(s\) datée\(s\)/)).toBeVisible();
    await expect(appliquer).toBeEnabled();
    await appliquer.click();
    await expect(dialog).toBeHidden();

    for (const id of [SEED.standDemandeur, SEED.standCible]) {
      const stand = await lireStand(id);
      expect(stand.horaires, id).toHaveLength(1);
      expect(stand.horaires[0].fenetres[0].heureDebut).toMatch(/^10:00/);
      expect(stand.ouvertures, id).toHaveLength(1);
      expect(stand.ouvertures[0].date).toBe(SEED.jour);
    }
    // The model itself was not in the selection and keeps its own rows.
    expect((await lireStand(STAND_MODELE)).horaires).toHaveLength(1);
    await page.context().close();
  });
});
