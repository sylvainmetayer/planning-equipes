// Shared by the data files and the generators: data files themselves export only
// their default, since Eleventy would otherwise paginate over their named exports.
export const PAGES = ['commun', 'accueil', 'legal', 'equipe', 'calcul', 'hebergement', 'faq', 'contact', 'mentions'];
export const ANSWER_KEYS = ['legal', 'equipe', 'calcul', 'hebergement', 'faq', 'contact', 'mentions'];

export function urlOf(copy, lang, key) {
  if (key === 'accueil') return lang === 'fr' ? '/' : '/en/';
  const slug = copy[lang][key].slug;
  return lang === 'fr' ? `/${slug}/` : `/en/${slug}/`;
}
