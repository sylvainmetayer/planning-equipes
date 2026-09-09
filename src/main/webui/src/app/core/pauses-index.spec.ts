import { describe, expect, it } from 'vitest';
import { JourneeAnimateurPauses, PauseDueView, RapportPauses } from './models';
import {
  compterSansRelais,
  indexerPauses,
  libellePause,
  pausesDe,
  segmentsPause,
} from './pauses-index';

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
    pauseSurPoste: true,
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
  it('places a break on the track as a share of it', () => {
    // Track 13:00 → 20:00 (420 min): 18:40 is at 340/420, twenty minutes are 20/420.
    const [segment] = segmentsPause([pause()], 13 * 60, 420);

    expect(segment.offsetPercent).toBeCloseTo((340 / 420) * 100, 5);
    expect(segment.widthPercent).toBeCloseTo((20 / 420) * 100, 5);
    expect(segment.heureDebut).toBe('18:40');
    expect(segment.heureFin).toBe('19:00');
    expect(segment.sansRelais).toBe(false);
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

    expect(seul.sansRelais).toBe(true);
    expect(seul.label).toContain("personne d'autre sur le stand");
    expect(ensemble.sansRelais).toBe(false);
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
