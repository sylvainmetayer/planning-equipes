// The copy of every page, by language, read from the JSON files Pages CMS
// edits (src/_data/pages/*.json): French at the root of each file, the English
// version under its "en" key. See .pages.yml.
import { readFileSync } from 'node:fs';

import { PAGES, countWords } from '../lib.js';
import { typo } from '../text.js';

// Every string of a page, typographically set once, here: the templates and the
// JSON-LD then agree on the exact characters.
// {articles}, {Articles}, {mineurs}: the counts of articles.js, never typed in a text.
const fill = (text, lang) => text.replace(/\{(articles|Articles|mineurs)\}/g, (_, k) => countWords(lang)[k]);

const set = (value, lang) =>
  typeof value === 'string'
    ? typo(fill(value, lang), lang)
    : Array.isArray(value)
      ? value.map((v) => set(v, lang))
      : value && typeof value === 'object'
        ? Object.fromEntries(Object.entries(value).map(([k, v]) => [k, set(v, lang)]))
        : value;

export default function () {
  const content = { fr: {}, en: {} };
  for (const name of PAGES) {
    const { en, ...fr } = JSON.parse(readFileSync(new URL(`./pages/${name}.json`, import.meta.url), 'utf8'));
    if (!en) throw new Error(`${name}.json : la version anglaise (clé "en") manque`);
    content.fr[name] = set(fr, 'fr');
    content.en[name] = set(en, 'en');
  }
  return content;
}
