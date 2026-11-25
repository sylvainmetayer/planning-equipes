import MarkdownIt from "markdown-it";

const md = new MarkdownIt({ html: false, linkify: false, typographer: false });

// Typography: curly apostrophes, and in French a no-break space before ? ! : ; » and after «, and between a
// number and h / ko. The copy is written with plain spaces in Pages CMS.
export function typo(text, lang) {
  // Apostrophes are typographic in both languages
  const apostrophes = text.replace(/(\p{L})'(\p{L})/gu, "$1\u2019$2");
  if (lang !== "fr") return apostrophes;
  return apostrophes
    .replace(/ ([?!:;»])/g, " $1")
    .replaceAll("« ", "«\u00a0")
    .replace(/(\d) (h|ko)\b/g, "$1 $2");
}

export const markdown = (text, lang) => md.render(typo(text, lang));
export const markdownInline = (text, lang) => md.renderInline(typo(text, lang));

const attr = (text) =>
  text
    .replaceAll("&", "&amp;")
    .replaceAll('"', "&quot;")
    .replaceAll("<", "&lt;");
const OPAQUE = /^<(\/?)(abbr|script|style|code|pre|svg|textarea|title)\b/i;
const EXTERNAL = /^<a\b[^>]*\shref="https?:\/\/(?!planning\.sylvain\.dev[/"])/i;

// Built pages, after rendering: in <main>, the first occurrence of each abbreviation
// gets its meaning (<abbr title>); in <body>, every link leaving the site says so to
// screen readers, as its icon (a CSS mask) says it to the eye.
// Splits HTML into tags and the text between them, without a backtracking regex
function tokens(html) {
  const out = [];
  let i = 0;
  while (i < html.length) {
    const lt = html.indexOf("<", i);
    if (lt < 0) {
      out.push(html.slice(i));
      break;
    }
    if (lt > i) out.push(html.slice(i, lt));
    const gt = html.indexOf(">", lt);
    const end = gt < 0 ? html.length : gt + 1;
    out.push(html.slice(lt, end));
    i = end;
  }
  return out;
}

function abbreviate(text, abbreviations, seen) {
  let out = text;
  for (const { sigle, sens } of abbreviations) {
    if (seen.has(sigle)) continue;
    const re = new RegExp(String.raw`(?<![\p{L}\d])${sigle}(?![\p{L}\d])`, "u");
    if (re.test(out)) {
      seen.add(sigle);
      out = out.replace(re, `<abbr title="${attr(sens)}">${sigle}</abbr>`);
    }
  }
  return out;
}

// Built pages, after rendering: in <main>, the first occurrence of each abbreviation
// gets its meaning (<abbr title>); in <body>, every link leaving the site says so to
// screen readers, as its icon (a CSS mask) says it to the eye.
export function annotate(html, abbreviations, external) {
  const body = html.indexOf("<body");
  if (body < 0) return html;
  const seen = new Set();
  const state = { opaque: 0, inMain: false, leaving: false };
  const tag = (part) => {
    const opaque = part.match(OPAQUE);
    if (opaque && !part.endsWith("/>")) state.opaque += opaque[1] ? -1 : 1;
    if (/^<main\b/i.test(part)) state.inMain = true;
    if (/^<\/main>/i.test(part)) state.inMain = false;
    if (EXTERNAL.test(part)) state.leaving = true;
    if (!state.leaving || !/^<\/a>/i.test(part)) return part;
    state.leaving = false;
    return `<span class="visually-hidden"> ${external}</span>${part}`;
  };
  const out = tokens(html.slice(body)).map((part) => {
    if (part.startsWith("<")) return tag(part);
    return state.inMain && state.opaque <= 0
      ? abbreviate(part, abbreviations, seen)
      : part;
  });
  return html.slice(0, body) + out.join("");
}
