import { describe, expect, it } from 'vitest';
import { PlageStand } from '../../core/models';
import {
  emptyPlageForm,
  plageDays,
  plageFormError,
  plageOfForm,
  plagesOfDay,
  sortedPlages,
} from './stand-plages';

function plage(date: string, heureDebut: string, partial: Partial<PlageStand> = {}): PlageStand {
  return { id: 1, mode: 'OUVERTURE', date, heureDebut, heureFin: null, motif: null, ...partial };
}

describe('the plages of a stand', () => {
  const plages = [
    plage('2026-07-12', '14:00:00'),
    plage('2026-07-11', '14:00:00', { mode: 'FERMETURE' }),
    plage('2026-07-11', '10:00:00', { depliee: true }),
  ];

  it('sorts by day then by start, and offers each day once', () => {
    expect(sortedPlages(plages).map((each) => `${each.date} ${each.heureDebut}`)).toEqual([
      '2026-07-11 10:00:00',
      '2026-07-11 14:00:00',
      '2026-07-12 14:00:00',
    ]);
    expect(plageDays(plages)).toEqual(['2026-07-11', '2026-07-12']);
  });

  it('narrows to one day, or keeps them all', () => {
    expect(plagesOfDay(plages, '2026-07-11')).toHaveLength(2);
    expect(plagesOfDay(plages, '')).toHaveLength(3);
  });

  it('refuses a form without a day or a start, and an end that does not follow the start', () => {
    expect(plageFormError(emptyPlageForm(), 4)).not.toBeNull();
    expect(
      plageFormError(
        { ...emptyPlageForm('2026-07-11'), heureDebut: '10:00', heureFin: '09:00' },
        4,
      ),
    ).not.toBeNull();
    expect(
      plageFormError(
        { ...emptyPlageForm('2026-07-11'), heureDebut: '10:00', heureFin: '12:00' },
        4,
      ),
    ).toBeNull();
  });

  it('refuses an opening headcount that is not a whole number, or beyond the stand', () => {
    const base = { ...emptyPlageForm('2026-07-11'), heureDebut: '10:00' };
    expect(plageFormError({ ...base, effectif: '0' }, 4)).not.toBeNull();
    expect(plageFormError({ ...base, effectif: '1.5' }, 4)).not.toBeNull();
    expect(plageFormError({ ...base, effectif: '5' }, 4)).not.toBeNull();
    expect(plageFormError({ ...base, effectif: '4' }, 4)).toBeNull();
    // A closure has no headcount: what was typed before switching the type does not count.
    expect(plageFormError({ ...base, mode: 'FERMETURE', effectif: '99' }, 4)).toBeNull();
  });

  it('builds the body: an empty end is « until closing », a closure carries no headcount', () => {
    const base = {
      ...emptyPlageForm('2026-07-11'),
      heureDebut: '10:00',
      effectif: '2',
      motif: ' ',
    };
    expect(plageOfForm(base)).toEqual({
      id: null,
      mode: 'OUVERTURE',
      date: '2026-07-11',
      heureDebut: '10:00',
      heureFin: null,
      motif: null,
      effectif: 2,
    });
    expect(plageOfForm({ ...base, mode: 'FERMETURE', heureFin: '12:00' }).effectif).toBeNull();
  });
});
