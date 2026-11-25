// Markdown twin of each page (<url>index.md), listed in llms.txt: the same copy
// as the HTML, without its layout, for the tools that read pages as text.
import copyOf from './_data/copy.js';
import site from './_data/site.json' with { type: 'json' };
import articles from './_data/articles.js';
import { urlOf } from './lib.js';

const copy = copyOf();

const sections = (list) => list.map((s) => `## ${s.h2}\n\n${s.texte}`).join('\n\n');

export function toMarkdown(p) {
  const { lang, key } = p;
  const page = copy[lang][key];
  const out = [`# ${page.h1}`];
  const sep = lang === 'fr' ? ' : ' : ': ';
  if (key === 'accueil') {
    out.push(
      page.lede, `## ${page.loi.h2}`, page.loi.reponse, page.loi.refus.map((r) => `- ${r.qui}, ${r.poste}${sep}${r.raison} (${r.article})`).join('\n'),
      articles.map((a) => `- ${a.number} (${a[lang].subject})${sep}${a[lang].rule}`).join('\n'),
      `## ${page.jourj.h2}`,
      page.jourj.intro,
      page.jourj.points.map((x) => `- ${x}`).join('\n'),
      `## ${page.equipes.h2}`,
      page.equipes.texte,
      page.equipes.tuiles.map((t) => `- **${t.titre}**${sep}${t.texte}`).join('\n'),
      page.equipes.outils,
      `## ${page.cas.h2}`,
      page.cas.niveaux.map((n, i) => `${i + 1}. **${n.titre}**${sep}${n.texte}`).join('\n'),
      `## ${page.images.h2}`,
      page.images.ecrans.map((e) => `- ${e.alt}`).join('\n'),
      `## ${page.ensemble.h2}`,
      page.ensemble.prix,
      page.ensemble.etapes.map((e) => `- **${e.titre}**${sep}${page.ensemble.vous} ${e.vous} ${page.ensemble.nous} ${e.nous}`).join('\n'),
      `## ${copy[lang].faq.h1}`,
      copy[lang].faq.items.filter((i) => i.accueil).map((i) => `### ${i.q}\n\n${i.a}`).join('\n\n'),
    );
  } else {
    out.push(page.reponse);
    if (page.guide) out.push(`[${page.guide}](${site.url}/docs/installer/)`);
    if (key === 'legal') out.push(`## ${page.articlesH}`, articles.map((a) => `- **${a.number}**, ${a[lang].subject}${sep}${a[lang].rule}`).join('\n'));
    if (page.sections) out.push(sections(page.sections));
    if (page.tableau) out.push(`## ${page.tableau.h2}`, [`| ${page.tableau.colonnes.join(' | ')} |`, '| --- | --- | --- |', ...page.tableau.lignes.map((l) => `| ${l.q} | ${l.tableur} | ${l.logiciel} |`)].join('\n'));
    if (page.items) out.push(page.items.map((i) => `## ${i.q}\n\n${i.a}`).join('\n\n'));
  }
  if (key === 'contact') out.push(`${page.lien}${sep}https://tally.so/r/${site.tally[lang]}`, `${page.ecrire} ${site.email}`);
  else if (page.cta) out.push(`${page.cta} ${site.url}${urlOf(copy, lang, 'contact')} ${site.email}`);
  return `${out.filter(Boolean).join('\n\n')}\n`;
}
