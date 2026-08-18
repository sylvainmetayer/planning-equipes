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
    horaires: []
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
    joursIndisponibles: []
  };
}

const enfance: TypologieItem = { id: 'ENF', label: 'Enfance', ninja: false };

describe('buildTypologieDetail', () => {
  it('lists the stands proposing it and the animateurs vetted on it', () => {
    const sections = buildTypologieDetail(
      enfance,
      [stand('S1', ['ENF']), stand('S2', ['DIV'])],
      [animateur('A1', { ENF: 'AUTONOME' }), animateur('A2', { DIV: 'DEBUTANT' })]
    );

    const chips = sections.flatMap((section) => section.rows).filter((row) => row.chips);
    expect(chips[0].chips).toEqual(['S1']);
    expect(chips[1].chips).toEqual(['A1']);
  });

  it('shows an explicit "aucun" on both sides, which is exactly what this view is opened to spot', () => {
    const sections = buildTypologieDetail(enfance, [stand('S1', ['DIV'])], [animateur('A1', { DIV: 'AUTONOME' })]);

    const rows = sections.flatMap((section) => section.rows).filter((row) => row.muted);
    expect(rows).toHaveLength(2);
    expect(rows.every((row) => row.value === 'Aucun')).toBe(true);
  });
});
