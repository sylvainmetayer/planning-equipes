// The short help of each screen (« ? » of the toolbar) and the Nouveautés list
// of versions, checked in a browser: the button must land on the section of the
// screen — and of the tab — the reader is on, and Nouveautés must be a plain
// list with nothing to filter.

import { APIRequestContext, expect, test } from '@playwright/test';
import { contexteAdmin, pageAdmin } from './support';

let admin: APIRequestContext;

test.beforeAll(async ({ playwright }, testInfo) => {
  admin = await contexteAdmin(playwright, testInfo.project.use.baseURL as string);
});

test.afterAll(async () => {
  await admin.dispose();
});

/** A screen or a tab, and the short help the toolbar's « ? » must open on it. */
const ECRANS: readonly { adresse: string; section: string; titre: string }[] = [
  { adresse: '/', section: 'ecran-accueil', titre: "État de l'édition" },
  { adresse: '/journee?onglet=calculer', section: 'ecran-calculer', titre: 'Calculer' },
  { adresse: '/journee?onglet=versions', section: 'ecran-versions', titre: 'Versions du plan' },
  { adresse: '/diagnostic?onglet=besoin', section: 'ecran-besoin', titre: 'Besoin en animateurs' },
  { adresse: '/diagnostic?onglet=risques', section: 'ecran-risques', titre: 'Risques' },
  { adresse: '/diagnostic?onglet=charge', section: 'ecran-charge', titre: 'Charge' },
  {
    adresse: '/diagnostic?onglet=repas-pauses',
    section: 'ecran-repas-pauses',
    titre: 'Repas et pauses',
  },
  {
    adresse: '/regles?onglet=verrouillages',
    section: 'ecran-verrouillages',
    titre: 'Verrouillages',
  },
];

for (const { adresse, section, titre } of ECRANS) {
  test(`« ? » opens the short help of ${adresse}`, async ({ browser }) => {
    const page = await pageAdmin(browser, admin);
    await page.goto(adresse);
    await page.locator('a[aria-label="Aide"]').click();
    await expect(page).toHaveURL(new RegExp(`/aide#${section}$`));
    const carte = page.locator(`#${section}`);
    await expect(carte).toBeVisible();
    await expect(carte.getByRole('heading', { name: titre, exact: true })).toBeVisible();
    // The way on: the guide of the site, in a new tab.
    await expect(carte.getByRole('link', { name: 'Guide du site' })).toHaveAttribute(
      'href',
      /^https:\/\/planning\.sylvain\.dev\/(en\/)?docs\/(regles|rules)\/[a-z-]+\/$/,
    );
  });
}

test('« ? » follows a tab switched inside the page', async ({ browser }) => {
  const page = await pageAdmin(browser, admin);
  await page.goto('/diagnostic');
  // An in-page tab switch writes the address bar without navigating.
  await page.getByRole('radio', { name: /Risques/ }).click();
  await expect(page).toHaveURL(/onglet=risques/);
  await page.locator('a[aria-label="Aide"]').click();
  await expect(page).toHaveURL(/\/aide#ecran-risques$/);
});

test('« Guide détaillé » scrolls to its section from the guide itself', async ({ browser }) => {
  const page = await pageAdmin(browser, admin);
  await page.goto('/aide#ecran-calculer');
  await page.locator('#ecran-calculer').getByRole('link', { name: 'Guide détaillé' }).click();
  await expect(page).toHaveURL(/\/aide#calculer$/);
  await expect(page.locator('#calculer')).toBeInViewport();
});

test('tells how to do without a forced assignment', async ({ browser }) => {
  const page = await pageAdmin(browser, admin);
  await page.goto('/aide#sans-affectation-forcee');
  const carte = page.locator('#sans-affectation-forcee');
  await expect(
    carte.getByRole('heading', { name: 'Comment faire sans affectation forcée' }),
  ).toBeVisible();
  await expect(carte).toContainText('verrouillez');
  await expect(carte).toContainText('indisponibilités');
});

test('Nouveautés is a plain list of versions, nothing to filter', async ({ browser }) => {
  const page = await pageAdmin(browser, admin);
  await page.goto('/nouveautes');
  await expect(page.getByRole('heading', { name: 'Nouveautés', level: 1 })).toBeVisible();
  await expect(page.getByLabel('Rechercher')).toHaveCount(0);
  await expect(page.getByLabel('Nature')).toHaveCount(0);
  // A build without git history says so; otherwise each version carries its number and its date.
  const versions = page.locator('.news-release');
  if ((await versions.count()) > 0) {
    await expect(versions.first().locator('.news-release-title')).toBeVisible();
    await expect(versions.first().locator('.news-release-date')).not.toBeEmpty();
  }
});
