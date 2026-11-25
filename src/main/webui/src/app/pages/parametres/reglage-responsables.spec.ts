import { describe, expect, it } from 'vitest';
import { ReglagesResponsables } from '../../core/models';
import {
  defaultOf,
  fromLocalInput,
  rowsOf,
  toSettings,
  toLocalInput,
} from './reglage-responsables';

const SERVEUR: ReglagesResponsables = {
  parDefaut: { nomsVisibles: false, ouvertureLe: null },
  parStand: {
    S2: { nomsVisibles: true, ouvertureLe: '2026-07-01T10:00:00.000Z' },
    S3: { nomsVisibles: false, ouvertureLe: null },
  },
};

describe('reglage-responsables', () => {
  it('reads an exception either way and a stand without one as following the edition', () => {
    expect(rowsOf(SERVEUR, ['S1', 'S2', 'S3']).map((l) => [l.standId, l.mode])).toEqual([
      ['S1', 'herite'],
      ['S2', 'noms'],
      ['S3', 'horaires'],
    ]);
  });

  it('sends each stand shown — null lifts its exception — and drops a date that means nothing', () => {
    const lignes = rowsOf(SERVEUR, ['S1', 'S2', 'S3']);
    const corps = toSettings(defaultOf(SERVEUR), lignes);
    expect(Object.keys(corps.parStand)).toEqual(['S1', 'S2', 'S3']);
    expect(corps.parStand['S1']).toBeNull();
    expect(corps.parStand['S3']).toEqual({ nomsVisibles: false, ouvertureLe: null });
    expect(corps.parStand['S2']?.ouvertureLe).toBe('2026-07-01T10:00:00.000Z');
    // No stand known (a cold start) means nothing sent about any stand: none is erased.
    expect(toSettings(defaultOf(SERVEUR), []).parStand).toEqual({});
    expect(
      toSettings({ noms: false, ouverture: '2026-07-01T10:00' }, []).parDefaut.ouvertureLe,
    ).toBeNull();
  });

  it('converts a datetime-local value both ways and refuses nonsense', () => {
    const iso = fromLocalInput('2026-07-01T10:30');
    expect(iso).not.toBeNull();
    expect(toLocalInput(iso)).toBe('2026-07-01T10:30');
    expect(fromLocalInput('')).toBeNull();
    expect(fromLocalInput('pas une date')).toBeNull();
    expect(toLocalInput(null)).toBe('');
  });
});
