// The Labour Code articles the page quotes must be the ones ConstraintCatalog
// (served by GET /api/constraints) cites: a rule that changes its legal basis
// changes here too (site/README.md).
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import articles, { countWords } from '../src/_data/articles.js';
import { join } from 'node:path';
import { catalogPath, files, pages, root, strings } from './helpers.mjs';

const catalog = readFileSync(catalogPath, 'utf8');
const ARTICLE = /\b[LRD]\d{4}-\d+(?:-\d+)?\b/g;

test('the articles are all cited by ConstraintCatalog', () => {
  assert.ok(articles.length >= 10);
  for (const { number } of articles) assert.ok(catalog.includes(number), `${number} n'est pas dans ConstraintCatalog`);
});

test('every article quoted in a text exists in ConstraintCatalog', () => {
  for (const [name, file] of Object.entries(pages)) {
    for (const [path, text] of strings(file)) {
      for (const number of text.match(ARTICLE) ?? []) assert.ok(catalog.includes(number), `${name}.json ${path} : ${number} n'est pas dans ConstraintCatalog`);
    }
  }
});

test('the refused examples of the home page quote one of the listed articles', () => {
  const numbers = new Set(articles.map((a) => a.number));
  const { en, ...fr } = pages.accueil;
  for (const lang of [fr, en]) for (const r of lang.loi.refus) assert.ok(numbers.has(r.article), `${r.article} ne figure pas dans articles.js`);
});

test('both languages describe every article', () => {
  for (const a of articles) for (const lang of ['fr', 'en']) assert.ok(a[lang].subject && a[lang].rule, `${a.number} sans ${lang}`);
});

// A count of articles is never typed: the texts say {articles}, copy.js fills it.
const NUMBER = /\b(\d+|zéro|un|deux|trois|quatre|cinq|six|sept|huit|neuf|dix|onze|douze|treize|quatorze|quinze|seize|vingt|zero|one|two|three|four|five|seven|eight|nine|ten|eleven|twelve|thirteen|fourteen|fifteen|sixteen|seventeen|eighteen|nineteen|twenty)\b[^.]{0,12}\barticles\b/i;

test('no source text types a number of articles', () => {
  for (const [name, file] of Object.entries(pages)) {
    for (const [path, text] of strings(file)) assert.ok(!NUMBER.test(text), `${name}.json ${path} : nombre d'articles écrit à la main (${text.match(NUMBER)?.[0]}), utiliser {articles}`);
  }
});

test('the built pages state the count of articles.js, and leave no placeholder', () => {
  const { fr } = { fr: countWords('fr') };
  const built = files(join(root, '_site'), '.html').concat(files(join(root, '_site'), '.md'), files(join(root, '_site'), '.txt'));
  assert.ok(built.length > 0, "_site n'est pas construit");
  let stated = 0;
  for (const f of built) {
    const text = readFileSync(f, 'utf8');
    assert.ok(!/\{(articles|Articles|mineurs)\}/.test(text), `${f} : placeholder non remplacé`);
    for (const m of text.matchAll(/\b([\p{L}-]+) articles (?:du Code du travail|of the French Labour Code)/giu)) {
      const word = m[1].toLowerCase();
      if (['les', 'the', 'ces', 'des', 'aux', 'ses', 'quels', 'tous', 'ces'].includes(word) || !NUMBER.test(`${word} articles`)) continue;
      stated++;
      assert.ok([countWords('fr').articles, countWords('en').articles].includes(word), `${f} : « ${m[0]} », articles.js en compte ${fr.articles}`);
    }
  }
  assert.ok(stated > 0, 'aucune page ne donne le nombre');
});
