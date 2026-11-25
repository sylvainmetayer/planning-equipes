import { describe, expect, it } from 'vitest';
import { Avertissement } from '../../core/models';
import { ApiError, CODE_MODIFICATION_CONCURRENTE } from '../../core/api.service';
import {
  draftOf,
  contactsOf,
  hasRepresentative,
  journalText,
  staleStamp,
  telHref,
} from './contacts';

describe('contacts', () => {
  it('turns missing contacts into empty inputs and back', () => {
    expect(draftOf(null)).toEqual({
      urgenceNom: '',
      urgenceTelephone: '',
      representantNom: '',
      representantTelephone: '',
      modifieLe: null,
    });
    const brouillon = draftOf({
      urgenceNom: 'Camille',
      representantTelephone: null,
      modifieLe: '2026-11-25T10:00:00Z',
    });
    expect(brouillon.modifieLe).toBe('2026-11-25T10:00:00Z');
    expect(brouillon.urgenceNom).toBe('Camille');
    expect(brouillon.representantTelephone).toBe('');
    expect(contactsOf(brouillon)).toEqual(brouillon);
  });

  it('keeps the stamp of the write that superseded a stale read, and nothing else', () => {
    const stale = new ApiError(
      409,
      'conflict',
      'modifiés par quelqu’un d’autre',
      CODE_MODIFICATION_CONCURRENTE,
      '2026-11-25T11:00:00Z',
    );
    expect(staleStamp(stale)).toBe('2026-11-25T11:00:00Z');
    expect(staleStamp(new ApiError(409, 'conflict', 'autre'))).toBeNull();
    expect(staleStamp(new Error('x'))).toBeNull();
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
