// Dark theme (issue #317): the toolbar's three-state toggle, its persistence,
// and the fact that "système" really means the machine's preference — live.
//
// Asserted on the painted background, not only on the attribute: the whole
// point is that a single `color-scheme` flips every `light-dark()` pair
// `mat.theme()` compiled in, and an attribute alone would prove nothing about
// that.
//
// Two colours are not `mat.theme()`'s to switch and are measured here as well:
// the brand accent a deployment injects at runtime, and the icon of the button
// itself — the one thing that has to name the scheme the machine resolved to.

import { APIRequestContext, Page, expect, test } from '@playwright/test';
import { contexteAdmin, pageAdmin } from './support';
import { repartirDeLaReference } from './reference';

let admin: APIRequestContext;

test.beforeAll(async ({ playwright }, testInfo) => {
  admin = await contexteAdmin(playwright, testInfo.project.use.baseURL as string);
  await repartirDeLaReference(admin);
});

test.afterAll(async () => {
  await admin.dispose();
});

/** The `color-scheme` the application put on the root element. */
function schemeApplique(page: Page): Promise<string> {
  return page.evaluate(() => document.documentElement.style.colorScheme);
}

/** What the page is actually painted with, once the media query has resolved. */
function fondDeLaPage(page: Page): Promise<string> {
  return page.evaluate(() => getComputedStyle(document.body).backgroundColor);
}

/**
 * La paire que `core/branding.ts` pose sur `--app-accent` quand un déploiement
 * renseigne BRANDING_ACCENT_COLOR. Recopiée ici parce que la valeur est
 * injectée hors d'Angular : `branding.spec.ts` épingle la même chaîne, donc
 * une dérive côté source rend d'abord le test unitaire rouge.
 */
const ACCENT_MARQUE = '#8b1e3f';
const ACCENT_PAIRE = `light-dark(${ACCENT_MARQUE}, oklch(from ${ACCENT_MARQUE} max(l, 0.78) min(c, 0.14) h))`;

type Srgb = [number, number, number];

/**
 * Les octets réellement affichés pour une valeur CSS de couleur, dans le
 * schéma peint au moment de l'appel. Le détour par un canvas est nécessaire :
 * `getComputedStyle` rend une couleur dérivée dans son espace d'origine
 * (`oklch(0.78 0.14 8.3)`), et le contraste WCAG se calcule en sRGB.
 */
function couleurPeinte(page: Page, valeur: string): Promise<Srgb> {
  return page.evaluate((v) => {
    const sonde = document.createElement('span');
    sonde.style.color = v;
    document.body.appendChild(sonde);
    const resolue = getComputedStyle(sonde).color;
    sonde.remove();

    const canvas = document.createElement('canvas');
    canvas.width = 1;
    canvas.height = 1;
    const ctx = canvas.getContext('2d');
    if (!ctx) {
      throw new Error('pas de contexte 2d pour mesurer la couleur');
    }
    ctx.fillStyle = resolue;
    ctx.fillRect(0, 0, 1, 1);
    const [r, g, b] = ctx.getImageData(0, 0, 1, 1).data;
    return [r, g, b] as [number, number, number];
  }, valeur);
}

/** Contraste WCAG 2.1 entre deux couleurs sRGB. */
function contraste(a: Srgb, b: Srgb): number {
  const luminance = ([r, g, b]: Srgb): number => {
    const [lr, lg, lb] = [r, g, b].map((v) => {
      const c = v / 255;
      return c <= 0.04045 ? c / 12.92 : ((c + 0.055) / 1.055) ** 2.4;
    });
    return 0.2126 * lr + 0.7152 * lg + 0.0722 * lb;
  };
  const [clair, sombre] = [luminance(a), luminance(b)].sort((x, y) => y - x);
  return (clair + 0.05) / (sombre + 0.05);
}

function boutonTheme(page: Page) {
  return page.getByRole('button', { name: /^Thème/ });
}

test('la bascule passe en sombre et le choix survit au rechargement', async ({ browser }) => {
  const page = await pageAdmin(browser, admin);
  await page.emulateMedia({ colorScheme: 'light' });
  await page.goto('/');

  // Nothing chosen yet: the document declares both and lets the machine pick.
  await expect(boutonTheme(page)).toHaveAttribute('data-theme-preference', 'system');
  expect(await schemeApplique(page)).toBe('light dark');
  const fondClair = await fondDeLaPage(page);

  await boutonTheme(page).click();
  await expect(boutonTheme(page)).toHaveAttribute('data-theme-preference', 'light');
  await boutonTheme(page).click();
  await expect(boutonTheme(page)).toHaveAttribute('data-theme-preference', 'dark');
  expect(await schemeApplique(page)).toBe('dark');

  const fondSombre = await fondDeLaPage(page);
  expect(fondSombre, 'le fond doit vraiment changer, pas seulement l’attribut').not.toBe(fondClair);

  // The accessible name states where the user is, not just what the button does.
  await expect(boutonTheme(page)).toHaveAccessibleName(/^Thème sombre\./);

  await page.reload();
  await expect(boutonTheme(page)).toHaveAttribute('data-theme-preference', 'dark');
  expect(await schemeApplique(page)).toBe('dark');
  expect(await fondDeLaPage(page)).toBe(fondSombre);

  // Third click: back to "système", which is a stored answer too.
  await boutonTheme(page).click();
  await expect(boutonTheme(page)).toHaveAttribute('data-theme-preference', 'system');
  await page.reload();
  await expect(boutonTheme(page)).toHaveAttribute('data-theme-preference', 'system');

  await page.context().close();
});

test("sans choix explicite, l'interface suit la préférence système à chaud", async ({ browser }) => {
  const page = await pageAdmin(browser, admin);
  await page.emulateMedia({ colorScheme: 'dark' });
  await page.goto('/');

  await expect(boutonTheme(page)).toHaveAttribute('data-theme-preference', 'system');
  const fondSombre = await fondDeLaPage(page);

  // No reload: `color-scheme: light dark` is native, the browser repaints alone.
  await page.emulateMedia({ colorScheme: 'light' });
  await expect
    .poll(() => fondDeLaPage(page), { message: 'le fond doit suivre la préférence système sans rechargement' })
    .not.toBe(fondSombre);

  await page.context().close();
});

test('un choix explicite résiste à un changement de préférence système', async ({ browser }) => {
  const page = await pageAdmin(browser, admin);
  await page.emulateMedia({ colorScheme: 'dark' });
  await page.goto('/');

  // système → clair : the user says "light", the machine still says dark.
  await boutonTheme(page).click();
  await expect(boutonTheme(page)).toHaveAttribute('data-theme-preference', 'light');
  const fondChoisi = await fondDeLaPage(page);
  expect(await schemeApplique(page)).toBe('light');

  await page.emulateMedia({ colorScheme: 'light' });
  expect(await fondDeLaPage(page)).toBe(fondChoisi);
  await page.emulateMedia({ colorScheme: 'dark' });
  expect(await fondDeLaPage(page)).toBe(fondChoisi);

  await page.context().close();
});

/**
 * Le déploiement qui configure sa propre couleur d'accent (issue #317) : cette
 * couleur est choisie sur fond blanc, donc foncée, et `--app-accent` sert de
 * couleur de texte sur `--mat-sys-surface` dans une douzaine de partials. Sans
 * la paire `light-dark()` que pose `core/branding.ts`, le thème sombre la
 * laissait à ~2:1 — illisible.
 */
test("l'accent de marque reste lisible sur les deux fonds", async ({ browser }) => {
  const page = await pageAdmin(browser, admin);
  await page.emulateMedia({ colorScheme: 'light' });
  await page.goto('/');

  // Ce que `appliquerBranding()` écrit quand BRANDING_ACCENT_COLOR est renseigné.
  await page.evaluate((paire) => {
    document.documentElement.style.setProperty('--app-accent', paire);
  }, ACCENT_PAIRE);

  // Thème clair : la couleur configurée, à l'octet près.
  await boutonTheme(page).click();
  await expect(boutonTheme(page)).toHaveAttribute('data-theme-preference', 'light');
  const accentClair = await couleurPeinte(page, 'var(--app-accent)');
  expect(accentClair, 'la moitié claire doit rester la couleur configurée').toEqual([139, 30, 63]);
  const fondClair = await couleurPeinte(page, await fondDeLaPage(page));
  expect(contraste(accentClair, fondClair), 'accent de marque sur la surface claire').toBeGreaterThan(4.5);

  // Thème sombre : la jumelle éclaircie, pas la couleur brute — qui y tombait
  // à 2,1:1 avant que `core/branding.ts` ne pose une paire.
  await boutonTheme(page).click();
  await expect(boutonTheme(page)).toHaveAttribute('data-theme-preference', 'dark');
  const accentSombre = await couleurPeinte(page, 'var(--app-accent)');
  expect(accentSombre, 'la moitié sombre doit être une autre couleur').not.toEqual(accentClair);
  const fondSombre = await couleurPeinte(page, await fondDeLaPage(page));
  expect(contraste(accentSombre, fondSombre), 'accent de marque sur la surface sombre').toBeGreaterThan(4.5);

  await page.context().close();
});

/**
 * L'icône du bouton nomme le schéma peint, jamais celui choisi : sous
 * « système », le coucher du soleil bascule la machine et l'icône doit suivre.
 * Le point `auto` est ce qui distingue « sombre parce que j'ai choisi » de
 * « sombre parce qu'il est 21 h ».
 */
test("sous « système », l'icône suit le schéma que la machine résout", async ({ browser }) => {
  const page = await pageAdmin(browser, admin);
  await page.emulateMedia({ colorScheme: 'light' });
  await page.goto('/');

  const icone = boutonTheme(page).locator('mat-icon');
  const point = boutonTheme(page).locator('.theme-auto-dot');

  await expect(boutonTheme(page)).toHaveAttribute('data-theme-preference', 'system');
  await expect(icone).toHaveText('light_mode');
  await expect(point).toBeVisible();

  await page.emulateMedia({ colorScheme: 'dark' });
  await expect(icone).toHaveText('dark_mode');
  await expect(point).toBeVisible();

  // Choix explicite « sombre » : même icône, plus de marqueur.
  await boutonTheme(page).click();
  await boutonTheme(page).click();
  await expect(boutonTheme(page)).toHaveAttribute('data-theme-preference', 'dark');
  await expect(icone).toHaveText('dark_mode');
  await expect(point).toHaveCount(0);

  await page.context().close();
});
