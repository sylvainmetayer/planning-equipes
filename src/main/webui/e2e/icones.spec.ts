// The icon font, checked where it actually fails: in a browser.
//
// `<mat-icon>delete</mat-icon>` is the word "delete" until the Material Icons
// font turns it into one glyph. When that font does not arrive — a 404 the SPA
// fallback dresses up as an HTML page, a cache holding a truncated file, a
// stylesheet that stopped applying `.material-icons` — nothing throws: the
// browser simply draws the word, the icon button clips it, and every button on
// every screen shows « ... ». No unit test sees this, because jsdom has no
// fonts at all.
//
// So the check is made of three independent facts, each of which alone would
// have caught the defect:
//   1. the file is served, and is a font rather than a page pretending to be one;
//   2. the browser reports the face as loaded and usable;
//   3. the rendered icons are single square glyphs, not clipped words — asserted
//      on a one-word ligature (`delete`) and on a two-word one (`grid_view`),
//      whose underscore is its own failure mode.
// A screenshot of the toolbar is attached either way, so the report shows what
// the assertions claim.

import { expect, test } from '@playwright/test';
import { contexteAdmin, pageAdmin, seedPlanning } from './support';
import type { APIRequestContext } from '@playwright/test';

/** Widest an icon may render: 24 px of glyph, plus room for a theme that grew it. */
const LARGEUR_MAX_GLYPHE = 32;

let admin: APIRequestContext;

test.beforeAll(async ({ playwright }, testInfo) => {
  admin = await contexteAdmin(playwright, testInfo.project.use.baseURL as string);
  await seedPlanning(admin);
});

test.afterAll(async () => {
  await admin.dispose();
});

test('la police des icônes est servie comme une police, pas comme une page', async () => {
  const reponse = await admin.get('/fonts/material-icons.woff2');

  expect(reponse.status()).toBe(200);
  // The SPA fallback answers index.html on an unknown path: a 200 proves nothing
  // on its own, the four magic bytes do.
  const octets = await reponse.body();
  expect(octets.subarray(0, 4).toString('latin1')).toBe('wOF2');
  expect(octets.byteLength).toBeGreaterThan(50_000);
});

test('le navigateur charge la police et rend les icônes en glyphes, pas en mots rognés', async ({
  browser
}, testInfo) => {
  const page = await pageAdmin(browser, admin);
  await page.goto('/stands');
  await page.waitForLoadState('networkidle');

  // 2. The face is there and usable at the size mat-icon draws it.
  const police = await page.evaluate(async () => {
    await document.fonts.ready;
    return {
      chargee: document.fonts.check('24px "Material Icons"'),
      faces: [...document.fonts].filter((f) => f.family === 'Material Icons').map((f) => f.status)
    };
  });
  expect(police.faces, "la déclaration @font-face n'a pas été vue").not.toHaveLength(0);
  expect(police.faces).toContain('loaded');
  expect(police.chargee, 'la police est déclarée mais inutilisable').toBe(true);

  // 3. What the user actually sees. A ligature that formed is one square glyph;
  // the un-ligatured word is far wider than the button and gets clipped.
  const icone = page.locator('mat-icon', { hasText: 'delete' }).first();
  await expect(icone).toBeVisible();
  const mesure = await icone.evaluate((el) => {
    const rect = el.getBoundingClientRect();
    return {
      famille: getComputedStyle(el).fontFamily,
      largeur: rect.width,
      hauteur: rect.height,
      // A clipped word overflows its box; a glyph does not.
      debordement: el.scrollWidth - el.clientWidth
    };
  });

  // Attached before the assertions, never after: on a failure the report must
  // show what was actually drawn, which is the whole point of a screenshot here.
  await testInfo.attach('icone-supprimer.png', {
    body: await icone.screenshot(),
    contentType: 'image/png'
  });

  expect(mesure.famille).toContain('Material Icons');
  expect(mesure.largeur).toBeGreaterThan(0);
  expect(mesure.largeur, `l'icône fait ${Math.round(mesure.largeur)} px de large : c'est un mot, pas un glyphe`)
    .toBeLessThanOrEqual(LARGEUR_MAX_GLYPHE);
  expect(mesure.hauteur).toBeLessThanOrEqual(LARGEUR_MAX_GLYPHE);
  expect(mesure.debordement, "l'icône déborde de sa boîte : elle est rognée").toBeLessThanOrEqual(1);

  await page.close();
});

test("une icône en deux mots forme sa ligature aussi, l'underscore compris", async ({ browser }) => {
  const page = await pageAdmin(browser, admin);
  await page.goto('/stands');
  await page.waitForLoadState('networkidle');

  // A width alone would not do: while the font is still loading, `font-display:
  // block` draws nothing at all — narrow, and invisible. So the face has to be
  // usable before the measurement means anything.
  await page.waitForFunction(() => document.fonts.check('24px "Material Icons"'), null, { timeout: 10_000 });

  // `grid_view` sits in the navigation: its underscore is a glyph of its own,
  // and a subset font that dropped it would draw the two words instead.
  const mesure = await page.evaluate((maximum) => {
    const icones = [...document.querySelectorAll('mat-icon')];
    const cible = icones.find((el) => el.textContent?.trim().includes('_'));
    if (!cible) {
      return { trouvee: false, largeur: 0, texte: '' };
    }
    return {
      trouvee: true,
      largeur: cible.getBoundingClientRect().width,
      texte: cible.textContent!.trim(),
      visible: cible.getBoundingClientRect().width > 0,
      trop: cible.getBoundingClientRect().width > maximum
    };
  }, LARGEUR_MAX_GLYPHE);

  expect(mesure.trouvee, 'aucune icône en deux mots sur cet écran').toBe(true);
  expect(mesure.visible, `« ${mesure.texte} » n'occupe aucune place : rien n'est dessiné`).toBe(true);
  expect(mesure.trop, `« ${mesure.texte} » fait ${Math.round(mesure.largeur)} px : la ligature ne s'est pas formée`)
    .toBe(false);

  await page.close();
});
