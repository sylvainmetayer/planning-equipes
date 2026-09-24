import { describe, expect, it } from 'vitest';
import { Compte, Edition, Habilitation } from '../../core/models';
import {
  editionsWithStands,
  emailError,
  expiryInstant,
  filterAccounts,
  grantErrors,
  initialGrantDraft,
  isOwnAccount,
  rightState,
  rightsSummary,
  sortAccounts,
  toGrantRequest,
  withEdition,
  withRole,
} from './comptes';

const NOW = new Date('2026-06-15T10:00:00Z');

const EDITIONS: Edition[] = [
  { id: '2025', nom: 'Année 2025', defaut: false, creeLe: null },
  { id: '2026', nom: 'Année 2026', defaut: true, creeLe: null },
];

function right(patch: Partial<Habilitation> = {}): Habilitation {
  return {
    id: 'h1',
    role: 'RH',
    editionId: null,
    expireLe: null,
    standIds: [],
    creePar: 'admin@example.org',
    creeLe: '2026-01-01T00:00:00Z',
    retireeLe: null,
    ...patch,
  };
}

function account(patch: Partial<Compte> = {}): Compte {
  return {
    id: 'c1',
    email: 'camille@example.org',
    nom: 'Camille Martin',
    sujet: null,
    creeLe: '2026-01-01T00:00:00Z',
    derniereConnexionLe: null,
    desactiveLe: null,
    habilitations: [],
    ...patch,
  };
}

describe('rightState', () => {
  it('reads a right in force, one past its expiry, and a withdrawn one', () => {
    expect(rightState(right(), NOW)).toBe('active');
    expect(rightState(right({ expireLe: '2026-07-01T00:00:00Z' }), NOW)).toBe('active');
    expect(rightState(right({ expireLe: '2026-06-01T00:00:00Z' }), NOW)).toBe('expired');
    expect(rightState(right({ retireeLe: '2026-02-01T00:00:00Z' }), NOW)).toBe('withdrawn');
  });

  it('says withdrawn over expired: withdrawing is what somebody did', () => {
    const both = right({ expireLe: '2026-06-01T00:00:00Z', retireeLe: '2026-03-01T00:00:00Z' });
    expect(rightState(both, NOW)).toBe('withdrawn');
  });
});

describe('rightsSummary', () => {
  it('says so when an account holds no right in force', () => {
    expect(rightsSummary(account(), EDITIONS, NOW)).toBe('Aucun droit délégué');
  });

  it('names role and edition, and counts a stand scope', () => {
    const compte = account({
      habilitations: [
        right(),
        right({
          id: 'h2',
          role: 'RESPONSABLE_STAND',
          editionId: '2026',
          standIds: ['s1', 's2', 's3'],
        }),
      ],
    });

    expect(rightsSummary(compte, EDITIONS, NOW)).toBe(
      'RH (lecture seule) — Toutes les éditions ; Responsable de stand — Année 2026, 3 stand(s)',
    );
  });

  it('leaves out expired and withdrawn rights, which open nothing', () => {
    const compte = account({
      habilitations: [
        right({ expireLe: '2026-06-01T00:00:00Z' }),
        right({ id: 'h2', retireeLe: '2026-03-01T00:00:00Z' }),
      ],
    });

    expect(rightsSummary(compte, EDITIONS, NOW)).toBe('Aucun droit délégué');
  });

  it('falls back to the id of an edition deleted since', () => {
    const compte = account({ habilitations: [right({ editionId: '2019' })] });

    expect(rightsSummary(compte, EDITIONS, NOW)).toBe('RH (lecture seule) — 2019');
  });
});

describe('filterAccounts', () => {
  const comptes = [
    account(),
    account({
      id: 'c2',
      email: 'dominique@example.org',
      nom: null,
      habilitations: [right({ role: 'RESPONSABLE_STAND', editionId: '2026', standIds: ['s1'] })],
    }),
  ];

  it('keeps everything on an empty filter', () => {
    expect(filterAccounts(comptes, '  ', EDITIONS, NOW)).toHaveLength(2);
  });

  it('matches the address, the name, accent- and case-insensitively', () => {
    expect(filterAccounts(comptes, 'DOMINIQUE', EDITIONS, NOW).map((c) => c.id)).toEqual(['c2']);
    expect(filterAccounts(comptes, 'martin', EDITIONS, NOW).map((c) => c.id)).toEqual(['c1']);
  });

  it('matches the rights summary: a role, an edition', () => {
    expect(filterAccounts(comptes, 'responsable', EDITIONS, NOW).map((c) => c.id)).toEqual(['c2']);
    expect(filterAccounts(comptes, 'annee 2026', EDITIONS, NOW).map((c) => c.id)).toEqual(['c2']);
  });
});

describe('sortAccounts', () => {
  it('lists active accounts first, then by address, without touching its input', () => {
    const comptes = [
      account({ id: 'z', email: 'zoe@example.org' }),
      account({ id: 'off', email: 'alice@example.org', desactiveLe: '2026-01-02T00:00:00Z' }),
      account({ id: 'b', email: 'bruno@example.org' }),
    ];

    expect(sortAccounts(comptes).map((c) => c.id)).toEqual(['b', 'z', 'off']);
    expect(comptes.map((c) => c.id)).toEqual(['z', 'off', 'b']);
  });
});

describe('isOwnAccount', () => {
  it('recognises the signed-in session by its e-mail, trimmed and case-insensitively', () => {
    expect(isOwnAccount(account(), ' Camille@Example.org ')).toBe(true);
  });

  it('never matches another address, the break-glass session, or no session', () => {
    expect(isOwnAccount(account(), 'dominique@example.org')).toBe(false);
    expect(isOwnAccount(account(), 'admin')).toBe(false);
    expect(isOwnAccount(account(), null)).toBe(false);
  });
});

describe('emailError', () => {
  it('asks for an address, then for a well-formed one', () => {
    expect(emailError('   ')).toBe('Adresse e-mail manquante.');
    expect(emailError('camille')).toBe('Adresse e-mail invalide.');
    expect(emailError('ca mille@example.org')).toBe('Adresse e-mail invalide.');
    expect(emailError(' camille@example.org ')).toBeNull();
  });
});

describe('the grant draft', () => {
  it('gives a stand manager the current edition instead of every edition', () => {
    const draft = withRole(initialGrantDraft(), 'RESPONSABLE_STAND', '2026');

    expect(draft.editionId).toBe('2026');
  });

  it('keeps an edition already picked when the role becomes stand manager', () => {
    const draft = withRole(
      { ...initialGrantDraft(), editionId: '2025' },
      'RESPONSABLE_STAND',
      '2026',
    );

    expect(draft.editionId).toBe('2025');
  });

  it('drops the stands when the role leaves stand manager, or when the edition changes', () => {
    const manager = {
      ...initialGrantDraft(),
      role: 'RESPONSABLE_STAND' as const,
      editionId: '2026',
      standIds: ['s1'],
    };

    expect(withRole(manager, 'RH', '2026').standIds).toEqual([]);
    expect(withEdition(manager, '2025').standIds).toEqual([]);
    expect(withEdition(manager, '2026')).toBe(manager);
  });
});

describe('grantErrors', () => {
  it('accepts an RH right on every edition, without expiry', () => {
    expect(grantErrors(initialGrantDraft(), NOW)).toEqual([]);
  });

  it('refuses a stand manager without an edition or without a stand', () => {
    const message =
      "Un responsable de stand l'est dans une édition, pour au moins un de ses stands.";
    const base = { ...initialGrantDraft(), role: 'RESPONSABLE_STAND' as const };

    expect(grantErrors({ ...base, editionId: null, standIds: ['s1'] }, NOW)).toEqual([message]);
    expect(grantErrors({ ...base, editionId: '2026', standIds: [] }, NOW)).toEqual([message]);
    expect(grantErrors({ ...base, editionId: '2026', standIds: ['s1'] }, NOW)).toEqual([]);
  });

  it('refuses stands on any other role', () => {
    expect(grantErrors({ ...initialGrantDraft(), standIds: ['s1'] }, NOW)).toEqual([
      'Seul un responsable de stand a un périmètre de stands.',
    ]);
  });

  it('refuses an expiry already past, and accepts today: the day picked is included', () => {
    const today = new Date(2026, 5, 15, 12, 0);
    const past = { ...initialGrantDraft(), expiryDate: '2026-06-14' };

    expect(grantErrors(past, today)).toEqual(["La date d'expiration est déjà passée."]);
    expect(grantErrors({ ...initialGrantDraft(), expiryDate: '2026-06-15' }, today)).toEqual([]);
  });
});

describe('expiryInstant and toGrantRequest', () => {
  it('ends the right at the end of the picked day, local time', () => {
    expect(expiryInstant('2026-08-31')).toBe(new Date(2026, 8, 1).toISOString());
    expect(expiryInstant('')).toBeNull();
    expect(expiryInstant('31/08/2026')).toBeNull();
  });

  it('builds the request body, stands only for a stand manager', () => {
    expect(
      toGrantRequest({
        role: 'RESPONSABLE_STAND',
        editionId: '2026',
        expiryDate: '2026-08-31',
        standIds: ['s1'],
      }),
    ).toEqual({
      role: 'RESPONSABLE_STAND',
      editionId: '2026',
      expireLe: new Date(2026, 8, 1).toISOString(),
      standIds: ['s1'],
    });
    expect(toGrantRequest(initialGrantDraft())).toEqual({
      role: 'RH',
      editionId: null,
      expireLe: null,
      standIds: [],
    });
  });
});

describe('editionsWithStands', () => {
  it('lists once each edition whose stands a right names', () => {
    const compte = account({
      habilitations: [
        right({ id: 'a', role: 'RESPONSABLE_STAND', editionId: '2026', standIds: ['s1'] }),
        right({ id: 'b', role: 'RESPONSABLE_STAND', editionId: '2025', standIds: ['s2'] }),
        right({ id: 'c', role: 'RESPONSABLE_STAND', editionId: '2026', standIds: ['s3'] }),
        right({ id: 'd', editionId: '2024' }),
      ],
    });

    expect(editionsWithStands(compte)).toEqual(['2025', '2026']);
    expect(editionsWithStands(null)).toEqual([]);
  });
});
