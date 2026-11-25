// Checks the built site (_site/, made by `npm test` before this file runs):
// internal links resolve, JSON-LD parses, hreflang is reciprocal, the sitemap
// lists every page and each page has a Markdown twin.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { existsSync, readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { files, root } from './helpers.mjs';

const dist = join(root, '_site');
const pages = files(dist, '.html');
const read = (f) => readFileSync(f, 'utf8');
const ORIGIN = 'https://planning.sylvain.dev';

const resolves = (path) => {
  const clean = decodeURIComponent(path.split(/[?#]/)[0]);
  const target = join(dist, clean);
  return existsSync(target) && !target.endsWith('/') ? true : existsSync(join(target, 'index.html'));
};

test('every internal link and resource resolves', () => {
  for (const page of pages) {
    const html = read(page);
    for (const [, url] of html.matchAll(/(?:href|src|poster)="(\/[^"]*)"/g)) assert.ok(resolves(url), `${page} : ${url}`);
    for (const [, url] of html.matchAll(/srcset="([^"]*)"/g)) for (const part of url.split(',')) assert.ok(resolves(part.trim().split(' ')[0]), `${page} : ${part}`);
    for (const [, id] of html.matchAll(/href="#([^"]+)"/g)) assert.ok(html.includes(`id="${decodeURIComponent(id)}"`), `${page} : #${id}`);
  }
});

test('every page has one title, one h1, one canonical and a parsable JSON-LD', () => {
  for (const page of pages.filter((p) => !p.endsWith('404.html'))) {
    const html = read(page);
    assert.equal(html.match(/<h1[ >]/g)?.length, 1, `${page} : h1`);
    assert.equal(html.match(/<link rel="canonical"/g)?.length, 1, `${page} : canonical`);
    const ld = html.match(/<script type="application\/ld\+json">\s*([\s\S]*?)<\/script>/);
    assert.ok(ld, `${page} : JSON-LD`);
    assert.ok(JSON.parse(ld[1])['@graph'].length >= 3);
  }
});

test('hreflang is reciprocal and the sitemap lists every page', () => {
  const sitemap = read(join(dist, 'sitemap.xml'));
  for (const page of pages.filter((p) => !p.endsWith('404.html'))) {
    const html = read(page);
    // A noindex page is listed neither in the sitemap nor in llms.txt
    if (html.includes('<meta name="robots" content="noindex">')) {
      const self = html.match(/<link rel="canonical" href="([^"]+)"/)[1];
      assert.ok(!sitemap.includes(`<loc>${self}</loc>`), `${self} noindex mais dans le sitemap`);
      assert.ok(!read(join(dist, 'llms.txt')).includes(self), `${self} noindex mais dans llms.txt`);
      continue;
    }
    const self = html.match(/<link rel="canonical" href="([^"]+)"/)[1];
    // The documentation exists in French only: no alternate, but the sitemap lists it
    if (self.startsWith(`${ORIGIN}/docs/`)) {
      assert.ok(!html.includes('hreflang="en" href'), `${self} : alternate anglaise`);
      assert.ok(sitemap.includes(`<loc>${self}</loc>`), `${self} absent du sitemap`);
      assert.ok(existsSync(join(dirname(page), 'index.md')), `${page} : jumeau Markdown`);
      continue;
    }
    const alt = html.match(/hreflang="(?:fr|en)" href="([^"]+)"/g).map((m) => m.match(/href="([^"]+)"/)[1]).find((u) => u !== self);
    assert.ok(alt, `${page} : alternate`);
    const other = read(join(dist, alt.replace(ORIGIN, ''), 'index.html'));
    assert.ok(other.includes(`href="${self}"`), `${alt} ne renvoie pas vers ${self}`);
    assert.ok(sitemap.includes(`<loc>${self}</loc>`), `${self} absent du sitemap`);
    assert.ok(existsSync(join(dirname(page), 'index.md')), `${page} : jumeau Markdown`);
  }
});

test('llms.txt and robots.txt', () => {
  assert.match(read(join(dist, 'llms.txt')), /^# Planning Équipes/);
  const robots = read(join(dist, 'robots.txt'));
  for (const bot of ['GPTBot', 'ClaudeBot', 'PerplexityBot']) assert.match(robots, new RegExp(`User-agent: ${bot}\\nAllow: /`));
  assert.match(robots, /Sitemap: https:\/\/planning\.sylvain\.dev\/sitemap\.xml/);
});

test('the content security policy allows the fonts and nothing remote but the beacon', () => {
  const headers = read(join(root, 'src/_headers'));
  assert.match(headers, /font-src 'self'/);
  assert.doesNotMatch(headers, /fonts\.(googleapis|gstatic)/);
});

test('the docs are published in place, without the decisions and the project memory', () => {
  const docs = join(dist, 'docs');
  assert.ok(existsSync(join(docs, 'index.html')) && existsSync(join(docs, 'architecture/index.html')));
  assert.ok(!existsSync(join(docs, 'decisions')) && !existsSync(join(docs, 'memoire-du-projet')));
  assert.ok(existsSync(join(docs, 'diagrammes/solve.svg')) && !existsSync(join(docs, 'diagrammes/solve.puml')));
  for (const page of pages.filter((p) => p.includes(`${join(dist, 'docs')}/`))) {
    const html = read(page);
    assert.match(html, /<html lang="fr">/, `${page} : lang`);
    // Anything that is not published points to GitHub, never to a dead /docs path
    assert.ok(!/href="(?:\.\.\/|\/docs\/(?:decisions|memoire))/.test(html), `${page} : lien relatif non réécrit`);
    assert.ok(!/href="[^"#:]*\.md[#"]/.test(html), `${page} : lien .md non réécrit`);
  }
  assert.ok(read(join(dist, 'llms.txt')).includes('/docs/architecture/index.md'), 'llms.txt sans la documentation');
});

test('every page has its own title', () => {
  const seen = new Map();
  for (const page of pages.filter((p) => !p.endsWith('404.html'))) {
    const title = read(page).match(/<title>([^<]*)<\/title>/)[1];
    assert.ok(!seen.has(title), `${page} et ${seen.get(title)} : même titre « ${title} »`);
    seen.set(title, page);
  }
});

test('the search reads a light index of the docs, and finds a page by a word of its headings', async () => {
  const { docMatch } = await import('../src/assets/search.js');
  const file = join(dist, 'docs/search.json');
  assert.ok(readFileSync(file).length < 100_000, 'index de recherche des docs trop lourd');
  const index = JSON.parse(read(file));
  const api = index['/docs/api/'];
  const [id, heading] = api.h.at(-1);
  const word = heading.normalize('NFD').replace(/\p{M}/gu, '').toLowerCase().split(/[^\p{L}\p{N}]+/u).filter((w) => w.length > 3).at(-1);
  const found = docMatch(api, [word]);
  assert.ok(found.excerpt, `#${id} introuvable par « ${word} »`);
  if (found.hash) assert.ok(read(join(dist, 'docs/api/index.html')).includes(`id="${decodeURIComponent(found.hash.slice(1))}"`), found.hash);
});
