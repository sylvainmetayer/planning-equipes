import { describe, expect, it } from 'vitest';
import { Animateur, Stand, TypologieItem } from '../../core/models';
import { buildTypologieDetail } from './typologie-detail';

function stand(id: string, typologiesProposees: string[]): Stand {
  return {
    id,
    nom: id,
    typologiesProposees,
    effectifMin: 1,
    effectifMax: 1,
    reserveMajeurs: false,
    premium: false,
    niveauEffort: 'NORMAL',
    emplacement: null,
    indisponibilites: [],
    ouvertures: [],
    horaires: [],
  };
}

function animateur(id: string, competences: Record<string, string>): Animateur {
  return {
    id,
    prenom: id,
    nom: '',
    dateNaissance: '2000-01-01',
    manager: false,
    competences: competences as Animateur['competences'],
    souhaits: [],
    joursIndisponibles: [],
  };
}

const enfance: TypologieItem = { id: 'ENF', label: 'Enfance', ninja: false };

describe('buildTypologieDetail', () => {
  it('lists the stands proposing it and the animateurs vetted on it', () => {
    const sections = buildTypologieDetail(
      enfance,
      [stand('S1', ['ENF']), stand('S2', ['DIV'])],
      [animateur('A1', { ENF: 'AUTONOME' }), animateur('A2', { DIV: 'DEBUTANT' })],
    );

    const chips = sections.flatMap((section) => section.rows).filter((row) => row.chips);
    expect(chips[0].chips).toEqual(['S1']);
    expect(chips[1].chips).toEqual(['A1']);
  });

  it('shows an explicit "aucun" on both sides, which is exactly what this view is opened to spot', () => {
    const sections = buildTypologieDetail(
      enfance,
      [stand('S1', ['DIV'])],
      [animateur('A1', { DIV: 'AUTONOME' })],
    );

    // The cap row is muted too when there is none (issue #594), so the two
    // « Aucun » ones are singled out rather than counted among every muted row.
    const rows = sections
      .flatMap((section) => section.rows)
      .filter((row) => row.muted && row.value === 'Aucun');
    expect(rows).toHaveLength(2);
  });

  /**
   * Issue #590: what the plan actually did with a typologie is a different
   * question from who may hold it, and it only shows once the page has read the
   * plan — an empty section would read as « nobody », not as « nobody asked ».
   */
  it('leaves out the assignment section until the plan has been read', () => {
    const sections = buildTypologieDetail(enfance, [], []);

    expect(sections.map((section) => section.title)).not.toContain(
      'Qui tient quoi, et pour quel volume',
    );
  });

  it('adds what the plan did with the typologie, gaps included, once it has', () => {
    const sections = buildTypologieDetail(enfance, [], [], {
      typologie: 'ENFANCE',
      label: 'Enfance',
      ninja: false,
      maxCreneauxParAnimateur: null,
      animateursAffectes: ['Ada Martin'],
      animateursCompetents: ['Bob Martin'],
      competentsJamaisAffectes: ['Bob Martin'],
      affectesSansCompetence: ['Ada Martin'],
      heures: 7.5,
      postes: 3,
    });

    const affectation = sections.find(
      (section) => section.title === 'Qui tient quoi, et pour quel volume',
    );
    expect(affectation).toBeDefined();
    expect(affectation!.rows.map((row) => row.value ?? row.chips)).toEqual([
      '3',
      '7.5 h',
      ['Ada Martin'],
      ['Bob Martin'],
      ['Ada Martin'],
    ]);
  });
});
