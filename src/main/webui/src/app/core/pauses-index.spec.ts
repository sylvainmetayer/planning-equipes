import { describe, expect, it } from 'vitest';
import { CoupureRepasView, JourneeAnimateurPauses, PauseDueView, RapportPauses } from './models';
import {
  compterSansRelais,
  coupuresOf,
  indexerCoupures,
  indexerPauses,
  libelleCoupure,
  libellePause,
  pausesDe,
  segmentsCoupure,
  segmentsPause,
} from './pauses-index';

function coupure(overrides: Partial<CoupureRepasView> = {}): CoupureRepasView {
  return {
    libelle: 'midi',
    fenetreDebut: '12:00:00',
    fenetreFin: '14:00:00',
    dureeRequiseMinutes: 60,
    debut: '12:00:00',
    fin: '13:00:00',
    plusGrandTrouMinutes: 120,
    satisfaite: true,
    ...overrides,
  };
}

function pause(overrides: Partial<PauseDueView> = {}): PauseDueView {
  return {
    debut: '18:40:00',
    fin: '19:00:00',
    heureLimite: '19:00:00',
    dureeMinutes: 20,
    standId: 'JEUX',
    standNom: 'Village des jeux',
    relais: [{ animateurId: 'bob', nomComplet: 'Bob Durand' }],
    relaisDisponible: true,
    simultanee: false,
    ...overrides,
  };
}

function journee(overrides: Partial<JourneeAnimateurPauses> = {}): JourneeAnimateurPauses {
  return {
    animateurId: 'alice',
    nomComplet: 'Alice Martin',
    mineur: false,
    date: '2026-07-10',
    jour: 3,
    sequences: [{ debut: '13:00:00', fin: '20:00:00', minutes: 420, pausesDues: [pause()] }],
    pausesPlanifiees: [],
    coupuresRepas: [],
    ...overrides,
  };
}

function rapport(journees: JourneeAnimateurPauses[]): RapportPauses {
  return {
    journeesAnalysees: journees.length,
    pausesDues: 0,
    relaisManquants: 0,
    coupuresRepasDues: 0,
    coupuresRepasManquantes: 0,
    journees,
    message: '',
  };
}

describe('indexerPauses / pausesDe', () => {
  it('indexes each animateur-day, breaks in start order, and answers nothing for the others', () => {
    const index = indexerPauses(
      rapport([
        journee({
          sequences: [
            {
              debut: '08:00:00',
              fin: '20:30:00',
              minutes: 750,
              pausesDues: [
                pause({ debut: '20:20:00', fin: '20:40:00' }),
                pause({ debut: '14:00:00', fin: '14:20:00' }),
              ],
            },
          ],
        }),
        journee({
          animateurId: 'bob',
          sequences: [{ debut: '13:00:00', fin: '18:00:00', minutes: 300, pausesDues: [] }],
        }),
      ]),
    );

    expect(pausesDe(index, '2026-07-10', 'alice').map((p) => p.debut)).toEqual([
      '14:00:00',
      '20:20:00',
    ]);
    expect(pausesDe(index, '2026-07-10', 'bob')).toEqual([]);
    expect(pausesDe(index, '2026-07-11', 'alice')).toEqual([]);
    expect(pausesDe(indexerPauses(null), '2026-07-10', 'alice')).toEqual([]);
    // A day without a date can never match a dated break.
    expect(pausesDe(index, null, 'alice')).toEqual([]);
    expect(pausesDe(index, undefined, 'alice')).toEqual([]);
  });
});

describe('segmentsPause', () => {
  it('keeps the stand and the créneau of a break, so a relay-less one can open the bench', () => {
    const segments = segmentsPause(
      [pause({ creneauId: 41 }), pause({ debut: '19:30:00', fin: '19:50:00' })],
      13 * 60,
      420,
    );

    expect(segments.map((segment) => [segment.standId, segment.creneauId])).toEqual([
      ['JEUX', 41],
      ['JEUX', null],
    ]);
  });

  it('places a break on the track as a share of it', () => {
    // Track 13:00 → 20:00 (420 min): 18:40 is at 340/420, twenty minutes are 20/420.
    const [segment] = segmentsPause([pause()], 13 * 60, 420);

    expect(segment.offsetPercent).toBeCloseTo((340 / 420) * 100, 5);
    expect(segment.widthPercent).toBeCloseTo((20 / 420) * 100, 5);
    expect(segment.heureDebut).toBe('18:40');
    expect(segment.heureFin).toBe('19:00');
    expect(segment.withoutRelais).toBe(false);
    expect(segment.label).toBe('Pause 18:40 – 19:00 sur Village des jeux');
  });

  it('flags the relay-less and the simultaneous breaks, in that order of importance', () => {
    const [seul, ensemble] = segmentsPause(
      [
        pause({ relais: [], relaisDisponible: false, simultanee: true }),
        pause({ debut: '19:00:00', fin: '19:20:00', simultanee: true }),
      ],
      13 * 60,
      420,
    );

    expect(seul.withoutRelais).toBe(true);
    expect(seul.label).toContain("personne d'autre sur le stand");
    expect(ensemble.withoutRelais).toBe(false);
    expect(ensemble.simultanee).toBe(true);
    expect(ensemble.label).toContain("en même temps qu'une autre pause");
  });

  it('clips a break to the track and drops one outside it', () => {
    const segments = segmentsPause(
      [
        pause({ debut: '12:50:00', fin: '13:10:00' }),
        pause({ debut: '21:00:00', fin: '21:20:00' }),
      ],
      13 * 60,
      420,
    );

    expect(segments).toHaveLength(1);
    expect(segments[0].offsetPercent).toBe(0);
    expect(segments[0].widthPercent).toBeCloseTo((10 / 420) * 100, 5);
  });

  it('draws a break crossing midnight to the end of the track', () => {
    const [segment] = segmentsPause([pause({ debut: '23:50:00', fin: '00:10:00' })], 18 * 60, 360);

    expect(segment.offsetPercent).toBeCloseTo((350 / 360) * 100, 5);
    expect(segment.widthPercent).toBeCloseTo((10 / 360) * 100, 5);
  });
});

describe('compterSansRelais / libellePause', () => {
  it('counts the relay-less breaks of the report, or of one day', () => {
    const r = rapport([
      journee({
        sequences: [
          {
            debut: '13:00:00',
            fin: '20:00:00',
            minutes: 420,
            pausesDues: [pause({ relais: [], relaisDisponible: false })],
          },
        ],
      }),
      journee({
        animateurId: 'bob',
        date: '2026-07-11',
        sequences: [
          {
            debut: '13:00:00',
            fin: '20:00:00',
            minutes: 420,
            pausesDues: [pause({ relais: [], relaisDisponible: false }), pause()],
          },
        ],
      }),
    ]);

    expect(compterSansRelais(r)).toBe(2);
    expect(compterSansRelais(r, '2026-07-11')).toBe(1);
    expect(compterSansRelais(null)).toBe(0);
  });

  it('names the stand and the times, and says what is wrong', () => {
    expect(libellePause(pause())).toBe('Pause 18:40 – 19:00 sur Village des jeux');
    expect(libellePause(pause({ relaisDisponible: false, relais: [] }))).toBe(
      "Pause 18:40 – 19:00 sur Village des jeux — personne d'autre sur le stand",
    );
  });
});

/**
 * Issue #598, second pass: the meal break was computed and drawn nowhere.
 * « Quand est-ce que je mange » is the first thing somebody reads their own
 * planning for, so the timeline and the PDF both carry it now — and it is a
 * different thing from the twenty-minute break on the stand above.
 */
describe('indexerCoupures', () => {
  it('indexes the meal breaks by animateur-day, earliest first', () => {
    const index = indexerCoupures(
      rapport([
        journee({
          coupuresRepas: [
            coupure({ libelle: 'soir', debut: '19:00:00', fin: '20:00:00' }),
            coupure(),
          ],
        }),
      ]),
    );

    expect(coupuresOf(index, '2026-07-10', 'alice').map((found) => found.libelle)).toEqual([
      'midi',
      'soir',
    ]);
    expect(coupuresOf(index, '2026-07-11', 'alice')).toEqual([]);
  });

  // A day that leaves no room owes a break the plan cannot place. Drawing it at
  // midnight would be a lie; the Pauses screen already reports it as a breach.
  it('leaves out a break the day makes no room for, rather than drawing it at midnight', () => {
    const index = indexerCoupures(
      rapport([
        journee({ coupuresRepas: [coupure({ debut: null, fin: null, satisfaite: false })] }),
      ]),
    );

    expect(coupuresOf(index, '2026-07-10', 'alice')).toEqual([]);
  });
});

describe('segmentsCoupure', () => {
  it('places the meal break on the same track as the breaks', () => {
    // A 08:00-20:00 track, 720 minutes: a 12:00-13:00 meal starts a third in
    // and takes a twelfth of it.
    const [segment] = segmentsCoupure([coupure()], 8 * 60, 12 * 60);

    expect(segment.offsetPercent).toBeCloseTo(33.33, 1);
    expect(segment.widthPercent).toBeCloseTo(8.33, 1);
    expect(segment.heureDebut).toBe('12:00');
    expect(segment.label).toContain('Repas (midi)');
  });

  it('drops a meal break the track does not cover', () => {
    expect(segmentsCoupure([coupure()], 14 * 60, 6 * 60)).toEqual([]);
  });

  it('names the window it belongs to', () => {
    expect(libelleCoupure(coupure({ libelle: 'soir', debut: '19:00:00', fin: '20:00:00' }))).toBe(
      'Repas (soir) 19:00 – 20:00',
    );
  });
});
