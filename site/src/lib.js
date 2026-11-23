// Shared by the data files and the generators: data files themselves export only
// their default, since Eleventy would otherwise paginate over their named exports.
export const PAGES = ['commun', 'accueil', 'legal', 'benevoles', 'calcul', 'faq'];
export const ANSWER_KEYS = ['legal', 'benevoles', 'calcul', 'faq'];

export function urlOf(copy, lang, key) {
  if (key === 'accueil') return lang === 'fr' ? '/' : '/en/';
  const slug = copy[lang][key].slug;
  return lang === 'fr' ? `/${slug}/` : `/en/${slug}/`;
}
