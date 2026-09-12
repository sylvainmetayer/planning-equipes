import { describe, expect, it } from 'vitest';
import {
  affecterDates,
  bornesCalendrier,
  datesDePlage,
  formatVacations,
  parseVacations,
  retirerDate,
} from './journees-types';

describe('parseVacations', () => {
  it('reads one vacation per comma and a trailing R as a meal relay', () => {
    const saisie = parseVacations(
      '09:00-12:00, 12:00-13:00 R, 13h-14h r, 14:00-20:00 (R); 20h00-00:00',
    );

    expect(saisie.erreur).toBeNull();
    expect(saisie.vacations).toEqual([
      { heureDebut: '09:00', heureFin: '12:00', couverturePause: false },
      { heureDebut: '12:00', heureFin: '13:00', couverturePause: true },
      { heureDebut: '13:00', heureFin: '14:00', couverturePause: true },
      { heureDebut: '14:00', heureFin: '20:00', couverturePause: true },
      { heureDebut: '20:00', heureFin: '00:00', couverturePause: false },
    ]);
  });

  it('names the piece it cannot read, and refuses an open end or a repeat', () => {
    expect(parseVacations('09:00-12:00, 14:00-')).toMatchObject({
      erreur: 'FORME',
      morceau: '14:00-',
    });
    expect(parseVacations('9:5-12:00')).toMatchObject({ erreur: 'HEURE', morceau: '9:5-12:00' });
    expect(parseVacations('09:00-12:00, 09:00-12:00 R')).toMatchObject({ erreur: 'DOUBLON' });
    expect(parseVacations('  ')).toMatchObject({ erreur: 'VIDE' });
  });

  it('formats what it reads', () => {
    const ligne = '09:00-12:00, 12:00-13:00 R, 14:00-20:00';
    expect(formatVacations(parseVacations(ligne).vacations!)).toBe(ligne);
    // Server hours come with seconds; the chip does not show them.
    expect(
      formatVacations([{ heureDebut: '09:00:00', heureFin: '12:00:00', couverturePause: false }]),
    ).toBe('09:00-12:00');
  });
});

describe('the calendar helpers', () => {
  it('lists every date of a range, bounds included, and nothing for a reversed one', () => {
    expect(datesDePlage('2027-07-30', '2027-08-02')).toEqual([
      '2027-07-30',
      '2027-07-31',
      '2027-08-01',
      '2027-08-02',
    ]);
    expect(datesDePlage('2027-08-02', '2027-07-30')).toEqual([]);
    expect(datesDePlage('', '2027-07-30')).toEqual([]);
  });

  // A `<input type="date">` accepts years up to 275760, and the ISO form of such
  // a year starts with a `+`, which sorts below every plain date: the walk that
  // stopped when the current day passed the end never stopped, and filled an
  // array until the tab died. Nothing is proposed for a range nobody meant.
  it('proposes nothing for a year no edition has, instead of counting to it', () => {
    expect(datesDePlage('2027-07-30', '12345-08-02')).toEqual([]);
    expect(datesDePlage('2027-07-30', '2027-02-31')).toEqual([]);
    expect(datesDePlage('2027-07-30', 'demain')).toEqual([]);
  });

  it('proposes a year of dates at most, bounds included', () => {
    expect(datesDePlage('2027-01-01', '2027-12-31')).toHaveLength(365);
    expect(datesDePlage('2027-01-01', '2028-01-01')).toHaveLength(366);
    expect(datesDePlage('2027-01-01', '2028-01-02')).toEqual([]);
  });

  it('assigns dates, moving one already there, and keeps the calendar sorted', () => {
    const calendrier = affecterDates(
      [
        { date: '2027-07-13', journeeTypeId: 1 },
        { date: '2027-07-12', journeeTypeId: 1 },
      ],
      ['2027-07-13', '2027-07-14'],
      2,
    );

    expect(calendrier).toEqual([
      { date: '2027-07-12', journeeTypeId: 1 },
      { date: '2027-07-13', journeeTypeId: 2 },
      { date: '2027-07-14', journeeTypeId: 2 },
    ]);
    expect(retirerDate(calendrier, '2027-07-13')).toHaveLength(2);
    expect(bornesCalendrier(calendrier)).toEqual({ du: '2027-07-12', au: '2027-07-14' });
    expect(bornesCalendrier([])).toBeNull();
  });
});
