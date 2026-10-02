#!/usr/bin/env node
'use strict';
// Prints `name@version`, one per line, for every dependency whose spec moved
// between two package.json files, at the lowest version the new spec allows:
// the version Renovate proposed (« ^11.0.0 » → 11.0.0), not the newest one the
// range would let `npm install` pick, which can be younger than the seven days
// Renovate waits (`minimumReleaseAge`). A spec that is not a plain version
// with an optional ^, ~ or = is printed as is.
//
//   node .github/scripts/renovate-proposed-versions.js <before.json> <after.json>
//
// Read by artefacts-renovate.yml.
const { readFileSync } = require('node:fs');

const [before, after] = process.argv.slice(2).map((file) => JSON.parse(readFileSync(file, 'utf8')));
for (const section of ['dependencies', 'devDependencies', 'optionalDependencies']) {
  for (const [name, spec] of Object.entries(after[section] ?? {})) {
    if (before[section]?.[name] === spec) {
      continue;
    }
    const plain = /^[\^~=]?v?(\d+\.\d+\.\d+(?:-[0-9A-Za-z.-]+)?)$/.exec(spec);
    console.log(plain ? `${name}@${plain[1]}` : `${name}@${spec}`);
  }
}
