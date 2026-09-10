import { describe, expect, it } from 'vitest';
import { formatDeltaScore } from './score-format';

describe('formatDeltaScore', () => {
  it('rend un delta lisible, jamais une interpolation brute de l’objet score', () => {
    const rendu = formatDeltaScore({ hardScore: 0, mediumScore: 0, softScore: -3 });
    expect(rendu).toBe('0hard / 0medium / -3soft');
    expect(rendu).not.toContain('[object Object]');
  });

  it('marque les améliorations d’un + explicite, les dégradations gardent leur signe', () => {
    expect(formatDeltaScore({ hardScore: 1, mediumScore: -2, softScore: 0 })).toBe(
      '+1hard / -2medium / 0soft',
    );
  });

  it('un échange neutre rend un delta entièrement nul', () => {
    expect(formatDeltaScore({ hardScore: 0, mediumScore: 0, softScore: 0 })).toBe(
      '0hard / 0medium / 0soft',
    );
  });
});
