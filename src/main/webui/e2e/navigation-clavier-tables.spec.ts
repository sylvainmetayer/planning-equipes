// Keyboard navigation of the reference-data tables (issue #315), end to end:
// entering the table, walking it with the arrows, opening a row with Enter and
// ticking one with Space.
//
// Only a browser proves the two halves a unit test cannot see: that the row
// really is in the tab order (a roving `tabindex` is meaningless if nothing
// can land on it) and that the keys the table does *not* use still reach the
// global shortcuts service underneath.
//
// The **number** of tab stops is asserted here, and that is deliberate. The
// first version of this spec tabbed up to twelve times without counting, on
// the ground that "what matters is that the table is reachable" — and the
// owner of the repository could not find the feature at all. A count nobody
// watches is a count that grows silently: adding five sortable headers to the
// animateurs table pushed it from six to eleven. Either the count stays small
// and asserted, or the entry that does not need counting (arrow down from the
// filter) is the one that must keep working.

import { APIRequestContext, Page, expect, test } from '@playwright/test';
import { contexteAdmin, pageAdmin, seedPlanning } from './support';

let admin: APIRequestContext;

test.beforeAll(async ({ playwright }, testInfo) => {
  admin = await contexteAdmin(playwright, testInfo.project.use.baseURL as string);
  // Four animateurs rather than two: walking down and back up needs room.
  await seedPlanning(admin, { avecCollegueIndisponible: true, avecCollegueLibre: true });
});

test.afterAll(async () => {
  await admin.dispose();
});

/** Index of the row holding the focus, read from the DOM rather than guessed. */
async function ligneFocalisee(page: Page): Promise<string | null> {
  return page.evaluate(
    () => document.activeElement?.closest('tr')?.getAttribute('data-row-index') ?? null,
  );
}

/** Accessible name of whatever holds the focus, for a readable failure. */
async function elementFocalise(page: Page): Promise<string> {
  return page.evaluate(() => {
    const element = document.activeElement as HTMLElement | null;
    if (!element) {
      return 'aucun';
    }
    const nom = element.getAttribute('aria-label') ?? (element.textContent ?? '').trim();
    return `${element.tagName.toLowerCase()} « ${nom.slice(0, 40)} »`;
  });
}

/**
 * Tabs until the focus lands on a row, and answers how many presses it took.
 * Bounded well above the expected count so a regression reports the real
 * number instead of "unreachable".
 */
async function tabulerJusquAUneLigne(page: Page): Promise<number> {
  const chemin: string[] = [];
  for (let presses = 0; presses < 25; presses += 1) {
    if ((await ligneFocalisee(page)) !== null) {
      return presses;
    }
    await page.keyboard.press('Tab');
    chemin.push(await elementFocalise(page));
  }
  throw new Error(`no table row reachable by Tab; path: ${chemin.join(' → ')}`);
}

/** Escapes a label read from the page before it is used as a pattern. */
function motif(texte: string): RegExp {
  return new RegExp(texte.replace(/[.*+?^${}()|[\]\\]/g, '\\$&'));
}

/**
 * Checks, on the page currently open, that no sortable header takes its name
 * from a control it contains, and answers how many headers it looked at.
 *
 * Material renders the header cell inside a `role="button"` it never names:
 * the browser computes that name from the content, descending into every
 * `aria-label` on the way. Making « Accusé de réception » sortable was enough
 * for the six lines of its help button to become the name of the sort control
 * — read out in full every time the focus reached the header, and matching two
 * elements instead of one for anything looking that button up by name. Only a
 * browser computes an accessible name, so only this sweep can see it; the unit
 * side asserts the structure that produces it.
 */
async function entetesQuiNeVolentPasLeurNom(page: Page): Promise<number> {
  const entetes = page.locator('th[mat-sort-header]');
  const total = await entetes.count();
  for (let index = 0; index < total; index += 1) {
    const entete = entetes.nth(index);
    const conteneur = entete.locator('.mat-sort-header-container');
    await expect(conteneur, 'conteneur de tri de Material').toHaveCount(1);
    const controles = entete.locator('[aria-label]');
    for (let rang = 0; rang < (await controles.count()); rang += 1) {
      const etiquette = ((await controles.nth(rang).getAttribute('aria-label')) ?? '').trim();
      // The first line is enough to identify it, and the only one a one-line
      // failure message can show.
      const extrait = etiquette.split('\n')[0].trim().slice(0, 40);
      if (extrait.length < 8) {
        continue;
      }
      await expect(
        conteneur,
        `l'en-tête de tri reprend le nom d'un contrôle qu'il contient (« ${extrait}… »)`,
      ).not.toHaveAccessibleName(motif(extrait));
    }
  }
  return total;
}

test.describe('navigation clavier des tables de référence', () => {
  test('descend au clavier, ouvre le détail, sélectionne pour une action groupée', async ({
    browser,
  }) => {
    test.slow();
    const page = await pageAdmin(browser, admin);
    await page.goto('/animateurs');

    // Narrow the table to the seeded rows: the referential of a real instance
    // is 150 lines long, and the spec must know what row 0 is.
    await page.getByLabel('Filtrer').fill('E2E-');
    await expect(page.locator('tr[data-row-index]')).toHaveCount(4);

    // A single tab stop for the whole table: only one row is offered to Tab.
    await expect(page.locator('tr[data-row-index][tabindex="0"]')).toHaveCount(1);

    // Arrow down from the filter: the entry that costs no counting, and the
    // one the help and the « ? » dialog both name.
    await page.getByLabel('Filtrer').focus();
    await page.keyboard.press('ArrowDown');
    expect(await ligneFocalisee(page)).toBe('0');

    // Tab still works, and how far away the first row sits is measured rather
    // than assumed. Eleven stops, in order: « Effacer le filtre »,
    // « Réinitialiser la vue », « Tout sélectionner », the six sortable
    // headers (Id, Nom, Majeur, Manager, Indisponibilités, Accusé de
    // réception), the acknowledgement help button, then the row.
    await page.getByLabel('Filtrer').focus();
    expect(await tabulerJusquAUneLigne(page), 'tabulations depuis le filtre').toBe(11);
    expect(await ligneFocalisee(page)).toBe('0');

    // Arrows walk the rows, and stop at the ends rather than wrapping.
    await page.keyboard.press('ArrowDown');
    expect(await ligneFocalisee(page)).toBe('1');
    await page.keyboard.press('End');
    expect(await ligneFocalisee(page)).toBe('3');
    await page.keyboard.press('ArrowDown');
    expect(await ligneFocalisee(page)).toBe('3');
    await page.keyboard.press('Home');
    expect(await ligneFocalisee(page)).toBe('0');
    await page.keyboard.press('ArrowUp');
    expect(await ligneFocalisee(page)).toBe('0');

    // Entrée opens the consultation dialog of the focused row.
    await page.keyboard.press('ArrowDown');
    const identifiant = await page.locator('tr[data-row-index="1"] td').nth(1).innerText();
    await page.keyboard.press('Enter');
    const detail = page.getByRole('dialog');
    await expect(detail).toBeVisible();
    await expect(detail).toContainText(identifiant.trim());
    await page.keyboard.press('Escape');
    await expect(detail).toBeHidden();

    // Escape gave the focus back to the row it was opened from, so Espace
    // lands on that same row and the bulk actions bar follows.
    expect(await ligneFocalisee(page)).toBe('1');
    await page.keyboard.press(' ');
    await expect(page.getByText('1 élément(s) sélectionné(s)')).toBeVisible();
    await expect(page.locator('tr[data-row-index="1"]')).toHaveClass(/row-selected/);
    // Le surlignage passe par une classe : `aria-selected` n'est pas lisible
    // sur la ligne d'un `role="table"`, et aucune ligne ne doit le porter.
    await expect(page.locator('tr[data-row-index][aria-selected]')).toHaveCount(0);

    // A second row, then back to none: the bar counts what is ticked.
    await page.keyboard.press('ArrowDown');
    await page.keyboard.press(' ');
    await expect(page.getByText('2 élément(s) sélectionné(s)')).toBeVisible();
    await page.keyboard.press(' ');
    await expect(page.getByText('1 élément(s) sélectionné(s)')).toBeVisible();

    // Tab leaves the row into its own actions, and never back into the table:
    // a roving tabindex, not a focus trap.
    await page.locator('tr[data-row-index="1"]').focus();
    await page.keyboard.press('Tab');
    expect(await ligneFocalisee(page)).toBe('1');
    await expect(page.locator('tr[data-row-index="1"] input[type="checkbox"]')).toBeFocused();

    await page.getByRole('button', { name: 'Tout désélectionner' }).click();
    await page.context().close();
  });

  test('laisse les raccourcis globaux passer depuis une ligne focalisée', async ({ browser }) => {
    const page = await pageAdmin(browser, admin);
    await page.goto('/stands');

    await page.getByLabel('Filtrer').fill('E2E-');
    await page.getByLabel('Filtrer').focus();
    await page.keyboard.press('ArrowDown');
    expect(await ligneFocalisee(page)).not.toBeNull();

    // « ? » from a row still opens the shortcut list — the table only consumes
    // the keys it uses — and that list says how one gets into the table, which
    // is the whole point of a shortcut nobody can otherwise guess.
    await page.keyboard.press('?');
    const raccourcis = page.getByRole('dialog');
    await expect(raccourcis).toBeVisible();
    await expect(raccourcis).toContainText('Dans un tableau de données de référence');
    await expect(raccourcis).toContainText('Flèche bas');
    await expect(raccourcis).toContainText('entrer dans le tableau');
    await page.keyboard.press('Escape');
    await expect(raccourcis).toBeHidden();

    // … and « / » still jumps back into the page filter.
    await page.keyboard.press('/');
    await expect(page.getByLabel('Filtrer')).toBeFocused();

    // Espace is consumed on a row and nowhere else: typed in the filter it is
    // still a space, not a selection and not a page scroll.
    await page.getByLabel('Filtrer').fill('E2E');
    await page.keyboard.press('End');
    await page.keyboard.press(' ');
    await expect(page.getByLabel('Filtrer')).toHaveValue('E2E ');

    await page.context().close();
  });

  test('donne le focus à la ligne cliquée, pour que les flèches enchaînent', async ({
    browser,
  }) => {
    const page = await pageAdmin(browser, admin);
    await page.goto('/animateurs');
    await page.getByLabel('Filtrer').fill('E2E-');
    await expect(page.locator('tr[data-row-index]')).toHaveCount(4);

    // The most likely gesture of all: click a row, then use the arrows. If the
    // click did not focus the row, the arrows would scroll the page instead.
    // A data cell, not the first one: that one holds the selection checkbox,
    // and clicking a control focuses the control — as it must.
    await page.locator('tr[data-row-index="2"] td').nth(2).click();
    expect(await ligneFocalisee(page)).toBe('2');
    // The click also moved the roving tabindex, so Tab now comes back here.
    await expect(page.locator('tr[data-row-index="2"]')).toHaveAttribute('tabindex', '0');

    await page.keyboard.press('ArrowDown');
    expect(await ligneFocalisee(page)).toBe('3');

    await page.context().close();
  });

  test('ne laisse aucun en-tête de tri se nommer par un contrôle imbriqué', async ({ browser }) => {
    const page = await pageAdmin(browser, admin);

    // L'en-tête de l'accusé de réception, celui qui porte le bouton d'aide :
    // il s'annonce par son titre de colonne, rien de plus.
    await page.goto('/animateurs');
    await expect(page.locator('th[mat-sort-header]').first()).toBeVisible();
    const accuse = page
      .locator('th[mat-sort-header]', { hasText: 'Accusé de réception' })
      .locator('.mat-sort-header-container');
    await expect(accuse).toHaveAccessibleName('Accusé de réception');

    // … et le bouton d'aide, lui, garde son explication entière et reste le
    // seul à la porter : deux éléments du même nom, c'est ce qui a rendu la
    // régression visible.
    await expect(page.getByRole('button', { name: /Ce que l'animateur a répondu/ })).toHaveCount(1);

    let verifies = await entetesQuiNeVolentPasLeurNom(page);
    expect(verifies, 'en-têtes triables de /animateurs').toBe(6);

    // Le témoin, dans le navigateur qui calcule vraiment ce nom : l'attribut
    // retiré, l'en-tête réabsorbe l'explication du bouton d'aide — l'état
    // exact qui a été livré. Sans cette ligne, la garde pourrait passer au
    // vert pour de mauvaises raisons (un sélecteur qui ne trouve plus rien).
    // Le DOM ainsi bricolé est jeté par la navigation qui suit.
    await accuse.evaluate((element) => element.removeAttribute('aria-labelledby'));
    await expect(accuse).toHaveAccessibleName(/Ce que l'animateur a répondu/);

    // Le balayage vaut pour toutes les tables triables, pas seulement celle
    // qui a cassé : la prochaine colonne à recevoir une icône ou une case à
    // cocher est celle qui ramènerait le défaut.
    for (const route of ['/creneaux', '/hours']) {
      await page.goto(route);
      await expect(page.locator('table')).toBeVisible();
      verifies += await entetesQuiNeVolentPasLeurNom(page);
    }
    expect(verifies, 'en-têtes triables balayés').toBeGreaterThanOrEqual(8);

    await page.context().close();
  });

  test('trie les cinq colonnes ajoutées, et met le tri dans l’URL', async ({ browser }) => {
    const page = await pageAdmin(browser, admin);
    await page.goto('/animateurs');
    await page.getByLabel('Filtrer').fill('E2E-');
    await expect(page.locator('tr[data-row-index]')).toHaveCount(4);

    for (const [entete, colonne] of [
      ['Id', 'id'],
      ['Nom', 'nom'],
      ['Manager', 'manager'],
      ['Indisponibilités', 'indisponibilites'],
      ['Accusé de réception', 'confirmation'],
    ] as const) {
      await page.getByRole('button', { name: new RegExp(`^${entete}`) }).click();
      await expect(page).toHaveURL(new RegExp(`sort=${colonne}&dir=asc`));
      await expect(page.locator('tr[data-row-index]')).toHaveCount(4);
    }

    // The acknowledgement header carries a help button inside the sort header:
    // opening its tooltip must not also re-sort the column.
    const urlAvant = page.url();
    await page.locator('th .column-help').click();
    await expect(page).toHaveURL(urlAvant);

    // A reload restores the sort from the URL, like every other view state.
    await page.reload();
    await expect(page).toHaveURL(/sort=confirmation&dir=asc/);

    await page.context().close();
  });
});
