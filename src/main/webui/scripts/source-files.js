'use strict';

const { readdirSync } = require('node:fs');
const { join } = require('node:path');
const { byCodeUnit } = require('./code-unit-order');

/*
 * Every file under `dir`, at any depth, whose name ends with one of
 * `extensions` — the specs left out unless `specs` is set — as absolute
 * paths in code-unit order, so a report does not move with the file system's
 * listing order.
 */
function sourceFiles(dir, extensions, { specs = false } = {}) {
  return readdirSync(dir, { recursive: true, withFileTypes: true })
    .filter(
      (entry) =>
        entry.isFile() &&
        extensions.some((extension) => entry.name.endsWith(extension)) &&
        (specs || !entry.name.endsWith('.spec.ts')),
    )
    .map((entry) => join(entry.parentPath, entry.name))
    .sort(byCodeUnit);
}

module.exports = { sourceFiles };
