// The one index of the site search, loaded by the palette on its first opening:
// every docs guide (title, description, h2/h3 and their anchors) then every page of
// the showcase in both languages. `k` is "doc" or "page", `l` the language of the entry.
const plain = (s) => String(s).replace(/[*_`]/g, '');

export default class SearchIndex {
  data() {
    return { permalink: '/search.json', eleventyExcludeFromCollections: true };
  }

  render({ docs, allPages, copy }) {
    const guides = docs.map((d) => ({ k: 'doc', l: 'fr', u: d.url, t: plain(d.h1), d: d.description, h: d.toc.map((h) => [h.id, plain(h.text)]) }));
    const pages = allPages.map((p) => {
      const c = copy[p.lang][p.key];
      return { k: 'page', l: p.lang, u: p.url, t: plain(p.key === 'accueil' ? copy[p.lang].commun.nav.accueil : c.h1), d: p.description };
    });
    return JSON.stringify([...guides, ...pages]);
  }
}
