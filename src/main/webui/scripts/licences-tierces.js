#!/usr/bin/env node
'use strict';

/*
 * Generates (or checks) `docs/licences-tierces.md`, the inventory of the
 * licences of everything this application redistributes.
 *
 * The application is under the AGPL, and that part is covered by LICENSE and
 * NOTICE. But the image ships two closures the AGPL says nothing about: the
 * jars bundled in `target/quarkus-app/` and the npm packages compiled into the
 * Angular bundle. Their MIT, BSD and Apache notices have to travel with the
 * binary, and a third party deploying the image asks that question first —
 * what is in it, and under what? Nothing in the repository answered (audit
 * #392, D9): `pom.xml` declares the licence of the project, and no file listed
 * those of its dependencies.
 *
 * Two sources, one writer:
 *   - Java: `target/licenses/THIRD-PARTY.txt`, produced by
 *     `./mvnw license:add-third-party` (scopes compile + runtime, licence
 *     names merged to SPDX identifiers — see the plugin's configuration in
 *     pom.xml);
 *   - npm: `package-lock.json` alone. Every entry of a v3 lockfile carries its
 *     `license` field, so the inventory needs neither `node_modules` nor a new
 *     build dependency — it runs on a bare checkout.
 *
 * The output carries no generation date on purpose: it would change on every
 * run, and the `--check` mode below — which is what keeps the committed file
 * honest — would then fail on the clock rather than on the content.
 */
const { readFileSync, writeFileSync } = require('node:fs');
const { join } = require('node:path');

const ROOT = join(__dirname, '..', '..', '..', '..');
const MAVEN_REPORT = join(ROOT, 'target', 'licenses', 'THIRD-PARTY.txt');
const LOCKFILE = join(ROOT, 'src', 'main', 'webui', 'package-lock.json');
const OUTPUT = join(ROOT, 'docs', 'licences-tierces.md');

/**
 * `     (EPL-2.0) (GPL-2.0-with-classpath-exception) Jakarta RESTful WS API
 *      (jakarta.ws.rs:jakarta.ws.rs-api:3.1.0 - https://github.com/…)`
 *
 * The leading `(…)` groups are the licences, the trailing one is the GAV and
 * the project URL. A licence name holding a parenthesis of its own would break
 * this, which is why the plugin merges the one that did (`GNU Lesser General
 * Public License (LGPL), Version 2.1`) to `LGPL-2.1`: a new one shows up as a
 * parse failure below, not as a silently mangled line.
 */
const MAVEN_LINE =
  /^\s*((?:\([^()]*\)\s*)+)(.+?)\s+\(([^\s:]+):([^\s:]+):([^\s:)]+)(?:\s+-\s+([^)]*))?\)\s*$/;

/** Byte order, not `localeCompare`: the file must not depend on the ICU data of whoever regenerates it. */
function byId(a, b) {
  if (a.id === b.id) return 0;
  return a.id < b.id ? -1 : 1;
}

function fail(message) {
  console.error(`licences-tierces : ${message}`);
  process.exit(1);
}

function readJavaDependencies() {
  let report;
  try {
    report = readFileSync(MAVEN_REPORT, 'utf8');
  } catch {
    fail(
      `${MAVEN_REPORT} est introuvable.\n` +
        "Régénérez-le depuis la racine : ./mvnw license:add-third-party — c'est lui qui\n" +
        'résout les dépendances Java, ce script ne fait que les mettre en forme.',
    );
  }

  const dependencies = [];
  for (const line of report.split('\n')) {
    // The first line announces the total; blank lines separate nothing.
    if (line.trim() === '' || line.startsWith('Lists of ')) continue;

    const match = MAVEN_LINE.exec(line);
    if (!match) {
      fail(
        `ligne illisible dans ${MAVEN_REPORT} :\n  ${line.trim()}\n` +
          'Une licence portant une parenthèse dans son nom ? Ajoutez-la aux <licenseMerges>\n' +
          'du license-maven-plugin (pom.xml) pour la ramener à son identifiant SPDX.',
      );
    }

    const [, licenceBlock, name, group, artifact, version] = match;
    const licences = [...licenceBlock.matchAll(/\(([^()]*)\)/g)].map((each) => each[1].trim());
    dependencies.push({ id: `${group}:${artifact}`, name, version, licences });
  }

  if (dependencies.length === 0) {
    fail(`${MAVEN_REPORT} ne liste aucune dépendance — le rapport est-il tronqué ?`);
  }
  return dependencies.sort(byId);
}

function readNpmDependencies() {
  let lock;
  try {
    lock = JSON.parse(readFileSync(LOCKFILE, 'utf8'));
  } catch (error) {
    fail(`${LOCKFILE} est illisible : ${error.message}`);
  }
  if (lock.lockfileVersion < 3) {
    fail(
      `lockfileVersion ${lock.lockfileVersion} : ce script lit le champ "license" des\n` +
        'entrées "packages", que seuls les verrous v3 et suivants portent.',
    );
  }

  const byKey = new Map();
  for (const [path, entry] of Object.entries(lock.packages ?? {})) {
    // The root (empty key) is the project itself; a link is a local package.
    if (!path.startsWith('node_modules/') || entry.link) continue;
    // `dev` marks what is only reachable through devDependencies: none of it
    // ends up in the bundle. Everything else is kept, `optional` and
    // `devOptional` included — overstating the inventory costs a line,
    // understating it costs a missing notice.
    if (entry.dev) continue;

    const name = path.slice(path.lastIndexOf('node_modules/') + 'node_modules/'.length);
    if (typeof entry.license !== 'string' || entry.license.trim() === '') {
      fail(
        `${name}@${entry.version} ne déclare aucune licence exploitable dans le verrou.\n` +
          'Vérifiez le paquet à la main : une dépendance sans licence connue est une\n' +
          'question à trancher, pas une ligne à publier.',
      );
    }
    byKey.set(`${name}@${entry.version}`, {
      id: `${name}@${entry.version}`,
      name,
      version: entry.version,
      licences: [entry.license.trim()],
    });
  }

  if (byKey.size === 0) {
    fail(`${LOCKFILE} ne liste aucune dépendance de production.`);
  }
  return [...byKey.values()].sort(byId);
}

/** `{ licence -> count }`, a dual-licensed component counting in each of the two. */
function countByLicence(dependencies) {
  const counts = new Map();
  for (const dependency of dependencies) {
    for (const licence of dependency.licences) {
      counts.set(licence, (counts.get(licence) ?? 0) + 1);
    }
  }
  return counts;
}

function summaryTable(java, npm) {
  const javaCounts = countByLicence(java);
  const npmCounts = countByLicence(npm);
  const licences = [...new Set([...javaCounts.keys(), ...npmCounts.keys()])].sort();

  const rows = licences.map((licence) => {
    const cell = (count) => (count ? String(count) : '—');
    return `| ${licence} | ${cell(javaCounts.get(licence))} | ${cell(npmCounts.get(licence))} |`;
  });

  return [
    '| Licence | Dépendances Java | Paquets npm |',
    '| --- | ---: | ---: |',
    ...rows,
    `| **Total** | **${java.length}** | **${npm.length}** |`,
  ].join('\n');
}

function javaTable(dependencies) {
  return [
    '| Dépendance | Version | Licence |',
    '| --- | --- | --- |',
    ...dependencies.map((d) => `| \`${d.id}\` | ${d.version} | ${d.licences.join(' ou ')} |`),
  ].join('\n');
}

function npmTable(dependencies) {
  return [
    '| Paquet | Version | Licence |',
    '| --- | --- | --- |',
    ...dependencies.map((d) => `| \`${d.name}\` | ${d.version} | ${d.licences.join(' ou ')} |`),
  ].join('\n');
}

function buildDocument(java, npm) {
  return `# Licences des dépendances tierces

**Fichier généré — ne pas le modifier à la main.** Il se régénère en deux
commandes, depuis la racine puis depuis \`src/main/webui\` :

\`\`\`bash
./mvnw license:add-third-party
npm run licences
\`\`\`

Le job \`test\` du workflow *Tests* refait les deux et refuse la branche si le
fichier commité a pris du retard.

## Pourquoi ce fichier

L'application est distribuée sous AGPL-3.0-only — c'est l'objet de
[\`LICENSE\`](../LICENSE) et de [\`NOTICE\`](../NOTICE). Mais l'image publiée
redistribue aussi les bibliothèques ci-dessous, et leurs licences MIT, BSD ou
Apache demandent que leur notice voyage avec le binaire. Ce fichier est la
réponse à la première question que pose un tiers qui déploie l'image : qu'est-ce
qu'il y a dedans, et sous quoi ?

Ce qu'il ne couvre **pas** : les paquets du système de base de l'image (JRE
Temurin, client PostgreSQL, Ubuntu). Ceux-là sont inventoriés par le SBOM
CycloneDX que \`docker-ghcr.yml\` produit et attache à chaque image publiée —
voir [\`developpement.md\`](developpement.md).

## Résumé

Un composant sous double licence compte dans chacune des deux.

${summaryTable(java, npm)}

## Dépendances Java

Fermeture transitive des scopes \`compile\` et \`runtime\` : ce que l'image
embarque dans \`target/quarkus-app/\`. Les scopes \`test\` et \`provided\` en sont
exclus — rien de ce qu'ils apportent n'est distribué.

${javaTable(java)}

## Paquets npm

Fermeture des dépendances de production de \`src/main/webui\`, pairs compris.
C'est un **surensemble** de ce que le bundle Angular embarque réellement : un
paquet qui ne sert qu'à la compilation mais qu'une dépendance de production
entraîne (le compilateur Angular, par exemple) est listé ici alors que rien de
lui ne part dans le bundle. Surestimer ne coûte qu'une ligne ; sous-estimer
coûterait une notice manquante.

${npmTable(npm)}
`;
}

const java = readJavaDependencies();
const npm = readNpmDependencies();
const document = buildDocument(java, npm);

if (process.argv.includes('--check')) {
  let committed;
  try {
    committed = readFileSync(OUTPUT, 'utf8');
  } catch {
    fail(`${OUTPUT} est introuvable — régénérez-le avec \`npm run licences\`.`);
  }
  if (committed !== document) {
    const expected = document.split('\n');
    const actual = committed.split('\n');
    const rank = expected.findIndex((line, index) => line !== actual[index]);
    fail(
      'docs/licences-tierces.md ne correspond plus aux dépendances du projet.\n' +
        `Première divergence, ligne ${rank + 1} :\n` +
        `  commité : ${actual[rank] ?? '(fin du fichier)'}\n` +
        `  attendu : ${expected[rank] ?? '(fin du fichier)'}\n` +
        'Régénérez-le : ./mvnw license:add-third-party puis, ici, npm run licences.',
    );
  }
  console.log(
    `licences-tierces : inventaire à jour (${java.length} dépendances Java, ${npm.length} paquets npm).`,
  );
} else {
  writeFileSync(OUTPUT, document);
  console.log(
    `licences-tierces : ${OUTPUT} écrit (${java.length} dépendances Java, ${npm.length} paquets npm).`,
  );
}
