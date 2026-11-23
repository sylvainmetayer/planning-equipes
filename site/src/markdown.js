// Markdown twin of each page (<url>index.md), listed in llms.txt: the same copy
// as the HTML, without its layout, for the tools that read pages as text.
import copyOf from './_data/copy.js';
import site from './_data/site.json' with { type: 'json' };
import articles from './_data/articles.js';

const copy = copyOf();

const sections = (list) => list.map((s) => `## ${s.h2}\n\n${s.texte}`).join('\n\n');

export function toMarkdown(p) {
  const { lang, key } = p;
  const page = copy[lang][key];
  const out = [`# ${page.h1}`];
  if (key === 'accueil') {
    out.push(page.lede, `## ${page.loi.h2}`, page.loi.reponse);
    out.push(
      articles.map((a) => `- ${a.number} (${a[lang].subject}) : ${a[lang].rule}`).join('\n'),
      `## ${page.jourj.h2}`,
      page.jourj.intro,
      page.jourj.points.map((x) => `- ${x}`).join('\n'),
      `## ${page.equipes.h2}`,
      page.equipes.texte,
      page.equipes.outils,
      `## ${page.ensemble.h2}`,
      page.ensemble.etapes.map((e) => `- **${e.titre}**`).join('\n'),
      page.ensemble.prix,
      `## ${copy[lang].faq.h1}`,
      copy[lang].faq.items.filter((i) => i.accueil).map((i) => `### ${i.q}\n\n${i.a}`).join('\n\n'),
    );
  } else {
    out.push(page.reponse);
    if (key === 'legal') out.push(`## ${page.articlesH}`, articles.map((a) => `- **${a.number}**, ${a[lang].subject} : ${a[lang].rule}`).join('\n'));
    if (page.sections) out.push(sections(page.sections));
    if (key === 'faq') out.push(page.items.map((i) => `## ${i.q}\n\n${i.a}`).join('\n\n'));
  }
  out.push(`${page.cta ?? ''} ${site.email}`.trim());
  return `${out.filter(Boolean).join('\n\n')}\n`;
}
