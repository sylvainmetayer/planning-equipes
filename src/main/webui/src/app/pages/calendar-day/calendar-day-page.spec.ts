import { describe, expect, it } from 'vitest';
import { Animateur, Creneau, PosteAffectation, Stand } from '../../core/models';
import { buildDays } from './calendar-day-page';

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

describe('buildDays — issue #60 partial-closure regression', () => {
  it('groups every name on one line when nothing narrows the créneau', () => {
    const c1 = creneau({ id: 1 });
    const s1 = stand('STAND-01');
    const days = buildDays([
      poste({ id: 'p1', creneau: c1, stand: s1, animateur: animateur('Oscar') }),
      poste({ id: 'p2', creneau: c1, stand: s1, animateur: animateur('Ines') })
    ]);

    expect(days[0].slots[0].stands).toHaveLength(1);
    const line = days[0].slots[0].stands[0];
    expect(line).toMatchObject({ heureDebut: '13:40', heureFin: '19:00' });
    expect(labels(line)).toEqual(['Oscar', 'Ines']);
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
    const ines = lines.find((line) => line.heureDebut === '13:40');
    const oscar = lines.find((line) => line.heureDebut === '16:00');
    expect(ines).toMatchObject({ heureFin: '14:00' });
    expect(labels(ines!)).toEqual(['Ines']);
    expect(oscar).toMatchObject({ heureFin: '19:00' });
    expect(labels(oscar!)).toEqual(['Oscar']);
    // Neither line ever claims the full créneau span the closure cuts through.
    expect(lines.every((line) => !(line.heureDebut === '13:40' && line.heureFin === '19:00'))).toBe(true);
  });

  it('falls back to the créneau hours when a poste has no effective-window override', () => {
    const c1 = creneau({ id: 1, heureDebut: '09:00', heureFin: '12:00' });
    const days = buildDays([poste({ id: 'p1', creneau: c1, stand: stand('S1'), animateur: animateur('A') })]);

    expect(days[0].slots[0].stands[0]).toMatchObject({ heureDebut: '09:00', heureFin: '12:00' });
  });
});

describe('buildDays — understaffing indicator', () => {
  it('carries the stand effectifMin on each line, so a partially-staffed stand can be flagged', () => {
    // Regression for the reported DEV case: 17/07/2026 Stand Stratégie 16,
    // 18h30-00h, effectifMin 2 but only one animateur assigned — the line
    // must carry enough data (entries.length < effectifMin) for the template
    // to flag it, not just the fully-unassigned case already shown in red.
    const c1 = creneau({ id: 1, heureDebut: '18:30', heureFin: '00:00' });
    const s1 = stand('Stratégie 16', 2);
    const days = buildDays([poste({ id: 'p1', creneau: c1, stand: s1, animateur: animateur('Oscar') })]);

    const line = days[0].slots[0].stands[0];
    expect(labels(line)).toEqual(['Oscar']);
    expect(line.effectifMin).toBe(2);
    expect(line.entries.length).toBeLessThan(line.effectifMin);
  });

  it('a fully-staffed stand is not understaffed', () => {
    const c1 = creneau({ id: 1 });
    const s1 = stand('S1', 2);
    const days = buildDays([
      poste({ id: 'p1', creneau: c1, stand: s1, animateur: animateur('A') }),
      poste({ id: 'p2', creneau: c1, stand: s1, animateur: animateur('B') })
    ]);

    const line = days[0].slots[0].stands[0];
    expect(line.entries.length).toBeGreaterThanOrEqual(line.effectifMin);
  });

  it('flags the whole day card when any of its stand-lines is understaffed, visible without opening it', () => {
    const c1 = creneau({ id: 1, jour: 1 });
    const days = buildDays([
      poste({ id: 'p1', creneau: c1, stand: stand('Stratégie 16', 2), animateur: animateur('Oscar') })
    ]);

    expect(days[0].understaffed).toBe(true);
  });

  it('does not flag a day whose stands are all fully staffed or fully unassigned', () => {
    const c1 = creneau({ id: 1, jour: 1 });
    const days = buildDays([
      poste({ id: 'p1', creneau: c1, stand: stand('S1', 1), animateur: animateur('A') }),
      poste({ id: 'p2', creneau: c1, stand: stand('S2', 1) })
    ]);

    expect(days[0].understaffed).toBe(false);
  });
});
