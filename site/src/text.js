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
    .replaceAll('« ', '«\u00a0')
    .replace(/(\d) (h|ko)\b/g, '$1 $2');
}

export const markdown = (text, lang) => md.render(typo(text, lang));
export const markdownInline = (text, lang) => md.renderInline(typo(text, lang));

const attr = (text) => text.replaceAll('&', '&amp;').replaceAll('"', '&quot;').replaceAll('<', '&lt;');
const OPAQUE = /^<(\/?)(abbr|script|style|code|pre|svg|textarea|title)\b/i;
const EXTERNAL = /^<a\b[^>]*\shref="https?:\/\/(?!planning\.sylvain\.dev[/"])/i;

// Built pages, after rendering: in <main>, the first occurrence of each abbreviation
// gets its meaning (<abbr title>); in <body>, every link leaving the site says so to
// screen readers, as its icon (a CSS mask) says it to the eye.
export function annotate(html, abbreviations, external) {
  const body = html.indexOf('<body');
  if (body < 0) return html;
  const seen = new Set();
  let opaque = 0;
  let inMain = false;
  let leaving = false;
  const out = html.slice(body).split(/(<[^>]*>)/).map((part) => {
    if (part.startsWith('<')) {
      const tag = part.match(OPAQUE);
      if (tag && !part.endsWith('/>')) opaque += tag[1] ? -1 : 1;
      if (/^<main\b/i.test(part)) inMain = true;
      if (/^<\/main>/i.test(part)) inMain = false;
      if (EXTERNAL.test(part)) leaving = true;
      if (leaving && /^<\/a>/i.test(part)) {
        leaving = false;
        return `<span class="visually-hidden"> ${external}</span>${part}`;
      }
      return part;
    }
    if (!inMain || opaque > 0) return part;
    let text = part;
    for (const { sigle, sens } of abbreviations) {
      if (seen.has(sigle)) continue;
      const re = new RegExp(`(?<![\\p{L}\\d])${sigle}(?![\\p{L}\\d])`, 'u');
      if (re.test(text)) {
        seen.add(sigle);
        text = text.replace(re, `<abbr title="${attr(sens)}">${sigle}</abbr>`);
      }
    }
    return text;
  });
  return html.slice(0, body) + out.join('');
}
