#!/usr/bin/env node
'use strict';

/*
 * No spec silences the compiler with a cast — `as unknown as`, `as any`,
 * `as never`.
 *
 * Audit #392 (B8) counted 114 of them — the price of the fake services a
 * page spec builds by hand, `{ save: vi.fn() }` read back through
 * `as unknown as [string, Item]`, a shape the spec declares and the compiler
 * never holds against the service. The rule is in `src/main/webui/AGENTS.md`
 * (tests): a page spec fakes the `core/` services through
 * `core/testing/fake.ts`, typed on the class, and reaches a component's
 * protected member as `page['member']`, type-checked. The count went down to
 * zero under a ratchet; this keeps it there.
 *
 * The three spellings are one debt: `as any` turns the checking off where
 * `as unknown as` hides it, `as never` is assignable to every type, and none
 * of the three is held by the linter in a spec (`no-explicit-any` is off for
 * `*.spec.ts`, eslint.config.js). Refusing only the first would move the
 * casts to the other two instead of removing them.
 *
 * Searched in the whole text of every `*.spec.ts` under `src/`, comments
 * included, the words possibly on two lines and whole words only — neither
 * `has never` nor `as anything` is one — and a cast once: `as unknown as
 * never` is one `as unknown as`.
 *
 *   node scripts/check-unknown-casts.js
 */
const { readFileSync } = require('node:fs');
const { join, relative, sep } = require('node:path');
const { sourceFiles } = require('./source-files');

const WEBUI = join(__dirname, '..');
const CAST = /\bas\s+(?:unknown\s+as|any|never)\b/g;
/** How the messages name what is searched. */
const CASTS = '« as unknown as », « as any », « as never »';

const offenders = [];
const specs = sourceFiles(join(WEBUI, 'src'), ['.spec.ts'], { specs: true });
for (const file of specs) {
  const hits = (readFileSync(file, 'utf8').match(CAST) ?? []).length;
  if (hits > 0) offenders.push(`  ${relative(WEBUI, file).split(sep).join('/')} : ${hits}`);
}

if (offenders.length) {
  console.error(`check-unknown-casts : casts (${CASTS}) dans les specs :`);
  for (const line of offenders) console.error(line);
  console.error(
    '  → un faux service typé (core/testing/fake.ts : fakeOf, provideFake) plutôt qu’un cast ; ' +
      "un membre protégé du composant se lit `page['membre']`, typé",
  );
  process.exit(1);
}

console.log(`check-unknown-casts : aucun cast (${CASTS}) dans les ${specs.length} specs.`);
