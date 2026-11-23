import { test } from 'node:test';
import assert from 'node:assert/strict';
import { existsSync } from 'node:fs';
import { join } from 'node:path';
import { pages, root, shape, strings } from './helpers.mjs';

const split = (file) => {
  const { en, ...fr } = file;
  return { fr, en };
};

test('French and English have the same structure', () => {
  for (const [name, file] of Object.entries(pages)) {
    const { fr, en } = split(file);
    assert.ok(en, `${name}.json : la clé "en" manque`);
    assert.deepEqual(shape(en), shape(fr), `${name}.json : le français et l'anglais ont des clés différentes`);
  }
});

test('no empty text and no trace of the old fictional festival', () => {
  for (const [name, file] of Object.entries(pages)) {
    for (const [path, text] of strings(file)) {
      assert.ok(text.trim(), `${name}.json ${path} est vide`);
      assert.ok(!/Combelune/i.test(text), `${name}.json ${path} cite encore Combelune`);
    }
  }
});

test('titles and descriptions fit a search result', () => {
  for (const [name, file] of Object.entries(pages)) {
    const { fr, en } = split(file);
    for (const [lang, copy] of [['fr', fr], ['en', en]]) {
      if (!copy.titre) continue;
      assert.ok(copy.titre.length <= 70, `${name} ${lang} : titre de ${copy.titre.length} caractères`);
      assert.ok(copy.description.length <= 160, `${name} ${lang} : description de ${copy.description.length} caractères`);
    }
  }
});

test('the head of the home page stays short', () => {
  const { fr, en } = split(pages.accueil);
  for (const c of [fr, en]) {
    assert.ok(c.h1.split(/\s+/).length <= 7, `h1 : ${c.h1}`);
    assert.ok(c.lede.split(/\s+/).length <= 12, `lede : ${c.lede}`);
  }
});

test('every capture, poster, thumbnail and video exists in both languages', () => {
  const { fr, en } = split(pages.accueil);
  for (const [lang, c] of [['fr', fr], ['en', en]]) {
    for (const e of c.images.ecrans) {
      for (const f of [`images/${lang}/${e.id}.webp`, `images/${lang}/vignettes/${e.id}.webp`]) assert.ok(existsSync(join(root, 'src', f)), f);
    }
    for (const f of [...c.images.films.map((x) => x.id), c.jourj.film, c.equipes.film, c.cas.film]) {
      for (const p of [`images/${lang}/film-${f}.webp`, `videos/${lang}/${f}.mp4`]) assert.ok(existsSync(join(root, 'src', p)), p);
    }
  }
});

test('the questions of the home page exist on the FAQ page, once each', () => {
  const { fr, en } = split(pages.faq);
  for (const c of [fr, en]) {
    const ids = c.items.map((i) => i.id);
    assert.equal(new Set(ids).size, ids.length);
    assert.ok(c.items.some((i) => i.accueil));
  }
  assert.deepEqual(fr.items.map((i) => i.id), en.items.map((i) => i.id));
});
