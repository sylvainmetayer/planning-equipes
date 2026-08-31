import { describe, expect, it } from 'vitest';
import { HelpSection, buildHelpSections, filterHelpSections } from './aide-content';
import { buildRaccourcisNavigation } from '../../core/keyboard-shortcuts';

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

  it('gives every link exactly one destination: an in-app route or an external href', () => {
    const links = sections.flatMap((section) => section.links);
    expect(links.length).toBeGreaterThan(0);
    for (const link of links) {
      if (link.route !== undefined) {
        expect(link.href).toBeUndefined();
        expect(link.route.startsWith('/')).toBe(true);
      } else {
        expect(link.href).toMatch(/^(https:|mailto:)/);
      }
    }
  });

  /**
   * The import screen deep-links to `/aide#import-csv-animateurs`. A link that
   * lands on nothing is worse than no link, so the anchor is asserted here and
   * not only in the E2E — and the section has to be its own, not a paragraph
   * lost inside « Données de référence », which is where it started.
   */
  it('gives the CSV import an anchor of its own, reachable by that exact id', () => {
    const section = sections.find((candidate) => candidate.id === 'import-csv-animateurs');
    expect(section).toBeDefined();
    const texte = textOf(section as HelpSection);
    // What the screen's own help text claims, said again where the link lands.
    expect(texte).toContain('« | »');
    expect(texte).toContain('typologie:REFERENT');
    expect(texte).toContain('date de naissance');
    expect(texte).toContain("Télécharger un fichier d'exemple");
    // And nothing else still explains the import from inside another section.
    const donnees = sections.find((candidate) => candidate.id === 'donnees');
    expect(textOf(donnees as HelpSection)).not.toContain('CSV UTF-8');
    // The two words a lost user types into the search box of this very page.
    expect(filterHelpSections(sections, 'CSV')).toContainEqual(section);
    expect(filterHelpSections(sections, 'tableur')).toContainEqual(section);
  });

  it('covers the solver configuration and how to read a score', () => {
    const ids = sections.map((section) => section.id);
    expect(ids).toContain('configuration-solveur');
    expect(ids).toContain('lire-les-resultats');
    expect(ids).toContain('tuner');
  });

  it('documents the keyboard shortcuts, and can be found by looking for them', () => {
    const section = sections.find((candidate) => candidate.id === 'raccourcis-clavier');
    expect(section).toBeDefined();
    const texte = textOf(section as HelpSection);
    // The entry points, spelled the way a reader would look for them.
    for (const raccourci of ['Ctrl+K', 'Ctrl+Entrée', 'Échap']) {
      expect(texte).toContain(raccourci);
    }
    // The `g` + letter table itself, not just a mention that one exists.
    for (const raccourci of buildRaccourcisNavigation()) {
      expect(texte).toContain(`g ${raccourci.touche}`);
    }
    // Both words a lost user types into the search box of this very page.
    expect(filterHelpSections(sections, 'raccourci')).toContainEqual(section);
    expect(filterHelpSections(sections, 'clavier')).toContainEqual(section);
  });

  it('spells out the edge cases of the manual adjustments, not just what the four types are', () => {
    const section = sections.find((candidate) => candidate.id === 'ajustements-manuels');
    expect(section).toBeDefined();
    const texte = textOf(section as HelpSection);

    // The four types, each with what it actually covers.
    for (const type of ['Indisponibilité forcée', 'Affectation forcée', 'Incompatibilité', 'Affinité']) {
      expect(texte).toContain(type);
    }
    // The traps: any-one-of semantics, slot-not-stand, the empty scope, the
    // deleted slot, what is refused and what deliberately is not.
    expect(texte).toContain('jamais un « tous »');
    expect(texte).toContain('Elle porte sur le créneau, pas sur le stand');
    expect(texte).toContain('créneau supprimé');
    expect(texte).toContain('refusés à l\'enregistrement');
    expect(texte).toContain('Passent donc délibérément');
    expect(texte).toContain('réenregistrer sous son propre identifiant');
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
