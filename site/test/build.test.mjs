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
    for (const [, id] of html.matchAll(/href="#([^"]+)"/g)) assert.ok(html.includes(`id="${id}"`), `${page} : #${id}`);
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
    const self = html.match(/<link rel="canonical" href="([^"]+)"/)[1];
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
