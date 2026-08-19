import { describe, expect, it } from 'vitest';
import { statutDemandeClasse, statutDemandeLabel } from './demande-echange-labels';
import type { StatutDemandeEchange } from './models';

describe('demande-echange-labels', () => {
  const statuts: StatutDemandeEchange[] = ['PROPOSEE', 'ACCEPTEE', 'REFUSEE', 'ANNULEE'];

  it('couvre chaque statut du cycle de vie avec un libellé français', () => {
    expect(statutDemandeLabel('PROPOSEE')).toBe('En attente');
    expect(statutDemandeLabel('ACCEPTEE')).toBe('Acceptée');
    expect(statutDemandeLabel('REFUSEE')).toBe('Refusée');
    expect(statutDemandeLabel('ANNULEE')).toBe('Annulée');
  });

  it('donne à chaque statut un modificateur CSS distinct', () => {
    const classes = statuts.map(statutDemandeClasse);
    expect(classes).toEqual(['attente', 'acceptee', 'refusee', 'annulee']);
    expect(new Set(classes).size).toBe(statuts.length);
  });
});
