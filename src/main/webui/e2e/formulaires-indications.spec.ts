// A hint that wraps onto a second line must push the next row down, not run
// into the field below it and its label: the form dialogs lay their fields on
// a grid, and a fixed-height subscript left the second line of « Clé lisible
// citée par les fichiers d'import (facultatif) » over the next field's label.
// Measured in a real browser, since jsdom lays nothing out.

import { APIRequestContext, Locator, Page, expect, test } from '@playwright/test';
import { SEED, contexteAdmin, dialogueOuvert, pageAdmin, seedPlanning } from './support';
import { repartirDeLaReference } from './reference';

let admin: APIRequestContext;

test.beforeAll(async ({ playwright }, testInfo) => {
  admin = await contexteAdmin(playwright, testInfo.project.use.baseURL as string);
  await repartirDeLaReference(admin);
  await seedPlanning(admin);
});

test.afterAll(async () => {
  await admin.dispose();
});

/** A narrow window, where the hints of a two-column form wrap. */
const VIEWPORT = { width: 1024, height: 900 };

/**
 * Every pair of a hint and another field whose boxes cross, named by their
 * text. The other field is its outlined box, not its floating label: an
 * outline label sits astride the top border, a few pixels above the box, and a
 * one-line hint ends right there in every Material form — what must never
 * happen is a hint's line running into the box below. Empty when every hint
 * keeps to its own row.
 */
async function hintsOverFields(dialog: Locator): Promise<string[]> {
  return dialog.evaluate((root) => {
    const visible = (rect: DOMRect) => rect.width > 0 && rect.height > 0;
    // One pixel of slack for subpixel layout: touching is not overlapping.
    const crosses = (a: DOMRect, b: DOMRect) =>
      a.left + 1 < b.right && b.left + 1 < a.right && a.top + 1 < b.bottom && b.top + 1 < a.bottom;
    const fields = Array.from(root.querySelectorAll('mat-form-field'));
    const overlaps: string[] = [];
    for (const field of fields) {
      for (const hint of Array.from(field.querySelectorAll('mat-hint'))) {
        const hintBox = hint.getBoundingClientRect();
        if (!visible(hintBox)) {
          continue;
        }
        for (const other of fields) {
          const box = other === field ? null : other.querySelector('.mat-mdc-text-field-wrapper');
          const otherBox = box?.getBoundingClientRect();
          if (otherBox && visible(otherBox) && crosses(hintBox, otherBox)) {
            const label = other.querySelector('mat-label')?.textContent?.trim();
            overlaps.push(`« ${hint.textContent?.trim()} » / « ${label} »`);
          }
        }
      }
    }
    return overlaps;
  });
}

/** Asserts the dialog carries at least one visible hint, and that none of them runs into another field. */
async function expectNoHintOverAField(page: Page, dialog: Locator): Promise<void> {
  await expect(dialog.locator('mat-hint').first()).toBeVisible();
  // The open animation scales the dialog: measure once it has settled.
  await page.waitForTimeout(300);
  expect(await hintsOverFields(dialog)).toEqual([]);
}

test('les indications du formulaire de stand ne débordent sur aucun champ', async ({ browser }) => {
  const page = await pageAdmin(browser, admin);
  await page.setViewportSize(VIEWPORT);
  await page.goto(`/stands/${SEED.standDemandeur}`);

  await page.getByRole('heading', { name: 'Règles', exact: true }).click();
  await page.getByRole('button', { name: 'Modifier les règles' }).click();
  const dialog = await dialogueOuvert(page);

  await expectNoHintOverAField(page, dialog);

  await page.context().close();
});

test("les indications du formulaire d'emplacement ne débordent sur aucun champ", async ({
  browser,
}) => {
  const page = await pageAdmin(browser, admin);
  await page.setViewportSize(VIEWPORT);
  await page.goto('/stands?onglet=lieux');

  await page.getByRole('button', { name: 'Ajouter' }).click();
  const dialog = await dialogueOuvert(page);

  await expectNoHintOverAField(page, dialog);

  await page.context().close();
});
