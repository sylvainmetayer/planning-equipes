// The Labour Code articles the page quotes must be the ones ConstraintCatalog
// (served by GET /api/constraints) cites: a rule that changes its legal basis
// changes here too (site/README.md).
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import articles from '../src/_data/articles.js';
import { catalogPath, pages, strings } from './helpers.mjs';

const catalog = readFileSync(catalogPath, 'utf8');
const ARTICLE = /\b[LRD]\d{4}-\d+(?:-\d+)?\b/g;

test('the ten articles are all cited by ConstraintCatalog', () => {
  assert.equal(articles.length, 10);
  for (const { number } of articles) assert.ok(catalog.includes(number), `${number} n'est pas dans ConstraintCatalog`);
});

test('every article quoted in a text exists in ConstraintCatalog', () => {
  for (const [name, file] of Object.entries(pages)) {
    for (const [path, text] of strings(file)) {
      for (const number of text.match(ARTICLE) ?? []) assert.ok(catalog.includes(number), `${name}.json ${path} : ${number} n'est pas dans ConstraintCatalog`);
    }
  }
});

test('the refused examples of the home page quote one of the ten articles', () => {
  const numbers = new Set(articles.map((a) => a.number));
  const { en, ...fr } = pages.accueil;
  for (const lang of [fr, en]) for (const r of lang.loi.refus) assert.ok(numbers.has(r.article), `${r.article} ne figure pas dans articles.js`);
});

test('both languages describe every article', () => {
  for (const a of articles) for (const lang of ['fr', 'en']) assert.ok(a[lang].subject && a[lang].rule, `${a.number} sans ${lang}`);
});
