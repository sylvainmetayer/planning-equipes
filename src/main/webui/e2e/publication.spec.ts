// Publier le planning (issue #245), de bout en bout : le décompte est par
// personne, la liste nominative se relit avant l'envoi, et l'espace d'un
// animateur ne bouge qu'une fois publié.
//
// Le fil conducteur est celui du reproche d'origine : le plan persisté bougeait
// après sa communication et rien ne le disait. Ici, on le fait bouger — un
// siège change de main — et on vérifie que l'application nomme exactement les
// deux personnes concernées, écrit à elles seules, puis retombe à zéro.

import { APIRequestContext, expect, test } from '@playwright/test';
import { SEED, contexteAdmin, jetonDe, ouvrirSessionEspace, pageAdmin, seedPlanning } from './support';

const EMAIL_ALICE = `${SEED.demandeur}@example.org`;

interface ApercuPublication {
  jamaisPublie: boolean;
  planVide: boolean;
  solveEnCours: boolean;
  dernierePublicationLe: string | null;
  nombreConcernes: number;
  destinataires: { animateurId: string; nomAffiche: string; changements: string[] }[];
}

async function apercu(admin: APIRequestContext): Promise<ApercuPublication> {
  const reponse = await admin.get('/api/planning/publication');
  expect(reponse.ok(), await reponse.text()).toBe(true);
  return (await reponse.json()) as ApercuPublication;
}

/** Donne le siège du stand deux à Alice : Alice et Bruno bougent, personne d'autre. */
async function deplacerUnSiege(admin: APIRequestContext): Promise<void> {
  const reponse = await admin.post('/api/database/import', {
    headers: { 'Content-Type': 'text/plain' },
    data:
      `delete from poste_affectation where id = 'E2E-P2';\n` +
      `insert into poste_affectation (edition_id, id, stand_id, creneau_id, animateur_id) ` +
      `values ('DEFAUT', 'E2E-P2', '${SEED.standCible}', ${SEED.creneauId}, '${SEED.demandeur}');`
  });
  expect(reponse.ok(), await reponse.text()).toBe(true);
}

test.describe('Publication du planning', () => {
  let admin: APIRequestContext;

  test.beforeEach(async ({ playwright, baseURL }) => {
    admin = await contexteAdmin(playwright, baseURL as string);
    // Le seed publie déjà : on part donc d'un plan communiqué, comme après un
    // premier envoi réel.
    await seedPlanning(admin);
  });

  test('juste après une publication, plus personne n’est à prévenir', async () => {
    const etat = await apercu(admin);

    expect(etat.jamaisPublie).toBe(false);
    expect(etat.dernierePublicationLe).not.toBeNull();
    expect(etat.nombreConcernes).toBe(0);

    const refus = await admin.post('/api/planning/publication');
    expect(refus.status(), await refus.text()).toBe(409);
  });

  test('déplacer un siège ne concerne que les deux personnes en cause', async () => {
    await deplacerUnSiege(admin);

    const etat = await apercu(admin);
    expect(etat.nombreConcernes).toBe(2);
    expect(etat.destinataires.map((destinataire) => destinataire.animateurId).sort()).toEqual(
      [SEED.cible, SEED.demandeur].sort()
    );
    const alice = etat.destinataires.find((destinataire) => destinataire.animateurId === SEED.demandeur);
    expect(alice?.changements.join(' ')).toContain('Stand E2E deux');
  });

  test('le bouton porte le décompte et la liste nominative se relit avant l’envoi', async ({ browser }) => {
    await deplacerUnSiege(admin);
    const page = await pageAdmin(browser, admin);
    await page.goto('/solveur');

    await expect(page.getByRole('button', { name: /Publier — 2 personnes concernées/ })).toBeVisible();
    await page.getByRole('button', { name: /Voir qui est concerné/ }).click();
    await expect(page.getByText('Stand E2E deux', { exact: false }).first()).toBeVisible();

    await page.close();
  });

  test('publier écrit aux seules personnes concernées, puis le décompte retombe à zéro', async () => {
    await deplacerUnSiege(admin);

    const publication = await admin.post('/api/planning/publication');
    expect(publication.ok(), await publication.text()).toBe(true);
    const rapport = (await publication.json()) as { envoyes: number; sansEmail: string[] };
    // Alice a une adresse, Bruno n'en a pas : le rapport le nomme au lieu de
    // le compter comme envoyé.
    expect(rapport.envoyes).toBe(1);
    expect(rapport.sansEmail.join(' ')).toContain('Bruno');

    expect((await apercu(admin)).nombreConcernes).toBe(0);

    const trace = await admin.get('/api/planning/publication/destinataires');
    expect(trace.ok()).toBe(true);
    const destinataires = (await trace.json()) as { animateurId: string; statut: string }[];
    expect(destinataires.map((destinataire) => destinataire.animateurId).sort()).toEqual(
      [SEED.cible, SEED.demandeur].sort()
    );
    expect(destinataires.map((destinataire) => destinataire.statut).sort()).toEqual(['ENVOYE', 'SANS_EMAIL']);
  });

  test('l’espace d’un animateur ne bouge qu’une fois publié', async () => {
    const jeton = await jetonDe(admin, SEED.demandeur);
    await ouvrirSessionEspace(admin, jeton, EMAIL_ALICE);

    const avant = await admin.get(`/api/espace-animateur/${jeton}`);
    expect(avant.ok()).toBe(true);
    expect(((await avant.json()) as { postes: unknown[] }).postes).toHaveLength(1);

    await deplacerUnSiege(admin);

    const pendant = await admin.get(`/api/espace-animateur/${jeton}`);
    expect(((await pendant.json()) as { postes: unknown[] }).postes).toHaveLength(1);

    const publication = await admin.post('/api/planning/publication');
    expect(publication.ok(), await publication.text()).toBe(true);

    const apres = await admin.get(`/api/espace-animateur/${jeton}`);
    expect(((await apres.json()) as { postes: unknown[] }).postes).toHaveLength(2);
  });
});
