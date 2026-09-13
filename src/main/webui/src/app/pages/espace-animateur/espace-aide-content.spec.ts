import { describe, expect, it } from 'vitest';
import { helpBlockText } from '../../shared/help-blocks';
import { EspaceAideSection, buildEspaceAideSections } from './espace-aide-content';

/** Every string of a section, so the tests can assert on its whole content. */
function textOf(section: EspaceAideSection): string {
  return JSON.stringify(section);
}

describe('buildEspaceAideSections', () => {
  const sections = buildEspaceAideSections();

  it('gives every section a unique anchor id', () => {
    const ids = sections.map((section) => section.id);
    expect(new Set(ids).size).toBe(ids.length);
  });

  it('covers every action the espace offers', () => {
    const ids = sections.map((section) => section.id);
    // Une capacité de l'espace sans réponse ici est un trou dans l'aide : ces
    // identifiants sont la liste de ce qu'un animateur peut faire.
    expect(ids).toEqual(
      expect.arrayContaining([
        'acces',
        'lire-planning',
        'emporter',
        'demander-echange',
        'remplacants',
        'suivi',
        'demandes-recues',
        'declarer-disponibilites',
        'foire-fermee',
      ]),
    );
  });

  it('opens every section with a question and answers it in one line', () => {
    for (const section of sections) {
      expect(section.question.length).toBeGreaterThan(0);
      expect(section.resume.length).toBeGreaterThan(0);
      // Le résumé se lit panneau replié, sur une ligne d'écran de téléphone.
      expect(section.resume.length).toBeLessThan(140);
      expect(section.blocks.length).toBeGreaterThan(0);
    }
  });

  it('never leaves a block empty', () => {
    for (const block of sections.flatMap((section) => section.blocks)) {
      for (const text of helpBlockText(block)) {
        expect(text.length).toBeGreaterThan(0);
      }
      expect(helpBlockText(block).length).toBeGreaterThan(0);
    }
  });

  it('only sends the reader to a tab of the espace', () => {
    const cibles = sections.map((section) => section.cible).filter(Boolean);
    expect(cibles.length).toBeGreaterThan(0);
    for (const target of cibles) {
      expect(['planning', 'echanges', 'disponibilites']).toContain(target);
    }
  });

  it('speaks the vocabulary of the screens, not the solver', () => {
    const text = sections.map(textOf).join(' ');
    for (const mot of ['créneau', 'stand', 'coéquipiers', 'foire au planning', 'organisation']) {
      expect(text).toContain(mot);
    }
    // Le vocabulaire d'organisateur n'a pas de sens pour un animateur : il ne
    // voit ni le solveur, ni un score, ni un découpage.
    for (const mot of ['solveur', 'score dur', 'découpage', 'contrainte souple']) {
      expect(text).not.toContain(mot);
    }
  });

  it('answers the availability tab in each of its states, not only the open one', () => {
    const section = sections.find((each) => each.id === 'declarer-disponibilites')!;
    const text = textOf(section);
    // Not open yet: the tab gives a date to come back on, it is not a refusal.
    expect(text).toContain("n'a pas encore commencé");
    // Sent but not dealt with: the card at the top, and what a new submission does.
    expect(text).toContain('Votre déclaration en attente');
    // Dealt with: what the organisers did with it, in the words the screen uses.
    expect(text).toContain('Mes déclarations précédentes');
    for (const statut of ['Prise en compte', 'Non retenue', "En attente de l'organisation"]) {
      expect(text, statut).toContain(statut);
    }
  });

  it('covers the fair before it opens as well as after it closes', () => {
    const section = sections.find((each) => each.id === 'foire-fermee')!;
    const text = textOf(section);
    // « Pas encore ouverte » and « fermée » look alike on screen and do not
    // call for the same thing: the help has to tell them apart.
    expect(text).toContain("n'est pas encore ouverte");
    expect(text).toContain('date à partir de laquelle revenir');
    // And the panel's question can no longer be the one of the closed case only.
    expect(section.question).not.toContain('plus rien');
  });

  it('states the two things a schedule taken away cannot say', () => {
    const emporter = sections.find((section) => section.id === 'emporter');
    // Le PDF et l'ICS sont figés : l'aide doit le dire, c'est la première
    // cause d'un animateur qui se présente au mauvais stand.
    expect(textOf(emporter!)).toContain('PDF');
    expect(textOf(emporter!)).toContain('ICS');
    expect(textOf(emporter!)).toMatch(/ne se mettent pas à jour/);
  });
});
