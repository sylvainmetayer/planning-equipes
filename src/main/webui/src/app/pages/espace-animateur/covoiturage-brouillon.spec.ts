import { describe, expect, it } from 'vitest';
import { CarpoolEspaceView } from '../../core/models';
import {
  carpoolLocked,
  carpoolModified,
  initialTeammates,
  openingAhead,
  toCarpoolRequest,
  toggleTeammate,
} from './covoiturage-brouillon';

function view(overrides: Partial<CarpoolEspaceView> = {}): CarpoolEspaceView {
  return {
    collectionOpen: true,
    collectionStart: null,
    collectionEnd: null,
    colleagues: [],
    teammateIds: [],
    status: null,
    reason: null,
    decidedAt: null,
    ...overrides,
  };
}

describe('covoiturage draft', () => {
  it('opens on the pending request and sees a teammate added or all removed', () => {
    const pending = view({ teammateIds: ['A2'], status: 'EN_ATTENTE' });

    expect(initialTeammates(pending)).toEqual(['A2']);
    expect(carpoolModified(pending, ['A2'])).toBe(false);
    expect(carpoolModified(pending, toggleTeammate(['A2'], 'A3'))).toBe(true);
    // Emptying a pending car withdraws it: a change worth sending.
    expect(carpoolModified(pending, [])).toBe(true);
  });

  it('starts blank after a cancelled car, which does not lock the tab', () => {
    const cancelled = view({ teammateIds: ['A2'], status: 'ANNULEE', reason: 'panne' });

    expect(initialTeammates(cancelled)).toEqual([]);
    expect(carpoolLocked(cancelled)).toBe(false);
    expect(carpoolModified(cancelled, [])).toBe(false);
    expect(carpoolModified(cancelled, ['A3'])).toBe(true);
  });

  it('starts blank after a request set aside, and nothing typed is no change', () => {
    const setAside = view({ teammateIds: ['A2'], status: 'ECARTEE', reason: 'complet' });

    expect(initialTeammates(setAside)).toEqual([]);
    expect(carpoolModified(setAside, [])).toBe(false);
    expect(carpoolModified(setAside, ['A2'])).toBe(true);
  });

  it('never takes a fourth teammate, and sends the car sorted', () => {
    expect(toggleTeammate(['A1', 'A2', 'A3'], 'A4')).toEqual(['A1', 'A2', 'A3']);
    expect(toggleTeammate(['A1', 'A2'], 'A1')).toEqual(['A2']);
    expect(toCarpoolRequest(['B', 'A'])).toEqual({ teammateIds: ['A', 'B'] });
  });

  it('reads a validated car as locked, where nothing counts as a change', () => {
    const validated = view({ teammateIds: ['A2'], status: 'VALIDEE' });

    expect(carpoolLocked(validated)).toBe(true);
    expect(carpoolLocked(view({ status: 'EN_ATTENTE' }))).toBe(false);
    expect(carpoolModified(validated, [])).toBe(false);
  });

  it('tells a window not open yet from one closed for good', () => {
    expect(
      openingAhead(view({ collectionOpen: false, collectionStart: '2026-06-01' }), '2026-05-20'),
    ).toBe('2026-06-01');
    expect(
      openingAhead(view({ collectionOpen: false, collectionStart: '2026-06-01' }), '2026-06-20'),
    ).toBeNull();
    expect(openingAhead(view(), '2026-05-20')).toBeNull();
  });
});
