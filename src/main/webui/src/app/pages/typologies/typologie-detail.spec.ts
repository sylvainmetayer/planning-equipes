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
      // Given a code, so that its own « Aucun » is not counted below.
      { ...enfance, code: 'ENFANCE' },
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

  it('shows the code next to the drawn id, and says so when there is none', () => {
    const withCode = buildTypologieDetail({ ...enfance, code: 'ENFANCE' }, [], []);
    expect(withCode[0].rows.find((row) => row.label === 'Code')?.value).toBe('ENFANCE');

    const withoutCode = buildTypologieDetail(enfance, [], [])[0].rows.find(
      (row) => row.label === 'Code',
    );
    expect(withoutCode?.value).toBe('Aucun');
    expect(withoutCode?.muted).toBe(true);
  });

  /**
   * Issue #590: what the plan did with a typologie is a different question from
   * who may hold it, and it has its own screen — « Planning par typologie ».
   * This dialog stays the referential's, so the two never tell two stories.
   */
  it('leaves the plan to the screen that reads it', () => {
    const sections = buildTypologieDetail(enfance, [], []);

    expect(sections.map((section) => section.title)).not.toContain(
      'Qui tient quoi, et pour quel volume',
    );
  });

  // The organiser's own note on the typologie, read here and in the plan view,
  // nowhere else. Absent is said in words rather than left blank.
  it('shows the description, and says so when there is none', () => {
    const withNote = buildTypologieDetail(
      { ...enfance, description: "Nécessite d'apprendre 45 jeux" },
      [],
      [],
    );
    const identite = withNote[0].rows.find((row) => row.label === 'Description');
    expect(identite?.value).toBe("Nécessite d'apprendre 45 jeux");
    expect(identite?.muted).toBe(false);

    const withoutNote = buildTypologieDetail(enfance, [], []);
    const vide = withoutNote[0].rows.find((row) => row.label === 'Description');
    expect(vide?.value).toBe('Aucune description');
    expect(vide?.muted).toBe(true);
  });
});
