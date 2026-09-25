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
    adresseRefusee: [],
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

  // The journal is persisted in localStorage and read back on the Notifications
  // page: the same report, counted rather than named.
  it('keeps names out of the journal, and caps the names it does show', () => {
    const beaucoup = Array.from({ length: 11 }, (_, index) => `A${index}`);
    const resume = resumeRelance(
      rapport({ envoyes: ['A-UMA'], dejaConfirmes: beaucoup }),
      (id) => id,
    );

    expect(resume.details).toContain('A0, A1');
    expect(resume.details).toContain('3');
    expect(resume.detailsJournal).toBeDefined();
    expect(resume.detailsJournal).not.toContain('A0');
    expect(resume.detailsJournal).toContain('11');
  });

  it('falls back on the id for a fiche the table no longer holds', () => {
    const resume = resumeRelance(rapport({ sansEmail: ['zz'] }), nameOf);

    expect(resume.details).toBe('Sans adresse e-mail : zz');
  });

  it('names the refused addresses apart, and counts them in the journal', () => {
    const resume = resumeRelance(rapport({ adresseRefusee: ['a'] }), nameOf);

    expect(resume.details).toContain(
      'Adresse refusée au dernier envoi, fiche à corriger : Alice Martin',
    );
    expect(resume.detailsJournal).toContain('1 adresse(s) refusée(s)');
    expect(resume.detailsJournal).not.toContain('Alice');
    expect(resume.variant).toBe('warning');
  });
});
