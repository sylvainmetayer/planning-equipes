import MarkdownIt from 'markdown-it';

const md = new MarkdownIt({ html: false, linkify: false, typographer: false });

// Typography: curly apostrophes, and in French a no-break space before ? ! : ; » and after «, and between a
// number and h / ko. The copy is written with plain spaces in Pages CMS.
export function typo(text, lang) {
  // Apostrophes are typographic in both languages
  const apostrophes = text.replace(/(\p{L})'(\p{L})/gu, '$1\u2019$2');
  if (lang !== 'fr') return apostrophes;
  return apostrophes
    .replace(/ ([?!:;»])/g, ' $1')
    .replace(/« /g, '« ')
    .replace(/(\d) (h|ko)\b/g, '$1 $2');
}

export const markdown = (text, lang) => md.render(typo(text, lang));
export const markdownInline = (text, lang) => md.renderInline(typo(text, lang));
