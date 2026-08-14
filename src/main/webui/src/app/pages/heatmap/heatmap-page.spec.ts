import { describe, expect, it } from 'vitest';
import { Animateur, Creneau, PosteAffectation, Stand } from '../../core/models';
import { buildAnimateurHeatmap, buildStandHeatmap } from './heatmap-page';

function creneau(overrides: Partial<Creneau> & { id: number; jour: number }): Creneau {
  return { date: '2026-08-01', heureDebut: '13:40', heureFin: '19:00', groupe: null, ...overrides };
}

function stand(id: string): Stand {
  return {
    id,
    nom: id,
    typologiesProposees: [],
    effectifMin: 1,
    effectifMax: 1,
    reserveMajeurs: false,
    premium: false,
    niveauEffort: 'NORMAL',
    emplacement: null,
    indisponibilites: [],
    ouvertures: []
  };
}

function animateur(id: string): Animateur {
  return { id, prenom: id, nom: '', dateNaissance: '2000-01-01', manager: false, competences: {}, joursIndisponibles: [], souhaits: [] };
}

function poste(overrides: Partial<PosteAffectation> & { id: string }): PosteAffectation {
  return { stand: null, creneau: null, animateur: null, ...overrides };
}

describe('buildStandHeatmap', () => {
  it('flags a stand with zero filled seats as critical (a coverage gap)', () => {
    const c1 = creneau({ id: 1, jour: 1 });
    const table = buildStandHeatmap([poste({ id: 'p1', creneau: c1, stand: stand('S1') })]);

    expect(table.rows).toHaveLength(1);
    expect(table.rows[0].cells[0]).toMatchObject({ level: 'critical', label: '0/1' });
  });

  it('flags a stand with some but not all seats filled as warning', () => {
    const c1 = creneau({ id: 1, jour: 1 });
    const table = buildStandHeatmap([
      poste({ id: 'p1', creneau: c1, stand: stand('S1'), animateur: animateur('A') }),
      poste({ id: 'p2', creneau: c1, stand: stand('S1') })
    ]);

    expect(table.rows[0].cells[0]).toMatchObject({ level: 'warning', label: '1/2' });
  });

  it('marks a fully-staffed stand as ok', () => {
    const c1 = creneau({ id: 1, jour: 1 });
    const table = buildStandHeatmap([poste({ id: 'p1', creneau: c1, stand: stand('S1'), animateur: animateur('A') })]);

    expect(table.rows[0].cells[0]).toMatchObject({ level: 'ok', label: '1/1' });
  });

  it('marks a day with no créneau for that stand as none, distinct from a gap', () => {
    const table = buildStandHeatmap([
      poste({ id: 'p1', creneau: creneau({ id: 1, jour: 1 }), stand: stand('S1'), animateur: animateur('A') }),
      poste({ id: 'p2', creneau: creneau({ id: 2, jour: 2 }), stand: stand('S2') })
    ]);

    const s1Row = table.rows.find((row) => row.id === 'S1')!;
    expect(s1Row.cells[1]).toMatchObject({ level: 'none', label: '' });
  });

  it('sorts stands alphabetically by name', () => {
    const c1 = creneau({ id: 1, jour: 1 });
    const table = buildStandHeatmap([
      poste({ id: 'p1', creneau: c1, stand: stand('Zebre') }),
      poste({ id: 'p2', creneau: c1, stand: stand('Alpha') })
    ]);

    expect(table.rows.map((row) => row.label)).toEqual(['Alpha', 'Zebre']);
  });
});

describe('buildAnimateurHeatmap', () => {
  it('marks a single poste that day as ok', () => {
    const table = buildAnimateurHeatmap([
      poste({ id: 'p1', creneau: creneau({ id: 1, jour: 1 }), stand: stand('S1'), animateur: animateur('A') })
    ]);

    expect(table.rows[0].cells[0]).toMatchObject({ level: 'ok', label: '1' });
  });

  it('flags two postes the same day as warning and three or more as critical (overload)', () => {
    const jour1 = creneau({ id: 1, jour: 1 });
    const jour2 = creneau({ id: 2, jour: 1 });
    const jour3 = creneau({ id: 3, jour: 1 });
    const table = buildAnimateurHeatmap([
      poste({ id: 'p1', creneau: jour1, stand: stand('S1'), animateur: animateur('A') }),
      poste({ id: 'p2', creneau: jour2, stand: stand('S2'), animateur: animateur('A') })
    ]);
    expect(table.rows[0].cells[0]).toMatchObject({ level: 'warning', label: '2' });

    const overloaded = buildAnimateurHeatmap([
      poste({ id: 'p1', creneau: jour1, stand: stand('S1'), animateur: animateur('A') }),
      poste({ id: 'p2', creneau: jour2, stand: stand('S2'), animateur: animateur('A') }),
      poste({ id: 'p3', creneau: jour3, stand: stand('S3'), animateur: animateur('A') })
    ]);
    expect(overloaded.rows[0].cells[0]).toMatchObject({ level: 'critical', label: '3' });
  });

  it('excludes unassigned postes and never produces a row for them', () => {
    const table = buildAnimateurHeatmap([poste({ id: 'p1', creneau: creneau({ id: 1, jour: 1 }), stand: stand('S1') })]);

    expect(table.rows).toHaveLength(0);
  });

  it('ranks animateurs by descending total load, heaviest first', () => {
    const jour1 = creneau({ id: 1, jour: 1 });
    const jour2 = creneau({ id: 2, jour: 2 });
    const table = buildAnimateurHeatmap([
      poste({ id: 'p1', creneau: jour1, stand: stand('S1'), animateur: animateur('Light') }),
      poste({ id: 'p2', creneau: jour1, stand: stand('S2'), animateur: animateur('Heavy') }),
      poste({ id: 'p3', creneau: jour2, stand: stand('S3'), animateur: animateur('Heavy') })
    ]);

    expect(table.rows.map((row) => row.id)).toEqual(['Heavy', 'Light']);
  });
});
