import copyOf from './copy.js';
import { ANSWER_KEYS, urlOf } from '../lib.js';

// urls[lang][key]: the path of a page, for the navigation and the links between pages
export default function () {
  const copy = copyOf();
  const urls = {};
  for (const lang of ['fr', 'en']) {
    urls[lang] = {};
    for (const key of ['accueil', ...ANSWER_KEYS]) urls[lang][key] = urlOf(copy, lang, key);
  }
  return urls;
}
