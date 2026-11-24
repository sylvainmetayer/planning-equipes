// Every page of the site, in both languages: the home page and the answer pages.
// `url` is the French path, `altUrl` the same page in the other language.
import copyOf from './copy.js';
import { execFileSync } from 'node:child_process';
import { ANSWER_KEYS, NOINDEX_KEYS, urlOf } from '../lib.js';

// Absolute path: the build must not run whatever `git` a modified PATH would find
const GIT = '/usr/bin/git';
const git = (args) => {
  try {
    return execFileSync(GIT, args, { encoding: 'utf8' }).trim();
  } catch {
    return '';
  }
};
// A shallow clone (Cloudflare Pages) cannot tell when a file last changed, and no git
// at all returns '': either way lastmod is left out rather than invented.
const shallow = git(['rev-parse', '--is-shallow-repository']) !== 'false';

// Date of the last commit touching the data of a page (the home also its templates)
const lastmod = (key) => {
  if (shallow) return '';
  const files = [`src/_data/pages/${key}.json`, ...(key === 'accueil' ? ['src/_includes/home.njk', 'src/_includes/film.njk'] : [])];
  const date = git(['log', '-1', '--format=%cs', '--', ...files]);
  return /^\d{4}-\d{2}-\d{2}$/.test(date) ? date : '';
};

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
        noindex: NOINDEX_KEYS.includes(key),
        lastmod: lastmod(key),
      });
    }
  }
  return pages;
}
