// Cloudflare Pages reads the Node version of the build from [vars] NODE_VERSION in
// wrangler.toml (and ignores the dashboard and mise.toml), so the three files that
// name it must agree, or the build runs on another Node than the one tested.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { root } from './helpers.mjs';

const read = (f) => readFileSync(join(root, f), 'utf8');

test('wrangler.toml, mise.toml and .node-version name the same full Node version', () => {
  const wrangler = /^NODE_VERSION\s*=\s*"([^"]+)"/m.exec(read('wrangler.toml'))?.[1];
  const mise = /^node\s*=\s*"([^"]+)"/m.exec(read('mise.toml'))?.[1];
  const file = read('.node-version').trim();
  assert.match(wrangler ?? '', /^\d+\.\d+\.\d+$/, 'NODE_VERSION must be a full version');
  assert.equal(mise, wrangler, 'mise.toml');
  assert.equal(file, wrangler, '.node-version');
});
