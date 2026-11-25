// The technical documentation (../docs/*.md), read in place at build time and
// published under /docs. Excluded: the decisions and the project memory, which
// stay on GitHub, as does every other file the pages link to (code, AGENTS.md…).
// A link or an anchor that no longer resolves is a warning, never a failure: the
// content of docs/ is not this build's to fix.
import { readdirSync, readFileSync } from 'node:fs';
import { posix } from 'node:path';
import { fileURLToPath } from 'node:url';
import MarkdownIt from 'markdown-it';
import site from './_data/site.json' with { type: 'json' };
import home from './_data/pages/docs.json' with { type: 'json' };
import { typo } from './text.js';

const DIR = fileURLToPath(new URL('../../docs/', import.meta.url));
const EXCLUDED = new Set(['memoire-du-projet.md']);
const GITHUB = `${site.repo}/blob/main/`;
const warnings = [];
const warn = (message) => warnings.push(message);

// GitHub's own anchors, so that the links written for GitHub keep working
const slugify = (text) => text.toLowerCase().replace(/[^\p{L}\p{N}\p{M} _-]/gu, '').trim().replaceAll(' ', '-');

// A malformed escape in a link is the source's to fix, not a reason to fail the build
const decode = (text) => {
  try {
    return decodeURIComponent(text);
  } catch {
    return text;
  }
};

const urlOfDoc = (name) => (name === 'README' ? '/docs/' : `/docs/${name}/`);

function target(href, published) {
  if (href.startsWith('#')) return { href: `#${decode(href.slice(1))}` };
  if (/^([a-z][a-z0-9+.-]*:|\/\/)/i.test(href)) return { href };
  const [path, rawHash] = href.split('#');
  // The anchors are written as the headings are, accents included: what GitHub's slugs and our ids are
  const hash = rawHash === undefined ? undefined : decode(rawHash);
  const repoPath = posix.normalize(posix.join('docs', path));
  const doc = repoPath.match(/^docs\/([^/]+)\.md$/)?.[1];
  if (doc && published.has(doc)) return { href: urlOfDoc(doc) + (hash ? `#${hash}` : ''), doc, hash };
  if (/^docs\/diagrammes\/[^/]+\.svg$/.test(repoPath)) return { href: `/${repoPath}` };
  const kind = repoPath.endsWith('/') || !posix.extname(repoPath) ? 'tree' : 'blob';
  return { href: `${site.repo}/${kind}/main/${repoPath.replace(/\/$/, '')}${hash ? `#${hash}` : ''}` };
}

// The text of an inline token, line breaks read as spaces
const plain = (inline) => inline.children.map((c) => (c.type === 'softbreak' || c.type === 'hardbreak' ? ' ' : c.type === 'text' || c.type === 'code_inline' ? c.content : '')).join('');

function renderer(published, state) {
  const md = new MarkdownIt({ html: true, linkify: false, typographer: false });
  const { escapeHtml } = md.utils;
  // Only <sub> and <sup> pass: any other tag in the text is shown as written
  md.renderer.rules.html_inline = (tokens, i) => (/^<\/?(sub|sup)>$/.test(tokens[i].content) ? tokens[i].content : escapeHtml(tokens[i].content));
  md.renderer.rules.html_block = (tokens, i) => `<p>${escapeHtml(tokens[i].content)}</p>\n`;
  // A wide table or block of code scrolls on its own, reachable by keyboard
  md.renderer.rules.table_open = () => '<div class="scroll" role="group" aria-label="Tableau" tabindex="0"><table>\n';
  md.renderer.rules.table_close = () => '</table></div>\n';
  md.renderer.rules.fence = (tokens, i) => {
    const t = tokens[i];
    const lang = t.info.trim().split(/\s+/)[0];
    return `<pre class="scroll" role="group" aria-label="Code" tabindex="0"><code${lang ? ` class="language-${escapeHtml(lang)}"` : ''}>${escapeHtml(t.content)}</code></pre>\n`;
  };
  md.renderer.rules.code_block = (tokens, i) => `<pre class="scroll" role="group" aria-label="Code" tabindex="0"><code>${escapeHtml(tokens[i].content)}</code></pre>\n`;
  md.core.ruler.push('docs', (s) => {
    const used = new Map();
    let first = true;
    for (let i = 0; i < s.tokens.length; i++) {
      const t = s.tokens[i];
      if (t.type === 'heading_open') {
        const inline = s.tokens[i + 1];
        const text = plain(inline);
        if (t.tag === 'h1' && first) {
          first = false;
          state.h1 = text;
          t.hidden = true; // the page header carries it
          inline.hidden = true;
          s.tokens[i + 2].hidden = true;
          continue;
        }
        let id = slugify(text);
        const n = used.get(id) ?? 0;
        used.set(id, n + 1);
        if (n) id += `-${n}`;
        t.attrSet('id', id);
        state.ids.add(id);
        if (t.tag === 'h2' || t.tag === 'h3') state.toc.push({ level: Number(t.tag[1]), id, text });
      }
      if (t.type === 'paragraph_open' && !state.lead) {
        state.lead = plain(s.tokens[i + 1]);
      }
      // A cell's alignment is a class, not an inline style (the CSP forbids it)
      if (t.type === 'th_open' || t.type === 'td_open') {
        const align = t.attrGet('style')?.match(/text-align:(\w+)/)?.[1];
        t.attrs = align ? [['class', `align-${align}`]] : null;
      }
      for (const c of t.children ?? []) {
        // A text made of an arrow alone (a cell reading "→ `{…}`") is no text to the contrast check: the arrow is decoration
        if (c.type === 'text' && /^\s*→\s*$/.test(c.content)) {
          c.type = 'html_inline';
          c.content = ' <span aria-hidden="true">→</span> ';
        }
        const key = c.type === 'link_open' ? 'href' : c.type === 'image' ? 'src' : null;
        if (!key) continue;
        const r = target(c.attrGet(key), published);
        c.attrSet(key, r.href);
        if (r.doc || r.href.startsWith('#')) state.links.push({ href: r.href, doc: r.doc, hash: r.hash ?? (r.href.startsWith('#') ? r.href.slice(1) : '') });
        if (c.type === 'image' && !c.content) warn(`${state.name}.md : image sans texte alternatif (${r.href})`);
      }
    }
  });
  // Hidden tokens render as nothing
  const base = md.renderer.render.bind(md.renderer);
  md.renderer.render = (tokens, ...rest) => base(tokens.filter((t) => !t.hidden), ...rest);
  return md;
}

const cut = (text, max) => {
  const flat = text.replace(/\s+/g, ' ').trim();
  if (flat.length <= max) return flat;
  const space = flat.lastIndexOf(' ', max - 1);
  return `${flat.slice(0, space > 0 ? space : max - 1)}…`;
};

let cache;
export function loadDocs() {
  if (cache) return cache;
  const names = readdirSync(DIR).filter((f) => f.endsWith('.md') && !EXCLUDED.has(f)).map((f) => f.slice(0, -3));
  const published = new Set(names);
  const docs = names.map((name) => {
    const source = readFileSync(`${DIR}${name}.md`, 'utf8');
    const state = { name, ids: new Set(), toc: [], links: [], h1: '', lead: '' };
    const body = renderer(published, state).render(source, {});
    if (!state.h1) warn(`${name}.md : pas de titre de niveau 1`);
    return {
      name,
      url: urlOfDoc(name),
      doc: true,
      lang: 'fr',
      key: 'docs',
      h1: state.h1 || name,
      // The home of /docs takes its title and description from the CMS (docs.json)
      title: name === 'README' ? typo(home.titre, 'fr') : `${cut(typo(state.h1 || name, 'fr'), 34)} | Documentation | ${site.name}`,
      description: name === 'README' ? typo(home.description, 'fr') : cut(state.lead || state.h1 || name, 155),
      body,
      toc: state.toc,
      source,
      state,
    };
  });
  const byName = Object.fromEntries(docs.map((d) => [d.name, d]));
  for (const d of docs) {
    for (const { doc, hash, href } of d.state.links) {
      if (!hash) continue;
      const ids = (doc ? byName[doc] : d).state.ids;
      if (ids.has(hash)) continue;
      warn(`${d.name}.md : ancre #${hash} introuvable dans ${doc ?? d.name}.md`);
      // The link keeps its page, without the anchor that no longer exists
      d.body = d.body.replaceAll(`href="${href}"`, `href="${doc ? urlOfDoc(doc) : d.url}"`);
    }
  }
  for (const d of docs) delete d.state;
  docs.sort((a, b) => (a.name === 'README' ? -1 : b.name === 'README' ? 1 : a.name.localeCompare(b.name, 'fr')));
  for (const w of warnings) console.warn(`[docs] ${w}`);
  return (cache = docs);
}
