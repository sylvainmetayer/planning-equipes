// The wording of a consigne, shared by every screen that marks a day as
// « sous consigne »: the band, a date, the meal chip.

import { describe, expect, it } from 'vitest';
import {
  bandeLabel,
  heureLabel,
  libelleDate,
  repasLabel,
  repasSurcharge,
} from './consigne-wording';

describe('the wording of a band', () => {
  it('says an hour the way an organiser does, and midnight for an open end', () => {
    expect(heureLabel('12:00:00')).toBe('12h');
    expect(heureLabel('09:30:00')).toBe('9h30');
    expect(heureLabel('18:00')).toBe('18h');
    expect(bandeLabel('12:00:00', '18:00:00')).toBe('12h–18h');
    expect(bandeLabel('12:00:00', null)).toBe('12h–minuit');
  });

  it('says a date with its weekday, the way the table and the selectors do', () => {
    expect(libelleDate('2026-07-10')).toBe('Vendredi 10/07');
  });
});

describe('the meal chip', () => {
  it('words the chip: the justification, then what is restated', () => {
    const repas = {
      midiDebut: '12:00:00',
      midiFin: '14:00:00',
      soirDebut: '18:00:00',
      soirFin: '22:00:00',
      coupureMinutes: 45,
      justification: 'Fermeture',
    };
    expect(repasSurcharge(repas)).toBe(true);
    expect(repasSurcharge(null)).toBe(false);
    expect(
      repasSurcharge({
        ...repas,
        midiDebut: null,
        midiFin: null,
        soirDebut: null,
        soirFin: null,
        coupureMinutes: null,
      }),
    ).toBe(false);
    expect(repasLabel(repas)).toBe('Fermeture · midi 12h–14h · soir 18h–22h · coupure 45 min');
    expect(repasLabel({ ...repas, midiDebut: null, midiFin: null, coupureMinutes: null })).toBe(
      'Fermeture · soir 18h–22h',
    );
  });
});
