#!/usr/bin/env node
'use strict';

/*
 * Étend au frontend la politique linguistique que `LanguagePolicyStructuralTest`
 * fait respecter côté Java : la prose du dépôt est en anglais, seul le
 * vocabulaire métier du glossaire reste français.
 *
 * Le backend a ce garde-fou depuis longtemps, le frontend n'en avait aucun —
 * d'où deux moitiés de dépôt jugées par des règles différentes, dont une non
 * écrite. C'est l'incohérence que ce script supprime (audit #392, question 14).
 *
 * LE GLOSSAIRE ET LE LEXIQUE SONT LUS DANS LE TEST JAVA, pas recopiés. Une
 * copie aurait dérivé, et deux règles qui divergent valent moins qu'une seule :
 * un mot ajouté au glossaire pour le backend vaut immédiatement ici.
 *
 * Ce qu'il ne regarde pas, et c'est délibéré : les chaînes de caractères.
 * L'application s'adresse à des bénévoles francophones, ses libellés sont en
 * français et le resteront — `$localize` s'occupe de l'anglais. Seuls les
 * commentaires et les noms déclarés sont concernés.
 *
 * MODE INVENTAIRE : `--inventaire` liste ce qui reste sans faire échouer.
 * Le chantier de renommage est en cours (voir la description de la PR qui
 * introduit ce script) ; tant qu'il n'est pas fini, faire échouer bloquerait
 * son propre correctif.
 */
const { readFileSync, readdirSync } = require('node:fs');
const { join, relative, basename } = require('node:path');

const RACINE = join(__dirname, '..');
const DEPOT = join(RACINE, '../../..');
const TEST_JAVA = join(DEPOT, 'src/test/java/dev/sylvain/planning/LanguagePolicyStructuralTest.java');
const SOURCES = join(RACINE, 'src');

/** Un bloc `mots("""…""")` du test Java, lu comme la source de vérité. */
function vocabulaire(nom) {
  const java = readFileSync(TEST_JAVA, 'utf8');
  const debut = java.indexOf(`${nom} = mots("""`);
  if (debut < 0) {
    console.error(
      `check-language-policy : le bloc ${nom} est introuvable dans ${relative(DEPOT, TEST_JAVA)}.\n` +
        "Le glossaire y est lu plutôt que recopié : si sa forme a changé, c'est ici qu'il faut suivre.\n"
    );
    process.exit(1);
  }
  const corps = java.slice(java.indexOf('\n', debut) + 1, java.indexOf('""")', debut));
  return new Set(corps.split(/\s+/).filter(Boolean));
}

const GLOSSAIRE = vocabulaire('GLOSSAIRE');
const LEXIQUE = vocabulaire('LEXIQUE_FR');

/** Deux mots du lexique dans un même bloc : une phrase, pas un terme métier isolé. */
const SEUIL_PHRASE = 2;

const sansAccents = (texte) => texte.normalize('NFD').replace(/[̀-ͯ]/g, '').toLowerCase();

function fichiers(dossier) {
  const trouves = [];
  for (const entree of readdirSync(dossier, { withFileTypes: true })) {
    const chemin = join(dossier, entree.name);
    if (entree.isDirectory()) {
      trouves.push(...fichiers(chemin));
    } else if (/\.(ts|css)$/.test(entree.name)) {
      trouves.push(chemin);
    }
  }
  return trouves;
}

/** Les mots d'un nom déclaré, découpé sur la casse et les séparateurs. */
const motsDuNom = (nom) =>
  sansAccents(nom)
    .split(/(?=[A-Z])|[^a-zA-Z]+/)
    .map(sansAccents)
    .filter(Boolean);

const commentairesFrancais = [];
const nomsFautifs = [];

for (const fichier of fichiers(SOURCES)) {
  const source = readFileSync(fichier, 'utf8');
  const nom = relative(DEPOT, fichier);

  for (const bloc of source.matchAll(/(^[ \t]*\/\/.*(?:\n[ \t]*\/\/.*)*)|(\/\*[\s\S]*?\*\/)/gm)) {
    const mots = sansAccents(bloc[0]).split(/[^a-z]+/).filter(Boolean);
    if (mots.filter((mot) => LEXIQUE.has(mot)).length >= SEUIL_PHRASE) {
      const ligne = source.slice(0, bloc.index).split('\n').length;
      commentairesFrancais.push(`${nom}:${ligne}`);
    }
  }

  if (fichier.endsWith('.css')) {
    continue;
  }
  for (const declaration of source.matchAll(
    /(?:^|\s)(?:const|let|var|function|class|interface|type|enum)\s+([A-Za-z_$][\w$]*)/g
  )) {
    const fautifs = motsDuNom(declaration[1]).filter((mot) => LEXIQUE.has(mot) && !GLOSSAIRE.has(mot));
    if (fautifs.length > 0) {
      nomsFautifs.push(`${nom} : ${declaration[1]} [${fautifs.join(', ')}]`);
    }
  }
}

const inventaire = process.argv.includes('--inventaire');
const total = commentairesFrancais.length + nomsFautifs.length;

if (inventaire) {
  const parFichier = (liste) => new Set(liste.map((entree) => entree.split(/[: ]/)[0])).size;
  console.log(
    `check-language-policy — inventaire :\n` +
      `  ${commentairesFrancais.length} bloc(s) de commentaire en français, dans ${parFichier(commentairesFrancais)} fichier(s)\n` +
      `  ${nomsFautifs.length} nom(s) déclaré(s) sur un mot français hors glossaire, dans ${parFichier(nomsFautifs)} fichier(s)`
  );
  const mots = new Map();
  nomsFautifs.forEach((entree) => {
    entree
      .split('[')[1]
      .replace(']', '')
      .split(', ')
      .forEach((mot) => mots.set(mot, (mots.get(mot) ?? 0) + 1));
  });
  const classement = [...mots].sort((a, b) => b[1] - a[1]).slice(0, 10);
  console.log(`  mots les plus fréquents : ${classement.map(([m, n]) => `${m} (${n})`).join(', ')}`);
  process.exit(0);
}

if (total > 0) {
  console.error(`check-language-policy : ${total} écart(s) à la politique linguistique.\n`);
  commentairesFrancais.forEach((entree) => console.error(`  commentaire français — ${entree}`));
  nomsFautifs.forEach((entree) => console.error(`  nom français hors glossaire — ${entree}`));
  console.error(
    '\nLa prose du dépôt est en anglais ; seul le vocabulaire métier du glossaire reste\n' +
      'français. Les libellés affichés ne sont pas concernés : ils sont français, et le\n' +
      'restent. Glossaire et lexique se modifient dans LanguagePolicyStructuralTest.java.\n'
  );
  process.exit(1);
}

console.log('check-language-policy : commentaires en anglais, noms déclarés conformes au glossaire.');
