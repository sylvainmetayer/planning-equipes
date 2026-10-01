// The acknowledgement filters (issue #504), pinned down without a table:
// who counts as « jamais confirmé », who counts as « silencieux depuis N
// jours », who counts as « échec d'envoi », and how the URL params are read
// back — tolerantly.

import { describe, expect, it } from 'vitest';
import type { ConfirmationView, LastMailDelivery } from '../../core/models';
import {
  SILENCE_JOURS_DEFAUT,
  failedSend,
  readModeAccuses,
  readNeverReminded,
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

/** The last mail to Alice, refused by the relay two days after the publication. */
function echec(patch: Partial<LastMailDelivery> = {}): LastMailDelivery {
  return {
    statut: 'ECHEC',
    categorie: 'ADRESSE_REFUSEE',
    le: '2026-07-03T10:00:00Z',
    type: 'relance-confirmation',
    ficheModifieeDepuis: false,
    enEchec: true,
    ...patch,
  };
}

describe("the « échec d'envoi » filter", () => {
  it('is read from envoi=echec, and a valid silence still wins', () => {
    expect(readModeAccuses(null, null, 'echec').mode).toBe('echec');
    expect(readModeAccuses('jamais', null, 'echec').mode).toBe('echec');
    expect(readModeAccuses(null, '4', 'echec').mode).toBe('silence');
    expect(readModeAccuses(null, null, 'autre').mode).toBe('tous');
  });

  it('keeps whoever holds a seat, has not confirmed and whose last mail failed', () => {
    const failed = reponse({ dernierEnvoi: echec() });

    expect(failedSend(failed)).toBe(true);
    expect(keptByAcknowledgement('echec', 3, failed, PUBLICATION, MAINTENANT)).toBe(true);
  });

  it('counts under « échec d’envoi » the people the home screen counts: a seat first', () => {
    const seatless = reponse({ affecte: false, dernierEnvoi: echec() });

    expect(failedSend(seatless), 'the column still says the invitation bounced').toBe(true);
    expect(
      keptByAcknowledgement('echec', 3, seatless, PUBLICATION, MAINTENANT),
      'echecsEnvoi only counts seated people, and its link opens this filter',
    ).toBe(false);
  });

  it('leaves out a mail that left, a fiche edited since, and whoever confirmed', () => {
    const parti = reponse({
      dernierEnvoi: echec({ statut: 'ENVOYE', categorie: null, enEchec: false }),
    });
    const corrige = reponse({ dernierEnvoi: echec({ ficheModifieeDepuis: true, enEchec: false }) });
    const confirme = reponse({ statut: 'CONFIRME', dernierEnvoi: echec() });

    for (const confirmation of [parti, corrige, confirme, reponse()]) {
      expect(keptByAcknowledgement('echec', 3, confirmation, PUBLICATION, MAINTENANT)).toBe(false);
    }
  });

  it('is not a silence: « silencieux depuis » leaves it out, « jamais confirmés » keeps it', () => {
    const failed = reponse({ dernierEnvoi: echec() });

    expect(keptByAcknowledgement('silence', 3, failed, PUBLICATION, MAINTENANT)).toBe(false);
    expect(keptByAcknowledgement('silence', 3, reponse(), PUBLICATION, MAINTENANT)).toBe(true);
    expect(keptByAcknowledgement('jamais', 3, failed, PUBLICATION, MAINTENANT)).toBe(true);
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

describe('« jamais relancés »', () => {
  it('reads `relance=jamais` and nothing else', () => {
    expect(readNeverReminded('jamais')).toBe(true);
    expect(readNeverReminded(null)).toBe(false);
    expect(readNeverReminded('oui')).toBe(false);
  });

  it('drops whoever a reminder already reached, however long ago', () => {
    // Reminded a month ago: silent for 3 days, but not « jamais relancé ».
    const relance = reponse({ statut: 'RELANCE', relanceLe: '2026-06-01T10:00:00Z' });
    expect(keptByAcknowledgement('silence', 3, relance, PUBLICATION, MAINTENANT)).toBe(true);
    expect(keptByAcknowledgement('silence', 3, relance, PUBLICATION, MAINTENANT, true)).toBe(false);
    expect(keptByAcknowledgement('jamais', 3, relance, PUBLICATION, MAINTENANT, true)).toBe(false);
  });

  it('keeps the silent nobody chased: the people the home screen counts', () => {
    expect(keptByAcknowledgement('silence', 3, reponse(), PUBLICATION, MAINTENANT, true)).toBe(
      true,
    );
    expect(keptByAcknowledgement('jamais', 3, reponse(), PUBLICATION, MAINTENANT, true)).toBe(true);
  });

  it('leaves the default mode showing everybody', () => {
    const relance = reponse({ statut: 'RELANCE', relanceLe: '2026-06-01T10:00:00Z' });
    expect(keptByAcknowledgement('tous', 3, relance, PUBLICATION, MAINTENANT, true)).toBe(true);
  });
});
