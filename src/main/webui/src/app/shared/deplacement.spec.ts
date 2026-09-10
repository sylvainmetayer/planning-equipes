import { describe, expect, it } from 'vitest';
import { cibleDepot, resumeDeplacement, scoreLabel } from './deplacement';
import type { DeplacementSimulation, PosteAffectation } from '../core/models';

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

  it('formats a score on the three levels', () => {
    expect(scoreLabel({ hardScore: -1, mediumScore: 0, softScore: 12 })).toBe('-1/0/12');
  });
});
