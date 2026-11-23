// Every page of the site, in both languages: the home page and the answer pages.
// `url` is the French path, `altUrl` the same page in the other language.
import copyOf from './copy.js';
import { ANSWER_KEYS, urlOf } from '../lib.js';

export default function () {
  const copy = copyOf();
  const pages = [];
  for (const lang of ['fr', 'en']) {
    for (const key of ['accueil', ...ANSWER_KEYS]) {
      const other = lang === 'fr' ? 'en' : 'fr';
      pages.push({
        lang,
        key,
        url: urlOf(copy, lang, key),
        altLang: other,
        altUrl: urlOf(copy, other, key),
        title: copy[lang][key].titre,
        description: copy[lang][key].description,
      });
    }
  }
  return pages;
}
