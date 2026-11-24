// Shared by the data files and the generators: data files themselves export only
// their default, since Eleventy would otherwise paginate over their named exports.
import articles from './_data/articles.js';

export const PAGES = ['commun', 'accueil', 'legal', 'equipe', 'calcul', 'hebergement', 'faq', 'contact', 'mentions', 'festival', 'salon', 'sport', 'tableur', 'mineurs'];
export const ANSWER_KEYS = ['legal', 'equipe', 'calcul', 'hebergement', 'faq', 'contact', 'mentions', 'festival', 'salon', 'sport', 'tableur', 'mineurs'];
// Pages kept out of the sitemap and llms.txt, and marked noindex
export const NOINDEX_KEYS = ['mentions'];

export function urlOf(copy, lang, key) {
  if (key === 'accueil') return lang === 'fr' ? '/' : '/en/';
  const slug = copy[lang][key].slug;
  return lang === 'fr' ? `/${slug}/` : `/en/${slug}/`;
}

// The counts the pages state, spelled out; copy.js fills {articles}, {Articles}
// and {mineurs} in every string with them.
const WORDS = {
  fr: ['zéro', 'un', 'deux', 'trois', 'quatre', 'cinq', 'six', 'sept', 'huit', 'neuf', 'dix', 'onze', 'douze', 'treize', 'quatorze', 'quinze', 'seize', 'dix-sept', 'dix-huit', 'dix-neuf', 'vingt'],
  en: ['zero', 'one', 'two', 'three', 'four', 'five', 'six', 'seven', 'eight', 'nine', 'ten', 'eleven', 'twelve', 'thirteen', 'fourteen', 'fifteen', 'sixteen', 'seventeen', 'eighteen', 'nineteen', 'twenty'],
};
export const countWords = (lang) => {
  const word = (n) => WORDS[lang][n] ?? String(n);
  const all = word(articles.length);
  return { articles: all, Articles: all[0].toUpperCase() + all.slice(1), mineurs: word(articles.filter((a) => a.mineurs).length) };
};
