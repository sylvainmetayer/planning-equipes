import { describe, expect, it } from 'vitest';
import { Animateur, Creneau, PosteAffectation, Stand } from '../../core/models';
import { buildAssignmentsByDate, hasUnderstaffedStand } from './calendar-month-page';

function creneau(overrides: Partial<Creneau> & { id: number }): Creneau {
  return { jour: 1, date: '2026-08-01', heureDebut: '13:40', heureFin: '19:00', groupe: null, ...overrides };
}

function stand(id: string, effectifMin = 1): Stand {
  return {
    id,
    nom: id,
    typologiesProposees: [],
    effectifMin,
    effectifMax: Math.max(1, effectifMin),
    reserveMajeurs: false,
    premium: false,
    emplacement: null,
    indisponibilites: [],
    ouvertures: []
  };
}

function animateur(id: string): Animateur {
  return { id, prenom: id, nom: '', dateNaissance: '2000-01-01', manager: false, competences: {}, joursIndisponibles: [] };
}

function poste(overrides: Partial<PosteAffectation> & { id: string }): PosteAffectation {
  return { stand: null, creneau: null, animateur: null, ...overrides };
}

describe('buildAssignmentsByDate — issue #60 partial-closure regression', () => {
  it('splits into two lines when a partial stand closure narrows some postes but not others', () => {
    const c1 = creneau({ id: 1, date: '2026-07-10', heureDebut: '13:40', heureFin: '19:00' });
    const s1 = stand('STAND-01');
    const byDate = buildAssignmentsByDate([
      poste({
        id: 'p1',
        creneau: c1,
        stand: s1,
        animateur: animateur('Oscar'),
        heureDebutEffective: '16:00',
        heureFinEffective: '19:00'
      }),
      poste({
        id: 'p2',
        creneau: c1,
        stand: s1,
        animateur: animateur('Ines'),
        heureDebutEffective: '13:40',
        heureFinEffective: '14:00'
      })
    ]);

    const lines = byDate.get('2026-07-10')![0].stands;
    expect(lines).toHaveLength(2);
    expect(lines).toContainEqual(
      expect.objectContaining({ heureDebut: '13:40', heureFin: '14:00', names: ['Ines'] })
    );
    expect(lines).toContainEqual(
      expect.objectContaining({ heureDebut: '16:00', heureFin: '19:00', names: ['Oscar'] })
    );
    expect(lines.every((line) => !(line.heureDebut === '13:40' && line.heureFin === '19:00'))).toBe(true);
  });

  it('falls back to the créneau hours when a poste has no effective-window override', () => {
    const c1 = creneau({ id: 1, date: '2026-08-01', heureDebut: '09:00', heureFin: '12:00' });
    const byDate = buildAssignmentsByDate([poste({ id: 'p1', creneau: c1, stand: stand('S1'), animateur: animateur('A') })]);

    expect(byDate.get('2026-08-01')![0].stands[0]).toMatchObject({ heureDebut: '09:00', heureFin: '12:00' });
  });
});

describe('buildAssignmentsByDate — understaffing indicator', () => {
  it('carries the stand effectifMin on each line, so a partially-staffed stand can be flagged', () => {
    // Regression for the reported DEV case: 17/07/2026 Stand Stratégie 16,
    // 18h30-00h, effectifMin 2 but only one animateur assigned.
    const c1 = creneau({ id: 1, date: '2026-07-17', heureDebut: '18:30', heureFin: '00:00' });
    const s1 = stand('Stratégie 16', 2);
    const byDate = buildAssignmentsByDate([poste({ id: 'p1', creneau: c1, stand: s1, animateur: animateur('Oscar') })]);

    const line = byDate.get('2026-07-17')![0].stands[0];
    expect(line.names).toEqual(['Oscar']);
    expect(line.effectifMin).toBe(2);
    expect(line.names.length).toBeLessThan(line.effectifMin);
  });

  it('hasUnderstaffedStand flags the month-grid day cell without opening the day', () => {
    const c1 = creneau({ id: 1, date: '2026-07-17' });
    const byDate = buildAssignmentsByDate([
      poste({ id: 'p1', creneau: c1, stand: stand('Stratégie 16', 2), animateur: animateur('Oscar') })
    ]);

    expect(hasUnderstaffedStand(byDate.get('2026-07-17'))).toBe(true);
    expect(hasUnderstaffedStand(byDate.get('2099-01-01'))).toBe(false);
  });

  it('hasUnderstaffedStand ignores a day whose stands are fully staffed or fully unassigned', () => {
    const c1 = creneau({ id: 1, date: '2026-07-17' });
    const byDate = buildAssignmentsByDate([
      poste({ id: 'p1', creneau: c1, stand: stand('S1', 1), animateur: animateur('A') }),
      poste({ id: 'p2', creneau: c1, stand: stand('S2', 1) })
    ]);

    expect(hasUnderstaffedStand(byDate.get('2026-07-17'))).toBe(false);
  });
});
