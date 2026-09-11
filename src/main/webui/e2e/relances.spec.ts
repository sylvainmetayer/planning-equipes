// Relancer à la main les silencieux (issue #504), de bout en bout : le filtre
// « Accusés » isole les gens à relancer, l'action groupée écrit à eux seuls,
// le compte rendu nomme ceux qu'elle a laissés de côté — et un second clic
// n'envoie rien, parce que la règle « personne ne reçoit deux fois le même
// message » vaut pour la main comme pour la nuit. Mailpit est le témoin : un
// seul courriel de relance, quel que soit le nombre de clics.

import { APIRequestContext, expect, test } from '@playwright/test';
import {
  SEED,
  contexteAdmin,
  nombreDeMails,
  ouvrirSelect,
  pageAdmin,
  seedPlanning,
} from './support';
import { repartirDeLaReference } from './reference';

const EMAIL_ALICE = `${SEED.demandeur}@example.org`;

interface RapportRelance {
  envoyes: string[];
  dejaConfirmes: string[];
  sansEmail: string[];
  dejaRelancesPourCettePublication: string[];
  echecs: string[];
  sansPoste: string[];
}

interface SyntheseConfirmations {
  confirmes: number;
  relances: number;
  silencieux: number;
  dernierePublicationLe: string | null;
  jamaisPublie: boolean;
}

async function synthese(admin: APIRequestContext): Promise<SyntheseConfirmations> {
  const reponse = await admin.get('/api/animateurs/confirmations/synthese');
  expect(reponse.ok(), await reponse.text()).toBe(true);
  return (await reponse.json()) as SyntheseConfirmations;
}

test.describe('Relance manuelle des silencieux', () => {
  let admin: APIRequestContext;

  test.beforeEach(async ({ playwright, baseURL }) => {
    admin = await contexteAdmin(playwright, baseURL as string);
    await repartirDeLaReference(admin);
    // Le seed publie : Alice (avec adresse) et Bruno (sans) tiennent chacun un
    // siège du plan publié, et aucun des deux n'a encore répondu.
    await seedPlanning(admin);
  });

  test.afterEach(async () => {
    await admin.dispose();
  });

  test('filtrer, sélectionner, relancer : un seul courriel, même après un second clic', async ({
    browser,
  }) => {
    const mailsAvant = await nombreDeMails(admin, EMAIL_ALICE);
    const page = await pageAdmin(browser, admin);
    await page.goto('/animateurs');

    // La synthèse en tête : deux personnes interrogées, aucune réponse.
    await expect(page.locator('.confirmations-synthese')).toContainText('Silencieux 2');
    await expect(page.locator('.confirmations-synthese')).toContainText('Dernière publication le');

    // Le filtre « jamais confirmés », porté par l'URL.
    await ouvrirSelect(page, 'Accusés');
    await page.getByRole('option', { name: 'Jamais confirmés' }).click();
    await expect(page).toHaveURL(/[?&]confirmation=jamais/);
    await expect(page.getByRole('row', { name: /Alice E2E/ })).toHaveCount(1);
    await expect(page.getByRole('row', { name: /Bruno E2E/ })).toHaveCount(1);

    // Tout sélectionner, puis relancer — après confirmation, puisque des
    // courriels partent.
    await page.getByRole('checkbox', { name: 'Tout sélectionner' }).check();
    await page.getByRole('button', { name: 'Relancer maintenant' }).click();
    await expect(page.getByRole('dialog')).toContainText('Relancer 2 animateur(s) maintenant ?');
    await page.getByRole('button', { name: 'Envoyer', exact: true }).click();

    // Le compte rendu : Alice relancée, Bruno nommé comme sans adresse.
    const bulle = page.locator('mat-snack-bar-container');
    await expect(bulle).toContainText('1 relance(s) envoyée(s)');
    await expect(bulle).toContainText('Sans adresse e-mail : Bruno E2E');
    await expect(page.getByRole('row', { name: /Alice E2E/ })).toContainText('Relancé');
    await expect(page.locator('.confirmations-synthese')).toContainText('Relancés 1');

    // Un second clic sur la même sélection ne renvoie rien : Alice est déjà
    // relancée pour cette publication, et le compte rendu le dit.
    await page.getByRole('checkbox', { name: 'Tout sélectionner' }).check();
    await page.getByRole('button', { name: 'Relancer maintenant' }).click();
    await page.getByRole('button', { name: 'Envoyer', exact: true }).click();
    await expect(page.locator('mat-snack-bar-container').last()).toContainText(
      '0 relance(s) envoyée(s)',
    );
    await expect(page.locator('mat-snack-bar-container').last()).toContainText(
      'Déjà relancés pour cette publication : Alice E2E',
    );

    // Mailpit a reçu exactement un courriel de relance pour Alice.
    await expect
      .poll(async () => nombreDeMails(admin, EMAIL_ALICE), { timeout: 10_000 })
      .toBe(mailsAvant + 1);

    await page.context().close();
  });

  test('« silencieux depuis N jours » survit au rechargement et se remet à zéro en une action', async ({
    browser,
  }) => {
    const page = await pageAdmin(browser, admin);
    // Publié à l'instant : personne n'est silencieux depuis un jour.
    await page.goto('/animateurs?silence=1');

    await expect(page.getByText('Aucune ligne ne correspond au filtre.')).toBeVisible();
    await expect(page.getByLabel('Jours')).toHaveValue('1');

    await page.reload();
    await expect(page.getByText('Aucune ligne ne correspond au filtre.')).toBeVisible();

    await page.getByRole('button', { name: 'Réinitialiser la vue' }).click();
    await expect(page).not.toHaveURL(/[?&]silence=/);
    await expect(page.getByRole('row', { name: /Alice E2E/ })).toHaveCount(1);

    await page.context().close();
  });

  test('la nuit ne réécrit pas à quelqu’un relancé à la main, et la main est refusée sans publication', async () => {
    const mailsAvant = await nombreDeMails(admin, EMAIL_ALICE);

    const relance = await admin.post('/api/animateurs/relances', {
      data: { animateurIds: [SEED.demandeur, SEED.cible] },
    });
    expect(relance.ok(), await relance.text()).toBe(true);
    const rapport = (await relance.json()) as RapportRelance;
    expect(rapport.envoyes).toEqual([SEED.demandeur]);
    expect(rapport.sansEmail).toEqual([SEED.cible]);

    // Rejouée : la clé est prise, personne ne reçoit un second message.
    const seconde = await admin.post('/api/animateurs/relances', {
      data: { animateurIds: [SEED.demandeur] },
    });
    expect(seconde.ok()).toBe(true);
    expect(((await seconde.json()) as RapportRelance).dejaRelancesPourCettePublication).toEqual([
      SEED.demandeur,
    ]);
    expect((await synthese(admin)).relances).toBe(1);
    await expect
      .poll(async () => nombreDeMails(admin, EMAIL_ALICE), { timeout: 10_000 })
      .toBe(mailsAvant + 1);

    // Sans publication, il n'y a rien à confirmer, donc personne à relancer.
    const oubli = await admin.post('/api/database/import', {
      headers: { 'Content-Type': 'text/plain' },
      data: 'delete from plan_snapshot;',
    });
    expect(oubli.ok(), await oubli.text()).toBe(true);
    expect((await synthese(admin)).jamaisPublie).toBe(true);
    const refus = await admin.post('/api/animateurs/relances', {
      data: { animateurIds: [SEED.demandeur] },
    });
    expect(refus.status(), await refus.text()).toBe(400);
  });
});
