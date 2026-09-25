import { describe, expect, it } from 'vitest';
import {
  decisionNonCommuniquee,
  statutDemandeClasse,
  statutDemandeLabel,
} from './demande-echange-labels';
import type { DemandeEchangeView, StatutDemandeEchange } from './models';

function swapRequest(
  status: StatutDemandeEchange,
  decidedAt: string | null,
  communicatedAt: string | null,
): DemandeEchangeView {
  return {
    statut: status,
    decideLe: decidedAt,
    communiqueeLe: communicatedAt,
  } as DemandeEchangeView;
}

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
    const classes = statuts.map((status) => statutDemandeClasse(status));
    expect(classes).toEqual(['attente', 'attente', 'acceptee', 'refusee', 'refusee', 'annulee']);
  });

  describe('decisionNonCommuniquee', () => {
    const DECIDE_LE = '2026-07-10T09:00:00Z';
    const PUBLIE_LE = '2026-07-10T18:00:00Z';

    it("retient les deux décisions de l'organisation que la publication n'a pas portées", () => {
      expect(decisionNonCommuniquee(swapRequest('ACCEPTEE', DECIDE_LE, null))).toBe(true);
      expect(decisionNonCommuniquee(swapRequest('REFUSEE', DECIDE_LE, null))).toBe(true);
    });

    it('oublie une décision dès que la publication est partie', () => {
      expect(decisionNonCommuniquee(swapRequest('ACCEPTEE', DECIDE_LE, PUBLIE_LE))).toBe(false);
    });

    // A withdrawal has no decision to announce. Since issue #540 the server
    // stamps `annuleLe` rather than `decideLe`, so one never reaches this
    // function any more; the statut test stays as a second lock, and the
    // fixture below still hands it the old shape on purpose.
    it('ignores a swap request withdrawn by its author, and those still waiting', () => {
      expect(decisionNonCommuniquee(swapRequest('ANNULEE', DECIDE_LE, null))).toBe(false);
      expect(decisionNonCommuniquee(swapRequest('REFUSEE_CIBLE', null, null))).toBe(false);
      expect(decisionNonCommuniquee(swapRequest('PROPOSEE', null, null))).toBe(false);
      expect(decisionNonCommuniquee(swapRequest('EN_ATTENTE_CIBLE', null, null))).toBe(false);
    });
  });
});
