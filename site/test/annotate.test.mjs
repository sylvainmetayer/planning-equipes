// The transform that annotates built pages, and the match of the site search
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { annotate } from '../src/text.js';
import { search } from '../src/assets/palette.js';

const ABBR = [{ sigle: 'PDF', sens: 'Portable Document Format' }, { sigle: 'IA', sens: 'intelligence "artificielle"' }];

test('only the first abbreviation of <main> gets its meaning, never in an attribute or outside main', () => {
  const html = '<html lang="fr"><body><header>PDF</header><main><img alt="PDF"><p>Un PDF, un autre PDF, PDFs, IA.</p><code>IA</code></main></body></html>';
  const out = annotate(html, ABBR, '(site externe)');
  assert.equal(out.match(/<abbr/g).length, 2);
  assert.ok(out.includes('<header>PDF</header>'));
  assert.ok(out.includes('alt="PDF"'));
  assert.ok(out.includes('Un <abbr title="Portable Document Format">PDF</abbr>, un autre PDF, PDFs'));
  assert.ok(out.includes('<abbr title="intelligence &quot;artificielle&quot;">IA</abbr>.'));
});

test('a link leaving the site says so to screen readers, an internal one does not', () => {
  const html = '<body><a href="https://github.com/x">Code</a><a href="https://planning.sylvain.dev/">Accueil</a><a href="/contact/">Contact</a></body>';
  const out = annotate(html, [], '(site externe)');
  assert.equal(out, '<body><a href="https://github.com/x">Code<span class="visually-hidden"> (site externe)</span></a><a href="https://planning.sylvain.dev/">Accueil</a><a href="/contact/">Contact</a></body>');
});

test('palette: every word must appear, accents ignored; guides, then sections, then pages, all of the language', () => {
  const index = [
    { k: 'doc', l: 'fr', u: '/docs/api/', t: 'API REST', d: 'Les invariants.', h: [['jobs-de-résolution', 'Jobs de résolution']] },
    { k: 'doc', l: 'fr', u: '/docs/solveur/', t: 'Le solveur', d: 'Résolution du planning.', h: [] },
    { k: 'page', l: 'fr', u: '/le-calcul/', t: 'Le calcul', d: 'La résolution, expliquée.' },
    { k: 'doc', l: 'en', u: '/en/docs/solver/', t: 'The solver', d: 'Resolution of the schedule.', h: [] },
    { k: 'page', l: 'en', u: '/en/the-calculation/', t: 'The calculation', d: 'Resolution, explained.' },
  ];
  assert.deepEqual(
    search(index, 'resolution', 'fr').map((r) => [r.kind, r.href]),
    [['guide', '/docs/solveur/'], ['section', '/docs/api/#jobs-de-résolution'], ['page', '/le-calcul/']],
  );
  assert.deepEqual(search(index, 'resolution', 'en').map((r) => r.href), ['/en/docs/solver/', '/en/the-calculation/']);
  assert.deepEqual(search(index, 'api invariants', 'fr').map((r) => r.href), ['/docs/api/']);
  assert.deepEqual(search(index, 'periode', 'fr'), []);
  assert.deepEqual(search(index, '  ', 'fr'), []);
});
