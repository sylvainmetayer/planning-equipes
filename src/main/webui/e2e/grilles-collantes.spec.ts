// Les deux grilles de saisie — les ouvertures des stands, les compétences des
// animateurs — sur assez de données pour qu'elles défilent : l'en-tête et la
// colonne de gauche restent en place, et une case atteinte aux flèches ne
// finit pas cachée dessous.
//
// Rien de cela n'est visible ailleurs : les tests unitaires rendent sans CSS,
// et `position: sticky` n'est un comportement que là où il y a une mise en
// page. La spec mesure aussi ce que la feuille de style tient pour acquis :
// la seconde ligne d'en-tête des ouvertures est épinglée sous la première à
// une hauteur écrite en dur (`--entete-jour-hauteur`, styles/ouvertures.css),
// et le `scroll-padding-top` de `.grille-defilement` (styles/pages.css) vaut
// pour la plus haute des deux bandes d'en-tête.

import { APIRequestContext, Locator, Page, expect, test } from '@playwright/test';
import { contexteAdmin, decaler, pageAdmin, seedReferentielSolveur } from './support';
import { repartirDeLaReference } from './reference';

/** De quoi déborder la boîte (70 vh, plafonnée à 42 rem) sur l'écran de la CI. */
const LIGNES = 30;
/** La ligne d'où l'on remonte au clavier, et le nombre de remontées. */
const LIGNE_DEPART = 15;
const REMONTEES = 12;
const JOUR = decaler('2026-07-10');
/** Tolérance des mesures : une bordure fusionnée vaut un demi-pixel de part et d'autre. */
const TOLERANCE = 1;

let admin: APIRequestContext;

test.beforeAll(async ({ playwright }, testInfo) => {
  admin = await contexteAdmin(playwright, testInfo.project.use.baseURL as string);
  await repartirDeLaReference(admin);
  await seedReferentielSolveur(
    admin,
    Array.from({ length: LIGNES }, (_, index) => ({
      id: `E2E-GC-A${index}`,
      prenom: `Prénom${index}`,
      nom: `Nom${index}`,
      dateNaissance: '1990-01-01',
    })),
    Array.from({ length: LIGNES }, (_, index) => ({
      id: `E2E-GC-S${index}`,
      nom: `Stand collant ${index}`,
      effectif: 2,
    })),
    [
      { id: 987101, date: JOUR, debut: '10:00', fin: '13:00' },
      { id: 987102, date: JOUR, debut: '14:00', fin: '18:00' },
    ],
  );
});

test.afterAll(async () => {
  await admin.dispose();
});

/** Un élément dont on attend qu'il ne bouge pas, et sur quelle(s) arête(s). */
interface Collant {
  nom: string;
  selecteur: string;
  enHaut?: boolean;
  aGauche?: boolean;
}

/** Ce qu'on a mesuré de lui : sa distance à l'arête de la boîte, en valeur absolue. */
interface Ecart {
  nom: string;
  enHaut: number | null;
  aGauche: number | null;
}

interface Mesures {
  /** La boîte a-t-elle vraiment de quoi défiler ? Sans cela le reste ne prouve rien. */
  defile: boolean;
  ecarts: Ecart[];
  /** Distance entre le haut de la seconde ligne d'en-tête et le bas de la première. */
  sousLaPremiereLigne: number | null;
}

/**
 * Défile la grille dans les deux sens, puis mesure d'un coup ce qui doit être
 * resté en place — en valeur absolue : ce qu'on juge est une distance à
 * l'arête, du mauvais côté comme du bon. Tout est lu dans le même repère,
 * celui de la fenêtre, et dans le même aller-retour : une mesure par élément
 * laisserait la page défiler entre deux.
 */
async function mesurer(
  grille: Locator,
  collants: Collant[],
  lignes: { premiere: string; seconde: string } | null,
): Promise<Mesures> {
  return grille.evaluate(
    (table, quoi) => {
      const boite = table.closest('.grille-defilement') as HTMLElement;
      boite.scrollTop = 400;
      boite.scrollLeft = 200;
      const cadre = boite.getBoundingClientRect();
      const rect = (selecteur: string) => table.querySelector(selecteur)!.getBoundingClientRect();
      return {
        defile: boite.scrollHeight > boite.clientHeight + 1 && boite.scrollTop > 0,
        ecarts: quoi.collants.map((collant) => {
          const cible = rect(collant.selecteur);
          return {
            nom: collant.nom,
            enHaut: collant.enHaut ? Math.abs(Math.round(cible.top - cadre.top)) : null,
            aGauche: collant.aGauche ? Math.abs(Math.round(cible.left - cadre.left)) : null,
          };
        }),
        sousLaPremiereLigne: quoi.lignes
          ? Math.abs(Math.round(rect(quoi.lignes.seconde).top - rect(quoi.lignes.premiere).bottom))
          : null,
      };
    },
    { collants, lignes },
  );
}

/** Chaque élément annoncé collant l'est resté, sur l'arête où on l'attendait. */
function verifierLesCollants(ecarts: Ecart[]): void {
  for (const ecart of ecarts) {
    if (ecart.enHaut !== null) {
      expect(ecart.enHaut, `${ecart.nom} reste en haut de la boîte`).toBeLessThanOrEqual(TOLERANCE);
    }
    if (ecart.aGauche !== null) {
      expect(ecart.aGauche, `${ecart.nom} reste à gauche de la boîte`).toBeLessThanOrEqual(
        TOLERANCE,
      );
    }
  }
}

/**
 * Remonte de case en case avec la flèche haut, et renvoie pour chaque étape de
 * combien la case focalisée passe sous le bas de l'en-tête : négatif, elle est
 * cachée dessous — ce que le `scroll-padding-top` de la boîte doit empêcher.
 * `NaN` si le focus a quitté la grille, ce qui invaliderait la mesure.
 */
async function remonterAuClavier(
  page: Page,
  selecteurCase: string,
  selecteurEntete: string,
): Promise<number[]> {
  const ecarts: number[] = [];
  for (let pas = 0; pas < REMONTEES; pas += 1) {
    await page.keyboard.press('ArrowUp');
    ecarts.push(
      await page.evaluate(
        (quoi) => {
          const active = document.activeElement as HTMLElement | null;
          const boite = active?.closest('.grille-defilement');
          if (!active?.closest(quoi.cellule) || !boite) {
            return Number.NaN;
          }
          const entete = boite.querySelector(quoi.entete)!.getBoundingClientRect();
          return Math.round(active.getBoundingClientRect().top - entete.bottom);
        },
        { cellule: selecteurCase, entete: selecteurEntete },
      ),
    );
  }
  return ecarts;
}

/** Les étapes où la case focalisée a fini sous l'en-tête, avec leur écart. */
function casesCachees(ecarts: number[]): string[] {
  return ecarts
    .map((ecart, pas) => ({ ecart, pas }))
    .filter(({ ecart }) => Number.isNaN(ecart) || ecart < -TOLERANCE)
    .map(({ ecart, pas }) => `remontée ${pas + 1} : ${ecart} px`);
}

test('la grille de saisie des ouvertures garde son en-tête et sa colonne de stands', async ({
  browser,
}) => {
  const page = await pageAdmin(browser, admin);
  await page.goto('/ouvertures?vue=saisie');
  const grille = page.locator('.grille-saisie');
  await expect(grille).toBeVisible();
  await expect(grille.locator('tbody tr').nth(LIGNE_DEPART)).toBeVisible();

  const mesures = await mesurer(
    grille,
    [
      { nom: "l'en-tête de jour", selecteur: '.entete-jour-saisie', enHaut: true },
      // Le coin appartient aux deux bandes : il tient sur les deux arêtes.
      { nom: 'le coin', selecteur: 'thead .colonne-stand', enHaut: true, aGauche: true },
      { nom: 'le nom du stand', selecteur: 'tbody .colonne-stand', aGauche: true },
    ],
    { premiere: '.entete-jour-saisie', seconde: '.entete-creneau' },
  );

  expect(mesures.defile, `${LIGNES} stands devraient déborder la boîte`).toBe(true);
  verifierLesCollants(mesures.ecarts);
  // La seconde ligne d'en-tête exactement sous la première : un décalage faux
  // laisserait entre les deux un trou où des cases défilent, ou un
  // recouvrement.
  expect(
    mesures.sousLaPremiereLigne,
    'la ligne des créneaux est collée sous celle du jour',
  ).toBeLessThanOrEqual(TOLERANCE);

  // Et la saisie au clavier : le navigateur juge « déjà visible » une case qui
  // n'est que sous l'en-tête collant, et ne défile donc pas — la saisie se
  // poursuivrait là où on ne la voit plus.
  await grille.locator('tbody tr').nth(LIGNE_DEPART).locator('input').first().focus();
  expect(
    casesCachees(await remonterAuClavier(page, '.cellule-saisie', '.entete-creneau')),
    'cases focalisées finissant sous l’en-tête',
  ).toEqual([]);

  await page.close();
});

test('la grille des compétences garde son en-tête et sa colonne d’animateurs', async ({
  browser,
}) => {
  const page = await pageAdmin(browser, admin);
  await page.goto('/competences');
  const grille = page.locator('.competences-grille');
  await expect(grille).toBeVisible();
  await expect(grille.locator('tbody tr').nth(LIGNE_DEPART)).toBeVisible();

  const mesures = await mesurer(
    grille,
    [
      { nom: "l'en-tête des typologies", selecteur: 'thead .colonne-typologie', enHaut: true },
      { nom: 'le coin', selecteur: 'thead .colonne-animateur', enHaut: true, aGauche: true },
      { nom: "le nom de l'animateur", selecteur: 'tbody .colonne-animateur', aGauche: true },
    ],
    null,
  );

  expect(mesures.defile, `${LIGNES} animateurs devraient déborder la boîte`).toBe(true);
  verifierLesCollants(mesures.ecarts);

  await grille.locator('tbody tr').nth(LIGNE_DEPART).locator('.cellule-competence').first().focus();
  expect(
    casesCachees(await remonterAuClavier(page, '.cellule-competence', 'thead .colonne-typologie')),
    'cases focalisées finissant sous l’en-tête',
  ).toEqual([]);

  await page.close();
});
