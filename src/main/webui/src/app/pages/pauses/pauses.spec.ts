import { describe, expect, it } from 'vitest';
import { JourneeAnimateurPauses, PauseDueView, RapportPauses } from '../../core/models';
import { groupesDuJour, heure, joursDuRapport, libelleRelais, planifieesDuJour, syntheseDuJour } from './pauses';

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
    ...overrides
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
    ...overrides
  };
}

function rapport(journees: JourneeAnimateurPauses[], overrides: Partial<RapportPauses> = {}): RapportPauses {
  return {
    pauseSurPoste: true,
    journeesAnalysees: journees.length,
    pausesDues: journees.flatMap((j) => j.sequences).flatMap((s) => s.pausesDues).length,
    relaisManquants: 0,
    journees,
    message: '',
    ...overrides
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
        journee({ animateurId: 'carol', date: '2026-07-11', jour: 4 })
      ])
    );

    expect(jours).toEqual([
      { date: '2026-07-10', jour: 3, title: 'J3 — 2026-07-10' },
      { date: '2026-07-11', jour: 4, title: 'J4 — 2026-07-11' }
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
              pausesDues: [pause({ debut: '16:00:00', fin: '16:20:00', heureLimite: '16:00:00' }), pause({ debut: '20:20:00', fin: '20:40:00', heureLimite: '20:20:00', standId: 'AUTRE', standNom: 'Autre' })]
            }
          ]
        }),
        journee({ animateurId: 'bob', nomComplet: 'Bob Durand', sequences: [{ debut: '13:00:00', fin: '20:00:00', minutes: 420, pausesDues: [pause({ debut: '18:40:00', fin: '19:00:00', relais: [] })] }] }),
        journee({ animateurId: 'dan', date: '2026-07-11' })
      ]),
      '2026-07-10'
    );

    // Nobody short of a relay here: plain alphabetical order.
    expect(groupes.map((g) => g.standId)).toEqual(['AUTRE', 'JEUX']);
    const jeux = groupes.find((g) => g.standId === 'JEUX')!;
    // Rotation order: by start time.
    expect(jeux.lignes.map((l) => [l.nomComplet, l.debut])).toEqual([
      ['Alice Martin', '16:00:00'],
      ['Bob Durand', '18:40:00']
    ]);
    expect(jeux.lignes[0].sequenceMinutes).toBe(630);
  });

  it('puts the stands short of a relay first and counts them', () => {
    const groupes = groupesDuJour(
      rapport([
        journee({ animateurId: 'a', nomComplet: 'A', sequences: [{ debut: '13:00:00', fin: '20:00:00', minutes: 420, pausesDues: [pause({ standId: 'AAA', standNom: 'Aaa' })] }] }),
        journee({ animateurId: 'b', nomComplet: 'B', sequences: [{ debut: '13:00:00', fin: '20:00:00', minutes: 420, pausesDues: [pause({ standId: 'ZZZ', standNom: 'Zzz', relais: [], relaisDisponible: false })] }] })
      ]),
      '2026-07-10'
    );

    expect(groupes.map((g) => [g.standId, g.relaisManquants])).toEqual([
      ['ZZZ', 1],
      ['AAA', 0]
    ]);
  });

  it('filters on the animateur, the stand or a relay, and on "without relay" only', () => {
    const journees = [
      journee(),
      journee({ animateurId: 'carol', nomComplet: 'Carol Petit', sequences: [{ debut: '13:00:00', fin: '20:00:00', minutes: 420, pausesDues: [pause({ standId: 'REF', standNom: 'Référencement', relais: [], relaisDisponible: false })] }] })
    ];

    expect(groupesDuJour(rapport(journees), '2026-07-10', 'alice').flatMap((g) => g.lignes).map((l) => l.animateurId)).toEqual(['alice']);
    expect(groupesDuJour(rapport(journees), '2026-07-10', 'référencement').map((g) => g.standId)).toEqual(['REF']);
    expect(groupesDuJour(rapport(journees), '2026-07-10', 'durand').flatMap((g) => g.lignes).map((l) => l.animateurId)).toEqual(['alice']);
    expect(groupesDuJour(rapport(journees), '2026-07-10', '', true).flatMap((g) => g.lignes).map((l) => l.animateurId)).toEqual(['carol']);
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
      sequences: [{ debut: '13:00:00', fin: '20:00:00', minutes: 420, pausesDues: [pause({ relais: [], relaisDisponible: false })] }],
      pausesPlanifiees: [{ debut: '12:00:00', fin: '12:30:00', minutes: 30 }]
    }),
    journee({ animateurId: 'dan', date: '2026-07-11', pausesPlanifiees: [{ debut: '12:00:00', fin: '13:00:00', minutes: 60 }] })
  ];

  it('lists the scheduled gaps of the day by start time, then filters on the name', () => {
    expect(planifieesDuJour(rapport(journees), '2026-07-10').map((p) => [p.nomComplet, p.debut, p.minutes])).toEqual([
      ['Bob Durand', '12:00:00', 30],
      ['Alice Martin', '13:00:00', 60]
    ]);
    expect(planifieesDuJour(rapport(journees), '2026-07-10', 'alice')).toHaveLength(1);
    expect(planifieesDuJour(null, '2026-07-10')).toEqual([]);
  });

  it('counts the day: breaks, of which without relay, people, scheduled gaps', () => {
    expect(syntheseDuJour(rapport(journees), '2026-07-10')).toEqual({ animateurs: 2, pauses: 2, relaisManquants: 1, planifiees: 2 });
    expect(syntheseDuJour(rapport(journees), '2026-07-11')).toEqual({ animateurs: 1, pauses: 1, relaisManquants: 0, planifiees: 1 });
    expect(syntheseDuJour(null, '2026-07-10')).toEqual({ animateurs: 0, pauses: 0, relaisManquants: 0, planifiees: 0 });
  });
});

describe('libelleRelais', () => {
  it('names the relays in order, and nothing when nobody', () => {
    expect(libelleRelais(pause({ relais: [{ animateurId: 'b', nomComplet: 'Bob' }, { animateurId: 'z', nomComplet: 'Zoé' }] }))).toBe('Bob, Zoé');
    expect(libelleRelais(pause({ relais: [] }))).toBe('');
  });
});
