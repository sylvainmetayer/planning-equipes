import { describe, expect, it } from 'vitest';
import { Animateur, Creneau, PosteAffectation, Stand } from '../../core/models';
import { buildAssignmentsByDate, disambiguateLabels, hasUnderstaffedStand } from './calendar-month-page';

function creneau(overrides: Partial<Creneau> & { id: number }): Creneau {
  return { jour: 1, date: '2026-08-01', heureDebut: '13:40', heureFin: '19:00', ...overrides };
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
    niveauEffort: 'NORMAL',
    emplacement: null,
    indisponibilites: [],
    ouvertures: [],
    horaires: []
  };
}

function animateur(id: string): Animateur {
  return { id, prenom: id, nom: '', dateNaissance: '2000-01-01', manager: false, competences: {}, souhaits: [], joursIndisponibles: [] };
}

function poste(overrides: Partial<PosteAffectation> & { id: string }): PosteAffectation {
  return { stand: null, creneau: null, animateur: null, ...overrides };
}

function labels(line: { entries: { label: string }[] }): string[] {
  return line.entries.map((entry) => entry.label);
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
    const ines = lines.find((line) => line.heureDebut === '13:40');
    const oscar = lines.find((line) => line.heureDebut === '16:00');
    expect(ines).toMatchObject({ heureFin: '14:00' });
    expect(labels(ines!)).toEqual(['Ines']);
    expect(oscar).toMatchObject({ heureFin: '19:00' });
    expect(labels(oscar!)).toEqual(['Oscar']);
    expect(lines.every((line) => !(line.heureDebut === '13:40' && line.heureFin === '19:00'))).toBe(true);
  });

  it('falls back to the créneau hours when a poste has no effective-window override', () => {
    const c1 = creneau({ id: 1, date: '2026-08-01', heureDebut: '09:00', heureFin: '12:00' });
    const byDate = buildAssignmentsByDate([poste({ id: 'p1', creneau: c1, stand: stand('S1'), animateur: animateur('A') })]);

    expect(byDate.get('2026-08-01')![0].stands[0]).toMatchObject({ heureDebut: '09:00', heureFin: '12:00' });
  });
});

describe('buildAssignmentsByDate — understaffing indicator', () => {
  it('counts the seats generated for the line, so a partially-staffed stand can be flagged', () => {
    // Regression for the reported DEV case: 17/07/2026 Stand Stratégie 16,
    // 18h30-00h, two seats but only one animateur assigned.
    const c1 = creneau({ id: 1, date: '2026-07-17', heureDebut: '18:30', heureFin: '00:00' });
    const s1 = stand('Stratégie 16', 2);
    const byDate = buildAssignmentsByDate([
      poste({ id: 'p1', creneau: c1, stand: s1, animateur: animateur('Oscar') }),
      poste({ id: 'p2', creneau: c1, stand: s1 })
    ]);

    const line = byDate.get('2026-07-17')![0].stands[0];
    expect(labels(line)).toEqual(['Oscar']);
    expect(line.effectifRequis).toBe(2);
    expect(line.entries.length).toBeLessThan(line.effectifRequis);
  });

  it('does not flag a meal-pause coverage slot, deliberately staffed at half the headcount', () => {
    // EFFECTIF_REDUIT: one seat generated on the pause vacation of a
    // two-person stand — filling it is full coverage, not a shortfall.
    const pause = creneau({ id: 2, date: '2026-07-15', heureDebut: '12:00', heureFin: '13:00', couverturePause: true });
    const byDate = buildAssignmentsByDate([
      poste({ id: 'p1', creneau: pause, stand: stand('Stand Argent', 2), animateur: animateur('Oscar') })
    ]);

    const line = byDate.get('2026-07-15')![0].stands[0];
    expect(line.couverturePause).toBe(true);
    expect(line.effectifRequis).toBe(1);
    expect(hasUnderstaffedStand(byDate.get('2026-07-15'))).toBe(false);
  });

  it('hasUnderstaffedStand flags the month-grid day cell without opening the day', () => {
    const c1 = creneau({ id: 1, date: '2026-07-17' });
    const s1 = stand('Stratégie 16', 2);
    const byDate = buildAssignmentsByDate([
      poste({ id: 'p1', creneau: c1, stand: s1, animateur: animateur('Oscar') }),
      poste({ id: 'p2', creneau: c1, stand: s1 })
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

describe('disambiguateLabels', () => {
  it('leaves unique labels untouched', () => {
    const options = disambiguateLabels([
      { value: 'A1', label: 'Ada Lovelace' },
      { value: 'A2', label: 'Alan Turing' }
    ]);

    expect(options).toEqual([
      { value: 'A1', label: 'Ada Lovelace' },
      { value: 'A2', label: 'Alan Turing' }
    ]);
  });

  it('appends the id to every option sharing a label with another one', () => {
    // Regression: the roster enforces no uniqueness on "Prénom Nom" — two
    // different animateurs named "Yasmine Laurent" (A79, A83) made the
    // filter dropdown's "Yasmine Laurent" entry pick just one of them
    // silently, so selecting the wrong one looked like the understaffing
    // flag itself was broken (it wasn't — the filter was just on the
    // fully-staffed namesake, not the understaffed one).
    const options = disambiguateLabels([
      { value: 'A79', label: 'Yasmine Laurent' },
      { value: 'A83', label: 'Yasmine Laurent' },
      { value: 'A1', label: 'Ada Lovelace' }
    ]);

    expect(options).toEqual([
      { value: 'A79', label: 'Yasmine Laurent (A79)' },
      { value: 'A83', label: 'Yasmine Laurent (A83)' },
      { value: 'A1', label: 'Ada Lovelace' }
    ]);
  });

  it('handles an empty list', () => {
    expect(disambiguateLabels([])).toEqual([]);
  });
});
