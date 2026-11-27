#!/usr/bin/env node
'use strict';

// Regenerated before every build/serve/test, like scripts/generate-version.js —
// do not rely on its output being committed.
//
// Writes the git history the « Nouveautés » screen reads: one line per commit,
// newest first, carrying the release tag when a commit has one. Only the facts
// git holds are collected here. What a commit *means* — which heading it falls
// under, and how its wording reads once the conventional prefix is off — is
// decided in `pages/nouveautes/news.ts`, in typed and unit-tested code: a
// classification rule nobody can test is a rule that drifts from `cliff.toml`.
//
// A build with no git history (no `.git`, a source export) writes an empty list
// rather than failing: the screen then says so, and everything else builds.
const { execFileSync } = require('node:child_process');
const { mkdirSync, writeFileSync } = require('node:fs');
const { dirname, join } = require('node:path');

/** Field separator: a unit separator cannot appear in a commit subject. */
const SEP = '\u001f';

/** The release tags that structure the screen: stable ones (as in `cliff.toml`) and their `-rc.N` pre-releases. */
const TAG_PATTERN = /^v\d+\.\d+\.\d+(-rc\.\d+)?$/;

const TARGET = join(__dirname, '../src/app/pages/nouveautes/news-data.ts');

function git(args) {
  return execFileSync('git', args, {
    encoding: 'utf8',
    stdio: ['ignore', 'pipe', 'ignore'],
  }).trim();
}

function lines(output) {
  return output ? output.split('\n') : [];
}

/**
 * Numeric comparison of two tags: `v1.10.0` is above `v1.9.0`, which a string
 * sort denies, and a pre-release (`-rc.N`) sits below the stable version of the same number.
 */
function compareVersions(left, right) {
  const parts = (tag) => {
    const [core, rc] = tag.slice(1).split('-rc.');
    return [...core.split('.').map(Number), rc === undefined ? Infinity : Number(rc)];
  };
  const [a, b] = [parts(left), parts(right)];
  return a[0] - b[0] || a[1] - b[1] || a[2] - b[2] || (a[3] === b[3] ? 0 : a[3] < b[3] ? -1 : 1);
}

/**
 * The release tag of every tagged commit. An annotated tag points at a tag
 * object, so the commit is read from `*objectname`, empty for a lightweight
 * one. Two tags on one commit keep the greater version: a release and its
 * re-tag say the same thing, and the screen has one heading to show.
 */
function tagsByCommit() {
  const byCommit = new Map();
  const format = `%(refname:strip=2)${SEP}%(objectname)${SEP}%(*objectname)`;
  for (const line of lines(git(['tag', '--list', `--format=${format}`]))) {
    const [name, objectName, dereferenced] = line.split(SEP);
    if (!TAG_PATTERN.test(name)) {
      continue;
    }
    const commit = dereferenced || objectName;
    const known = byCommit.get(commit);
    if (!known || compareVersions(name, known) > 0) {
      byCommit.set(commit, name);
    }
  }
  return byCommit;
}

function history() {
  const tags = tagsByCommit();
  const format = `%H${SEP}%ad${SEP}%s`;
  return lines(git(['log', '--no-merges', '--date=short', `--pretty=format:${format}`]))
    .map((line) => line.split(SEP))
    .filter(([, , subject]) => subject)
    .map(([sha, date, subject]) => ({ subject, date, tag: tags.get(sha) ?? null }));
}

let commits;
try {
  commits = history();
} catch {
  commits = [];
}

// JSON rather than a hand-rolled literal: a commit subject carries apostrophes
// and quotes, and `JSON.stringify` is the one escaping nobody has to review.
// The file is `.prettierignore`d, being generated.
const content = `// Generated at build time by scripts/generate-news.js — do not edit.
import type { NewsCommit } from './news';

/** The repository's commits, newest first. Empty when the build saw no git history. */
export const NEWS_COMMITS: readonly NewsCommit[] = ${JSON.stringify(commits, null, 2)};
`;

// The folder is created rather than assumed: a fresh checkout may not have it yet.
mkdirSync(dirname(TARGET), { recursive: true });
writeFileSync(TARGET, content);
console.log(`generate-news: ${commits.length} commits`);
