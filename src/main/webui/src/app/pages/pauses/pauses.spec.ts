import { describe, expect, it } from 'vitest';
import { JourneeAnimateurPauses, PauseDueView, RapportPauses } from '../../core/models';
import {
  coupuresRepasJournee,
  groupesDuJour,
  heure,
  joursDuRapport,
  libelleRelais,
  minutesManquantes,
  planifieesDuJour,
  syntheseDuJour,
} from './pauses';

function pause(overrides: Partial<PauseDueView> = {}): PauseDueView {
  return {
    debut: '19:00:00',
    fin: '19:20:00',
    simultanee: false,
    heureLimite: '19:00:00',
    dureeMinutes: 20,
    standId: 'JEUX',
    standNom: 'Village des jeux',
    relais: [{ animateurId: 'bob', nomComplet: 'Bob Durand' }],
    relaisDisponible: true,
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

function rapport(
  journees: JourneeAnimateurPauses[],
  overrides: Partial<RapportPauses> = {},
): RapportPauses {
  return {
    pauseSurPoste: true,
    journeesAnalysees: journees.length,
    pausesDues: journees.flatMap((j) => j.sequences).flatMap((s) => s.pausesDues).length,
    relaisManquants: 0,
    coupuresRepasDues: journees.flatMap((j) => j.coupuresRepas).length,
    coupuresRepasManquantes: journees.flatMap((j) => j.coupuresRepas).filter((c) => !c.satisfaite)
      .length,
    journees,
    message: '',
    ...overrides,
  };
}

describe('heure', () => {
  it('shortens a server time and leaves a short one alone', () => {
    expect(heure('19:00:00')).toBe('19:00');
    expect(heure('19:00')).toBe('19:00');
    // Same helper as every other view: the server always sends a time here.
    expect(heure('')).toBe('');
  });
});

describe('joursDuRapport', () => {
  it('lists each day once, chronologically, with its number', () => {
    const jours = joursDuRapport(
      rapport([
        journee({ date: '2026-07-11', jour: 4 }),
        journee({ animateurId: 'bob', date: '2026-07-10', jour: 3 }),
        journee({ animateurId: 'carol', date: '2026-07-11', jour: 4 }),
      ]),
    );

    expect(jours).toEqual([
      { date: '2026-07-10', jour: 3, title: 'J3 — 2026-07-10' },
      { date: '2026-07-11', jour: 4, title: 'J4 — 2026-07-11' },
    ]);
  });

  it('is empty without a report', () => {
    expect(joursDuRapport(null)).toEqual([]);
    expect(joursDuRapport(rapport([]))).toEqual([]);
  });
});

describe('groupesDuJour', () => {
  it('groups the breaks of the day by stand, deadlines in order, other days left out', () => {
    const groupes = groupesDuJour(
      rapport([
        journee({
          sequences: [
            {
              debut: '10:00:00',
              fin: '20:30:00',
              minutes: 630,
              pausesDues: [
                pause({ debut: '16:00:00', fin: '16:20:00', heureLimite: '16:00:00' }),
                pause({
                  debut: '20:20:00',
                  fin: '20:40:00',
                  heureLimite: '20:20:00',
                  standId: 'AUTRE',
                  standNom: 'Autre',
                }),
              ],
            },
          ],
        }),
        journee({
          animateurId: 'bob',
          nomComplet: 'Bob Durand',
          sequences: [
            {
              debut: '13:00:00',
              fin: '20:00:00',
              minutes: 420,
              pausesDues: [pause({ debut: '18:40:00', fin: '19:00:00', relais: [] })],
            },
          ],
        }),
        journee({ animateurId: 'dan', date: '2026-07-11' }),
      ]),
      '2026-07-10',
    );

    // Nobody short of a relay here: plain alphabetical order.
    expect(groupes.map((g) => g.standId)).toEqual(['AUTRE', 'JEUX']);
    const jeux = groupes.find((g) => g.standId === 'JEUX')!;
    // Rotation order: by start time.
    expect(jeux.lignes.map((l) => [l.nomComplet, l.debut])).toEqual([
      ['Alice Martin', '16:00:00'],
      ['Bob Durand', '18:40:00'],
    ]);
    expect(jeux.lignes[0].sequenceMinutes).toBe(630);
  });

  it('carries the créneau of each break for the bench link, null when the server named none', () => {
    const groupes = groupesDuJour(
      rapport([
        journee({
          sequences: [
            {
              debut: '13:00:00',
              fin: '20:00:00',
              minutes: 420,
              pausesDues: [pause({ creneauId: 41 }), pause({ debut: '19:30:00', fin: '19:50:00' })],
            },
          ],
        }),
      ]),
      '2026-07-10',
    );

    expect(groupes[0].lignes.map((ligne) => ligne.creneauId)).toEqual([41, null]);
    expect(groupes[0].lignes[0].standId).toBe('JEUX');
  });

  it('puts the stands short of a relay first and counts them', () => {
    const groupes = groupesDuJour(
      rapport([
        journee({
          animateurId: 'a',
          nomComplet: 'A',
          sequences: [
            {
              debut: '13:00:00',
              fin: '20:00:00',
              minutes: 420,
              pausesDues: [pause({ standId: 'AAA', standNom: 'Aaa' })],
            },
          ],
        }),
        journee({
          animateurId: 'b',
          nomComplet: 'B',
          sequences: [
            {
              debut: '13:00:00',
              fin: '20:00:00',
              minutes: 420,
              pausesDues: [
                pause({ standId: 'ZZZ', standNom: 'Zzz', relais: [], relaisDisponible: false }),
              ],
            },
          ],
        }),
      ]),
      '2026-07-10',
    );

    expect(groupes.map((g) => [g.standId, g.relaisManquants])).toEqual([
      ['ZZZ', 1],
      ['AAA', 0],
    ]);
  });

  it('filters on the animateur, the stand or a relay, and on "without relay" only', () => {
    const journees = [
      journee(),
      journee({
        animateurId: 'carol',
        nomComplet: 'Carol Petit',
        sequences: [
          {
            debut: '13:00:00',
            fin: '20:00:00',
            minutes: 420,
            pausesDues: [
              pause({
                standId: 'REF',
                standNom: 'Référencement',
                relais: [],
                relaisDisponible: false,
              }),
            ],
          },
        ],
      }),
    ];

    expect(
      groupesDuJour(rapport(journees), '2026-07-10', 'alice')
        .flatMap((g) => g.lignes)
        .map((l) => l.animateurId),
    ).toEqual(['alice']);
    expect(
      groupesDuJour(rapport(journees), '2026-07-10', 'référencement').map((g) => g.standId),
    ).toEqual(['REF']);
    expect(
      groupesDuJour(rapport(journees), '2026-07-10', 'durand')
        .flatMap((g) => g.lignes)
        .map((l) => l.animateurId),
    ).toEqual(['alice']);
    expect(
      groupesDuJour(rapport(journees), '2026-07-10', '', true)
        .flatMap((g) => g.lignes)
        .map((l) => l.animateurId),
    ).toEqual(['carol']);
    expect(groupesDuJour(rapport(journees), '2026-07-10', 'nobody')).toEqual([]);
  });

  it('is empty without a report or a day', () => {
    expect(groupesDuJour(null, '2026-07-10')).toEqual([]);
    expect(groupesDuJour(rapport([journee()]), null)).toEqual([]);
    expect(groupesDuJour(rapport([journee()]), '2026-07-12')).toEqual([]);
  });
});

describe('planifieesDuJour / syntheseDuJour', () => {
  const journees = [
    journee({ pausesPlanifiees: [{ debut: '13:00:00', fin: '14:00:00', minutes: 60 }] }),
    journee({
      animateurId: 'bob',
      nomComplet: 'Bob Durand',
      sequences: [
        {
          debut: '13:00:00',
          fin: '20:00:00',
          minutes: 420,
          pausesDues: [pause({ relais: [], relaisDisponible: false })],
        },
      ],
      pausesPlanifiees: [{ debut: '12:00:00', fin: '12:30:00', minutes: 30 }],
    }),
    journee({
      animateurId: 'dan',
      date: '2026-07-11',
      pausesPlanifiees: [{ debut: '12:00:00', fin: '13:00:00', minutes: 60 }],
    }),
  ];

  it('lists the scheduled gaps of the day by start time, then filters on the name', () => {
    expect(
      planifieesDuJour(rapport(journees), '2026-07-10').map((p) => [
        p.nomComplet,
        p.debut,
        p.minutes,
      ]),
    ).toEqual([
      ['Bob Durand', '12:00:00', 30],
      ['Alice Martin', '13:00:00', 60],
    ]);
    expect(planifieesDuJour(rapport(journees), '2026-07-10', 'alice')).toHaveLength(1);
    expect(planifieesDuJour(null, '2026-07-10')).toEqual([]);
  });

  it('counts the day: breaks, of which without relay, people, scheduled gaps, meal breaks', () => {
    expect(syntheseDuJour(rapport(journees), '2026-07-10')).toEqual({
      animateurs: 2,
      pauses: 2,
      relaisManquants: 1,
      planifiees: 2,
      coupuresRepas: 0,
      coupuresRepasManquantes: 0,
    });
    expect(syntheseDuJour(rapport(journees), '2026-07-11')).toEqual({
      animateurs: 1,
      pauses: 1,
      relaisManquants: 0,
      planifiees: 1,
      coupuresRepas: 0,
      coupuresRepasManquantes: 0,
    });
    expect(syntheseDuJour(null, '2026-07-10')).toEqual({
      animateurs: 0,
      pauses: 0,
      relaisManquants: 0,
      planifiees: 0,
      coupuresRepas: 0,
      coupuresRepasManquantes: 0,
    });
  });
});

describe('coupuresRepasJournee', () => {
  const midi = {
    libelle: 'midi',
    fenetreDebut: '12:00:00',
    fenetreFin: '14:00:00',
    dureeRequiseMinutes: 60,
  };

  const noRoom = journee({
    animateurId: 'a84',
    nomComplet: 'Zoé Nguyen',
    coupuresRepas: [
      { ...midi, debut: null, fin: null, plusGrandTrouMinutes: 0, satisfaite: false },
    ],
  });
  const withRoom = journee({
    animateurId: 'alice',
    nomComplet: 'Alice Martin',
    coupuresRepas: [
      { ...midi, debut: '13:00:00', fin: '14:00:00', plusGrandTrouMinutes: 60, satisfaite: true },
    ],
  });

  it('puts the days short of a meal break first, and says how many minutes are missing', () => {
    const lignes = coupuresRepasJournee(rapport([withRoom, noRoom]), '2026-07-10');

    expect(
      lignes.map((ligne) => [ligne.nomComplet, ligne.satisfaite, ligne.minutesManquantes]),
    ).toEqual([
      ['Zoé Nguyen', false, 60],
      ['Alice Martin', true, 0],
    ]);
  });

  it('narrows to the missing ones on demand, and filters by name', () => {
    expect(coupuresRepasJournee(rapport([withRoom, noRoom]), '2026-07-10', '', true)).toHaveLength(
      1,
    );
    expect(coupuresRepasJournee(rapport([withRoom, noRoom]), '2026-07-10', 'alice')).toHaveLength(
      1,
    );
    expect(coupuresRepasJournee(rapport([withRoom, noRoom]), '2026-07-11')).toEqual([]);
    expect(coupuresRepasJournee(null, '2026-07-10')).toEqual([]);
  });

  it('counts a day that owes a meal break, whether or not it fits', () => {
    expect(syntheseDuJour(rapport([withRoom, noRoom]), '2026-07-10')).toMatchObject({
      coupuresRepas: 2,
      coupuresRepasManquantes: 1,
    });
  });

  // What the hard rule penalises, never below zero.
  it('never reports a negative shortfall', () => {
    expect(minutesManquantes({ dureeRequiseMinutes: 60, plusGrandTrouMinutes: 90 })).toBe(0);
    expect(minutesManquantes({ dureeRequiseMinutes: 60, plusGrandTrouMinutes: 15 })).toBe(45);
  });
});

describe('libelleRelais', () => {
  it('names the relays in order, and nothing when nobody', () => {
    expect(
      libelleRelais(
        pause({
          relais: [
            { animateurId: 'b', nomComplet: 'Bob' },
            { animateurId: 'z', nomComplet: 'Zoé' },
          ],
        }),
      ),
    ).toBe('Bob, Zoé');
    expect(libelleRelais(pause({ relais: [] }))).toBe('');
  });
});
