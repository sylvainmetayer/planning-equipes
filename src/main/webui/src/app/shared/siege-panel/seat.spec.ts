import { describe, expect, it } from 'vitest';
import {
  Animateur,
  ConstraintView,
  Creneau,
  PosteAffectation,
  Stand,
  VerrouillagePlanning,
} from '../../core/models';
import {
  lockLabel,
  nextSteps,
  qualityWarning,
  readableName,
  resolveSeat,
  ruleLabel,
  scoreEffect,
  seatLocks,
} from './seat';

const stand = (id: string): Stand => ({ id, nom: id }) as Stand;
const creneau = (id: number, heureDebut = '10:00:00', heureFin = '12:00:00'): Creneau => ({
  id,
  jour: 1,
  date: '2026-07-18',
  heureDebut,
  heureFin,
});
const animateur = (id: string): Animateur => ({ id, prenom: id, nom: 'X' }) as Animateur;
const poste = (
  id: string,
  standId: string,
  creneauId: number,
  animateurId: string | null,
): PosteAffectation => ({
  id,
  stand: stand(standId),
  creneau: creneau(creneauId),
  animateur: animateurId ? animateur(animateurId) : null,
});

const lock = (partial: Partial<VerrouillagePlanning>): VerrouillagePlanning => ({
  id: 'v',
  type: 'STAND',
  animateurId: null,
  standId: null,
  creneauId: null,
  jour: null,
  raison: null,
  ...partial,
});

describe('resolveSeat', () => {
  const postes = [
    poste('P2', 'tir', 1, 'alice'),
    poste('P10', 'tir', 1, null),
    poste('P3', 'tir', 1, null),
    poste('P4', 'quilles', 1, 'bruno'),
    poste('P5', 'tir', 2, 'bruno'),
  ];

  it('finds a seat by its id', () => {
    expect(resolveSeat(postes, { posteId: 'P4' })?.id).toBe('P4');
    expect(resolveSeat(postes, { posteId: 'P9' })).toBeNull();
  });

  // The old bench address names a timeslot and a stand: its question was
  // « who could take the empty seat ». Ordered by id, numerically, so the same
  // address opens the same seat.
  it('opens the first free seat of a timeslot and a stand', () => {
    expect(resolveSeat(postes, { creneauId: 1, standId: 'tir' })?.id).toBe('P3');
    expect(resolveSeat(postes, { creneauId: 1 })?.id).toBe('P3');
  });

  it('falls back on the first seat when none is free, and on nothing when none exists', () => {
    expect(resolveSeat(postes, { creneauId: 1, standId: 'quilles' })?.id).toBe('P4');
    expect(resolveSeat(postes, { creneauId: 9 })).toBeNull();
  });

  it("opens the named person's seat — a break without relay is theirs", () => {
    expect(resolveSeat(postes, { creneauId: 1, standId: 'tir', animateurId: 'alice' })?.id).toBe(
      'P2',
    );
  });

  it('finds a seat by its day, stand and hours, as a line of the changes names it', () => {
    expect(
      resolveSeat(postes, {
        date: '2026-07-18',
        standId: 'quilles',
        heureDebut: '10:00',
        heureFin: '12:00',
        animateurId: 'bruno',
      })?.id,
    ).toBe('P4');
    expect(
      resolveSeat(postes, { date: '2026-07-18', standId: 'quilles', heureDebut: '14:00' }),
    ).toBeNull();
  });
});

describe('seatLocks', () => {
  const held = poste('P1', 'tir', 1, 'alice');

  it('reads every lock reaching the seat, the one on the seat itself first and alone removable', () => {
    const locks = seatLocks(
      [
        lock({ id: 'stand', type: 'STAND', standId: 'tir' }),
        lock({ id: 'autre', type: 'STAND', standId: 'quilles' }),
        lock({ id: 'siege', type: 'ANIMATEUR_CRENEAU', animateurId: 'alice', creneauId: 1 }),
        lock({ id: 'jour', type: 'JOUR', jour: '2026-07-18' }),
        lock({ id: 'personne', type: 'ANIMATEUR', animateurId: 'alice' }),
        lock({ id: 'creneau', type: 'CRENEAU', creneauId: 2 }),
      ],
      held,
    );

    expect(locks.map((each) => each.lock.id)).toEqual(['siege', 'stand', 'jour', 'personne']);
    expect(locks.map((each) => each.removable)).toEqual([true, false, false, false]);
    expect(lockLabel(locks[0])).toContain('reste sur ce créneau');
  });

  it("reads no person's lock on an empty seat", () => {
    const empty = poste('P3', 'tir', 1, null);
    expect(
      seatLocks([lock({ type: 'ANIMATEUR_CRENEAU', animateurId: 'alice', creneauId: 1 })], empty),
    ).toEqual([]);
  });
});

describe('ruleLabel', () => {
  const catalogue = new Map<string, ConstraintView>([
    [
      'equilibrerCharge',
      { name: 'equilibrerCharge', libelleCourt: 'Charge équilibrée' } as ConstraintView,
    ],
    [
      'reposQuotidien',
      { name: 'reposQuotidien', description: 'Onze heures de repos.' } as ConstraintView,
    ],
  ]);

  it('says the short label of the catalogue, never the Java name', () => {
    expect(ruleLabel('equilibrerCharge', catalogue, 'Une description')).toBe('Charge équilibrée');
  });

  it('falls back on the description, then on the name taken apart', () => {
    expect(ruleLabel('reposQuotidien', catalogue)).toBe('Onze heures de repos.');
    expect(ruleLabel('inconnueRegle', null, 'Dite par le serveur')).toBe('Dite par le serveur');
    expect(ruleLabel('inconnueRegle', null)).toBe('inconnue regle');
    expect(readableName('plafond_heuresSemaine')).toBe('plafond heures semaine');
  });
});

describe('nextSteps', () => {
  it('asks to notify after any write, and to repair only when a seat was left empty', () => {
    expect(nextSteps('place', false)).toEqual({ notify: true, repair: false });
    expect(nextSteps('free', true)).toEqual({ notify: true, repair: true });
    expect(nextSteps('move', true)).toEqual({ notify: true, repair: true });
  });

  it('asks nothing after a lock: the plan the animateurs read did not change', () => {
    expect(nextSteps('lock', false)).toEqual({ notify: false, repair: false });
    expect(nextSteps('unlock', true)).toEqual({ notify: false, repair: false });
  });
});

describe('score wording', () => {
  it('warns when the quality level lost points, and only then', () => {
    expect(qualityWarning({ hardScore: 1, mediumScore: -3, softScore: 0 })).toContain('3');
    expect(qualityWarning({ hardScore: 1, mediumScore: 0, softScore: -5 })).toBeNull();
    expect(qualityWarning(null)).toBeNull();
  });

  it('words the levels that moved, signed', () => {
    expect(scoreEffect({ hardScore: 1, mediumScore: -2, softScore: 0 })).toBe(
      '+1 règle dure · -2 qualité',
    );
    expect(scoreEffect({ hardScore: 0, mediumScore: 0, softScore: 0 })).toBe(
      'sans effet sur le score',
    );
  });
});
