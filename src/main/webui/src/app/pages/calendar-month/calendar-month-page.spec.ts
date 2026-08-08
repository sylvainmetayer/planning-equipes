import { describe, expect, it } from 'vitest';
import { Animateur, Creneau, PosteAffectation, Stand } from '../../core/models';
import { buildAssignmentsByDate } from './calendar-month-page';

function creneau(overrides: Partial<Creneau> & { id: number }): Creneau {
  return { jour: 1, date: '2026-08-01', heureDebut: '13:40', heureFin: '19:00', groupe: null, ...overrides };
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
