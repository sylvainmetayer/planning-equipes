// The competences grid, through the interface only: two cells typed from the
// keyboard and saved, read back through the API; and the concurrent-modification
// guard applied row by row, with « Recharger » writing nothing of this screen.
//
// Pattern: modification-concurrente.spec.ts for the other session.

import { APIRequestContext, Page, expect, test } from '@playwright/test';
import { contexteAdmin, pageAdmin, seedReferentielSolveur, typologieId } from './support';
import { repartirDeLaReference } from './reference';

const IDS = { alice: 'E2E-CP1', bruno: 'E2E-CP2' } as const;

/** Seeded by the reference database (V3): every stand of the seeds carries it. */
const TYPOLOGIE_CODE = 'STRATEGIE';

let admin: APIRequestContext;
/** Its id, which the application drew (`T8`…) and which the grid and the fiches key on. */
let TYPOLOGIE: string;

interface AnimateurApi {
  id: string;
  nom: string;
  competences: Record<string, string>;
  modifieLe: string | null;
}

test.beforeAll(async ({ playwright }, testInfo) => {
  admin = await contexteAdmin(playwright, testInfo.project.use.baseURL as string);
  await repartirDeLaReference(admin);
  TYPOLOGIE = await typologieId(admin, TYPOLOGIE_CODE);
});

test.beforeEach(async () => {
  await seedReferentielSolveur(
    admin,
    [
      { id: IDS.alice, prenom: 'Alice', nom: 'Compétente', dateNaissance: '1990-01-01' },
      { id: IDS.bruno, prenom: 'Bruno', nom: 'Compétent', dateNaissance: '1992-02-02' },
    ],
    [],
    [],
  );
});

test.afterAll(async () => {
  await admin.dispose();
});

async function ficheEnBase(id: string): Promise<AnimateurApi> {
  const lecture = await admin.get('/api/animateurs');
  const roster = (await lecture.json()) as AnimateurApi[];
  const fiche = roster.find((candidat) => candidat.id === id);
  expect(fiche, `${id} should be seeded`).toBeDefined();
  return fiche!;
}

/** The other session: writes the fiche through the API, with no precondition. */
async function autreSessionEcrit(id: string, competences: Record<string, string>): Promise<void> {
  const fiche = await ficheEnBase(id);
  const ecriture = await admin.put(`/api/animateurs/${id}`, {
    data: { ...fiche, competences, modifieLe: null },
  });
  expect(ecriture.status(), await ecriture.text()).toBe(200);
}

function cellule(page: Page, animateurId: string, typologieId: string) {
  return page.locator(`[data-cellule="${animateurId}#${typologieId}"]`);
}

async function ouvrirLaGrille(page: Page, typologies: string[] = []): Promise<void> {
  await page.goto(
    typologies.length ? `/competences?typologies=${typologies.join(',')}` : '/competences',
  );
  await expect(page.locator('#contenu')).toContainText('Compétences');
  await expect(cellule(page, IDS.alice, TYPOLOGIE)).toBeVisible();
}

test("deux cases saisies au clavier, enregistrées, relues par l'API", async ({ browser }) => {
  const page = await pageAdmin(browser, admin);
  try {
    // One column, so the first cell of the first row is the one named below:
    // the way in lands on the first displayed cell, whichever typologie leads.
    await ouvrirLaGrille(page, [TYPOLOGIE]);

    // The way in: arrow down from the filter lands on the first cell.
    await page.getByLabel('Filtrer par nom').fill('Compétent');
    await page.getByLabel('Filtrer par nom').press('ArrowDown');
    await expect(cellule(page, IDS.alice, TYPOLOGIE)).toBeFocused();

    await page.keyboard.press('3');
    await expect(cellule(page, IDS.alice, TYPOLOGIE)).toHaveAttribute('data-niveau', 'REFERENT');
    // Enter goes down, like a spreadsheet; 1 is « Débutant ».
    await page.keyboard.press('Enter');
    await expect(cellule(page, IDS.bruno, TYPOLOGIE)).toBeFocused();
    await page.keyboard.press('1');
    await expect(cellule(page, IDS.bruno, TYPOLOGIE)).toHaveAttribute('data-niveau', 'DEBUTANT');

    // Nothing written yet.
    expect((await ficheEnBase(IDS.alice)).competences).toEqual({});
    // The count sits in its own span: the accessible name has no space before it.
    await expect(page.getByRole('button', { name: /Enregistrer\s*\(2\)/ })).toBeEnabled();

    await page.getByRole('button', { name: /Enregistrer/ }).click();
    // The confirmation is a notification, in the overlay rather than the page.
    await expect(page.getByText('Compétences enregistrées')).toBeVisible();

    expect((await ficheEnBase(IDS.alice)).competences).toEqual({ [TYPOLOGIE]: 'REFERENT' });
    expect((await ficheEnBase(IDS.bruno)).competences).toEqual({ [TYPOLOGIE]: 'DEBUTANT' });
    await expect(page.getByRole('button', { name: /Enregistrer/ })).toBeDisabled();
  } finally {
    await page.context().close();
  }
});

/** Types Alice and Bruno, then lets the other session write Bruno before this one saves. */
async function saisirPuisSubirUneEcritureAilleurs(page: Page): Promise<void> {
  await ouvrirLaGrille(page);
  await cellule(page, IDS.alice, TYPOLOGIE).click();
  await expect(cellule(page, IDS.alice, TYPOLOGIE)).toHaveAttribute('data-niveau', 'DEBUTANT');
  await cellule(page, IDS.bruno, TYPOLOGIE).focus();
  await page.keyboard.press('3');
  await expect(cellule(page, IDS.bruno, TYPOLOGIE)).toHaveAttribute('data-niveau', 'REFERENT');

  await autreSessionEcrit(IDS.bruno, { [TYPOLOGIE]: 'AUTONOME' });

  await page.getByRole('button', { name: /Enregistrer/ }).click();
}

test.describe('modification concurrente, ligne par ligne', () => {
  test("« Recharger » n'écrit rien de la ligne périmée, les autres lignes sont écrites", async ({
    browser,
  }) => {
    const page = await pageAdmin(browser, admin);
    try {
      await saisirPuisSubirUneEcritureAilleurs(page);

      const conflit = page.getByRole('dialog').filter({ hasText: 'Modifiée entre-temps' });
      await expect(conflit).toBeVisible();
      await expect(conflit).toContainText(IDS.bruno);
      await expect(conflit).toContainText('autre session');
      await conflit.getByRole('button', { name: 'Recharger' }).click();

      await expect(page.getByRole('dialog')).toHaveCount(0);
      // Alice's row was written; Bruno's shows the other session's version.
      expect((await ficheEnBase(IDS.alice)).competences).toEqual({ [TYPOLOGIE]: 'DEBUTANT' });
      expect((await ficheEnBase(IDS.bruno)).competences).toEqual({ [TYPOLOGIE]: 'AUTONOME' });
      await expect(cellule(page, IDS.bruno, TYPOLOGIE)).toHaveAttribute('data-niveau', 'AUTONOME');
      await expect(page.getByRole('button', { name: /Enregistrer/ })).toBeDisabled();
    } finally {
      await page.context().close();
    }
  });

  test('« Écraser quand même » impose la saisie de cet écran', async ({ browser }) => {
    const page = await pageAdmin(browser, admin);
    try {
      await saisirPuisSubirUneEcritureAilleurs(page);

      const conflit = page.getByRole('dialog').filter({ hasText: 'Modifiée entre-temps' });
      await expect(conflit).toBeVisible();
      await conflit.getByRole('button', { name: 'Écraser quand même' }).click();

      // The dialog closes before the second write lands: the confirmation is
      // what says the overwrite reached the server, so it comes before the read.
      await expect(page.getByRole('dialog')).toHaveCount(0);
      await expect(page.getByText('Compétences enregistrées')).toBeVisible();
      expect((await ficheEnBase(IDS.bruno)).competences).toEqual({ [TYPOLOGIE]: 'REFERENT' });
      await expect(cellule(page, IDS.bruno, TYPOLOGIE)).toHaveAttribute('data-niveau', 'REFERENT');
    } finally {
      await page.context().close();
    }
  });
});
