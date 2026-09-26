import { describe, expect, it } from 'vitest';
import { defaultExample, describeExample, groupExamples } from './exemples';

describe('describeExample', () => {
  it('names the realistic example as an organiser reads it', () => {
    const exemple = describeExample('festival-realiste-canicule.yaml');

    expect(exemple.libelle).toBe('Festival réaliste — canicule');
    expect(exemple.famille).toBe('decouvrir');
    expect(exemple.phrase).toContain('16 jour(s) · 65 stand(s) · 153 animateur(s)');
  });

  it('reads the size of a rung of the ladder from its name, and says what it adds', () => {
    const exemple = describeExample('gamme-05-2j-4stands-8animateurs-mineurs.yaml');

    expect(exemple.famille).toBe('tester');
    expect(exemple.libelle).toContain('Gamme 5');
    expect(exemple.libelle).toContain('mineurs');
    expect(exemple.phrase).toContain('2 jour(s) · 4 stand(s) · 8 animateur(s)');
  });

  it('files the extreme cases apart, with or without a size in their name', () => {
    expect(describeExample('extreme-10-sans-animateur.yaml')).toMatchObject({
      famille: 'extremes',
      libelle: 'Sans animateur',
    });
    expect(
      describeExample('extreme-06-7j-20stands-120animateurs-24h-sur-24.yaml').phrase,
    ).toContain('7 jour(s) · 20 stand(s) · 120 animateur(s)');
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
    'extreme-02-30j-150stands-1000animateurs-mois.yaml',
    'gamme-02-1j-2stands-4animateurs-relais-midi.yaml',
    'gamme-01-1j-2stands-3animateurs.yaml',
    'scenario-complet.yaml',
    'festival-realiste-canicule.yaml',
    'exemple-animateurs.csv',
  ];

  it('sorts the families to discover, to test, then the extremes, each by rank', () => {
    const groupes = groupExamples(names);

    expect(groupes.map((groupe) => groupe.titre)).toEqual([
      'Pour découvrir',
      'Pour tester un cas',
      'Extrêmes',
    ]);
    expect(groupes[0].exemples.map((exemple) => exemple.name)).toEqual([
      'festival-realiste-canicule.yaml',
      'scenario-complet.yaml',
    ]);
    expect(groupes[1].exemples.map((exemple) => exemple.name)).toEqual([
      'gamme-01-1j-2stands-3animateurs.yaml',
      'gamme-02-1j-2stands-4animateurs-relais-midi.yaml',
    ]);
  });

  it('leaves out what is not a scenario', () => {
    const all = groupExamples(names).flatMap((groupe) => groupe.exemples);

    expect(all.some((exemple) => exemple.name.endsWith('.csv'))).toBe(false);
  });

  it('offers the realistic example first, and the first to discover without it', () => {
    expect(defaultExample(groupExamples(names))).toBe('festival-realiste-canicule.yaml');
    expect(defaultExample(groupExamples(['gamme-01-1j-2stands-3animateurs.yaml']))).toBe(
      'gamme-01-1j-2stands-3animateurs.yaml',
    );
    expect(defaultExample([])).toBeNull();
  });
});
