// Where a version string leads on GitHub, and how two of them compare.
//
// `APP_VERSION` (generated, `scripts/generate-version.js`) is either the exact
// tag the build sits on — `v1.2.0`, the form `docs/versioning.md` prescribes —
// or the short commit SHA. The two do not land on the same page: a tag has a
// release (or, failing one, GitHub's own page for the bare tag, at the very
// same URL), a SHA only has its commit.

import { REPO_URL } from '../version';

/** `vX.Y.Z`, optionally followed by a pre-release suffix (`v1.2.0-rc.1`). */
const RELEASE_TAG = /^v(\d+)\.(\d+)\.(\d+)(?:-([0-9A-Za-z.-]+))?$/;

/** True when the version is a release tag rather than a commit SHA (or `unknown`). */
export function isReleaseTag(version: string): boolean {
  return RELEASE_TAG.test(version);
}

/**
 * The page a version links to.
 *
 * <p>A tag goes to `/releases/tag/<tag>`: the release when one was published
 * on it, and GitHub renders the tag itself under that same path when none was
 * — so the link never dead-ends, and it lands on the changelog whenever there
 * is one. `/commit/<tag>` would resolve too, but on a diff nobody reading
 * « which version am I on? » wants. A SHA keeps pointing at its commit.</p>
 */
export function versionUrl(version: string, repoUrl: string = REPO_URL): string {
  return isReleaseTag(version)
    ? `${repoUrl}/releases/tag/${version}`
    : `${repoUrl}/commit/${version}`;
}

/**
 * Semantic-version order on two release tags: negative when `a` is older than
 * `b`, zero when equal, positive when newer. A pre-release sorts before the
 * release it precedes (`v1.2.0-rc.1` < `v1.2.0`), which is all SemVer's rule
 * this needs — the pre-release identifiers themselves are compared as plain
 * strings. Anything that is not a release tag compares as older than any tag,
 * and equal to another non-tag.
 */
export function compareVersions(a: string, b: string): number {
  const left = RELEASE_TAG.exec(a);
  const right = RELEASE_TAG.exec(b);
  if (!left || !right) {
    return Number(Boolean(left)) - Number(Boolean(right));
  }
  for (let index = 1; index <= 3; index++) {
    const difference = Number(left[index]) - Number(right[index]);
    if (difference !== 0) {
      return difference;
    }
  }
  const leftPre = left[4];
  const rightPre = right[4];
  if (leftPre === rightPre) {
    return 0;
  }
  if (leftPre === undefined) {
    return 1;
  }
  if (rightPre === undefined) {
    return -1;
  }
  return leftPre < rightPre ? -1 : 1;
}
