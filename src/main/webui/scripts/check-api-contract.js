#!/usr/bin/env node
'use strict';

/*
 * Fails when a service of `core/api/` calls an address the OpenAPI contract
 * does not publish — a path, a verb, or a query parameter the server does not
 * have.
 *
 * `core/api/` owns the URLs (issue #392, B3), and moving them there took the
 * one check they had with it: the page specs used to assert the exact path a
 * page called, and a service spec replaced them for three services out of
 * eleven. The other eight — 56 paths — were confronted to nothing. Renaming
 * `/api/pauses` server-side, or writing `appliquer=${!apply}` in the one
 * call where a wrong boolean rewrites every stand's schedule, left every
 * test green.
 *
 * The reference is `docs/schema/openapi.json`, what the application itself
 * publishes; `api-types-check` reads the same file for the payloads. Every
 * `this.api.<verb>(url, …)` of `core/api/*.ts` is read from the TypeScript
 * AST and its URL resolved statically: literals, template literals, a local
 * `const` (both branches of a ternary), a parameter typed as a union of
 * string literals (`'pdf' | 'ics'`), `new URLSearchParams({ a, b })`. Any
 * other expression in a path is a placeholder, matched to a `{param}` of the
 * contract. A URL the script cannot resolve is an error, not a pass: name the
 * segments in a type, or build the path from literals.
 *
 * The public links of the espace animateur (`espace-animateur-links.ts`) are
 * not calls — they are addresses a page shows — so they are checked by path
 * only, whatever the verb.
 *
 *   node scripts/check-api-contract.js
 */
const { readdirSync, readFileSync } = require('node:fs');
const { join, relative } = require('node:path');

const RACINE = join(__dirname, '..');
const SCHEMA = join(RACINE, '../../../docs/schema/openapi.json');
const API_DIR = join(RACINE, 'src/app/core/api');
const ts = require(join(RACINE, 'node_modules/typescript/lib/typescript.js'));

/** The methods of `ApiService`, and the HTTP verb each one sends. */
const VERBES = {
  get: 'get',
  getPreservingHttpError: 'get',
  getResponse: 'get',
  downloadGet: 'get',
  post: 'post',
  downloadPost: 'post',
  postRaw: 'post',
  put: 'put',
  delete: 'delete',
  patch: 'patch',
};

/* --------------------------- the contract -------------------------------- */

/** Path template (placeholders neutralised) → verb → the names of its query parameters. */
function operationsDuContrat() {
  const contrat = JSON.parse(readFileSync(SCHEMA, 'utf8'));
  const operations = new Map();
  for (const [chemin, item] of Object.entries(contrat.paths ?? {})) {
    const communs = item.parameters ?? [];
    const parVerbe = new Map();
    for (const [verbe, operation] of Object.entries(item)) {
      if (verbe === 'parameters') continue;
      const query = new Set(
        [...communs, ...(operation.parameters ?? [])]
          .filter((parametre) => parametre.in === 'query')
          .map((parametre) => parametre.name),
      );
      parVerbe.set(verbe, query);
    }
    operations.set(normalise(chemin), parVerbe);
  }
  return operations;
}

/** `{id}` and `{}` are the same placeholder; a trailing slash is not a route. */
function normalise(chemin) {
  const sansParams = neutralisePlaceholders(chemin).replace(/\/+/g, '/');
  return sansParams.length > 1 && sansParams.endsWith('/') ? sansParams.slice(0, -1) : sansParams;
}

/** `{anything}` → `{}`, in one linear pass; an unclosed `{` is kept as it is. */
function neutralisePlaceholders(chemin) {
  let result = '';
  let index = 0;
  while (index < chemin.length) {
    const open = chemin.indexOf('{', index);
    const close = open < 0 ? -1 : chemin.indexOf('}', open);
    if (close < 0) {
      return result + chemin.slice(index);
    }
    result += chemin.slice(index, open) + '{}';
    index = close + 1;
  }
  return result;
}

/* ------------------------- resolving a URL expression -------------------- */

/**
 * Every string an expression can evaluate to, with `{}` where a value the
 * script cannot know is interpolated. `null` when the expression cannot be
 * resolved at all — a variable from elsewhere, a call whose result is the
 * whole URL.
 */
function candidats(expression, portee) {
  if (ts.isParenthesizedExpression(expression)) {
    return candidats(expression.expression, portee);
  }
  if (ts.isStringLiteral(expression) || ts.isNoSubstitutionTemplateLiteral(expression)) {
    return [expression.text];
  }
  if (ts.isTemplateExpression(expression)) {
    return templateCandidates(expression, portee);
  }
  if (ts.isConditionalExpression(expression)) {
    const vrai = candidats(expression.whenTrue, portee);
    const faux = candidats(expression.whenFalse, portee);
    return vrai && faux ? [...vrai, ...faux] : null;
  }
  if (ts.isIdentifier(expression)) {
    return portee.get(expression.text) ?? null;
  }
  if (isUrlSearchParamsLiteral(expression)) {
    const cles = expression.arguments[0].properties.map((propriete) => propriete.name.getText());
    return [cles.map((cle) => `${cle}={}`).join('&')];
  }
  if (
    // encodeURIComponent(x), String(x)…: one value, unknown.
    ts.isCallExpression(expression) ||
    ts.isPropertyAccessExpression(expression) ||
    ts.isElementAccessExpression(expression)
  ) {
    return ['{}'];
  }
  return null;
}

/** Every combination of the values each span of a template literal can take. */
function templateCandidates(expression, portee) {
  let formes = [expression.head.text];
  for (const span of expression.templateSpans) {
    const valeurs = candidats(span.expression, portee) ?? ['{}'];
    formes = formes.flatMap((forme) => valeurs.map((valeur) => forme + valeur + span.literal.text));
  }
  return formes;
}

/** `new URLSearchParams({ a, b })`, the one shape whose keys are known statically. */
function isUrlSearchParamsLiteral(expression) {
  return (
    ts.isNewExpression(expression) &&
    ts.isIdentifier(expression.expression) &&
    expression.expression.text === 'URLSearchParams' &&
    expression.arguments?.length === 1 &&
    ts.isObjectLiteralExpression(expression.arguments[0])
  );
}

/**
 * What a method's parameters and local `const`s can be, by name. A parameter
 * typed as a union of string literals enumerates them; anything else typed is
 * one unknown value. A `const` is resolved through `candidats`, in order.
 */
function porteeDe(methode) {
  const portee = new Map();
  for (const parametre of methode.parameters ?? []) {
    if (!ts.isIdentifier(parametre.name)) continue;
    const litteraux = litterauxDuType(parametre.type);
    portee.set(parametre.name.text, litteraux ?? ['{}']);
  }
  const corps = methode.body;
  if (corps && ts.isBlock(corps)) {
    for (const instruction of corps.statements) {
      if (ts.isVariableStatement(instruction)) {
        bindDeclarations(instruction.declarationList.declarations, portee);
      }
    }
  }
  return portee;
}

function bindDeclarations(declarations, portee) {
  for (const declaration of declarations) {
    if (!ts.isIdentifier(declaration.name) || !declaration.initializer) continue;
    const valeurs = candidats(declaration.initializer, portee);
    if (valeurs !== null) {
      portee.set(declaration.name.text, valeurs);
    }
  }
}

function litterauxDuType(type) {
  if (!type) return null;
  if (ts.isLiteralTypeNode(type) && ts.isStringLiteral(type.literal)) {
    return [type.literal.text];
  }
  if (ts.isUnionTypeNode(type)) {
    const membres = type.types.map(litterauxDuType);
    return membres.every((membre) => membre !== null) ? membres.flat() : null;
  }
  return null;
}

/* ------------------------------ the scan --------------------------------- */

const operations = operationsDuContrat();
const ecarts = [];
let appels = 0;
let liens = 0;

function signaler(fichier, noeud, message) {
  const { line } = fichier.getLineAndCharacterOfPosition(noeud.getStart());
  ecarts.push(`${relative(RACINE, fichier.fileName)}:${line + 1}  ${message}`);
}

/** `chemin?a={}&b={}` against the contract: the route, the verb, the parameter names. */
function confronter(fichier, noeud, verbe, url) {
  const [cheminBrut, query = ''] = url.split('?');
  const debut = cheminBrut.indexOf('/api/');
  if (debut < 0) {
    signaler(fichier, noeud, `« ${url} » ne commence pas par /api/`);
    return;
  }
  const chemin = normalise(cheminBrut.slice(debut));
  const parVerbe = operations.get(chemin);
  if (!parVerbe) {
    signaler(
      fichier,
      noeud,
      `${verbe ? verbe.toUpperCase() + ' ' : ''}${chemin} n'existe pas dans le contrat`,
    );
    return;
  }
  if (verbe === null) {
    return;
  }
  const parametres = parVerbe.get(verbe);
  if (!parametres) {
    signaler(
      fichier,
      noeud,
      `${verbe.toUpperCase()} ${chemin} : le contrat n'a que ${[...parVerbe.keys()].map((v) => v.toUpperCase()).join(', ')} sur ce chemin`,
    );
    return;
  }
  if (query === '') {
    return;
  }
  if (query === '{}') {
    signaler(
      fichier,
      noeud,
      `${verbe.toUpperCase()} ${chemin} : paramètres de requête non résolus (« ?${query} »)`,
    );
    return;
  }
  for (const paire of query.split('&')) {
    const nom = paire.split('=')[0];
    if (!parametres.has(nom)) {
      signaler(
        fichier,
        noeud,
        `${verbe.toUpperCase()} ${chemin} : le paramètre « ${nom} » n'est pas dans le contrat` +
          (parametres.size > 0 ? ` (attendu : ${[...parametres].join(', ')})` : ''),
      );
    }
  }
}

function methodeEnglobante(noeud) {
  for (let courant = noeud; courant; courant = courant.parent) {
    if (
      ts.isMethodDeclaration(courant) ||
      ts.isFunctionDeclaration(courant) ||
      ts.isArrowFunction(courant) ||
      ts.isFunctionExpression(courant)
    ) {
      return courant;
    }
  }
  return null;
}

for (const nom of readdirSync(API_DIR).sort()) {
  if (!nom.endsWith('.ts') || nom.endsWith('.spec.ts')) continue;
  const chemin = join(API_DIR, nom);
  const fichier = ts.createSourceFile(
    chemin,
    readFileSync(chemin, 'utf8'),
    ts.ScriptTarget.ES2022,
    true,
  );

  const visiter = (noeud) => {
    if (isApiCall(noeud)) {
      checkApiCall(fichier, noeud);
      return;
    }
    if (isReturnedLink(noeud)) {
      checkReturnedLink(fichier, noeud);
    }
    ts.forEachChild(noeud, visiter);
  };
  visiter(fichier);
}

/** this.api.<verbe>(url, …) */
function isApiCall(noeud) {
  return (
    ts.isCallExpression(noeud) &&
    ts.isPropertyAccessExpression(noeud.expression) &&
    ts.isPropertyAccessExpression(noeud.expression.expression) &&
    noeud.expression.expression.name.text === 'api' &&
    noeud.expression.expression.expression.kind === ts.SyntaxKind.ThisKeyword
  );
}

function scopeOf(noeud) {
  const methode = methodeEnglobante(noeud);
  return methode ? porteeDe(methode) : new Map();
}

function checkApiCall(fichier, noeud) {
  const verbe = VERBES[noeud.expression.name.text];
  if (verbe === undefined) {
    signaler(
      fichier,
      noeud,
      `this.api.${noeud.expression.name.text} n'est pas un verbe connu de ce script`,
    );
    return;
  }
  if (noeud.arguments.length === 0) {
    signaler(fichier, noeud, `this.api.${noeud.expression.name.text} sans adresse`);
    return;
  }
  appels += 1;
  const formes = candidats(noeud.arguments[0], scopeOf(noeud));
  if (formes === null) {
    signaler(
      fichier,
      noeud,
      `adresse non résolue : « ${noeud.arguments[0].getText()} » — écrivez-la en littéral, ou typez ses segments`,
    );
    return;
  }
  for (const forme of formes) {
    confronter(fichier, noeud, verbe, forme);
  }
}

/** A template returned as a link, in a module without calls: path only. */
function isReturnedLink(noeud) {
  return (
    ts.isReturnStatement(noeud) &&
    noeud.expression &&
    (ts.isTemplateExpression(noeud.expression) || ts.isStringLiteral(noeud.expression)) &&
    noeud.expression.getText().includes('/api/')
  );
}

function checkReturnedLink(fichier, noeud) {
  const formes = candidats(noeud.expression, scopeOf(noeud));
  for (const forme of formes ?? []) {
    liens += 1;
    confronter(fichier, noeud, null, forme);
  }
}

if (ecarts.length > 0) {
  console.error(
    `check-api-contract : ${ecarts.length} écart(s) entre core/api/ et docs/schema/openapi.json :`,
  );
  for (const ecart of ecarts) {
    console.error(`  ${ecart}`);
  }
  process.exit(1);
}
console.log(
  `check-api-contract : ${appels} appel(s) et ${liens} lien(s) de core/api/ confrontés au contrat, 0 écart.`,
);
