// Audits every page of the built site with Lighthouse, mobile and desktop, and
// fails under 95 in any category.
//
//   node scripts/check-lighthouse.mjs [dist-or-url]
//
// With a folder (default _site) it serves the build locally, text compressed
// like Cloudflare does. With a URL (e.g. `wrangler pages dev _site`, which also
// sends the _headers) it audits that origin. The pages are the ones of the
// sitemap. The browser is CHROME_PATH, or the one chrome-launcher finds.
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import lighthouse from 'lighthouse';
import desktop from 'lighthouse/core/config/desktop-config.js';
import * as chromeLauncher from 'chrome-launcher';
import { serveDist } from './lib/serve-dist.mjs';

const target = process.argv[2] ?? '_site';
const MIN = 95;
const CATEGORIES = ['performance', 'accessibility', 'best-practices', 'seo'];

let base = target;
let server;
if (!/^https?:/.test(target)) {
  server = serveDist(resolve(target));
  await new Promise((done) => server.listen(0, '127.0.0.1', done));
  base = `http://127.0.0.1:${server.address().port}`;
}
const distFolder = /^https?:/.test(target) ? '_site' : target;
const sitemap = readFileSync(resolve(distFolder, 'sitemap.xml'), 'utf8');
const pages = [...sitemap.matchAll(/<loc>https?:\/\/[^/]+([^<]*)<\/loc>/g)].map((m) => m[1]);

const chrome = await chromeLauncher.launch({ chromeFlags: ['--headless=new', '--no-sandbox'] });
const rows = [];
let failed = false;
try {
  for (const path of pages) {
    for (const [profile, config] of [['mobile', undefined], ['desktop', desktop]]) {
      // One audit at a time: Lighthouse measures performance on an idle browser
      const { lhr } = await lighthouse(`${base}${path}`, { port: chrome.port, output: 'json', logLevel: 'error', onlyCategories: CATEGORIES }, config); // NOSONAR
      if (lhr.runtimeError) throw new Error(`${path} : ${lhr.runtimeError.message}`);
      const scores = CATEGORIES.map((c) => Math.round((lhr.categories[c].score ?? 0) * 100));
      if (scores.some((s) => s < MIN)) failed = true;
      rows.push(`| ${path} | ${profile} | ${scores.join(' | ')} |`);
    }
  }
} finally {
  await chrome.kill();
  server?.close();
}
console.log('| Page | Profil | Performance | Accessibilité | Bonnes pratiques | SEO |\n| --- | --- | --- | --- | --- | --- |');
console.log(rows.join('\n'));
if (failed) {
  console.error(`\nUn score est sous ${MIN}`);
  process.exitCode = 1;
}
