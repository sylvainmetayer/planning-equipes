import { describe, expect, it } from 'vitest';
import { defaultExample, describeExample, groupExamples } from './exemples';

describe('describeExample', () => {
  it('names the realistic example as an organiser reads it', () => {
    const exemple = describeExample('festival-realiste-canicule.yaml');

    expect(exemple.libelle).toBe('Festival réaliste — canicule');
    expect(exemple.famille).toBe('decouvrir');
    expect(exemple.phrase).toContain('16 jour(s) · 65 stand(s) · 153 animateur(s)');
  });

  /** A file added to the folder without its line here still shows, and still without its extension. */
  it('names a file it does not know without showing the file', () => {
    const exemple = describeExample('nouveau-cas_de-test.yaml');

    expect(exemple.libelle).toBe('Nouveau cas de test');
    expect(exemple.libelle).not.toContain('.yaml');
  });
});

describe('groupExamples', () => {
  const names = [
    'scenario-continu.yaml',
    'scenario-avec-erreur-planning.yaml',
    'scenario-complet.yaml',
    'festival-realiste-canicule.yaml',
    'exemple-animateurs.csv',
  ];

  it('sorts the families to discover, then to test, each by rank', () => {
    const groupes = groupExamples(names);

    expect(groupes.map((groupe) => groupe.titre)).toEqual(['Pour découvrir', 'Pour tester un cas']);
    expect(groupes[0].exemples.map((exemple) => exemple.name)).toEqual([
      'festival-realiste-canicule.yaml',
      'scenario-complet.yaml',
    ]);
    expect(groupes[1].exemples.map((exemple) => exemple.name)).toEqual([
      'scenario-avec-erreur-planning.yaml',
      'scenario-continu.yaml',
    ]);
  });

  it('leaves out what is not a scenario', () => {
    const all = groupExamples(names).flatMap((groupe) => groupe.exemples);

    expect(all.some((exemple) => exemple.name.endsWith('.csv'))).toBe(false);
  });

  it('offers the realistic example first, and the first to discover without it', () => {
    expect(defaultExample(groupExamples(names))).toBe('festival-realiste-canicule.yaml');
    expect(defaultExample(groupExamples(['scenario-continu.yaml']))).toBe('scenario-continu.yaml');
    expect(defaultExample([])).toBeNull();
  });
});
