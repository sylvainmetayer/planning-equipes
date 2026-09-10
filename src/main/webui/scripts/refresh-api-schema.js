#!/usr/bin/env node
'use strict';

/*
 * Recopie le contrat OpenAPI que le build Maven vient de déposer vers
 * `docs/schema/openapi.json`, la version commitée.
 *
 * Pourquoi le commiter plutôt que le lire dans `target/` : parce qu'il est le
 * contrat. Commité, une modification d'API se voit dans le diff d'une PR, à la
 * revue, au lieu d'apparaître le jour où un écran se tait. C'est la même
 * raison qui fait vivre `src/test/resources/json-contract.txt` dans le dépôt.
 *
 * Il vient de `quarkus.smallrye-openapi.store-schema-directory`, donc de
 * l'application elle-même : rien n'est décrit à la main, et `npm run
 * api-types-check` refuse de travailler sur une photo périmée.
 *
 *   ./mvnw package -DskipTests   puis   npm run api-schema
 */
const { copyFileSync, existsSync, readFileSync } = require('node:fs');
const { join } = require('node:path');

const RACINE = join(__dirname, '..');
const FRAIS = join(RACINE, '../../../target/openapi/openapi.json');
const COMMITE = join(RACINE, '../../../docs/schema/openapi.json');

if (!existsSync(FRAIS)) {
  console.error(
    `api-schema : ${FRAIS} est absent.\n` +
      "Le contrat est déposé par le build Maven — lancez `./mvnw package -DskipTests` d'abord.\n",
  );
  process.exit(1);
}

const avant = existsSync(COMMITE) ? readFileSync(COMMITE, 'utf8') : '';
copyFileSync(FRAIS, COMMITE);
const apres = readFileSync(COMMITE, 'utf8');

if (avant === apres) {
  console.log('api-schema : le contrat commité était déjà à jour.');
} else {
  const schemas = Object.keys(JSON.parse(apres).components?.schemas ?? {}).length;
  console.log(
    `api-schema : docs/schema/openapi.json mis à jour (${schemas} schémas). ` +
      "Relisez le diff : il dit ce que l'API a changé.",
  );
}
