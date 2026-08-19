import { describe, expect, it } from 'vitest';
import { resumeEnvoi } from './envoi-planning';

describe('resumeEnvoi', () => {
  it('résume un envoi complet en une ligne, sans détails superflus', () => {
    const resume = resumeEnvoi({ envoyes: 3, sansEmail: [], echecs: [] });
    expect(resume.titre).toBe('3 planning(s) envoyé(s)');
    expect(resume.details).toBeUndefined();
  });

  it('nomme les animateurs sans adresse et les échecs — pas de simples compteurs', () => {
    const resume = resumeEnvoi({
      envoyes: 1,
      sansEmail: ['Bruno Petit'],
      echecs: ['Chloé Durand', 'David Roux']
    });
    expect(resume.titre).toBe('1 planning(s) envoyé(s)');
    expect(resume.details).toBe(
      "Sans adresse e-mail : Bruno Petit — Échec de l'envoi : Chloé Durand, David Roux"
    );
  });
});
