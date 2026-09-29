import { describe, expect, it } from 'vitest';
import { MembreEquipe, StandResponsable, ResponsableView } from '../../core/models';
import {
  anyNamed,
  daysOf,
  initialDay,
  shiftsOn,
  staffingLabel,
  teamOn,
  windowLabel,
  windowsOn,
} from './responsable';

const BUVETTE: StandResponsable = {
  standId: 's1',
  standNom: 'Buvette',
  nominatif: false,
  vacations: [
    {
      debut: '2026-07-12T10:00:00',
      fin: '2026-07-12T12:00:00',
      pourvus: 1,
      vides: 1,
      personnes: [],
    },
    {
      debut: '2026-07-11T22:00:00',
      fin: '2026-07-12T02:00:00',
      pourvus: 2,
      vides: 0,
      personnes: [],
    },
  ],
};

const ALICE: MembreEquipe = {
  cle: '1',
  prenom: 'Alice',
  nom: 'Martin',
  plages: [
    { debut: '2026-07-11T10:00:00', fin: '2026-07-11T12:00:00', standNom: 'Buvette' },
    { debut: '2026-07-11T14:00:00', fin: '2026-07-11T16:00:00', standNom: null },
  ],
};

function view(patch: Partial<ResponsableView> = {}): ResponsableView {
  return { editionId: '2026', editionNom: 'Année 2026', stands: [BUVETTE], equipe: [], ...patch };
}

describe('daysOf and initialDay', () => {
  it('lists the days shifts start on, in order', () => {
    expect(daysOf(view())).toEqual(['2026-07-11', '2026-07-12']);
    expect(daysOf(null)).toEqual([]);
  });

  it('opens on the day asked, else today or the next one, else the first', () => {
    const days = ['2026-07-11', '2026-07-12'];
    expect(initialDay(days, '2026-07-12', '2026-01-01')).toBe('2026-07-12');
    expect(initialDay(days, '2030-01-01', '2026-07-12')).toBe('2026-07-12');
    expect(initialDay(days, null, '2026-01-01')).toBe('2026-07-11');
    expect(initialDay(days, null, '2027-01-01')).toBe('2026-07-11');
    expect(initialDay([], null, '2026-01-01')).toBeNull();
  });
});

describe('one day of a stand and of a person', () => {
  it('keeps the shifts starting that day, a night shift on the evening it opens', () => {
    expect(shiftsOn(BUVETTE, '2026-07-11').map((v) => v.debut)).toEqual(['2026-07-11T22:00:00']);
  });

  it("keeps a person's windows of the day, and the team working that day", () => {
    const vue = view({ equipe: [ALICE] });
    expect(windowsOn(ALICE, '2026-07-11')).toHaveLength(2);
    expect(teamOn(vue, '2026-07-11')).toEqual([ALICE]);
    expect(teamOn(vue, '2026-07-12')).toEqual([]);
  });
});

describe('labels', () => {
  it('dates the end of a shift past midnight', () => {
    expect(windowLabel('2026-07-11T10:00:00', '2026-07-11T12:00:00')).toBe('10:00 – 12:00');
    expect(windowLabel('2026-07-11T22:00:00', '2026-07-12T02:00:00')).toBe(
      '22:00 – 02:00 (lendemain)',
    );
  });

  it('says a head count, never a name', () => {
    expect(staffingLabel(BUVETTE.vacations[0])).toBe('1 animateur sur 2 place(s)');
    expect(staffingLabel(BUVETTE.vacations[1])).toBe('2 animateurs sur 2 place(s)');
  });

  it('tells whether any stand is shown by name', () => {
    expect(anyNamed(view())).toBe(false);
    expect(anyNamed(view({ stands: [{ ...BUVETTE, nominatif: true }] }))).toBe(true);
  });
});
