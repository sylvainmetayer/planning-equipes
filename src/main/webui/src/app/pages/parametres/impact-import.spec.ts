import { describe, expect, it } from 'vitest';
import type { ImpactImport } from '../../core/models';
import { messageImpactImport } from '../../core/impact-import';

function impact(overrides: Partial<ImpactImport> = {}): ImpactImport {
  return {
    animateurs: 12,
    stands: 5,
    postes: 40,
    planningResolu: true,
    demandesEchange: 3,
    demandesEnAttente: 2,
    verrous: 4,
    ...overrides,
  };
}

describe('messageImpactImport', () => {
  it('chiffre le référentiel, le planning résolu et les demandes menacées', () => {
    const message = messageImpactImport(impact(), 'charger un scénario');
    expect(message).toContain('charger un scénario');
    expect(message).toContain('12 animateur(s) et 5 stand(s)');
    expect(message).toContain('(40 affectation(s))');
    expect(message).toContain('4 verrouillage(s)');
    expect(message).toContain('instantané sera enregistré automatiquement');
    expect(message).toContain("3 demande(s) d'échange (dont 2 en attente)");
    // What a deleted animateur takes away with them.
    expect(message).toContain("demandes d'échange, sessions et codes d'accès");
  });

  it('reste utile sans planning résolu ni demandes : pas de lignes vides', () => {
    const message = messageImpactImport(
      impact({ planningResolu: false, demandesEchange: 0, demandesEnAttente: 0 }),
      'importer un fichier scénario',
    );
    expect(message).toContain('12 animateur(s)');
    expect(message).not.toContain('affectation');
    expect(message).not.toContain('demande(s)');
    expect(message).not.toContain('instantané');
  });

  it("garde l'avertissement générique quand le comptage a échoué", () => {
    const message = messageImpactImport(null, 'charger un scénario');
    expect(message).toContain('supprimés');
    expect(message).not.toContain('animateur(s)');
  });
});
