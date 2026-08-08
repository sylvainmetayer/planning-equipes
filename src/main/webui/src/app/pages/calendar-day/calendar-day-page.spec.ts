import { describe, expect, it } from 'vitest';
import { Animateur, Creneau, PosteAffectation, Stand } from '../../core/models';
import { buildDays } from './calendar-day-page';

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

describe('buildDays — issue #60 partial-closure regression', () => {
  it('groups every name on one line when nothing narrows the créneau', () => {
    const c1 = creneau({ id: 1 });
    const s1 = stand('STAND-01');
    const days = buildDays([
      poste({ id: 'p1', creneau: c1, stand: s1, animateur: animateur('Oscar') }),
      poste({ id: 'p2', creneau: c1, stand: s1, animateur: animateur('Ines') })
    ]);

    expect(days[0].slots[0].stands).toHaveLength(1);
    expect(days[0].slots[0].stands[0]).toMatchObject({
      heureDebut: '13:40',
      heureFin: '19:00',
      names: ['Oscar', 'Ines']
    });
  });

  it('splits into two lines when a partial stand closure narrows some postes but not others', () => {
    // Regression for the exact report: STAND-01 closed 14:00-16:00 inside a
    // 13:40-19:00 créneau must never show someone as covering the full
    // 13:40-19:00 span — Oscar (16:00-19:00) and Ines (13:40-14:00) must land
    // on two distinct, correctly-windowed lines.
    const c1 = creneau({ id: 1, heureDebut: '13:40', heureFin: '19:00' });
    const s1 = stand('STAND-01');
    const days = buildDays([
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

    const lines = days[0].slots[0].stands;
    expect(lines).toHaveLength(2);
    expect(lines).toContainEqual(
      expect.objectContaining({ heureDebut: '13:40', heureFin: '14:00', names: ['Ines'] })
    );
    expect(lines).toContainEqual(
      expect.objectContaining({ heureDebut: '16:00', heureFin: '19:00', names: ['Oscar'] })
    );
    // Neither line ever claims the full créneau span the closure cuts through.
    expect(lines.every((line) => !(line.heureDebut === '13:40' && line.heureFin === '19:00'))).toBe(true);
  });

  it('falls back to the créneau hours when a poste has no effective-window override', () => {
    const c1 = creneau({ id: 1, heureDebut: '09:00', heureFin: '12:00' });
    const days = buildDays([poste({ id: 'p1', creneau: c1, stand: stand('S1'), animateur: animateur('A') })]);

    expect(days[0].slots[0].stands[0]).toMatchObject({ heureDebut: '09:00', heureFin: '12:00' });
  });
});
