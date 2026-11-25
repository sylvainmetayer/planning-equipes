import { describe, expect, it } from 'vitest';
import { Avertissement } from '../../core/models';
import { brouillonDe, contactsDe, hasRepresentative, journalText, telHref } from './contacts';

describe('contacts', () => {
  it('turns missing contacts into empty inputs and back', () => {
    expect(brouillonDe(null)).toEqual({
      urgenceNom: '',
      urgenceTelephone: '',
      representantNom: '',
      representantTelephone: '',
    });
    const brouillon = brouillonDe({ urgenceNom: 'Camille', representantTelephone: null });
    expect(brouillon.urgenceNom).toBe('Camille');
    expect(brouillon.representantTelephone).toBe('');
    expect(contactsDe(brouillon)).toEqual(brouillon);
  });

  it('knows a legal representative by a name or a number', () => {
    expect(hasRepresentative({})).toBe(false);
    expect(hasRepresentative({ representantNom: '  ' })).toBe(false);
    expect(hasRepresentative({ representantNom: 'Dominique' })).toBe(true);
    expect(hasRepresentative({ representantTelephone: '0123456789' })).toBe(true);
    expect(hasRepresentative({ urgenceNom: 'Camille' })).toBe(false);
  });

  it('writes a tel: address of digits and a leading plus', () => {
    expect(telHref('+33 6 12 34 56 78')).toBe('tel:+33612345678');
    expect(telHref('(01) 23.45')).toBe('tel:012345');
  });

  it('keeps nothing of a warning naming a minor in the notification log', () => {
    const mineur: Avertissement = { type: 'MINEUR_SANS_REPRESENTANT', message: 'x' };
    const autre: Avertissement = { type: 'STAND_JAMAIS_OUVERT', message: 'y' };
    expect(journalText(mineur)).toBe('');
    expect(journalText(autre)).toBe('y');
  });
});
