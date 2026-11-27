import { describe, expect, it } from 'vitest';
import { classify, highlights, isPreRelease, NewsCommit, releases, toEntry } from './news';

function commit(subject: string, partial: Partial<NewsCommit> = {}): NewsCommit {
  return { subject, date: '2026-09-14', tag: null, ...partial };
}

describe('classify', () => {
  it('sorts a commit exactly as cliff.toml does', () => {
    expect(classify('feat(espace): ce qui a changé pour vous')).toBe('feature');
    expect(classify('fix(publication): numéroter le jour')).toBe('fix');
    expect(classify('perf(solveur): réduire le temps de construction')).toBe('perf');
    expect(classify('refactor(api): sortir la transport de la décision')).toBe('refactor');
    expect(classify('docs(aide): resserrer les deux guides')).toBe('docs');
  });

  it('files dependency bumps under their own heading, whatever their type', () => {
    expect(classify('fix(deps): update dependency quarkus to v3.2')).toBe('deps');
    expect(classify('chore(deps): update node to v24')).toBe('deps');
  });

  it('warns on a breaking marker and on the two reserved scopes', () => {
    expect(classify('feat(instantanes)!: un instantané dit s’il est à jour')).toBe('attention');
    expect(classify('feat(contraintes-legales): plancher de repos quotidien')).toBe('attention');
    // The scope wins over the type: a fix under it warns too.
    expect(classify('fix(contraintes-legales): compter la nuit une fois')).toBe('attention');
    expect(classify('feat(attention): importance d’une règle en trois positions')).toBe(
      'attention',
    );
  });

  it('keeps housekeeping and unconventional subjects off the screen', () => {
    for (const subject of [
      'chore: ménage',
      'ci: rétablir les déclencheurs',
      'test(solveur): attendre la ligne du job',
      'style: reformater',
      'build: passer à Java 25',
      'release: v1.2.0',
      'Corrige un bug sans préfixe',
    ]) {
      expect(classify(subject), subject).toBeNull();
    }
  });
});

describe('toEntry', () => {
  it('strips the prefix, keeps the scope and capitalises the wording', () => {
    expect(toEntry(commit('feat(espace): ce qui a changé pour vous'))).toEqual({
      heading: 'feature',
      scope: 'espace',
      summary: 'Ce qui a changé pour vous',
      date: '2026-09-14',
    });
  });

  it('accepts a commit with no scope', () => {
    expect(toEntry(commit('feat: figer l’en-tête des grilles'))?.scope).toBeNull();
  });

  it('keeps the wording of a breaking commit, marker aside', () => {
    expect(toEntry(commit('feat(instantanes)!: un instantané dit son âge'))).toEqual({
      heading: 'attention',
      scope: 'instantanes',
      summary: 'Un instantané dit son âge',
      date: '2026-09-14',
    });
  });

  it('shows nothing for a commit no heading keeps', () => {
    expect(toEntry(commit('ci: rétablir les déclencheurs'))).toBeNull();
  });
});

describe('releases', () => {
  it('opens an unreleased section with the commits that follow the last tag', () => {
    const decoupage = releases([
      commit('feat: après la version', { date: '2026-09-15' }),
      commit('fix: dans la version', { date: '2026-09-10', tag: 'v1.0.0' }),
      commit('feat: avant la version', { date: '2026-09-01' }),
    ]);

    expect(decoupage.map((release) => release.version)).toEqual([null, 'v1.0.0']);
    expect(decoupage[0].date).toBe('2026-09-15');
    // A release carries the history that preceded it, as `git cliff` reads it.
    expect(decoupage[1].count).toBe(2);
    expect(decoupage[1].date).toBe('2026-09-10');
  });

  it('groups a release by heading, in the order of the release notes', () => {
    const [release] = releases([
      commit('docs: une note'),
      commit('fix: une correction'),
      commit('feat!: une rupture'),
      commit('feat: une fonctionnalité'),
    ]);

    expect(release.groups.map((group) => group.heading)).toEqual([
      'attention',
      'feature',
      'fix',
      'docs',
    ]);
    expect(release.count).toBe(4);
  });

  it('drops a release whose every commit is housekeeping', () => {
    expect(releases([commit('ci: un déclencheur'), commit('chore: du ménage')])).toEqual([]);
  });

  it('answers on an empty history rather than failing', () => {
    expect(releases([])).toEqual([]);
  });
});

describe('highlights', () => {
  const list = releases([
    commit('feat(espace): ce qui a changé pour vous', { date: '2026-09-14', tag: 'v4.0.0-rc.1' }),
    commit('fix(publication): numéroter le jour', { date: '2026-09-13' }),
    commit('feat!: une rupture', { date: '2026-09-12' }),
    commit('fix: une correction seule', { date: '2026-09-01', tag: 'v3.5.0' }),
  ]);

  it('keeps the warnings and the new features, and drops the rest', () => {
    const [release] = highlights(list);
    expect(release.groups.map((group) => group.heading)).toEqual(['attention', 'feature']);
    expect(release.count).toBe(2);
  });

  it('leaves out a version that has no highlight', () => {
    expect(highlights(list).map((release) => release.version)).toEqual(['v4.0.0-rc.1']);
  });
});

describe('releases and pre-releases', () => {
  it('folds a pre-release into its stable version, and keeps one that has none', () => {
    const history = [
      commit('fix: après la finale', { tag: 'v4.0.0' }),
      commit('feat: pendant la préversion', { tag: 'v4.0.0-rc.1' }),
      commit('feat: avant', { tag: null }),
    ];
    const [release] = releases(history);
    expect(release.version).toBe('v4.0.0');
    expect(release.count).toBe(3);
    expect(releases(history.slice(1)).map((each) => each.version)).toEqual(['v4.0.0-rc.1']);
  });
});

describe('isPreRelease', () => {
  it('announces a version with a suffix as a pre-release', () => {
    expect(isPreRelease('v4.0.0-rc.1')).toBe(true);
    expect(isPreRelease('v4.0.0')).toBe(false);
    expect(isPreRelease(null)).toBe(false);
  });
});
