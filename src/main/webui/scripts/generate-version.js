#!/usr/bin/env node
'use strict';

// Regenerated before every build/serve/test — do not rely on its output being committed.
const { execSync } = require('node:child_process');
const { writeFileSync } = require('node:fs');
const { join } = require('node:path');

function run(command) {
  return execSync(command, { encoding: 'utf8', stdio: ['ignore', 'pipe', 'ignore'] }).trim();
}

let version;
try {
  version = run('git describe --tags --exact-match HEAD');
} catch {
  try {
    version = run('git rev-parse --short HEAD');
  } catch {
    version = 'unknown';
  }
}

const content = `// Generated at build time by scripts/generate-version.js — do not edit.
export const APP_VERSION = '${version}';
export const REPO_URL = 'https://github.com/sylvainmetayer/planning-equipes';
`;

writeFileSync(join(__dirname, '../src/app/version.ts'), content);
console.log(`generate-version: APP_VERSION=${version}`);
