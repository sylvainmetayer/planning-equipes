import { describe, expect, it } from 'vitest';
import { Animateur, Emplacement, Stand } from './models';
import {
  animateurName,
  animateurNames,
  emplacementNames,
  labelOf,
  labelsOf,
  standNames,
} from './reference-labels';

describe('reference labels', () => {
  it('names a stand by its nom and falls back to the id when unknown', () => {
    const noms = standNames([{ id: 'S1', nom: 'Bourse aux jeux' } as Stand]);
    expect(labelOf(noms, 'S1')).toBe('Bourse aux jeux');
    expect(labelOf(noms, 'S9')).toBe('S9');
  });

  it('keeps the id of a row whose name is blank rather than printing nothing', () => {
    const noms = standNames([{ id: 'S1', nom: '  ' } as Stand]);
    expect(labelOf(noms, 'S1')).toBe('S1');
  });

  it('names an emplacement by its nom', () => {
    const noms = emplacementNames([{ id: 'L1', nom: 'Kiosque' } as Emplacement]);
    expect(labelsOf(noms, ['L1', 'L2'])).toEqual(['Kiosque', 'L2']);
  });

  it('names an animateur « Prénom Nom », trimmed when one half is missing', () => {
    expect(animateurName({ prenom: 'Alice', nom: 'Martin' })).toBe('Alice Martin');
    expect(animateurName({ prenom: '', nom: 'Martin' })).toBe('Martin');
    const noms = animateurNames([
      { id: 'A1', prenom: 'Alice', nom: 'Martin' } as Animateur,
      { id: 'A2', prenom: '', nom: '' } as Animateur,
    ]);
    expect(labelsOf(noms, ['A1', 'A2', 'A3'])).toEqual(['Alice Martin', 'A2', 'A3']);
  });
});
