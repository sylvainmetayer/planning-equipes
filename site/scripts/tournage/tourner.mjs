// Tournage des captures et des vidéos de la vitrine (site/README.md, « Le tournage »).
//
// Joue le scénario `vitrine-fete-du-jeu-de-pierrefeuille.yaml` sur une pile déjà
// montée (application, PostgreSQL, Mailpit : voir tourner.sh) et dépose du brut :
//   <OUT>/<langue>/<nom>.png            les neuf écrans, en PNG
//   <OUT>/<langue>/film-<nom>.webm      les trois parcours, tels que Playwright les filme
//   <OUT>/<langue>/film-<nom>.marks     « début fin durée-cible » par ligne : les passages que le montage ramène à cette durée
// tourner.sh encode ensuite le tout (WebP, MP4, vignettes, affiches).
//
// Variables : BASE_URL (http://localhost:8080), MAILPIT_URL (http://localhost:8025),
// ADMIN_PASSWORD, SCENARIO (chemin du YAML), OUT (dossier du brut), LANGS (« fr,en »),
// ONLY (« ecrans », « calcul », « jourj », « espace » : un seul morceau, pour le réglage),
// CHROMIUM (exécutable Chromium, si Playwright n'a pas le sien).
import { chromium, request } from 'playwright';
import { mkdirSync, readFileSync, renameSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';

const BASE = process.env.BASE_URL ?? 'http://localhost:8080';
const MAILPIT = process.env.MAILPIT_URL ?? 'http://localhost:8025';
const MOT_DE_PASSE = process.env.ADMIN_PASSWORD;
if (!MOT_DE_PASSE) throw new Error('ADMIN_PASSWORD manque (tourner.sh le fournit)');
const SCENARIO = process.env.SCENARIO ?? '/repo/src/main/resources/scenarios/vitrine-fete-du-jeu-de-pierrefeuille.yaml';
const OUT = process.env.OUT ?? '/out';
const LANGS = (process.env.LANGS ?? 'fr,en').split(',');
const ONLY = process.env.ONLY;
const JOUR = '2027-08-10';
const HEURE = '15:00';
const BUREAU = { width: 1440, height: 900 };
const TV = { width: 1600, height: 900 };
const FILM = { width: 1280, height: 800 };
const TELEPHONE = { width: 412, height: 800 };

const pause = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

/* ----------------------------- préparation ----------------------------- */

/**
 * Remet l'application dans l'état de départ : le scénario importé dans une
 * édition active, seule de son espèce, résolue, publiée, l'horloge arrêtée au
 * 10 août 2027 à 15 h, un lien d'affichage TV. Rejouée avant chaque langue, car
 * les parcours modifient le plan (une absence, une présence confirmée, un calcul).
 */
async function preparer() {
  const api = await request.newContext({ baseURL: BASE });
  const connexion = await api.post('/j_security_check', {
    form: { j_username: 'admin', j_password: MOT_DE_PASSE },
    maxRedirects: 0,
  });
  if (connexion.status() !== 302) throw new Error(`Connexion refusée (${connexion.status()})`);

  const ok = async (reponse, quoi) => {
    if (!reponse.ok()) throw new Error(`${quoi} : ${reponse.status()} ${(await reponse.text()).slice(0, 300)}`);
    return reponse;
  };
  // Une édition de passage porte l'en-tête de l'import ; l'édition du scénario, elle, naît du fichier. Les
  // éditions d'une passe précédente (avec leurs accusés de lecture) disparaissent toutes à la fin.
  const avant = await (await ok(await api.get('/api/editions'), 'Éditions')).json();
  const passage = await (await ok(await api.post('/api/editions', { data: { nom: 'Passage' } }), 'Édition de passage')).json();
  // L'import d'un scénario dans une édition de même nom la réutilise, publications et accusés compris : on la supprime d'abord
  await ok(await api.put(`/api/editions/${passage.id}/active`, { headers: { 'X-Edition-Id': passage.id } }), 'Activation du passage');
  for (const ancienne of avant) {
    await ok(await api.delete(`/api/editions/${ancienne.id}`, { headers: { 'X-Edition-Id': passage.id } }), `Suppression de ${ancienne.id}`);
  }
  const importe = await (
    await ok(
      await api.post('/api/reference-data/import-scenario-fichier', {
        headers: { 'Content-Type': 'text/plain', 'X-Edition-Id': passage.id },
        data: readFileSync(SCENARIO, 'utf8'),
      }),
      'Import du scénario',
    )
  ).json();
  const edition = importe.editionId;
  const h = { 'X-Edition-Id': edition };

  await ok(await api.put(`/api/editions/${edition}/active`, { headers: h }), 'Activation');
  await ok(await api.delete(`/api/editions/${passage.id}`, { headers: h }), 'Suppression du passage');

  // Une horloge déjà arrêtée par une passe précédente gèlerait le passé : les deux premiers jours resteraient vides
  await ok(await api.put('/api/horloge', { headers: h, data: { dateDuJour: '' } }), 'Horloge rendue');
  const job = await (await ok(await api.post('/api/solve/async/reference-data?seconds=40', { headers: h }), 'Calcul')).json();
  for (let essai = 0; essai < 120; essai++) {
    const etat = await (await api.get(`/api/jobs/${job.id}`, { headers: h })).json();
    if (etat.status === 'COMPLETED') break;
    if (['FAILED', 'CANCELLED', 'INTERRUPTED'].includes(etat.status)) throw new Error(`Calcul : ${etat.status} ${etat.error ?? ''}`);
    await pause(2000);
  }
  await ok(await api.post('/api/planning/publication', { headers: h, data: { exclusions: [] } }), 'Publication');
  await ok(await api.put('/api/horloge', { headers: h, data: { dateDuJour: JOUR, heureDuJour: HEURE } }), 'Horloge');
  const mural = await (
    await ok(
      await api.post('/api/affichage-mural', { headers: h, data: { libelle: 'Salle de contrôle', fullNames: false, emplacements: [] } }),
      'Lien TV',
    )
  ).json();
  const animateurs = await (await ok(await api.get('/api/animateurs', { headers: h }), 'Animateurs')).json();
  return { api, edition, h, muralToken: mural.token, animateurs };
}

/* ------------------------------- navigateur ------------------------------ */

async function contexte(browser, etat, lang, viewport, options = {}) {
  const ctx = await browser.newContext({
    viewport,
    locale: lang === 'en' ? 'en-GB' : 'fr-FR',
    colorScheme: 'light',
    storageState: await etat.api.storageState(),
    ...options,
  });
  await ctx.addInitScript(
    ([edition, langue]) => {
      localStorage.setItem('planning-equipes.editionId', edition);
      localStorage.setItem('planning-equipes.locale', langue);
      localStorage.setItem('planning-equipes.theme', 'light');
    },
    [etat.edition, lang],
  );
  return ctx;
}

/** Referme le menu latéral, comme sur les captures : le contenu prend la largeur. */
async function fermerLeMenu(page) {
  const bouton = page.locator('mat-toolbar button').first();
  await bouton.click();
  await pause(700);
}

async function ouvrir(page, chemin, { menu = true } = {}) {
  await page.goto(`${BASE}${chemin}`);
  await page.locator('mat-toolbar').first().waitFor();
  await pause(1500);
  if (menu) await fermerLeMenu(page);
  await pause(600);
}

/* --------------------------------- écrans -------------------------------- */

async function ecrans(browser, etat, lang) {
  const dossier = join(OUT, lang);
  mkdirSync(dossier, { recursive: true });
  const ctx = await contexte(browser, etat, lang, BUREAU);
  const page = await ctx.newPage();
  const pages = [
    ['accueil', '/'],
    ['planning', '/journee'],
    ['rail', '/journee?vue=rail'],
    ['personne', '/journee?axe=personne'],
    ['horaires', '/ouvertures'],
    ['regles', '/regles'],
    ['diagnostic', '/diagnostic'],
    ['diffuser', '/publication'],
  ];
  for (const [nom, chemin] of pages) {
    await ouvrir(page, chemin);
    await page.screenshot({ path: join(dossier, `${nom}.png`) });
  }
  await ctx.close();

  // L'affichage TV est public : un jeton, aucune session
  const tv = await browser.newContext({ viewport: TV, locale: lang === 'en' ? 'en-GB' : 'fr-FR', colorScheme: 'light' });
  await tv.addInitScript((langue) => localStorage.setItem('planning-equipes.locale', langue), lang);
  const ecran = await tv.newPage();
  await ecran.goto(`${BASE}/mural/${etat.muralToken}`);
  await pause(4000);
  await ecran.screenshot({ path: join(dossier, 'mural.png') });
  await tv.close();
}

/* --------------------------------- films --------------------------------- */

/**
 * Un film : un contexte qui enregistre, un chrono pour dater les passages à
 * accélérer. Playwright écrit un .webm au `close()` du contexte, sous un nom
 * tiré au hasard ; on le renomme.
 */
async function filmer(browser, etat, lang, nom, viewport, scenario) {
  const dossier = join(OUT, lang);
  mkdirSync(dossier, { recursive: true });
  const ctx = await contexte(browser, etat, lang, viewport, { recordVideo: { dir: dossier, size: viewport } });
  const page = await ctx.newPage();
  const debut = Date.now();
  const marques = [];
  const accelerer = async (cible, attente) => {
    const de = (Date.now() - debut) / 1000;
    await attente();
    marques.push(`${de.toFixed(2)} ${((Date.now() - debut) / 1000).toFixed(2)} ${cible}`);
  };
  try {
    await scenario(page, { ctx, accelerer, pause });
  } finally {
    const video = page.video();
    await ctx.close();
    renameSync(await video.path(), join(dossier, `film-${nom}.webm`));
    // Sans passage à accélérer, pas de fichier : tourner.sh n'a rien à lire
    if (marques.length) writeFileSync(join(dossier, `film-${nom}.marks`), `${marques.join('\n')}\n`);
  }
}

/** Le texte d'un bouton ou d'un champ, dans les deux langues de l'interface. */
const L = {
  chercher: /Chercher une personne|Search for a person/,
  absent: /Marquer absent|Mark absent/,
  raison: /Raison|Reason/,
  confirmer: /Confirmer l.absence|Confirm the absence/,
  remplacer: /Remplacer sur le reste du créneau|Replace for the rest of the time slot/,
  affecter: /Affecter|Assign/,
  malade: { fr: 'Malade', en: 'Sick' },
  recevoirCode: /Recevoir mon code|Send me my code/,
  ouvrirEspace: /Ouvrir mon espace|Open my space/,
  jaiLu: /J'ai lu et je serai là|I have read it and I will be there/,
  apercu: /Aperçu|Overview/,
  coequipiers: /Coéquipiers|Teammates/,
  echanges: /Mes échanges|My swaps/,
  recommencer: /Recommencer de zéro|Start over from scratch/,
  faisable: /Plan faisable|Feasible plan/,
  voirPlanning: /Voir le planning|View the schedule|Show the schedule/,
};

/** Saisie à vitesse humaine, pour que le film se lise. */
const taper = (champ, texte) => champ.pressSequentially(texte, { delay: 90 });

/** Qui est de service le 10 août de 14 h à 18 h, d'après le plan enregistré : l'absent du parcours. */
async function absentDuParcours(etat) {
  const plan = await (await etat.api.get('/api/planning/persisted', { headers: etat.h })).json();
  const poste = plan.postes.find((p) => p.creneau.date === JOUR && p.creneau.heureDebut.startsWith('14:00') && p.animateur);
  return `${poste.animateur.prenom} ${poste.animateur.nom}`;
}

/** Une animateur avec une adresse et assez de postes à venir pour que son espace soit plein. */
async function animateurDeLEspace(etat) {
  const plan = await (await etat.api.get('/api/planning/persisted', { headers: etat.h })).json();
  const postes = {};
  for (const p of plan.postes) if (p.animateur && p.creneau.date > JOUR) postes[p.animateur.id] = (postes[p.animateur.id] ?? 0) + 1;
  return etat.animateurs.find((a) => a.email && postes[a.id] >= 6);
}

/** Combien de messages Mailpit tient pour cette adresse. */
async function nombreDeMails(etat, email) {
  const q = encodeURIComponent(`to:"${email}"`);
  return (await (await etat.api.get(`${MAILPIT}/api/v1/search?query=${q}`)).json()).messages_count ?? 0;
}

/** Le code d'accès du message arrivé après `avant` : une publication a écrit à la même adresse, et ses codes sont périmés. */
async function codeMailpit(etat, email, avant) {
  const q = encodeURIComponent(`to:"${email}"`);
  for (let essai = 0; essai < 60; essai++) {
    const recherche = await (await etat.api.get(`${MAILPIT}/api/v1/search?query=${q}`)).json();
    const nouveaux = (recherche.messages ?? []).slice(0, Math.max(0, recherche.messages_count - avant));
    for (const m of nouveaux) {
      const texte = (await (await etat.api.get(`${MAILPIT}/api/v1/message/${m.ID}`)).json()).Text;
      const code = /\b(\d{6})\b/.exec(texte)?.[1];
      if (code) return code;
    }
    await pause(500);
  }
  throw new Error(`Aucun code dans Mailpit pour ${email}`);
}

/** Un calcul de zéro : l'écran Solveur, la confirmation, l'attente (accélérée), le résultat, le planning. */
async function filmCalcul(page, { accelerer, lang, etat }) {
  await ouvrir(page, '/solveur');
  await pause(3500);
  await page.getByRole('button', { name: L.recommencer }).click();
  await pause(1800);
  await page.getByRole('dialog').getByRole('button', { name: L.recommencer }).click();
  await page.getByText(L.faisable).first().waitFor({ timeout: 120000 });
  await pause(3000);
  // Le reste du calcul se passe sans rien à montrer : sa durée varie, le montage la ramène à 8 s
  await accelerer(8, async () => {
    for (let essai = 0; essai < 240; essai++) {
      const actif = await etat.api.get('/api/jobs/active', { headers: etat.h });
      if (!actif.ok() || (await actif.text()).trim() === '' || actif.status() === 204) break;
      await pause(1000);
    }
  });
  await pause(3500);
  await ouvrir(page, '/journee');
  await pause(3500);
}

/** Une absence le jour J : recherche, absence, place vide, remplaçant, prévenir. */
async function filmJourJ(page, { lang, etat }) {
  const absent = await absentDuParcours(etat);
  await ouvrir(page, '/aujourdhui');
  await pause(2200);
  const recherche = page.getByLabel(L.chercher).first();
  await recherche.click();
  await taper(recherche, absent);
  await pause(1800);
  await page.getByRole('button', { name: L.absent }).first().click();
  await pause(1200);
  await taper(page.getByLabel(L.raison), L.malade[lang]);
  await pause(1200);
  await page.getByRole('button', { name: L.confirmer }).click();
  await pause(3200);
  await page.getByRole('button', { name: L.remplacer }).click();
  await pause(2500);
  await page.getByRole('button', { name: L.affecter }).first().click();
  await pause(4500);
}

/** Le téléphone d'une animateur : le code reçu par e-mail, son planning, ses coéquipiers, ses échanges. */
async function filmEspace(page, { etat }) {
  const animateur = await animateurDeLEspace(etat);
  await page.goto(`${BASE}/animateur/${animateur.accessToken}`);
  await pause(2000);
  const avant = await nombreDeMails(etat, animateur.email);
  await page.getByRole('button', { name: L.recevoirCode }).click();
  const code = await codeMailpit(etat, animateur.email, avant);
  const champ = page.locator('input:visible').first();
  await champ.click();
  await pause(800);
  await taper(champ, code);
  await pause(900);
  await page.getByRole('button', { name: L.ouvrirEspace }).click();
  await pause(3000);
  await page.getByRole('button', { name: L.jaiLu }).click();
  await pause(2500);
  await page.locator('mat-button-toggle').filter({ hasText: L.apercu }).locator('button').click();
  await pause(3000);
  await page.locator('mat-button-toggle').filter({ hasText: L.coequipiers }).locator('button').click();
  await pause(3000);
  await page.locator('a, button').filter({ hasText: L.echanges }).first().click();
  await pause(4000);
}

/* ---------------------------------- main --------------------------------- */

async function main() {
  const browser = await chromium.launch({
    ...(process.env.CHROMIUM ? { executablePath: process.env.CHROMIUM } : {}),
  });
  try {
    for (const lang of LANGS) {
      console.log(`== ${lang}`);
      // Chaque morceau repart d'un état neuf : une absence, une présence confirmée ou un calcul
      // ne doivent pas se voir dans le film suivant
      const morceaux = {
        ecrans: (etat) => ecrans(browser, etat, lang),
        calcul: (etat) => filmer(browser, etat, lang, 'calcul', FILM, (page, outils) => filmCalcul(page, { ...outils, lang, etat })),
        jourj: (etat) => filmer(browser, etat, lang, 'jourj', FILM, (page, outils) => filmJourJ(page, { ...outils, lang, etat })),
        espace: (etat) => filmer(browser, etat, lang, 'espace', TELEPHONE, (page, outils) => filmEspace(page, { ...outils, lang, etat })),
      };
      for (const [nom, jouer] of Object.entries(morceaux)) {
        if (ONLY && ONLY !== nom) continue;
        const etat = await preparer();
        await jouer(etat);
        await etat.api.dispose();
      }
    }
  } finally {
    await browser.close();
  }
}

await main();
