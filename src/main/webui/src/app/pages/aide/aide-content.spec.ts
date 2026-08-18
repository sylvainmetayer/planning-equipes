import { describe, expect, it } from 'vitest';
import { HelpSection, buildHelpSections, filterHelpSections } from './aide-content';

/** Every string of a section, so the tests can assert on its whole content. */
function textOf(section: HelpSection): string {
  return JSON.stringify(section);
}

describe('buildHelpSections', () => {
  const sections = buildHelpSections();

  it('gives every section a unique anchor id', () => {
    const ids = sections.map((section) => section.id);
    expect(new Set(ids).size).toBe(ids.length);
  });

  it('only links to routes declared with a leading slash', () => {
    const routes = sections.flatMap((section) => section.links.map((link) => link.route));
    expect(routes.length).toBeGreaterThan(0);
    expect(routes.every((route) => route.startsWith('/'))).toBe(true);
  });

  it('covers the solver configuration and how to read a score', () => {
    const ids = sections.map((section) => section.id);
    expect(ids).toContain('configuration-solveur');
    expect(ids).toContain('lire-les-resultats');
    expect(ids).toContain('tuner');
  });
});

describe('filterHelpSections', () => {
  const sections = buildHelpSections();

  it('returns everything for an empty or blank query', () => {
    expect(filterHelpSections(sections, '')).toHaveLength(sections.length);
    expect(filterHelpSections(sections, '   ')).toHaveLength(sections.length);
  });

  it('matches text hidden inside blocks, not just titles', () => {
    // "vacation" only ever appears in paragraph and definition bodies.
    const matches = filterHelpSections(sections, 'vacation');
    expect(matches.length).toBeGreaterThan(0);
    expect(matches.every((section) => textOf(section).includes('vacation'))).toBe(true);
    expect(matches.some((section) => !section.title.includes('vacation'))).toBe(true);
  });

  it('requires each term separately, not the whole phrase', () => {
    const matches = filterHelpSections(sections, 'score dur');
    expect(matches.length).toBeGreaterThan(0);
    expect(matches.every((section) => textOf(section).includes('score'))).toBe(true);
  });

  it('ignores case and diacritics', () => {
    expect(filterHelpSections(sections, 'DECOUPAGE')).toEqual(filterHelpSections(sections, 'découpage'));
  });

  it('requires every term of a multi-word query', () => {
    const both = filterHelpSections(sections, 'solveur zzzzz');
    expect(both).toHaveLength(0);
  });

  it('returns nothing rather than everything when no section matches', () => {
    expect(filterHelpSections(sections, 'zzzzz')).toHaveLength(0);
  });
});
