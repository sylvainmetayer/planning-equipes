import { describe, expect, it } from 'vitest';
import {
  decisionNonCommuniquee,
  statutDemandeClasse,
  statutDemandeLabel,
} from './demande-echange-labels';
import type { DemandeEchangeView, StatutDemandeEchange } from './models';

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

  describe('decisionNonCommuniquee', () => {
    function demande(
      statut: StatutDemandeEchange,
      decideLe: string | null,
      communiqueeLe: string | null,
    ): DemandeEchangeView {
      return { statut, decideLe, communiqueeLe } as DemandeEchangeView;
    }

    const DECIDE_LE = '2026-07-10T09:00:00Z';
    const PUBLIE_LE = '2026-07-10T18:00:00Z';

    it("retient les deux décisions de l'organisation que la publication n'a pas portées", () => {
      expect(decisionNonCommuniquee(demande('ACCEPTEE', DECIDE_LE, null))).toBe(true);
      expect(decisionNonCommuniquee(demande('REFUSEE', DECIDE_LE, null))).toBe(true);
    });

    it('oublie une décision dès que la publication est partie', () => {
      expect(decisionNonCommuniquee(demande('ACCEPTEE', DECIDE_LE, PUBLIE_LE))).toBe(false);
    });

    // A withdrawal stamps `decideLe` too — it shares the column — but nobody
    // decided anything, and there is nothing to announce back to its author.
    it('ignore une demande retirée par son auteur, et celles qui attendent encore', () => {
      expect(decisionNonCommuniquee(demande('ANNULEE', DECIDE_LE, null))).toBe(false);
      expect(decisionNonCommuniquee(demande('REFUSEE_CIBLE', null, null))).toBe(false);
      expect(decisionNonCommuniquee(demande('PROPOSEE', null, null))).toBe(false);
      expect(decisionNonCommuniquee(demande('EN_ATTENTE_CIBLE', null, null))).toBe(false);
    });
  });
});
