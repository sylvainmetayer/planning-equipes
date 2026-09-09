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
 * Les fichiers TypeScript sont lus par le compilateur, pas par une expression
 * régulière : un scan textuel prend `type de cible` dans un titre de test
 * français pour une déclaration nommée « de ». La première version de ce
 * script le faisait, et sur-comptait.
 *
 * <h2>Le frontend est tenu plus strict que le backend, et c'est délibéré</h2>
 *
 * `LanguagePolicyStructuralTest` ne regarde que les **types, méthodes et
 * fonctions** : ses motifs ne voient ni les champs de classe ni les variables.
 * Ce n'est pas un oubli. Un champ Java est sérialisé par Jackson : il *est* la
 * clé JSON, comme un accesseur, et son javadoc le dit — renommer
 * `DemandeEchange.cibleId` renommerait le contrat, ce qui reste hors périmètre
 * tant qu'aucune couche `@JsonProperty` ne découple les deux.
 *
 * Ici, rien de tel : les noms internes du frontend ne sont sérialisés nulle
 * part — les formes échangées vivent dans `models.ts`, dont ce contrôle exclut
 * les propriétés d'interface. Le frontend peut donc se tenir à la règle plus
 * exigeante sans rien casser, et c'est ce qu'il fait : propriétés de classe et
 * variables comprises, soit 222 noms de plus que la règle du backend.
 *
 * Mesuré avant de trancher : durcir le backend au même niveau y trouverait 381
 * variables et 51 champs, dont 35 dans `domain/` et `api/` — les clés du fil,
 * précisément celles qu'on ne peut pas toucher. L'asymétrie est donc le seul
 * choix qui donne une règle applicable des deux côtés.
 *
 * Ce qu'il ne regarde pas encore, et qu'il faudra ajouter avant de le rendre
 * bloquant : les 68 gabarits `.html`, `material-theme.scss`, `e2e/` et
 * `scripts/` — dont ce fichier, écrit en français comme ses voisins. Étendre le
 * périmètre agrandira l'inventaire ; autant le savoir avant de le croire fini.
 *
 * MODE INVENTAIRE : `--inventaire` liste ce qui reste sans faire échouer.
 * Le chantier de renommage est en cours (voir la description de la PR qui
 * introduit ce script) ; tant qu'il n'est pas fini, faire échouer bloquerait
 * son propre correctif.
 */
const { readFileSync, readdirSync } = require('node:fs');
const { join, relative } = require('node:path');

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

const ts = require(join(RACINE, 'node_modules/typescript/lib/typescript.js'));

/** Les noms français explicitement excusés, avec leur raison. */
const { exceptions: EXCEPTIONS } = JSON.parse(
  readFileSync(join(__dirname, 'language-policy-exceptions.json'), 'utf8')
);

const GLOSSAIRE = vocabulaire('GLOSSAIRE');
const LEXIQUE = vocabulaire('LEXIQUE_FR');
const OUTILS_FR = vocabulaire('OUTILS_FR');
const OUTILS_EN = vocabulaire('OUTILS_EN');

const sansAccents = (texte) => texte.normalize('NFD').replace(/[\u0300-\u036f]/g, '').toLowerCase();

/**
 * La prose d'un commentaire : ce que le test Java retire avant de compter —
 * étoiles de bordure, balises `{@link …}`, `@param`, balises HTML, et les
 * citations entre guillemets. Un commentaire anglais qui cite un libellé
 * français (« Légal (…) ») ne doit pas passer pour français.
 */
const prose = (brut) =>
  brut
    .replace(/^\s*\*/gm, ' ')
    .replace(/\{@\w+\s+[^}]*\}/g, ' ')
    .replace(/@\w+/g, ' ')
    .replace(/<[^>]*>/g, ' ')
    .replace(/«[^»]*»/g, ' ');

/**
 * Français ou anglais, à la majorité des mots-outils — la règle du test Java,
 * et non le lexique des noms déclarés.
 *
 * <p>Compter les mots de `LEXIQUE_FR` était faux dans les deux sens : il
 * contient `du`, `travail`, `dure`… qui apparaissent dans des citations
 * légales au milieu d'une phrase anglaise, et il ignore les mots-outils qui
 * font vraiment une phrase française. Mesuré sur l'arbre : 53 commentaires
 * anglais signalés à tort, 58 français manqués.</p>
 */
function estFrancais(texte) {
  let fr = 0;
  let en = 0;
  for (const brut of prose(texte).toLowerCase().match(/[\p{L}']+/gu) ?? []) {
    const mot = brut.replace(/'/g, '');
    if (OUTILS_FR.has(mot)) fr++;
    if (OUTILS_EN.has(mot)) en++;
    if (/[\u00e0-\u00ff]/.test(mot)) fr++;
  }
  return fr > en;
}

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

/**
 * Les mots d'un nom déclaré, découpé sur la casse **puis** mis en minuscules.
 *
 * <p>L'inverse — ce que faisait la première version — rend le découpage sur
 * la casse inopérant, puisqu'il ne reste plus une seule majuscule. Tout nom
 * composé passait alors entre les mailles : `texteCible`, `CibleImport`,
 * `jetonAcces` comptaient pour un seul mot, introuvable dans le lexique. Le
 * chantier paraissait trois fois plus petit qu'il n'est.</p>
 *
 * <p>Le motif est celui du test Java (`TOKEN`), pour que les deux moitiés du
 * dépôt découpent les noms de la même façon.</p>
 */
const motsDuNom = (nom) => (nom.match(/[A-Z]?[a-z\u00e0-\u00ff]+|[A-Z]+(?![a-z])|\d+/g) ?? []).map(sansAccents);

const commentairesFrancais = [];
const nomsFautifs = [];

for (const fichier of fichiers(SOURCES)) {
  const source = readFileSync(fichier, 'utf8');
  const nom = relative(DEPOT, fichier);

  const ligneDe = (position) => source.slice(0, position).split('\n').length;

  if (fichier.endsWith('.css')) {
    for (const bloc of source.matchAll(/\/\*[\s\S]*?\*\//g)) {
      if (estFrancais(bloc[0])) {
        commentairesFrancais.push(`${nom}:${ligneDe(bloc.index)}`);
      }
    }
    continue;
  }

  const fichierTs = ts.createSourceFile(nom, source, ts.ScriptTarget.ES2022, true);

  // Les commentaires sont pris comme trivia du compilateur — un « // » dans une
  // URL n'en est pas un — en relevant aussi bien ceux qui précèdent un nœud que
  // ceux qui le suivent : les derniers d'un bloc n'en précèdent aucun, et
  // échappaient entièrement à la première version.
  const plages = new Map();
  const relever = (noeud) => {
    for (const plage of [
      ...(ts.getLeadingCommentRanges(source, noeud.getFullStart()) ?? []),
      ...(ts.getTrailingCommentRanges(source, noeud.getEnd()) ?? [])
    ]) {
      plages.set(plage.pos, plage);
    }
    ts.forEachChild(noeud, relever);
  };
  relever(fichierTs);

  // Les lignes `//` qui se suivent forment UN bloc, comme dans le test Java :
  // les compter séparément gonfle l'inventaire et juge chaque ligne isolément,
  // là où c'est le paragraphe qui a une langue.
  const triees = [...plages.values()].sort((a, b) => a.pos - b.pos);
  const blocs = [];
  for (const plage of triees) {
    const precedent = blocs.at(-1);
    const contigue =
      precedent &&
      plage.kind === ts.SyntaxKind.SingleLineCommentTrivia &&
      precedent.kind === ts.SyntaxKind.SingleLineCommentTrivia &&
      source.slice(precedent.end, plage.pos).trim() === '';
    if (contigue) {
      precedent.end = plage.end;
    } else {
      blocs.push({ pos: plage.pos, end: plage.end, kind: plage.kind });
    }
  }
  for (const bloc of blocs) {
    if (estFrancais(source.slice(bloc.pos, bloc.end))) {
      commentairesFrancais.push(`${nom}:${ligneDe(bloc.pos)}`);
    }
  }

  // Et les noms déclarés comme le compilateur les voit, jamais comme un motif
  // textuel les devine.
  const declarations = (noeud) => {
    const declare =
      ts.isVariableDeclaration(noeud) ||
      ts.isFunctionDeclaration(noeud) ||
      ts.isClassDeclaration(noeud) ||
      ts.isInterfaceDeclaration(noeud) ||
      ts.isTypeAliasDeclaration(noeud) ||
      ts.isEnumDeclaration(noeud) ||
      ts.isMethodDeclaration(noeud) ||
      ts.isPropertyDeclaration(noeud);
    if (declare && noeud.name && ts.isIdentifier(noeud.name) && !Object.hasOwn(EXCEPTIONS, noeud.name.text)) {
      const fautifs = motsDuNom(noeud.name.text).filter((mot) => LEXIQUE.has(mot) && !GLOSSAIRE.has(mot));
      if (fautifs.length > 0) {
        nomsFautifs.push(`${nom} : ${noeud.name.text} [${fautifs.join(', ')}]`);
      }
    }
    ts.forEachChild(noeud, declarations);
  };
  declarations(fichierTs);
}

const inventaire = process.argv.includes('--inventaire');

const nomsVus = new Set();
for (const fichier of fichiers(SOURCES)) {
  if (fichier.endsWith('.ts')) {
    const sf = ts.createSourceFile(fichier, readFileSync(fichier, 'utf8'), ts.ScriptTarget.ES2022, true);
    const noter = (noeud) => {
      if (noeud.name && ts.isIdentifier(noeud.name)) nomsVus.add(noeud.name.text);
      ts.forEachChild(noeud, noter);
    };
    noter(sf);
  }
}
const exceptionsMortes = Object.keys(EXCEPTIONS).filter((nom) => !nomsVus.has(nom));
const exceptionsSansRaison = Object.entries(EXCEPTIONS)
  .filter(([, raison]) => !raison || raison.trim().length < 20)
  .map(([nom]) => nom);

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

for (const nom of exceptionsMortes) {
  nomsFautifs.push(`${nom} est excusé dans language-policy-exceptions.json mais n'existe plus`);
}
for (const nom of exceptionsSansRaison) {
  nomsFautifs.push(`${nom} est excusé sans raison écrite — une exception sans motif se lit comme un oubli`);
}

const total = commentairesFrancais.length + nomsFautifs.length;

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
