// How the counts of « ce qui a changé depuis cette résolution » read in
// French: the family names, their plural, and the summary line built from them.

import { describe, expect, it } from 'vitest';
import { familleLisible, resumeLisible } from './changements';

describe('familleLisible', () => {
  it('names a family in the singular and in the plural', () => {
    expect(familleLisible('ANIMATEUR', 1)).toBe('animateur');
    expect(familleLisible('ANIMATEUR', 3)).toBe('animateurs');
    expect(familleLisible('CRENEAU', 1)).toBe('créneau');
    expect(familleLisible('CRENEAU', 2)).toBe('créneaux');
  });

  it('reads a family this build does not know as itself rather than dropping it', () => {
    expect(familleLisible('QUELQUE_CHOSE', 2)).toBe('quelque_chose');
  });
});

describe('resumeLisible', () => {
  it('writes one count per family, in the order the server sent them', () => {
    expect(
      resumeLisible([
        { entite: 'ANIMATEUR', nombre: 3 },
        { entite: 'STAND', nombre: 1 },
        { entite: 'CRENEAU', nombre: 2 },
      ]),
    ).toBe('3 animateurs, 1 stand, 2 créneaux');
  });

  it('is empty when nothing moved', () => {
    expect(resumeLisible([])).toBe('');
  });
});
