// Les deux retours de tests manuels sur #338, vérifiés dans le navigateur.
//
//   1. la colonne « Accusé de réception » affichait des états que rien
//      n'expliquait. L'infobulle doit exister ET être atteignable au clavier :
//      posée sur une icône `aria-hidden`, elle n'aurait servi qu'à la souris ;
//   2. la foire au planning se borne par des dates, comme la collecte des
//      disponibilités. Hors fenêtre, l'espace doit dire « pas encore ouverte »
//      plutôt que « fermée » — et le serveur doit refuser l'écriture, pas
//      seulement masquer le bouton.

import { APIRequestContext, expect, test } from '@playwright/test';
import {
  SEED,
  contexteAdmin,
  jetonDe,
  ouvrirSessionEspace,
  pageAdmin,
  seedPlanning,
} from './support';
import { repartirDeLaReference } from './reference';

const EMAIL_ALICE = `${SEED.demandeur}@example.org`;

/** ISO date `decalage` days from today — les bornes se raisonnent en relatif. */
function jour(decalage: number): string {
  const date = new Date();
  date.setDate(date.getDate() + decalage);
  return date.toISOString().slice(0, 10);
}

async function configurerFoire(
  admin: APIRequestContext,
  corps: { foireOuverte: boolean; debut?: string | null; fin?: string | null },
): Promise<void> {
  const reponse = await admin.put('/api/echanges/configuration', { data: corps });
  expect(reponse.ok(), await reponse.text()).toBe(true);
}

let admin: APIRequestContext;

test.beforeAll(async ({ playwright }, testInfo) => {
  admin = await contexteAdmin(playwright, testInfo.project.use.baseURL as string);
  await repartirDeLaReference(admin);
  await seedPlanning(admin);
});

test.afterAll(async () => {
  // La foire est un état partagé de l'édition : chaque suite la laisse ouverte
  // et sans bornes, comme elle l'a trouvée.
  await configurerFoire(admin, { foireOuverte: true, debut: null, fin: null });
  await admin.dispose();
});

test.describe('Accusé de réception : la colonne s’explique', () => {
  test("l'infobulle est atteignable au clavier et annoncée", async ({ browser }) => {
    const page = await pageAdmin(browser, admin);
    await page.goto('/animateurs');

    // Un bouton, pas une icône nue : c'est ce qui le rend focusable. Le nom
    // accessible EST l'explication, donc un lecteur d'écran l'annonce.
    const aide = page.getByRole('button', { name: /Ce que l'animateur a répondu/ });
    await expect(aide).toBeVisible();

    // Les trois règles qui ne se devinent pas doivent y figurer.
    const explication = (await aide.getAttribute('aria-label')) ?? '';
    expect(explication).toContain('Relancé');
    expect(explication).toContain("Il n'y en aura pas d'autre");
    expect(explication).toContain('on ne lui a rien demandé');
    expect(explication).toContain('que les personnes dont le planning a réellement changé');

    // Atteignable au clavier : l'infobulle doit s'ouvrir sur une tabulation.
    //
    // Deux précautions, sans quoi ce test échoue alors que le produit va bien
    // — la première version les ignorait toutes les deux et a été fusionnée
    // rouge :
    //
    //  1. la tabulation doit être vraie. `locator.focus()` est un focus
    //     programmatique, et le `FocusMonitor` de Material n'ouvre l'infobulle
    //     que sur une origine `keyboard`. D'où le détour par Shift+Tab : on se
    //     place juste avant, puis on tabule pour de bon ;
    //  2. la souris doit être écartée. Playwright la laisse en (0,0), et le
    //     panneau de l'infobulle est posé en haut à gauche avant d'être
    //     déplacé à sa position définitive. Il passe donc sous le curseur
    //     immobile, puis s'en éloigne : le `mouseleave` qui en résulte referme
    //     l'infobulle vingt millisecondes après son ouverture.
    const coin = page.viewportSize();
    await page.mouse.move((coin?.width ?? 1280) - 2, (coin?.height ?? 720) - 2);

    await aide.focus();
    await page.keyboard.press('Shift+Tab');
    await expect(aide).not.toBeFocused();
    await page.keyboard.press('Tab');
    await expect(aide).toBeFocused();

    // Le panneau de Material ne porte pas `role="tooltip"` : c'est un simple
    // div, décrit au lecteur d'écran par l'`aria-label` vérifié plus haut. On
    // vérifie donc ce qu'une personne voit — le texte, dans la surcouche.
    await expect(
      page.locator('.cdk-overlay-container').getByText("Il n'y en aura pas d'autre"),
    ).toBeVisible();

    await page.close();
  });
});

test.describe('Foire au planning : bornes datées', () => {
  test('hors fenêtre, le serveur refuse la soumission et ne fait pas que masquer', async () => {
    await configurerFoire(admin, { foireOuverte: true, debut: jour(3), fin: jour(10) });

    const configuration = await (await admin.get('/api/echanges/configuration')).json();
    expect(configuration.foireOuverte).toBe(true);
    expect(configuration.ouverteAujourdhui).toBe(false);

    const jeton = await jetonDe(admin, SEED.demandeur);
    await ouvrirSessionEspace(admin, jeton, EMAIL_ALICE);
    const refus = await admin.post(`/api/espace-animateur/${jeton}/demandes`, {
      data: [{ creneauId: SEED.creneauId, standId: SEED.standDemandeur, cibleId: SEED.cible }],
    });

    expect(refus.status(), await refus.text()).toBe(400);
    expect(await refus.text()).toContain('fermée');
  });

  test("avant la date d'ouverture, l'espace dit « pas encore ouverte » et non « fermée »", async ({
    page,
  }) => {
    const debut = jour(5);
    await configurerFoire(admin, { foireOuverte: true, debut, fin: null });

    const jeton = await jetonDe(admin, SEED.demandeur);
    await ouvrirSessionEspace(page.request, jeton, EMAIL_ALICE);
    await page.goto(`/animateur/${jeton}/echanges`);

    // Le reproche d'origine : « c'est terminé » annoncé à quelqu'un qui arrive
    // deux semaines trop tôt, et qui ne revient donc jamais.
    await expect(page.getByText('pas encore ouverte', { exact: false })).toBeVisible();
    await expect(page.getByText('La foire au planning est fermée', { exact: false })).toHaveCount(
      0,
    );
  });

  test("une fois la fenêtre passée, l'espace dit bien « fermée »", async ({ page }) => {
    await configurerFoire(admin, { foireOuverte: true, debut: jour(-10), fin: jour(-3) });

    const jeton = await jetonDe(admin, SEED.demandeur);
    await ouvrirSessionEspace(page.request, jeton, EMAIL_ALICE);
    await page.goto(`/animateur/${jeton}/echanges`);

    // Rien à attendre : annoncer une date de retour serait promettre une
    // réouverture qui n'aura pas lieu.
    await expect(page.getByText('La foire au planning est fermée', { exact: false })).toBeVisible();
    await expect(page.getByText('pas encore ouverte', { exact: false })).toHaveCount(0);
  });

  test("l'écran admin distingue « ouverte » de « ouverte hors période »", async ({ browser }) => {
    await configurerFoire(admin, { foireOuverte: true, debut: jour(3), fin: jour(10) });

    const page = await pageAdmin(browser, admin);
    await page.goto('/echanges');

    // L'interrupteur dit « ouverte » pendant que le serveur refuse tout :
    // l'écran doit le dire, sinon il ment.
    await expect(page.getByText('hors de la période', { exact: false })).toBeVisible();

    await page.close();
  });
});
