import { describe, expect, it } from 'vitest';
import { causesRestantesMessage, hardScoreNegativeMessage } from './feasibility-messages';

describe('feasibility-messages', () => {
  describe('hardScoreNegativeMessage', () => {
    it('interpole le score dur dans le message', () => {
      expect(hardScoreNegativeMessage(-5)).toContain('-5');
    });

    // Le message existe pour être partagé mot pour mot entre le bandeau, la
    // page Problèmes et la notification : deux appels doivent produire le même
    // texte, sinon les trois surfaces divergent.
    it('produit un texte stable pour un même score', () => {
      expect(hardScoreNegativeMessage(-12)).toBe(hardScoreNegativeMessage(-12));
    });

    it('reste actionnable en citant les leviers de réglage', () => {
      const message = hardScoreNegativeMessage(-1);
      expect(message).toContain('vacations');
      expect(message).toContain('disponibilités');
    });
  });

  describe('causesRestantesMessage', () => {
    // Le backend plafonne la liste des causes mais compte le total : quand
    // rien n'est masqué il ne faut afficher aucune mention, pas un « + 0 ».
    it("retourne une chaîne vide quand rien n'est masqué", () => {
      expect(causesRestantesMessage(0)).toBe('');
    });

    it('retourne une chaîne vide pour un compte négatif', () => {
      expect(causesRestantesMessage(-3)).toBe('');
    });

    it('annonce le nombre de causes non détaillées', () => {
      expect(causesRestantesMessage(7)).toContain('7');
    });
  });
});
