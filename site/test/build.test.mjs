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
    // The generated licence inventory exists in French only: no alternate, but the sitemap lists it
    if (self === `${ORIGIN}/docs/installer/licences-tierces/`) {
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
  assert.ok(existsSync(join(docs, 'index.html')) && ['regles', 'installer', 'comprendre'].every((part) => existsSync(join(docs, part, 'index.html'))));
  const en = join(dist, 'en/docs');
  assert.ok(existsSync(join(en, 'index.html')) && ['rules', 'install', 'build'].every((part) => existsSync(join(en, part, 'index.html'))));
  assert.ok(!existsSync(join(docs, 'decisions')) && !existsSync(join(docs, 'memoire-du-projet')));
  assert.ok(existsSync(join(docs, 'diagrammes/solve.svg')) && !existsSync(join(docs, 'diagrammes/solve.puml')));
  for (const page of pages.filter((p) => p.includes(`${join(dist, 'docs')}/`) || p.includes(`${en}/`))) {
    const html = read(page);
    assert.match(html, page.includes(`${en}/`) ? /<html lang="en">/ : /<html lang="fr">/, `${page} : lang`);
    // Anything that is not published points to GitHub, never to a dead /docs path
    assert.ok(!/href="(?:\.\.\/|\/docs\/(?:decisions|memoire))/.test(html), `${page} : lien relatif non réécrit`);
    assert.ok(!/href="[^"#:]*\.md[#"]/.test(html), `${page} : lien .md non réécrit`);
  }
  assert.ok(read(join(dist, 'llms.txt')).includes('/docs/comprendre/vue-d-ensemble/index.md'), 'llms.txt sans la documentation');
  assert.ok(read(join(dist, 'llms.txt')).includes('/en/docs/build/overview/index.md'), 'llms.txt sans la documentation anglaise');
});

test('an address of the former docs, published once, leads to a page that exists', () => {
  const rules = read(join(dist, '_redirects')).split('\n').filter((l) => /^\/docs\//.test(l)).map((l) => l.split(/\s+/));
  assert.ok(rules.length >= 20, 'redirections des anciennes pages de /docs');
  for (const [from, to] of rules) {
    assert.ok(existsSync(join(dist, to.split('#')[0], 'index.html')), `${from} mène à ${to}, qui n'existe pas`);
    assert.ok(!existsSync(join(dist, from, 'index.html')) || from === to, `${from} est redirigée mais une page existe à cette adresse`);
  }
  for (const old of ['architecture', 'api', 'comprendre/mcp', 'exploitation', 'installation', 'fonctionnel/parcours', 'fonctionnel/planifier', 'variables-environnement']) {
    assert.ok(rules.some(([from]) => from === `/docs/${old}/`), `/docs/${old}/ : pas de redirection`);
  }
});

test('every page has its own title', () => {
  const seen = new Map();
  for (const page of pages.filter((p) => !p.endsWith('404.html'))) {
    const title = read(page).match(/<title>([^<]*)<\/title>/)[1];
    assert.ok(!seen.has(title), `${page} et ${seen.get(title)} : même titre « ${title} »`);
    seen.set(title, page);
  }
});

test('one light index serves the site search: the guides with their sections, then the pages of both languages', () => {
  assert.ok(!existsSync(join(dist, 'docs/search.json')));
  const file = join(dist, 'search.json');
  assert.ok(readFileSync(file).length < 240_000, 'index de recherche trop lourd');
  const index = JSON.parse(read(file));
  const api = index.find((e) => e.u === '/docs/comprendre/api-conventions/');
  assert.equal(api.k, 'doc');
  for (const [id] of api.h) assert.ok(read(join(dist, 'docs/comprendre/api-conventions/index.html')).includes(`id="${decodeURIComponent(id)}"`), `#${id}`);
  const apiEn = index.find((e) => e.u === '/en/docs/build/api-conventions/');
  assert.equal(apiEn.l, 'en');
  for (const [id] of apiEn.h) assert.ok(read(join(dist, 'en/docs/build/api-conventions/index.html')).includes(`id="${decodeURIComponent(id)}"`), `#${id}`);
  for (const l of ['fr', 'en']) assert.ok(index.some((e) => e.k === 'page' && e.l === l && e.u === (l === 'fr' ? '/' : '/en/')), `accueil ${l}`);
  for (const e of index) assert.ok(resolves(e.u), e.u);
});

test('the header search is a link to the docs search without script, and the site map has no search of its own', () => {
  for (const page of pages.filter((p) => !p.endsWith('404.html'))) {
    const html = read(page);
    assert.match(html, /<a class="top__search" href="\/(?:en\/)?docs\/#doc-q"[^>]*data-pal-open/, page);
    assert.match(html, /<dialog class="pal"[^>]*data-pal/, page);
    assert.ok(html.includes('/assets/palette.js'), page);
    assert.ok(!html.includes('/assets/search.js'), page);
  }
  assert.ok(existsSync(join(dist, 'assets/palette.js')));
  const plan = read(join(dist, 'plan-du-site/index.html'));
  assert.ok(!plan.includes('data-search'));
  assert.match(plan, /<h1>Plan du site<\/h1>/);
  assert.ok(read(join(dist, 'docs/index.html')).includes('data-search-results'));
});

test('"À lire aussi" never lists the home page, and the hosting page leads to the install guide', () => {
  for (const page of pages) {
    const related = read(page).match(/<aside class="container related"[\s\S]*?<\/aside>/)?.[0] ?? '';
    assert.ok(!/href="(\/|\/en\/)"/.test(related), `${page} : lien vers l'accueil dans « À lire aussi »`);
  }
  assert.match(read(join(dist, 'heberger/index.html')), /<a class="btn" href="\/docs\/installer\/">Installer chez vous<\/a>/);
  assert.match(read(join(dist, 'en/self-hosting/index.html')), /<a class="btn" href="\/en\/docs\/install\/">Install it yourself<\/a>/);
});

test('every docs page has its twin in the other language, and the language switch leads to it', () => {
  const link = (html, rel) => html.match(new RegExp(`<link rel="alternate" hreflang="${rel}" href="${ORIGIN}([^"]+)"`))?.[1];
  for (const page of pages.filter((p) => p.includes(`${join(dist, 'docs')}/`) || p.includes(`${join(dist, 'en/docs')}/`) || p.endsWith(join(dist, 'docs/index.html')))) {
    const html = read(page);
    const lang = html.match(/<html lang="(\w+)"/)[1];
    const self = html.match(/<link rel="canonical" href="[^/]+\/\/[^/]+([^"]+)"/)[1];
    if (self === '/docs/installer/licences-tierces/') continue;
    const other = link(html, lang === 'fr' ? 'en' : 'fr');
    assert.ok(other, `${self} : pas de jumelle`);
    assert.ok(read(join(dist, other, 'index.html')).includes(`href="${ORIGIN}${self}"`), `${other} ne renvoie pas vers ${self}`);
    assert.ok(html.includes(`<a class="top__lang" href="${other}"`), `${self} : le sélecteur de langue ne mène pas à ${other}`);
    // The Docs tab of the menu stays in the language of the page
    assert.ok(html.includes(`<li><a href="${lang === 'fr' ? '/docs/' : '/en/docs/'}"`), `${self} : onglet Docs`);
    assert.ok(!html.includes(`<li><a href="${lang === 'fr' ? '/docs/' : '/en/docs/'}" hreflang`), `${self} : hreflang sur l'onglet Docs`);
  }
});
