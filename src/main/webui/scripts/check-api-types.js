#!/usr/bin/env node
'use strict';

/*
 * Fails when `models.ts` et le contrat OpenAPI ne décrivent plus les mêmes
 * charges utiles.
 *
 * `models.ts` est écrit à la main et personne ne le confronte au serveur. Six
 * types avaient dérivé sans que rien ne le signale — le serveur envoyait des
 * champs que le front ne déclarait pas (`Animateur.ninja`,
 * `PlanningEvenement.parametresLegaux`, `JobView.message`…). Rien ne casse à
 * l'exécution : JSON.parse ignore ce qu'on ne lui demande pas. Le front est
 * simplement aveugle à une partie de ce qu'il reçoit, et le reste devient
 * faux au premier renommage côté serveur.
 *
 * La référence est `docs/schema/openapi.json`, déposé par le build Maven
 * (`quarkus.smallrye-openapi.store-schema-directory`) : c'est l'OpenAPI publié
 * par l'application, donc ce qu'elle sert réellement, et non une description
 * tenue à jour à la main.
 *
 * CE QUE CE SCRIPT NE VÉRIFIE PAS, et pourquoi : l'optionalité. Le schéma ne
 * porte aucun `required` — 0 propriété sur 944 —, SmallRye ne le déduisant que
 * de `@NotNull` ou `@Schema(required = true)`, que les records de l'API ne
 * portent pas. Comparer les optionalités ferait donc échouer les 94 types déjà
 * corrects, et *générer* les types depuis ce schéma les rendrait tous
 * optionnels — strictement plus faible que ce que le front déclare
 * aujourd'hui, et en collision avec `strict`. C'est pourquoi on vérifie ici au
 * lieu de générer. Annoter les records côté serveur est le préalable à la
 * génération, et un chantier à part entière.
 */
const { readFileSync, existsSync } = require('node:fs');
const { join } = require('node:path');

const RACINE = join(__dirname, '..');
const SCHEMA = join(RACINE, '../../../docs/schema/openapi.json');
const SCHEMA_FRAIS = join(RACINE, '../../../target/openapi/openapi.json');
const MODELS = join(RACINE, 'src/app/core/models.ts');
const MAPPING = join(__dirname, 'api-types-mapping.json');

const ts = require(join(RACINE, 'node_modules/typescript/lib/typescript.js'));

/** Les interfaces de `models.ts` et le nom de leurs propriétés. */
function interfacesDuFront() {
  const source = readFileSync(MODELS, 'utf8');
  const fichier = ts.createSourceFile('models.ts', source, ts.ScriptTarget.ES2022, true);
  const interfaces = new Map();
  fichier.forEachChild((noeud) => {
    if (!ts.isInterfaceDeclaration(noeud)) {
      return;
    }
    const proprietes = noeud.members
      .filter(ts.isPropertySignature)
      .map((membre) => membre.name.getText())
      .sort();
    interfaces.set(noeud.name.text, proprietes);
  });
  return interfaces;
}

/** Les schémas objets du contrat, et le nom de leurs propriétés. */
function schemasDuContrat(chemin) {
  const contrat = JSON.parse(readFileSync(chemin, 'utf8'));
  const schemas = new Map();
  for (const [nom, definition] of Object.entries(contrat.components?.schemas ?? {})) {
    if (definition.properties) {
      schemas.set(nom, Object.keys(definition.properties).sort());
    }
  }
  return schemas;
}

const ecarts = [];
const front = interfacesDuFront();
const contrat = schemasDuContrat(SCHEMA);
const { renommes, horsContrat } = JSON.parse(readFileSync(MAPPING, 'utf8'));

// Le contrat commité est-il encore celui que le build produit ? Sans ce
// contrôle, une API modifiée sans régénérer le schéma passerait inaperçue —
// et la vérification ci-dessous porterait sur une photo périmée.
if (existsSync(SCHEMA_FRAIS)) {
  const frais = schemasDuContrat(SCHEMA_FRAIS);
  const apparus = [...frais.keys()].filter((nom) => !contrat.has(nom));
  const disparus = [...contrat.keys()].filter((nom) => !frais.has(nom));
  const modifies = [...frais.keys()].filter(
    (nom) => contrat.has(nom) && contrat.get(nom).join() !== frais.get(nom).join()
  );
  if (apparus.length + disparus.length + modifies.length > 0) {
    ecarts.push(
      `docs/schema/openapi.json est périmé : ${apparus.length} schéma(s) apparu(s), ` +
        `${disparus.length} disparu(s), ${modifies.length} modifié(s). ` +
        'Régénérez-le avec `npm run api-schema`.'
    );
  }
}

for (const [nom, proprietesFront] of front) {
  if (Object.hasOwn(horsContrat, nom)) {
    if (contrat.has(nom)) {
      ecarts.push(
        `${nom} est déclaré hors contrat, mais le schéma porte désormais ce nom. ` +
          'Retirez-le de « horsContrat » dans scripts/api-types-mapping.json.'
      );
    }
    continue;
  }

  const nomServeur = renommes[nom] ?? nom;
  if (!contrat.has(nomServeur)) {
    ecarts.push(
      renommes[nom]
        ? `${nom} pointe « ${nomServeur} », qui n'est plus dans le contrat.`
        : `${nom} n'a pas de schéma. Rattachez-le dans « renommes », ou dites pourquoi dans « horsContrat ».`
    );
    continue;
  }

  const proprietesServeur = contrat.get(nomServeur);
  const manquantes = proprietesServeur.filter((p) => !proprietesFront.includes(p));
  const enTrop = proprietesFront.filter((p) => !proprietesServeur.includes(p));
  if (manquantes.length > 0 || enTrop.length > 0) {
    const details = [];
    if (manquantes.length > 0) {
      details.push(`envoyé(s) par le serveur, absent(s) du front : ${manquantes.join(', ')}`);
    }
    if (enTrop.length > 0) {
      details.push(`déclaré(s) par le front, absent(s) du serveur : ${enTrop.join(', ')}`);
    }
    ecarts.push(`${nom} → ${nomServeur} — ${details.join(' ; ')}`);
  }
}

// Une entrée de correspondance qui ne sert plus est un piège : elle se lit
// comme une décision alors que le type a disparu.
for (const nom of [...Object.keys(renommes), ...Object.keys(horsContrat)]) {
  if (!front.has(nom)) {
    ecarts.push(`${nom} figure dans scripts/api-types-mapping.json mais n'existe plus dans models.ts.`);
  }
}

if (ecarts.length > 0) {
  console.error(`check-api-types : ${ecarts.length} écart(s) entre models.ts et le contrat OpenAPI.\n`);
  ecarts.forEach((ecart) => console.error(`  - ${ecart}`));
  console.error(
    "\nLe contrat fait foi (audit #392, question 12). Un champ que le serveur envoie et\n" +
      "que le front ne déclare pas n'échoue nulle part : il est simplement invisible.\n"
  );
  process.exit(1);
}

const verifies = front.size - Object.keys(horsContrat).length;
console.log(
  `check-api-types : ${verifies} type(s) confrontés au contrat, ` +
    `${Object.keys(horsContrat).length} hors contrat avec leur raison, 0 écart.`
);
