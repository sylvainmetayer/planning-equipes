// JSON-LD of each page. The questions and answers of FAQPage are the ones of
// the <details> on the page, word for word: both come from the same data.
import copyOf from './_data/copy.js';
import site from './_data/site.json' with { type: 'json' };
import urls from './_data/urls.js';
import { typo } from './text.js';

const copy = copyOf();
const link = urls();
const abs = (path) => `${site.url}${path}`;

const faq = (lang, items) => ({
  '@type': 'FAQPage',
  '@id': abs(lang === 'fr' ? '/#faq' : '/en/#faq'),
  inLanguage: lang,
  mainEntity: items.map((i) => ({
    '@type': 'Question',
    name: typo(i.q, lang),
    acceptedAnswer: { '@type': 'Answer', text: typo(i.a, lang).replace(/\[([^\][]+)\]\([^)]*\)/g, '$1') },
  })),
});

function docSchema(p, modified) {
  const url = abs(p.url);
  const home = copy.fr.commun.nav.accueil;
  const trail = [{ name: home, item: abs(link.fr.accueil) }];
  if (p.name !== 'README') trail.push({ name: copy.fr.docs.h1, item: abs('/docs/') });
  trail.push({ name: p.name === 'README' ? copy.fr.docs.h1 : p.h1, item: url });
  return {
    '@context': 'https://schema.org',
    '@graph': [
      { '@type': 'Person', '@id': site.author.id, name: site.author.name, url: site.author.url, sameAs: site.author.sameAs },
      { '@type': 'WebSite', '@id': abs('/#site'), name: site.name, url: abs('/'), inLanguage: ['fr', 'en'], publisher: { '@id': site.author.id } },
      {
        '@type': 'TechArticle',
        '@id': `${url}#page`,
        url,
        name: typo(p.title, 'fr'),
        description: p.description,
        inLanguage: 'fr',
        isPartOf: { '@id': abs('/#site') },
        dateModified: modified,
        author: { '@id': site.author.id },
        primaryImageOfPage: { '@type': 'ImageObject', url: abs('/og-fr.png') },
      },
      { '@type': 'BreadcrumbList', itemListElement: trail.map((t, i) => ({ '@type': 'ListItem', position: i + 1, ...t })) },
    ],
  };
}

export default function schema(p, modified) {
  if (p.doc) return docSchema(p, modified);
  const { lang, key } = p;
  const page = copy[lang][key];
  const url = abs(p.url);
  const common = copy[lang].commun;
  const graph = [
    { '@type': 'Person', '@id': site.author.id, name: site.author.name, url: site.author.url, sameAs: site.author.sameAs },
    {
      '@type': 'WebSite',
      '@id': abs('/#site'),
      name: site.name,
      url: abs('/'),
      inLanguage: ['fr', 'en'],
      publisher: { '@id': site.author.id },
    },
    {
      '@type': page.items ? 'FAQPage' : 'WebPage',
      '@id': `${url}#page`,
      url,
      name: typo(page.titre, lang),
      description: page.description,
      inLanguage: lang,
      isPartOf: { '@id': abs('/#site') },
      about: { '@id': abs('/#app') },
      dateModified: modified,
      primaryImageOfPage: { '@type': 'ImageObject', url: abs(`/og-${lang}.png`) },
      ...(page.items ? { mainEntity: faq(lang, page.items).mainEntity } : {}),
    },
  ];
  if (key !== 'accueil') {
    graph.push({
      '@type': 'BreadcrumbList',
      itemListElement: [
        { '@type': 'ListItem', position: 1, name: common.nav.accueil, item: abs(link[lang].accueil) },
        { '@type': 'ListItem', position: 2, name: page.h1, item: url },
      ],
    });
  }
  if (key === 'accueil') {
    graph.push({
      '@type': 'SoftwareApplication',
      '@id': abs('/#app'),
      name: site.name,
      url: abs('/'),
      description: copy[lang].accueil.description,
      applicationCategory: 'BusinessApplication',
      applicationSubCategory: 'Planification d’équipes',
      operatingSystem: 'Web',
      inLanguage: ['fr', 'en'],
      license: site.license,
      image: abs('/og-fr.png'),
      screenshot: copy[lang].accueil.images.ecrans.map((e) => abs(`/images/${lang}/${e.id}.webp`)),
      featureList: copy[lang].accueil.jourj.points,
      author: { '@id': site.author.id },
      publisher: { '@id': site.author.id },
      sameAs: [site.repo],
    }, faq(lang, copy[lang].faq.items.filter((i) => i.accueil)));
    for (const f of page.images.films) {
      graph.push({
        '@type': 'VideoObject',
        name: f.titre,
        description: f.legende,
        thumbnailUrl: abs(`/images/${lang}/film-${f.id}.webp`),
        contentUrl: abs(`/videos/${lang}/${f.id}.mp4`),
        uploadDate: modified,
        duration: `PT${f.secondes}S`,
        inLanguage: lang,
      });
    }
  }
  return { '@context': 'https://schema.org', '@graph': graph };
}
