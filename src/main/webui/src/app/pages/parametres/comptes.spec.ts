import { describe, expect, it } from 'vitest';
import { Compte, Habilitation } from '../../core/models';
import { editionLabel, rightLabel, rightState, roleLabel, sortAccounts } from './comptes';

const MAINTENANT = new Date('2026-06-01T12:00:00Z');

function droit(extra: Partial<Habilitation> = {}): Habilitation {
  return {
    id: 'h1',
    role: 'ORGANISATEUR',
    editionId: 'E1',
    expireLe: null,
    creePar: null,
    creeLe: null,
    retireeLe: null,
    retireePar: null,
    ...extra,
  };
}

function compte(email: string, desactiveLe: string | null = null): Compte {
  return {
    id: email,
    email,
    nom: null,
    creeLe: '2026-01-01T00:00:00Z',
    derniereConnexionLe: null,
    desactiveLe,
    desactivePar: null,
    habilitations: [],
    passwordSet: false,
  };
}

describe('rightState', () => {
  it('a withdrawn right stays withdrawn even once expired', () => {
    expect(
      rightState(
        droit({ retireeLe: '2026-05-01T00:00:00Z', expireLe: '2026-05-02T00:00:00Z' }),
        MAINTENANT,
      ),
    ).toBe('withdrawn');
  });

  it('tells an expired right from one in force', () => {
    expect(rightState(droit({ expireLe: '2026-05-01T00:00:00Z' }), MAINTENANT)).toBe('expired');
    expect(rightState(droit({ expireLe: '2026-07-01T00:00:00Z' }), MAINTENANT)).toBe('active');
    expect(rightState(droit(), MAINTENANT)).toBe('active');
  });
});

describe('labels', () => {
  it('names the roles and the editions, an unknown edition by its id', () => {
    expect(roleLabel('ADMIN')).toBe('Administrateur');
    expect(editionLabel(null, [])).toBe('Toutes les éditions');
    expect(editionLabel('E1', [{ id: 'E1', nom: 'Année 2026', active: true, creeLe: null }])).toBe(
      'Année 2026',
    );
    expect(editionLabel('E9', [])).toBe('E9');
  });

  it('an administrator right names no edition', () => {
    expect(rightLabel(droit({ role: 'ADMIN', editionId: null }), [])).toBe('Administrateur');
    expect(rightLabel(droit({ editionId: null }), [])).toBe('Organisateur — Toutes les éditions');
  });
});

describe('sortAccounts', () => {
  it('lists the active accounts first, then by address', () => {
    const tri = sortAccounts([
      compte('z@x.fr'),
      compte('a@x.fr', '2026-01-01T00:00:00Z'),
      compte('b@x.fr'),
    ]);
    expect(tri.map((c) => c.email)).toEqual(['b@x.fr', 'z@x.fr', 'a@x.fr']);
  });
});
