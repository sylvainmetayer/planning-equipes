import { describe, expect, it } from 'vitest';
import { PersonneConformite } from '../../core/models';
import { hasFindings, hoursLabel, minorsOf, restLabel } from './conformite';

function person(over: Partial<PersonneConformite>): PersonneConformite {
  return {
    animateurId: 'A1',
    nom: 'Ada',
    mineur: false,
    heuresParSemaine: {},
    heuresTotal: 0,
    plafondHebdomadaire: 48,
    semainesEnDepassement: [],
    heuresDimanche: 0,
    heuresFerie: 0,
    heuresNuit: 0,
    ecartsDetail: [],
    ...over,
  };
}

describe('conformite', () => {
  it('writes hours with a comma and at most two decimals', () => {
    expect(hoursLabel(38.5)).toContain('38,5');
    expect(hoursLabel(12)).toContain('12');
    expect(hoursLabel(1.234)).toContain('1,23');
  });

  it('picks the minors out', () => {
    const people = [person({ animateurId: 'A' }), person({ animateurId: 'B', mineur: true })];
    expect(minorsOf(people).map((p) => p.animateurId)).toEqual(['B']);
  });

  it('has findings for a week over the ceiling or a breach, and none otherwise', () => {
    expect(hasFindings(person({}))).toBe(false);
    expect(hasFindings(person({ semainesEnDepassement: ['2026-W34'] }))).toBe(true);
    expect(hasFindings(person({ ecartsDetail: [{ regle: 'r', libelle: 'l', texte: 't' }] }))).toBe(
      true,
    );
  });

  it('writes the shortest rest, or a dash under two worked days', () => {
    expect(restLabel(person({ reposQuotidienMinHeures: 13.5 }))).toContain('13,5');
    expect(restLabel(person({ reposQuotidienMinHeures: null }))).toBe('—');
    expect(restLabel(person({}))).toBe('—');
  });
});
