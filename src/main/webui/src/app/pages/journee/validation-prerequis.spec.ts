import { describe, expect, it } from 'vitest';
import { PrerequisValidation } from '../../core/models';
import { libellePrerequis } from './validation-prerequis';

// The four prerequisites are read out, never enforced: the wording has to carry
// three states, not two — satisfied, missed with its count, and « non vérifié »
// when the server holds no analysis to answer from.
describe('libellePrerequis', () => {
  const prerequis = (partial: Partial<PrerequisValidation>): PrerequisValidation => ({
    code: 'ECARTS_DURS',
    connu: true,
    satisfait: true,
    nombre: 0,
    ...partial,
  });

  it('dit qu’un prérequis satisfait l’est', () => {
    expect(libellePrerequis(prerequis({ code: 'SIEGES_VIDES' }))).toContain('Aucun siège vide');
  });

  it('dit combien de fois un prérequis est manqué', () => {
    const libelle = libellePrerequis(
      prerequis({ code: 'SIEGES_VIDES', satisfait: false, nombre: 3 }),
    );

    expect(libelle).toContain('3');
  });

  it('ne prétend jamais qu’un prérequis non mesuré est satisfait', () => {
    const libelle = libellePrerequis(prerequis({ connu: false, satisfait: false }));

    expect(libelle).toContain('non vérifié');
  });

  it('a une phrase pour chacun des quatre prérequis', () => {
    const codes: PrerequisValidation['code'][] = [
      'ECARTS_DURS',
      'SIEGES_VIDES',
      'PAUSES_NON_RELAYEES',
      'POSTES_IRREMPLACABLES',
    ];

    const libelles = codes.map((code) =>
      libellePrerequis(prerequis({ code, satisfait: false, nombre: 2 })),
    );

    expect(new Set(libelles).size).toBe(4);
    expect(libelles.every((libelle) => libelle.length > 0)).toBe(true);
  });
});
