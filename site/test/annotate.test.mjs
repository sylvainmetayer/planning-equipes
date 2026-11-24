// The transform that annotates built pages, and the match of the site search
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { annotate } from '../src/text.js';
import { match } from '../src/assets/search.js';

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

test('search: every word must appear, accents ignored, the excerpt has no Markdown', () => {
  const md = '# Le calcul\n\nLe planning respecte le [Code du travail](/code-du-travail/) et les **mineurs**.';
  assert.equal(match(md, ['travail', 'mineurs']), 'Le planning respecte le Code du travail et les mineurs.');
  assert.equal(match(md, ['periode']), null);
  assert.equal(match('Une période', ['periode']), 'Une période');
  assert.equal(match('## Les mineurs\n\nTexte', ['mineurs']), 'Les mineurs');
  assert.equal(match('- 2,5 Go de mémoire', ['memoire']), '2,5 Go de mémoire');
});
