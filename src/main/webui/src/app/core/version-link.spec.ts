// Where a version leads, and how two of them order. A tag lands on its release
// page (the bare tag renders there too when no release was published), a
// commit on its diff: the footer link used to send `v1.0.0` to `/commit/`, a
// page that resolves but tells an operator nothing about the version.

import { describe, expect, it } from 'vitest';
import { compareVersions, isReleaseTag, versionUrl } from './version-link';

const REPO = 'https://github.com/sylvainmetayer/planning-equipes';

describe('isReleaseTag', () => {
  it('reconnaît un tag vX.Y.Z, avec ou sans suffixe de pré-version', () => {
    expect(isReleaseTag('v1.0.0')).toBe(true);
    expect(isReleaseTag('v12.3.45')).toBe(true);
    expect(isReleaseTag('v1.2.0-rc.1')).toBe(true);
  });

  it('refuse un SHA, une version sans v, et la valeur de secours', () => {
    expect(isReleaseTag('2f7a1c3')).toBe(false);
    expect(isReleaseTag('1.0.0')).toBe(false);
    expect(isReleaseTag('unknown')).toBe(false);
    expect(isReleaseTag('')).toBe(false);
  });
});

describe('versionUrl', () => {
  it('envoie un tag sur sa page de release', () => {
    expect(versionUrl('v1.0.0', REPO)).toBe(`${REPO}/releases/tag/v1.0.0`);
  });

  it('garde un commit sur sa page de commit', () => {
    expect(versionUrl('2f7a1c3', REPO)).toBe(`${REPO}/commit/2f7a1c3`);
  });
});

describe('compareVersions', () => {
  it('ordonne numériquement chaque composant, pas lexicalement', () => {
    expect(compareVersions('v1.10.0', 'v1.9.0')).toBeGreaterThan(0);
    expect(compareVersions('v2.0.0', 'v1.99.99')).toBeGreaterThan(0);
    expect(compareVersions('v1.0.1', 'v1.0.0')).toBeGreaterThan(0);
    expect(compareVersions('v1.0.0', 'v1.0.0')).toBe(0);
    expect(compareVersions('v1.0.0', 'v1.0.1')).toBeLessThan(0);
  });

  it('place une pré-version avant la version qu’elle annonce', () => {
    expect(compareVersions('v1.2.0-rc.1', 'v1.2.0')).toBeLessThan(0);
    expect(compareVersions('v1.2.0', 'v1.2.0-rc.1')).toBeGreaterThan(0);
    expect(compareVersions('v1.2.0-rc.1', 'v1.1.9')).toBeGreaterThan(0);
  });

  it('classe ce qui n’est pas un tag sous tout tag', () => {
    expect(compareVersions('2f7a1c3', 'v0.0.1')).toBeLessThan(0);
    expect(compareVersions('v0.0.1', 'unknown')).toBeGreaterThan(0);
    expect(compareVersions('2f7a1c3', 'unknown')).toBe(0);
  });
});
