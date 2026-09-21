// What the git history means, read from the commit subjects the build collected
// (`scripts/generate-news.js`). No Angular here beyond the labels, so the
// classification is unit-tested without rendering anything.
//
// THE RULES BELOW MIRROR `cliff.toml`, AND THAT IS THE POINT: the notes a
// release publishes and this screen answer the same question, so they may not
// sort a commit under two different headings. A parser added there needs its
// line here — `newsHeadings` and its spec are what make that visible.

import { correspondAuFiltre } from '../../core/text-filter';

/** One commit as the build read it. Written by `scripts/generate-news.js`. */
export interface NewsCommit {
  /** Commit subject, prefix included: `feat(espace): …`. */
  readonly subject: string;
  /** Authoring day, `YYYY-MM-DD`. */
  readonly date: string;
  /** The release this commit was tagged with, `vX.Y.Z`, or null. */
  readonly tag: string | null;
}

/**
 * The headings of `cliff.toml`, in the order it sorts them. `attention` is what
 * a release files under « ⚠️ Attention »: a breaking change, or a legal
 * constraint that can turn a solvable planning infeasible.
 */
export type NewsHeading = 'attention' | 'feature' | 'fix' | 'perf' | 'refactor' | 'deps' | 'docs';

export const NEWS_HEADINGS: readonly NewsHeading[] = [
  'attention',
  'feature',
  'fix',
  'perf',
  'refactor',
  'deps',
  'docs',
];

/** One line of the screen: what changed, on what, and when. */
export interface NewsEntry {
  readonly heading: NewsHeading;
  /** The conventional scope (`espace`, `contraintes`…), or null when the commit carries none. */
  readonly scope: string | null;
  /** The subject without its conventional prefix, first letter capitalised. */
  readonly summary: string;
  readonly date: string;
}

/** The entries of one release that share a heading. */
export interface NewsGroup {
  readonly heading: NewsHeading;
  readonly entries: readonly NewsEntry[];
}

/** A released version, or the commits that follow the last tag (`version: null`). */
export interface NewsRelease {
  readonly version: string | null;
  /** Day of the release — of its tagged commit, or of the newest commit still untagged. */
  readonly date: string;
  readonly groups: readonly NewsGroup[];
  readonly count: number;
}

/**
 * Subject → heading, in the order of `cliff.toml`'s `commit_parsers`. Anything
 * matching none of them is housekeeping an operator has nothing to do with
 * (`chore`, `ci`, `test`, `style`, `build`, `release`) or an unconventional
 * subject, and `filter_unconventional` drops both rather than guessing.
 */
const PARSERS: readonly { readonly pattern: RegExp; readonly heading: NewsHeading }[] = [
  // A `!` is the breaking marker, and the scope below warns without bumping the
  // major: both file the same heading. Before `^feat`, so they win over the type.
  { pattern: /^[a-z]+(\(.*\))?!:/, heading: 'attention' },
  { pattern: /^[a-z]+\(contraintes-legales\)/, heading: 'attention' },
  { pattern: /^feat/, heading: 'feature' },
  { pattern: /^fix\(deps\)/, heading: 'deps' },
  { pattern: /^fix/, heading: 'fix' },
  { pattern: /^perf/, heading: 'perf' },
  { pattern: /^refactor/, heading: 'refactor' },
  { pattern: /^chore\(deps\)/, heading: 'deps' },
  { pattern: /^docs/, heading: 'docs' },
];

/** The conventional prefix of a subject: type, optional scope, optional `!`. */
const PREFIX = /^([a-z]+)(?:\(([^)]*)\))?(!)?:\s*/;

/** Which heading a commit falls under, or null when the screen does not show it. */
export function classify(subject: string): NewsHeading | null {
  return PARSERS.find(({ pattern }) => pattern.test(subject))?.heading ?? null;
}

/**
 * The line a reader gets from a commit subject: prefix off, first letter up —
 * the same shape `cliff.toml`'s body template produces for a release.
 */
export function toEntry(commit: NewsCommit): NewsEntry | null {
  const heading = classify(commit.subject);
  if (!heading) {
    return null;
  }
  const prefix = PREFIX.exec(commit.subject);
  const summary = commit.subject.slice(prefix?.[0].length ?? 0);
  return {
    heading,
    scope: prefix?.[2] || null,
    summary: summary.charAt(0).toUpperCase() + summary.slice(1),
    date: commit.date,
  };
}

/**
 * The history cut into releases, newest first.
 *
 * <p>Commits newer than the last tag open an unreleased section (`version:
 * null`); a tagged commit starts its own, which then carries every older commit
 * until the previous tag — so the first release published holds the history
 * that preceded it, as `git cliff` reads it too.</p>
 */
export function releases(commits: readonly NewsCommit[]): NewsRelease[] {
  const sections: { version: string | null; commits: NewsCommit[] }[] = [];
  let current: { version: string | null; commits: NewsCommit[] } = { version: null, commits: [] };
  for (const commit of commits) {
    if (commit.tag) {
      sections.push(current);
      current = { version: commit.tag, commits: [] };
    }
    current.commits.push(commit);
  }
  sections.push(current);

  return sections
    .map((section) => {
      const entries = section.commits
        .map(toEntry)
        .filter((entry): entry is NewsEntry => entry !== null);
      const groups = NEWS_HEADINGS.map((heading) => ({
        heading,
        entries: entries.filter((entry) => entry.heading === heading),
      })).filter((group) => group.entries.length > 0);
      return {
        version: section.version,
        date: section.commits[0]?.date ?? '',
        groups,
        count: entries.length,
      };
    })
    .filter((release) => release.count > 0);
}

/**
 * The releases a reader asked for: the chosen heading, and a free-text search
 * over what is on screen — the wording, its scope and the version. A release
 * left with no entry disappears with them.
 */
export function filterReleases(
  list: readonly NewsRelease[],
  heading: NewsHeading | 'all',
  search: string,
): NewsRelease[] {
  return list
    .map((release) => {
      const groups = release.groups
        .filter((group) => heading === 'all' || group.heading === heading)
        .map((group) => ({
          heading: group.heading,
          entries: group.entries.filter((entry) =>
            correspondAuFiltre(search, [entry.summary, entry.scope ?? '', release.version ?? '']),
          ),
        }))
        .filter((group) => group.entries.length > 0);
      return {
        version: release.version,
        date: release.date,
        groups,
        count: groups.reduce((total, group) => total + group.entries.length, 0),
      };
    })
    .filter((release) => release.count > 0);
}

/** Reads a heading off the URL, falling back to « everything » on anything unknown. */
export function readHeading(value: string | null): NewsHeading | 'all' {
  return NEWS_HEADINGS.includes(value as NewsHeading) ? (value as NewsHeading) : 'all';
}

/**
 * What a heading reads as on screen. A function, never a constant: `$localize`
 * only resolves once `main.ts` has loaded the catalog, and a map built at module
 * scope would freeze the French source in an English session.
 */
export function headingLabel(heading: NewsHeading): string {
  switch (heading) {
    case 'attention':
      return $localize`:@@nouveautes.heading.attention:À surveiller`;
    case 'feature':
      return $localize`:@@nouveautes.heading.feature:Nouveautés`;
    case 'fix':
      return $localize`:@@nouveautes.heading.fix:Corrections`;
    case 'perf':
      return $localize`:@@nouveautes.heading.perf:Performances`;
    case 'refactor':
      return $localize`:@@nouveautes.heading.refactor:Réusinages`;
    case 'deps':
      return $localize`:@@nouveautes.heading.deps:Dépendances`;
    case 'docs':
      return $localize`:@@nouveautes.heading.docs:Documentation`;
  }
}

/** The icon each heading carries; all seven exist in the embedded icon font. */
export function headingIcon(heading: NewsHeading): string {
  switch (heading) {
    case 'attention':
      return 'warning';
    case 'feature':
      return 'star';
    case 'fix':
      return 'build';
    case 'perf':
      return 'speed';
    case 'refactor':
      return 'construction';
    case 'deps':
      return 'extension';
    case 'docs':
      return 'menu_book';
  }
}
