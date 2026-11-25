// The RH right, end to end: a local account invited by the admin API signs in
// and reads the compliance report of ITS edition (screen, PDF, CSV), keeps the
// emergency and legal-representative contacts of the people planned, while an
// organiser sees none of it and a colleague's espace carries no contact at all
// (site/src/docs/installer/rgpd.md). Every name and phone number is generated, never typed.

import { APIRequestContext, expect, test } from '@playwright/test';
import { readFileSync } from 'node:fs';
import {
  EDITION_REFERENCE,
  SEED,
  shiftDate,
  connecterCompteLocal,
  contexteAdmin,
  creerCompteLocal,
  jetonDe,
  nomFictif,
  ouvrirSessionEspace,
  publierPlanning,
  seedPlanning,
  telephoneFictif,
  type CompteLocal,
} from './support';
import { repartirDeLaReference } from './reference';

const MINEUR = 'E2E-M';
const EMAIL_ALICE = `${SEED.demandeur}@example.org`;
const EMAIL_BRUNO = `${SEED.cible}@example.org`;

// What the people of the fixture "wrote", generated once per run.
const representant = { nom: nomFictif('Representant'), tel: telephoneFictif() };
const urgenceAlice = { nom: nomFictif('Urgence'), tel: telephoneFictif() };

let admin: APIRequestContext;
let autreEdition: string;
const comptes: CompteLocal[] = [];

test.describe.configure({ mode: 'serial' });

test.beforeAll(async ({ playwright }, testInfo) => {
  admin = await contexteAdmin(playwright, testInfo.project.use.baseURL as string);
  await repartirDeLaReference(admin);
  await seedPlanning(admin, { avecCollegueLibre: true });
  // A minor on the day-after timeslot, so the report has someone to name.
  const mineur = await admin.post('/api/database/import', {
    headers: { 'Content-Type': 'text/plain' },
    data: [
      `insert into animateur (edition_id, id, prenom, nom, date_naissance, manager) values ('${EDITION_REFERENCE}', '${MINEUR}', 'Mina', 'E2E', '${shiftDateMineur()}', false);`,
      `insert into poste_affectation (edition_id, id, stand_id, creneau_id, animateur_id) values ('${EDITION_REFERENCE}', 'E2E-P4', '${SEED.standDemandeur}', ${SEED.creneauAutreJour}, '${MINEUR}');`,
    ].join('\n'),
  });
  expect(mineur.ok(), await mineur.text()).toBe(true);
  await publierPlanning(admin);
  // An edition the RH right does not name.
  const cree = await admin.post('/api/editions', { data: { nom: nomFictif('E2E-autre') } });
  expect(cree.ok(), await cree.text()).toBe(true);
  autreEdition = ((await cree.json()) as { id: string }).id;
});

test.afterAll(async () => {
  for (const compte of comptes) {
    await admin.post(`/api/comptes/${compte.id}/desactivation`);
  }
  await admin.delete(`/api/editions/${autreEdition}`);
  await admin.dispose();
});

/** Born in 2010, moved with the fixture so the person stays sixteen whatever the year. */
function shiftDateMineur(): string {
  return shiftDate('2010-03-03');
}

test("le RH lit le rapport de son édition, et d'aucune autre", async ({ page }) => {
  const rh = await creerCompteLocal(admin, page.request, 'RH E2E', {
    role: 'RH',
    editionId: EDITION_REFERENCE,
  });
  comptes.push(rh);
  await connecterCompteLocal(page, rh);

  await expect(page).toHaveURL(/\/rh$/);
  await expect(page.getByRole('heading', { level: 1, name: 'Conformité RH' })).toBeVisible();
  await expect(page.getByRole('heading', { name: 'Rapport de conformité' })).toBeVisible();
  await expect(page.getByRole('rowheader', { name: 'Mina E2E', exact: true })).toBeVisible();
  await expect(page.getByText('Représentant légal non renseigné', { exact: false })).toBeVisible();

  const editions = (await (await page.request.get('/api/rh/editions')).json()) as { id: string }[];
  expect(editions.map((e) => e.id)).toEqual([EDITION_REFERENCE]);
  // Another edition answers like one that does not exist, for every read.
  for (const route of ['conformite', 'conformite.pdf', 'conformite.csv', 'contacts']) {
    const reponse = await page.request.get(`/api/rh/editions/${autreEdition}/${route}`);
    expect(reponse.status(), route).toBe(404);
  }
  // And nothing of the administration opens.
  for (const route of ['/api/comptes', '/api/animateurs']) {
    expect((await page.request.get(route)).status(), route).toBe(403);
  }
});

test('le RH exporte le rapport en PDF et en CSV', async ({ page }) => {
  await connecterCompteLocal(page, comptes[0]);
  await expect(page.getByRole('heading', { name: 'Rapport de conformité' })).toBeVisible();

  const pdfPrevu = page.waitForEvent('download');
  await page.getByRole('button', { name: 'Exporter en PDF' }).click();
  const pdf = await pdfPrevu;
  expect(pdf.suggestedFilename()).toBe('rapport-conformite.pdf');
  expect(
    readFileSync(await pdf.path())
      .subarray(0, 4)
      .toString('latin1'),
  ).toBe('%PDF');

  const csvPrevu = page.waitForEvent('download');
  await page.getByRole('button', { name: 'Exporter en CSV' }).click();
  const csv = await csvPrevu;
  expect(csv.suggestedFilename()).toBe('rapport-conformite.csv');
  expect(readFileSync(await csv.path(), 'utf8')).toContain('Mina');
});

test("le RH renseigne le représentant légal d'un mineur, absent du CSV exporté", async ({
  page,
}) => {
  await connecterCompteLocal(page, comptes[0]);
  // The contacts table, told apart from the report's by its caption.
  const contacts = page
    .getByRole('table', { name: 'Contacts par animateur' })
    .getByRole('row')
    .filter({ has: page.getByRole('rowheader', { name: /Mina/ }) });
  await contacts.getByRole('button', { name: /Modifier/ }).click();
  await contacts.getByLabel('Représentant légal : nom').fill(representant.nom);
  await contacts.getByLabel('Représentant légal : téléphone').fill(representant.tel);
  await contacts.getByRole('button', { name: 'Enregistrer' }).click();
  await expect(contacts.getByText(representant.nom)).toBeVisible();

  // The gap is gone from the report after a reload.
  await page.reload();
  await expect(page.getByRole('heading', { name: 'Rapport de conformité' })).toBeVisible();
  await expect(page.getByText('Représentant légal non renseigné', { exact: false })).toHaveCount(0);

  // The CSV that leaves the application never carries the contact itself. Not
  // the PDF: its content streams are compressed, so a search of its bytes
  // would pass whatever it held — the report type it is drawn from has no
  // contact field, and that is the proof.
  const reponse = await page.request.get(`/api/rh/editions/${EDITION_REFERENCE}/conformite.csv`);
  expect(reponse.ok()).toBe(true);
  const csv = await reponse.text();
  expect(csv).toContain('Mina');
  expect(csv).not.toContain(representant.nom);
  expect(csv).not.toContain(representant.tel);
});

test("l'animateur dépose ses contacts dans « Mes contacts », le RH les lit", async ({ page }) => {
  const jeton = await jetonDe(admin, SEED.demandeur);
  await ouvrirSessionEspace(page.request, jeton, EMAIL_ALICE);
  await page.goto(`/animateur/${jeton}/contacts`);
  await expect(page.getByRole('heading', { level: 1, name: 'Mes contacts' })).toBeVisible();
  await page.getByLabel('Personne à prévenir : nom').fill(urgenceAlice.nom);
  await page.getByLabel('Personne à prévenir : téléphone').fill(urgenceAlice.tel);
  await page.getByRole('button', { name: 'Enregistrer' }).click();
  await expect(page.getByText('Vos contacts sont enregistrés.')).toBeVisible();

  const vues = (await (
    await admin.get(`/api/rh/editions/${EDITION_REFERENCE}/contacts`)
  ).json()) as { animateurId: string; contacts: { urgenceNom?: string } }[];
  expect(vues.find((v) => v.animateurId === SEED.demandeur)?.contacts.urgenceNom).toBe(
    urgenceAlice.nom,
  );
});

test("aucun contact n'apparaît chez un collègue, ni dans les fiches ni dans la base exportée", async ({
  page,
}) => {
  const animateurs = (await (await admin.get('/api/animateurs')).json()) as { id: string }[];
  const bruno = animateurs.find((a) => a.id === SEED.cible);
  const avecEmail = await admin.put(`/api/animateurs/${SEED.cible}`, {
    data: { ...bruno, email: EMAIL_BRUNO },
  });
  expect(avecEmail.ok(), await avecEmail.text()).toBe(true);
  const jeton = await jetonDe(admin, SEED.cible);
  await ouvrirSessionEspace(page.request, jeton, EMAIL_BRUNO);

  const valeurs = [urgenceAlice.nom, urgenceAlice.tel, representant.nom, representant.tel];
  const sansContact = (texte: string, lieu: string) => {
    for (const valeur of valeurs) {
      expect(texte, `${lieu} ne doit pas contenir ${valeur}`).not.toContain(valeur);
    }
  };

  // Each screen is read once what it loads is on screen, not before.
  await page.goto(`/animateur/${jeton}`);
  await expect(page.getByText('Stand E2E deux').first()).toBeVisible();
  sansContact(await page.locator('body').innerText(), 'espace planning');
  await page.goto(`/animateur/${jeton}/echanges`);
  await expect(page.getByRole('heading', { level: 1, name: 'Mes échanges' })).toBeVisible();
  sansContact(await page.locator('body').innerText(), 'espace échanges');
  // His own contact form is empty: Alice's are hers alone.
  await page.goto(`/animateur/${jeton}/contacts`);
  await expect(page.getByLabel('Personne à prévenir : nom')).toHaveValue('');
  sansContact(await page.locator('body').innerText(), 'espace contacts');

  sansContact(
    await (await page.request.get(`/api/espace-animateur/${jeton}`)).text(),
    'API espace',
  );
  sansContact(await (await admin.get('/api/animateurs')).text(), 'liste des fiches');
  sansContact(await (await admin.get('/api/database/export')).text(), 'export de la base');
});

test('un organisateur ne voit ni le rapport ni les contacts', async ({ page }) => {
  const organisateur = await creerCompteLocal(admin, page.request, 'Orga E2E', {
    role: 'ORGANISATEUR',
    editionId: EDITION_REFERENCE,
  });
  comptes.push(organisateur);
  await connecterCompteLocal(page, organisateur);
  await expect(page.getByRole('navigation', { name: 'Navigation principale' })).toBeVisible();

  for (const route of ['conformite', 'conformite.pdf', 'conformite.csv', 'contacts']) {
    const reponse = await page.request.get(`/api/rh/editions/${EDITION_REFERENCE}/${route}`);
    expect(reponse.status(), route).toBe(403);
  }
  expect((await page.request.get('/api/rh/editions')).status()).toBe(403);

  // The tab is not offered, and asking for it by address lands on the default one.
  await page.goto('/diagnostic?onglet=conformite');
  const onglets = page.getByRole('radiogroup', { name: 'Onglet du diagnostic' });
  await expect(onglets.getByRole('radio', { name: /^Problèmes/ })).toBeChecked();
  await expect(onglets.getByRole('radio', { name: 'Conformité' })).toHaveCount(0);
  await expect(page.getByText('Rapport de conformité')).toHaveCount(0);
  const texte = await page.locator('body').innerText();
  expect(texte).not.toContain(representant.nom);
  expect(texte).not.toContain(urgenceAlice.nom);
});
