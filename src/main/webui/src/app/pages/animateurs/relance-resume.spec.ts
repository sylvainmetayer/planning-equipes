import { describe, expect, it } from 'vitest';
import type { RapportRelance } from '../../core/models';
import { resumeRelance } from './relance-resume';

const NOMS: Record<string, string> = { a: 'Alice Martin', b: 'Bruno Petit', c: 'Chloé Durand' };
const nameOf = (id: string) => NOMS[id] ?? id;

function rapport(patch: Partial<RapportRelance> = {}): RapportRelance {
  return {
    envoyes: [],
    dejaConfirmes: [],
    sansEmail: [],
    dejaRelancesPourCettePublication: [],
    echecs: [],
    sansPoste: [],
    ...patch,
  };
}

describe('resumeRelance', () => {
  it('counts the reminders that left, with no details when everybody was written to', () => {
    const resume = resumeRelance(rapport({ envoyes: ['a', 'b'] }), nameOf);

    expect(resume.titre).toBe('2 relance(s) envoyée(s)');
    expect(resume.details).toBeUndefined();
    expect(resume.variant).toBe('success');
  });

  it('names who was left alone and why, by display name', () => {
    const resume = resumeRelance(
      rapport({
        envoyes: ['a'],
        dejaConfirmes: ['b'],
        dejaRelancesPourCettePublication: ['c'],
      }),
      nameOf,
    );

    expect(resume.details).toContain('Déjà confirmés : Bruno Petit');
    expect(resume.details).toContain('Déjà relancés pour cette publication : Chloé Durand');
    expect(resume.details).not.toContain('Sans adresse');
  });

  it('turns to a warning when a send failed, and still names the person', () => {
    const resume = resumeRelance(rapport({ echecs: ['a'] }), nameOf);

    expect(resume.variant).toBe('warning');
    expect(resume.details).toBe("Échec de l'envoi : Alice Martin");
  });

  it('falls back on the id for a fiche the table no longer holds', () => {
    const resume = resumeRelance(rapport({ sansEmail: ['zz'] }), nameOf);

    expect(resume.details).toBe('Sans adresse e-mail : zz');
  });
});
