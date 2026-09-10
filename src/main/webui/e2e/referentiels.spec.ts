// Reference-data CRUD through the real UI, focused on the animateur record —
// the one the foire au planning extended (email, espace link) — plus the
// lightest referential (typologies) for the create/delete round trip.

import { APIRequestContext, expect, test } from '@playwright/test';
import { contexteAdmin, pageAdmin, seedPlanning } from './support';
import { repartirDeLaReference } from './reference';

let admin: APIRequestContext;

test.beforeAll(async ({ playwright }, testInfo) => {
  admin = await contexteAdmin(playwright, testInfo.project.use.baseURL as string);
  await repartirDeLaReference(admin);
  await seedPlanning(admin);
  // Idempotence across runs: drop what this spec creates through the UI.
  await admin.delete('/api/animateurs/E2E-UI').catch(() => undefined);
  await admin.delete('/api/typologies/E2E-TYPO').catch(() => undefined);
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
    const dialog = page.getByRole('dialog');
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
    const dialog = page.getByRole('dialog');
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
});
