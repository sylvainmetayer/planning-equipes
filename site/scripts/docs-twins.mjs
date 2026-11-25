// Compares an English docs page with its French twin, structure first: the English
// page must say the same thing in the same shape. Used by the build (docs.js) and by
// `node scripts/docs-twins.mjs <english page.md>...` while translating.
// Errors (the shape): headings, links, images, code fences, tables, lists.
// Warnings (the content): code spans and numbers, which a translation leaves alone.
import { readFileSync } from 'node:fs';
import MarkdownIt from 'markdown-it';
import { parse as yaml } from 'yaml';

const md = new MarkdownIt({ html: true, linkify: false });

export function split(raw) {
  const front = raw.match(/^---\n([\s\S]*?)\n---\n/);
  return { meta: front ? yaml(front[1]) : {}, body: front ? raw.slice(front[0].length) : raw };
}

const count = (items) => {
  const m = new Map();
  for (const i of items) m.set(i, (m.get(i) ?? 0) + 1);
  return m;
};

export function shapeOf(body) {
  const tokens = md.parse(body, {});
  const s = { headings: [], links: [], images: [], fences: [], tables: [], lists: [], code: [], numbers: [], blocks: 0 };
  let table = null;
  const walk = (t) => {
    if (t.type === 'heading_open') s.headings.push(t.tag);
    if (t.type === 'fence') s.fences.push(t.info.trim().split(/\s+/)[0]);
    if (t.type === 'table_open') s.tables.push((table = { rows: 0, cols: 0 }));
    if (t.type === 'tr_open' && table) table.rows++;
    if (t.type === 'th_open' && table && table.rows === 1) table.cols++;
    if (t.type === 'table_close') table = null;
    if (t.type === 'bullet_list_open') s.lists.push('ul');
    if (t.type === 'ordered_list_open') s.lists.push('ol');
    if (t.type === 'list_item_open') s.lists.push('li');
    if (t.type === 'blockquote_open') s.blocks++;
    if (t.type === 'link_open') s.links.push(t.attrGet('href'));
    if (t.type === 'image') s.images.push(t.attrGet('src'));
    if (t.type === 'code_inline') s.code.push(t.content);
    if (t.type === 'text') s.numbers.push(...(t.content.match(/\d+/g) ?? []));
    t.children?.forEach(walk);
  };
  tokens.forEach(walk);
  return s;
}

const seq = (a) => JSON.stringify(a);

// The problems of an English page against its French twin: [errors, warnings]
export function compare(frBody, enBody) {
  const f = shapeOf(frBody);
  const e = shapeOf(enBody);
  const errors = [];
  const warnings = [];
  for (const k of ['headings', 'images', 'fences', 'lists']) if (seq(f[k]) !== seq(e[k])) errors.push(`${k} differ: FR ${f[k].length} / EN ${e[k].length}`);
  if (seq(f.tables) !== seq(e.tables)) errors.push(`tables differ (rows/columns): FR ${seq(f.tables)} / EN ${seq(e.tables)}`);
  if (f.blocks !== e.blocks) errors.push(`blockquotes differ: FR ${f.blocks} / EN ${e.blocks}`);
  if (seq(f.links) !== seq(e.links)) {
    const n = Math.max(f.links.length, e.links.length);
    for (let i = 0; i < n; i++) {
      if (f.links[i] !== e.links[i]) {
        errors.push(`link #${i + 1}: FR ${f.links[i]} / EN ${e.links[i]} (link targets are kept byte for byte)`);
        break;
      }
    }
    if (f.links.length !== e.links.length) errors.push(`links: FR ${f.links.length} / EN ${e.links.length}`);
  }
  for (const [name, a, b] of [['code span', f.code, e.code], ['number', f.numbers, e.numbers]]) {
    const ca = count(a);
    const cb = count(b);
    const only = (x, y) => [...x].filter(([k, n]) => n > (y.get(k) ?? 0)).map(([k]) => k);
    const missing = only(ca, cb);
    const extra = only(cb, ca);
    if (missing.length) warnings.push(`${name}s in FR, not (as often) in EN: ${missing.slice(0, 12).join(' | ')}`);
    if (extra.length) warnings.push(`${name}s in EN, not (as often) in FR: ${extra.slice(0, 12).join(' | ')}`);
  }
  return [errors, warnings];
}

if (process.argv[1]?.endsWith('docs-twins.mjs')) {
  const root = new URL('../src/', import.meta.url);
  let bad = 0;
  for (const file of process.argv.slice(2)) {
    const en = split(readFileSync(file, 'utf8'));
    if (!en.meta.twin) {
      console.log(`${file}: no \`twin:\` in the front matter`);
      bad++;
      continue;
    }
    const fr = split(readFileSync(new URL(`docs/${en.meta.twin}.md`, root), 'utf8'));
    const [errors, warnings] = compare(fr.body, en.body);
    for (const m of errors) console.log(`ERROR ${file}: ${m}`);
    for (const m of warnings) console.log(`warn  ${file}: ${m}`);
    if (!errors.length && !warnings.length) console.log(`ok    ${file}`);
    bad += errors.length;
  }
  process.exit(bad ? 1 : 0);
}
