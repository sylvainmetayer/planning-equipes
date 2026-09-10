import { describe, expect, it } from 'vitest';
import { statutDemandeClasse, statutDemandeLabel } from './demande-echange-labels';
import type { StatutDemandeEchange } from './models';

describe('demande-echange-labels', () => {
  const statuts: StatutDemandeEchange[] = [
    'EN_ATTENTE_CIBLE',
    'PROPOSEE',
    'ACCEPTEE',
    'REFUSEE',
    'REFUSEE_CIBLE',
    'ANNULEE',
  ];

  it('couvre chaque statut du cycle de vie avec un libellé français', () => {
    expect(statutDemandeLabel('EN_ATTENTE_CIBLE')).toBe('En attente du collègue');
    expect(statutDemandeLabel('PROPOSEE')).toBe("En attente de l'organisation");
    expect(statutDemandeLabel('ACCEPTEE')).toBe('Acceptée');
    expect(statutDemandeLabel('REFUSEE')).toBe('Refusée');
    expect(statutDemandeLabel('REFUSEE_CIBLE')).toBe('Déclinée par le collègue');
    expect(statutDemandeLabel('ANNULEE')).toBe('Annulée');
  });

  it('donne à chaque statut un modificateur CSS, partagé entre les deux attentes et les deux refus', () => {
    const classes = statuts.map(statutDemandeClasse);
    expect(classes).toEqual(['attente', 'attente', 'acceptee', 'refusee', 'refusee', 'annulee']);
  });
});
