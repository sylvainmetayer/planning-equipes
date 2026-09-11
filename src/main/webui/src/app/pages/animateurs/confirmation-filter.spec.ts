// The two acknowledgement filters (issue #504), pinned down without a table:
// who counts as « jamais confirmé », who counts as « silencieux depuis N
// jours », and how the URL params are read back — tolerantly.

import { describe, expect, it } from 'vitest';
import type { ConfirmationView } from '../../core/models';
import {
  SILENCE_JOURS_DEFAUT,
  readModeAccuses,
  keptByAcknowledgement,
} from './confirmation-filter';

const PUBLICATION = '2026-07-01T10:00:00Z';
const MAINTENANT = new Date('2026-07-05T10:00:00Z');

function reponse(patch: Partial<ConfirmationView> = {}): ConfirmationView {
  return {
    animateurId: 'a',
    nomAffiche: 'Alice',
    statut: 'NON_VU',
    affecte: true,
    confirmeLe: null,
    relanceLe: null,
    ...patch,
  };
}

describe('readModeAccuses', () => {
  it('opens on everybody when the URL carries nothing', () => {
    expect(readModeAccuses(null, null)).toEqual({ mode: 'tous', jours: SILENCE_JOURS_DEFAUT });
  });

  it('reads « jamais confirmés »', () => {
    expect(readModeAccuses('jamais', null).mode).toBe('jamais');
  });

  it('reads the number of days of « silencieux depuis »', () => {
    expect(readModeAccuses(null, '7')).toEqual({ mode: 'silence', jours: 7 });
  });

  it('lets a valid silence win over a confirmation param', () => {
    expect(readModeAccuses('jamais', '2').mode).toBe('silence');
  });

  it('falls back to the default on a value that is not a positive whole number of days', () => {
    for (const silence of ['0', '-3', '2.5', 'abc', '']) {
      expect(readModeAccuses(null, silence).mode, `silence=${silence}`).toBe('tous');
    }
    expect(readModeAccuses('inconnu', null).mode).toBe('tous');
  });
});

describe('keptByAcknowledgement', () => {
  it('keeps everybody in the default mode, answered or not', () => {
    expect(keptByAcknowledgement('tous', 3, undefined, PUBLICATION, MAINTENANT)).toBe(true);
    expect(keptByAcknowledgement('tous', 3, reponse({ affecte: false }), null, MAINTENANT)).toBe(
      true,
    );
  });

  it('« jamais confirmés » keeps the silent and the reminded, drops the confirmed and the seatless', () => {
    expect(keptByAcknowledgement('jamais', 3, reponse(), PUBLICATION, MAINTENANT)).toBe(true);
    expect(
      keptByAcknowledgement('jamais', 3, reponse({ statut: 'RELANCE' }), PUBLICATION, MAINTENANT),
    ).toBe(true);
    expect(
      keptByAcknowledgement('jamais', 3, reponse({ statut: 'CONFIRME' }), PUBLICATION, MAINTENANT),
    ).toBe(false);
    // Nothing was asked of somebody without a seat: they are not silent.
    expect(
      keptByAcknowledgement('jamais', 3, reponse({ affecte: false }), PUBLICATION, MAINTENANT),
    ).toBe(false);
    expect(keptByAcknowledgement('jamais', 3, undefined, PUBLICATION, MAINTENANT)).toBe(false);
  });

  it('« silencieux depuis N jours » counts from the publication', () => {
    // Published four days ago.
    expect(keptByAcknowledgement('silence', 3, reponse(), PUBLICATION, MAINTENANT)).toBe(true);
    expect(keptByAcknowledgement('silence', 4, reponse(), PUBLICATION, MAINTENANT)).toBe(false);
    expect(keptByAcknowledgement('silence', 10, reponse(), PUBLICATION, MAINTENANT)).toBe(false);
  });

  it('counts from the reminder instead once one went out: reminded yesterday is not silent for 3 days', () => {
    const relance = reponse({ statut: 'RELANCE', relanceLe: '2026-07-04T10:00:00Z' });
    expect(keptByAcknowledgement('silence', 3, relance, PUBLICATION, MAINTENANT)).toBe(false);
    // Reminded a week ago: silent again.
    const ancienne = reponse({ statut: 'RELANCE', relanceLe: '2026-06-20T10:00:00Z' });
    expect(keptByAcknowledgement('silence', 3, ancienne, PUBLICATION, MAINTENANT)).toBe(true);
  });

  it('never calls anybody silent before the first publication', () => {
    expect(keptByAcknowledgement('silence', 1, reponse(), null, MAINTENANT)).toBe(false);
  });

  it('never calls the confirmed silent, whatever the dates', () => {
    const confirme = reponse({ statut: 'CONFIRME', confirmeLe: '2026-07-02T10:00:00Z' });
    expect(keptByAcknowledgement('silence', 1, confirme, PUBLICATION, MAINTENANT)).toBe(false);
  });
});
