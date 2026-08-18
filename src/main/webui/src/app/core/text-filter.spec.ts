import { describe, expect, it } from 'vitest';
import { correspondAuFiltre, normaliserPourFiltre, termesDuFiltre } from './text-filter';

describe('normaliserPourFiltre', () => {
  it('strips accents and case', () => {
    expect(normaliserPourFiltre('Médiathèque')).toBe('mediatheque');
    expect(normaliserPourFiltre('ÉLÈVE')).toBe('eleve');
  });
});

describe('termesDuFiltre', () => {
  it('splits on whitespace and drops the empty terms', () => {
    expect(termesDuFiltre('  village   enf ')).toEqual(['village', 'enf']);
  });

  it('is empty for a blank query', () => {
    expect(termesDuFiltre('   ')).toEqual([]);
  });
});

describe('correspondAuFiltre', () => {
  it('matches everything when the query is blank', () => {
    expect(correspondAuFiltre('', ['STAND-1', 'Ludothèque'])).toBe(true);
    expect(correspondAuFiltre('   ', ['STAND-1', 'Ludothèque'])).toBe(true);
  });

  it('ignores accents and case on both sides', () => {
    expect(correspondAuFiltre('mediatheque', ['AUTRES-MEDIA', 'Autres - Médiathèque'])).toBe(true);
    expect(correspondAuFiltre('MÉDIA', ['AUTRES-MEDIA', 'Autres - Médiathèque'])).toBe(true);
  });

  it('requires every term, in any order and any field', () => {
    const champs = ['VILLAGE-ENFANTS', 'Village des Enfants', 'Place du 11 Novembre'];
    expect(correspondAuFiltre('enfants village', champs)).toBe(true);
    expect(correspondAuFiltre('village novembre', champs)).toBe(true);
    expect(correspondAuFiltre('village citadelle', champs)).toBe(false);
  });

  it('skips null, undefined and empty fields instead of matching on them', () => {
    expect(correspondAuFiltre('null', ['STAND-1', null, undefined, ''])).toBe(false);
    expect(correspondAuFiltre('stand', ['STAND-1', null])).toBe(true);
  });

  it('matches numeric fields', () => {
    expect(correspondAuFiltre('46.64', ['LUDO', 46.6466, 2.2518])).toBe(true);
  });
});
