// Les avertissements de saisie (voir docs/decisions/0020) vus du navigateur :
// le message apparaît, il est lisible, et — c'est tout l'enjeu — la ligne est
// bien enregistrée derrière. Un avertissement qui aurait bloqué l'écriture
// serait un refus déguisé, et cette suite est ce qui l'interdit.

import { APIRequestContext, expect, test } from '@playwright/test';
import { contexteAdmin, pageAdmin, seedReferentielSolveur } from './support';
import { repartirDeLaReference } from './reference';

let admin: APIRequestContext;

/** Un jour très éloigné des autres fixtures : les bornes de l'édition sont les siennes. */
const JOUR = '2027-06-10';
const STAND = 'E2E-AVERT-S';
const STAND_MATIN = 'E2E-AVERT-M';
const ANIMATEUR = 'E2E-AVERT';
const CRENEAU_REFERENCE = 987500;

/** Les stands que cette suite n'a pas créés, dans leur état d'origine, pour les rendre tels quels. */
let standsVoisins: Record<string, unknown>[] = [];

/** Les créneaux créés par l'IHM ont un id généré : on les retrouve par leur date. */
async function supprimerCreneauxDuJour(): Promise<void> {
  const creneaux = (await (await admin.get('/api/creneaux')).json()) as { id: number; date: string }[];
  for (const creneau of creneaux.filter((candidat) => candidat.date === JOUR)) {
    await admin.delete(`/api/creneaux/${creneau.id}`).catch(() => undefined);
  }
}

test.beforeAll(async ({ playwright }, testInfo) => {
  admin = await contexteAdmin(playwright, testInfo.project.use.baseURL as string);
  await repartirDeLaReference(admin);
  // Référentiel connu : les avertissements se lisent contre TOUS les stands et
  // TOUS les créneaux de l'édition, donc une ligne oubliée par une autre suite
  // déplacerait les bornes sous les assertions. Le créneau de référence est ce
  // qui donne des bornes à l'édition : sans lui, rien n'est « hors bornes ».
  await seedReferentielSolveur(admin, [], [], [
    { id: CRENEAU_REFERENCE, date: JOUR, debut: '14:00', fin: '18:00' }
  ]);

  // La base d'une pile e2e porte un stand de démonstration, ouvert par défaut :
  // il suffirait à couvrir n'importe quel créneau. On le ferme pour la journée
  // testée, et on le rend intact ensuite.
  standsVoisins = (await (await admin.get('/api/stands')).json()) as Record<string, unknown>[];
  for (const voisin of standsVoisins) {
    const ferme = await admin.put(`/api/stands/${voisin['id']}`, {
      data: {
        ...voisin,
        indisponibilites: [
          ...(voisin['indisponibilites'] as unknown[]),
          { date: JOUR, heureDebut: '00:00:00', heureFin: null, motif: 'e2e' }
        ]
      }
    });
    expect(ferme.ok(), await ferme.text()).toBe(true);
  }

  // Un seul stand ouvert ce jour-là, de 14 h à la fermeture, par une règle
  // RÉCURRENTE : c'est le cas que la résolution des horaires doit couvrir. Lu
  // sur les fenêtres datées seules, ce stand paraîtrait fermé et
  // l'avertissement crierait au loup sur un créneau parfaitement valide.
  const stand = await admin.post('/api/stands', {
    data: {
      id: STAND,
      nom: "Stand de l'après-midi",
      typologiesProposees: ['STRATEGIE'],
      effectifMin: 1,
      effectifMax: 2,
      reserveMajeurs: false,
      horaires: [{ mode: 'OUVERTURE', jours: 'TOUS', fenetres: [{ heureDebut: '14:00:00' }] }]
    }
  });
  expect(stand.ok(), await stand.text()).toBe(true);
});

test.afterAll(async () => {
  await supprimerCreneauxDuJour();
  await admin.delete(`/api/animateurs/${ANIMATEUR}`).catch(() => undefined);
  await admin.delete(`/api/stands/${STAND}`).catch(() => undefined);
  await admin.delete(`/api/stands/${STAND_MATIN}`).catch(() => undefined);
  for (const voisin of standsVoisins) {
    await admin.put(`/api/stands/${voisin['id']}`, { data: voisin }).catch(() => undefined);
  }
  await admin.dispose();
});

test.describe('avertissements de saisie', () => {
  test('un créneau qui déborde les ouvertures est enregistré, avec un message', async ({ browser }) => {
    const page = await pageAdmin(browser, admin);
    await page.goto('/creneaux');

    await page.getByRole('button', { name: 'Ajouter' }).click();
    const dialog = page.getByRole('dialog');
    await dialog.getByLabel('Date').fill(JOUR);
    await dialog.getByLabel('Début').fill('10:00');
    await dialog.getByLabel('Fin').fill('18:00');
    await dialog.getByRole('button', { name: 'Créer le créneau' }).click();
    await expect(dialog).toBeHidden();

    // Le message nomme la plage morte, pas seulement « attention ».
    await expect(page.getByText(/déborde l'amplitude d'ouverture/)).toBeVisible();
    await expect(page.getByText(/de 10:00 à 14:00/)).toBeVisible();
    // Un créneau se crée sans identifiant : c'est celui rendu par le serveur
    // qui doit s'afficher, dans une bulle qui reste jusqu'à sa fermeture.
    await expect(page.getByText(/Créneau undefined/)).toHaveCount(0);

    // Et la ligne est là : avertir, jamais bloquer.
    await page.getByRole('button', { name: 'Fermer' }).click();
    await expect(page.getByRole('row', { name: /10:00:00/ })).toHaveCount(1);

    await page.context().close();
  });

  test('un créneau dans l’amplitude ne déclenche rien', async ({ browser }) => {
    const page = await pageAdmin(browser, admin);
    await page.goto('/creneaux');

    await page.getByRole('button', { name: 'Ajouter' }).click();
    const dialog = page.getByRole('dialog');
    await dialog.getByLabel('Date').fill(JOUR);
    await dialog.getByLabel('Début').fill('15:00');
    await dialog.getByLabel('Fin').fill('17:00');
    await dialog.getByRole('button', { name: 'Créer le créneau' }).click();
    await expect(dialog).toBeHidden();

    await expect(page.getByText(/Création de Créneau/)).toBeVisible();
    await expect(page.getByText(/déborde l'amplitude d'ouverture/)).toHaveCount(0);

    await page.context().close();
  });

  test("un stand dont la fenêtre ne recoupe aucun créneau est enregistré, avec un message", async ({ browser }) => {
    const page = await pageAdmin(browser, admin);
    await page.goto('/stands');

    await page.getByRole('button', { name: 'Ajouter' }).first().click();
    const dialog = page.getByRole('dialog');
    await dialog.getByLabel('Identifiant').fill(STAND_MATIN);
    await dialog.getByLabel('Nom').fill('Stand du matin');
    // A stand always carries a typologie (issue #343): the form refuses to submit without one.
    await dialog.getByLabel('Typologies de jeu').click();
    await page.getByRole('option', { name: 'STRATEGIE' }).click();
    await page.keyboard.press('Escape');
    await dialog.getByRole('button', { name: "Ajouter une règle d'horaire" }).click();
    // The whole day's windows on one line: the morning, before the only créneau.
    await dialog.getByLabel('Fenêtres de la journée').fill('08:00-10:00');
    await dialog.getByRole('button', { name: 'Créer le stand' }).click();
    await expect(dialog).toBeHidden();

    // The message names the day and the window, and says the stand is written.
    await expect(page.getByText(/ne recoupent aucun créneau/)).toBeVisible();
    await expect(page.getByText(/08:00/)).toBeVisible();
    await page.getByRole('button', { name: 'Fermer' }).click();
    await expect(page.getByRole('row', { name: /Stand du matin/ })).toHaveCount(1);

    await page.context().close();
  });

  test('une date de naissance mineure et une indisponibilité hors bornes sont enregistrées, avec un message', async ({
    browser
  }) => {
    const page = await pageAdmin(browser, admin);
    await page.goto('/animateurs');

    await page.getByRole('button', { name: 'Ajouter' }).click();
    const dialog = page.getByRole('dialog');
    await dialog.getByLabel('Identifiant').fill(ANIMATEUR);
    await dialog.getByLabel('Prénom').fill('Camille');
    await dialog.getByLabel('Nom', { exact: true }).fill('Avert');
    await dialog.getByLabel('Date de naissance').fill('2015-06-11');
    await dialog.getByLabel('Jour').fill('2027-08-15');
    await dialog.getByRole('button', { name: 'Ajouter', exact: true }).click();
    await dialog.getByRole('button', { name: "Créer l'animateur" }).click();
    await expect(dialog).toBeHidden();

    // Les deux règles à la fois, chacune nommant ce qu'elle a vu. Assertions
    // portées sur la bulle seule : la fiche derrière porte, elle, le nom et la
    // date de naissance qu'on vient de saisir.
    const bulle = page.locator('mat-snack-bar-container');
    await expect(bulle).toContainText(/mineur pendant tout l'événement/);
    await expect(bulle).toContainText('2027-08-15');
    // Le message dit qui par son identifiant, jamais par son identité ni par sa
    // date de naissance : il finit dans un journal de navigateur.
    await expect(bulle).toContainText('E2E-AVERT');
    await expect(bulle).not.toContainText('Camille');
    await expect(bulle).not.toContainText('2015-06-11');

    // La fiche existe, et l'indisponibilité hors bornes a bien été écrite.
    await page.getByRole('button', { name: 'Fermer' }).click();
    await page.getByLabel('Filtrer').fill(ANIMATEUR);
    const ligne = page.getByRole('row', { name: new RegExp(ANIMATEUR) });
    await expect(ligne).toBeVisible();
    await ligne.getByRole('button', { name: 'Consulter le détail' }).click();
    await expect(page.getByRole('dialog')).toContainText('2027-08-15');

    // Le journal du navigateur survit à la déconnexion et se relit depuis la
    // page Notifications : la phrase qui dit qu'une personne est mineure n'y
    // est pas écrite (docs/rgpd.md §7), l'indisponibilité si.
    await page.keyboard.press('Escape');
    await page.goto('/notifications');
    await expect(page.getByText(/point\(s\) à vérifier/).first()).toBeVisible();
    await expect(page.getByText(/est mineur pendant tout l'événement/)).toHaveCount(0);
    await expect(page.getByText(/Indisponibilité hors de l'événement/).first()).toBeVisible();

    await page.context().close();
  });

  /**
   * Le cri au loup que la fonctionnalité doit éviter : la fiche reste celle
   * d'un mineur, mais on n'a touché ni sa date de naissance ni ses
   * indisponibilités. Une édition en lot envoie exactement cette requête-là,
   * une par ligne cochée.
   */
  test('modifier un mineur sans toucher sa date de naissance ne signale rien', async ({ browser }) => {
    const page = await pageAdmin(browser, admin);
    await page.goto('/animateurs');
    await page.getByLabel('Filtrer').fill(ANIMATEUR);

    const ligne = page.getByRole('row', { name: new RegExp(ANIMATEUR) });
    await ligne.getByRole('button', { name: 'Modifier' }).click();
    const dialog = page.getByRole('dialog');
    await dialog.getByLabel('Prénom').fill('Camille-Marie');
    await dialog.getByRole('button', { name: "Modifier l'animateur" }).click();
    await expect(dialog).toBeHidden();

    await expect(page.getByText(/Modification de Animateur/)).toBeVisible();
    await expect(page.getByText(/point\(s\) à vérifier/)).toHaveCount(0);

    await page.context().close();
  });

  test('une fiche cohérente ne déclenche rien à la modification', async ({ browser }) => {
    const page = await pageAdmin(browser, admin);
    await page.goto('/animateurs');
    await page.getByLabel('Filtrer').fill(ANIMATEUR);

    const ligne = page.getByRole('row', { name: new RegExp(ANIMATEUR) });
    await ligne.getByRole('button', { name: 'Modifier' }).click();
    const dialog = page.getByRole('dialog');
    await dialog.getByLabel('Date de naissance').fill('1990-01-01');
    await dialog.getByRole('button', { name: 'Retirer 2027-08-15' }).click();
    await dialog.getByRole('button', { name: "Modifier l'animateur" }).click();
    await expect(dialog).toBeHidden();

    await expect(page.getByText(/Modification de Animateur/)).toBeVisible();
    // Le libellé de la bulle d'avertissement, qu'aucun autre texte de l'écran
    // ne porte — contrairement au mot « mineur », que la colonne Majeur emploie.
    await expect(page.getByText(/point\(s\) à vérifier/)).toHaveCount(0);

    await page.context().close();
  });
});
