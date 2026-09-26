import { describe, expect, it } from 'vitest';
import { cibleDepot, moveTargets, resumeDeplacement, scoreLabel } from './deplacement';
import type { Animateur, DeplacementSimulation, PosteAffectation, Stand } from '../core/models';

function poste(id: string): PosteAffectation {
  return { id, stand: null, creneau: null, animateur: null };
}

function element(posteId: string | null): Element {
  const el = document.createElement('span');
  if (posteId) {
    el.dataset['posteId'] = posteId;
  }
  return el;
}

describe('cibleDepot', () => {
  const libres = [poste('L1'), poste('L2')];
  const tenus = [poste('T1')];

  it('takes the seat under the pointer, held or free', () => {
    expect(cibleDepot(element('T1'), libres, tenus)).toBe('T1');
    expect(cibleDepot(element('L2'), libres, tenus)).toBe('L2');
  });

  it('falls back to the first free seat when the pointer names nothing of this line', () => {
    expect(cibleDepot(element(null), libres, tenus)).toBe('L1');
    expect(cibleDepot(null, libres, tenus)).toBe('L1');
    // A seat of another line under the pointer is not this line's business.
    expect(cibleDepot(element('AILLEURS'), libres, tenus)).toBe('L1');
  });

  it('answers null on a full line with nobody under the pointer, rather than guessing whom to swap', () => {
    expect(cibleDepot(element(null), [], tenus)).toBeNull();
  });
});

describe('resumeDeplacement', () => {
  const noms: Record<string, string> = { A: 'Alice', B: 'Bruno' };
  const nomDe = (id: string) => noms[id] ?? id;
  const base: DeplacementSimulation = {
    posteSourceId: 'P1',
    posteCibleId: 'P2',
    animateurSourceId: 'A',
    animateurCibleId: 'B',
    scoreAvant: { hardScore: 0, mediumScore: -3, softScore: -1 },
    scoreApres: { hardScore: 0, mediumScore: -2, softScore: -1 },
    delta: { hardScore: 0, mediumScore: 1, softScore: 0 },
    casseContrainteDure: false,
    nouvellesViolationsDures: [],
  };

  it('tells a swap, a move and a hand-over apart, and always shows both scores', () => {
    expect(resumeDeplacement(base, nomDe).title).toBe('Alice et Bruno ont échangé leurs sièges.');
    expect(resumeDeplacement({ ...base, animateurCibleId: null }, nomDe).title).toContain(
      'Alice a changé de siège',
    );
    expect(resumeDeplacement({ ...base, posteCibleId: null }, nomDe).title).toBe(
      'Bruno prend le siège de Alice.',
    );
    expect(resumeDeplacement(base, nomDe).message).toBe('Score : 0/-3/-1 → 0/-2/-1.');
  });

  it('says who was placed on a seat that held nobody', () => {
    expect(
      resumeDeplacement({ ...base, animateurSourceId: null, posteCibleId: null }, nomDe).title,
    ).toBe('Bruno est placé(e) sur ce siège.');
  });

  it('formats a score on the three levels', () => {
    expect(scoreLabel({ hardScore: -1, mediumScore: 0, softScore: 12 })).toBe('-1/0/12');
  });
});

describe('moveTargets', () => {
  const seat = (
    id: string,
    standId: string,
    heureDebut: string,
    holder: string | null,
    jour = 1,
  ): PosteAffectation => ({
    id,
    stand: { id: standId, nom: standId } as Stand,
    creneau: {
      id: Number(heureDebut.slice(0, 2)) + jour * 100,
      jour,
      date: '',
      heureDebut,
      heureFin: '23:00',
    },
    animateur: holder ? ({ id: holder, prenom: holder, nom: 'X' } as Animateur) : null,
  });
  const source = seat('S', 'Tir', '10:00', 'Alice');
  const postes = [
    source,
    seat('S2', 'Tir', '10:00', null),
    seat('B1', 'Belote', '14:00', 'Bruno'),
    seat('B2', 'Belote', '14:00', null),
    seat('A1', 'Awalé', '10:00', null),
    seat('D', 'Dames', '10:00', null, 2),
  ];

  // Every other line of the day, as the drop accepts it: free seats before
  // held ones on a line, the day read in order — never the source's own line,
  // never another day.
  it('offers every other line of the day, in the order the day reads', () => {
    const targets = moveTargets(postes, source, () => false);

    expect(targets.map((target) => target.id)).toEqual(['A1', 'B2', 'B1']);
    expect(targets[0].label).toBe('10:00–23:00 · Awalé — siège libre');
    expect(targets[2].label).toBe('14:00–23:00 · Belote — échanger avec Bruno X');
  });

  it('leaves out the lines a lock closes', () => {
    const targets = moveTargets(postes, source, (poste) => poste.stand?.id === 'Belote');

    expect(targets.map((target) => target.id)).toEqual(['A1']);
  });
});
