import { describe, expect, it } from 'vitest';
import { ReglagesResponsables } from '../../core/models';
import {
  defautDe,
  fromLocalInput,
  lignesDe,
  reglagesDe,
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
    expect(lignesDe(SERVEUR, ['S1', 'S2', 'S3']).map((l) => [l.standId, l.mode])).toEqual([
      ['S1', 'herite'],
      ['S2', 'noms'],
      ['S3', 'horaires'],
    ]);
  });

  it('sends back only the stands that differ, and drops a date that means nothing', () => {
    const lignes = lignesDe(SERVEUR, ['S1', 'S2', 'S3']);
    const corps = reglagesDe(defautDe(SERVEUR), lignes);
    expect(Object.keys(corps.parStand)).toEqual(['S2', 'S3']);
    expect(corps.parStand['S3']).toEqual({ nomsVisibles: false, ouvertureLe: null });
    expect(corps.parStand['S2'].ouvertureLe).toBe('2026-07-01T10:00:00.000Z');
    expect(
      reglagesDe({ noms: false, ouverture: '2026-07-01T10:00' }, []).parDefaut.ouvertureLe,
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
