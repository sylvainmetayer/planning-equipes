import { describe, expect, it } from 'vitest';
import { VacationResponsable } from '../../core/models';
import { daysOf, personLabel, personLabels, staffingLabel, windowLabel } from './responsable';

function vacation(debut: string, fin: string, pourvus = 1, vides = 0): VacationResponsable {
  return { debut, fin, pourvus, vides, personnes: [] };
}

describe('responsable', () => {
  it('reads the window of a shift off its local date-times', () => {
    expect(windowLabel(vacation('2026-07-11T10:00:00', '2026-07-11T12:30:00'))).toBe(
      '10:00 – 12:30',
    );
  });

  it('says the seats and, only when there are some, the empty ones', () => {
    expect(staffingLabel(vacation('', '', 2, 0))).toBe('2 places');
    expect(staffingLabel(vacation('', '', 0, 1))).toBe('1 place — 1 à pourvoir');
    expect(staffingLabel(vacation('', '', 1, 2))).toBe('3 places — 2 à pourvoir');
  });

  it('groups the shifts by day', () => {
    const jours = daysOf({
      vacations: [
        vacation('2026-07-11T10:00:00', '2026-07-11T12:00:00'),
        vacation('2026-07-11T14:00:00', '2026-07-11T16:00:00'),
        vacation('2026-07-12T10:00:00', '2026-07-12T12:00:00'),
      ],
    });
    expect(jours.map((j) => [j.jour, j.vacations.length])).toEqual([
      ['2026-07-11', 2],
      ['2026-07-12', 1],
    ]);
  });

  it('writes a name as first then last', () => {
    expect(personLabel({ prenom: 'Alice', nom: 'Martin' })).toBe('Alice Martin');
    expect(
      personLabels({
        personnes: [
          { prenom: 'Alice', nom: 'Martin' },
          { prenom: 'Bruno', nom: 'Petit' },
        ],
      }),
    ).toBe('Alice Martin, Bruno Petit');
  });
});
