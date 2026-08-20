import { describe, expect, it } from 'vitest';
import { Animateur, Creneau, PosteAffectation, Stand } from '../../core/models';
import { buildAnimateurOptions, buildAnimateurTimeline, buildStandsSummary, exportFilename } from './animateur-timeline-page';

function creneau(overrides: Partial<Creneau> & { id: number; jour: number }): Creneau {
  return { date: '2026-08-01', heureDebut: '09:00', heureFin: '12:00', ...overrides };
}

function stand(id: string, typologiesProposees: string[] = []): Stand {
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

function animateur(id: string, prenom = id, nom = ''): Animateur {
  return { id, prenom, nom, dateNaissance: '2000-01-01', manager: false, competences: {}, joursIndisponibles: [], souhaits: [] };
}

function poste(overrides: Partial<PosteAffectation> & { id: string }): PosteAffectation {
  return { stand: null, creneau: null, animateur: null, ...overrides };
}

describe('buildAnimateurTimeline', () => {
  it('positions a single vacation spanning the whole amplitude at 0% offset and 100% width', () => {
    const days = buildAnimateurTimeline(
      [poste({ id: 'p1', creneau: creneau({ id: 1, jour: 1, heureDebut: '09:00', heureFin: '12:00' }), stand: stand('S1'), animateur: animateur('A') })],
      'A'
    );

    expect(days).toHaveLength(1);
    expect(days[0].blocks).toEqual([
      expect.objectContaining({ heureDebut: '09:00', heureFin: '12:00', offsetPercent: 0, widthPercent: 100 })
    ]);
    expect(days[0].gaps).toHaveLength(0);
  });

  it('lists the other animateurs of the same stand line as teammates, and only them', () => {
    const c1 = creneau({ id: 1, jour: 1, heureDebut: '09:00', heureFin: '12:00' });
    const s1 = stand('S1');
    const days = buildAnimateurTimeline(
      [
        poste({ id: 'p1', creneau: c1, stand: s1, animateur: animateur('A', 'Ada', 'Lovelace') }),
        poste({ id: 'p2', creneau: c1, stand: s1, animateur: animateur('B', 'Alan', 'Turing') }),
        poste({ id: 'p3', creneau: c1, stand: s1, animateur: null }), // unfilled seat: nobody to name
        // Same créneau, another stand: not a teammate.
        poste({ id: 'p4', creneau: c1, stand: stand('S2'), animateur: animateur('C', 'Grace', 'Hopper') })
      ],
      'A'
    );

    expect(days[0].blocks[0].coequipiers).toEqual(['Alan Turing']);
  });

  it('leaves the teammate list empty for a stand held alone', () => {
    const days = buildAnimateurTimeline(
      [poste({ id: 'p1', creneau: creneau({ id: 1, jour: 1 }), stand: stand('S1'), animateur: animateur('A') })],
      'A'
    );

    expect(days[0].blocks[0].coequipiers).toEqual([]);
  });

  it('does not pair two segments of the same stand and créneau split by a partial closure', () => {
    // A mid-créneau closure splits the stand into two windows: whoever holds
    // the morning half never meets whoever holds the afternoon half.
    const c1 = creneau({ id: 1, jour: 1, heureDebut: '09:00', heureFin: '18:00' });
    const s1 = stand('S1');
    const days = buildAnimateurTimeline(
      [
        poste({ id: 'p1', creneau: c1, stand: s1, animateur: animateur('A'), heureDebutEffective: '09:00', heureFinEffective: '12:00' }),
        poste({ id: 'p2', creneau: c1, stand: s1, animateur: animateur('B'), heureDebutEffective: '14:00', heureFinEffective: '18:00' })
      ],
      'A'
    );

    expect(days[0].blocks[0].coequipiers).toEqual([]);
  });

  it('inserts a gap between two vacations, sized proportionally to the amplitude', () => {
    // 08:00-10:00 then 11:00-13:00: amplitude is 08:00-13:00 (300 min), the
    // 1h gap (10:00-11:00, 60 min) should land at 40% offset / 20% width.
    const days = buildAnimateurTimeline(
      [
        poste({ id: 'p1', creneau: creneau({ id: 1, jour: 1, heureDebut: '08:00', heureFin: '10:00' }), stand: stand('S1'), animateur: animateur('A') }),
        poste({ id: 'p2', creneau: creneau({ id: 2, jour: 1, heureDebut: '11:00', heureFin: '13:00' }), stand: stand('S2'), animateur: animateur('A') })
      ],
      'A'
    );

    expect(days[0].blocks).toHaveLength(2);
    expect(days[0].gaps).toEqual([expect.objectContaining({ dureeMinutes: 60, offsetPercent: 40, widthPercent: 20 })]);
    expect(days[0].amplitudeDebut).toBe('08:00');
    expect(days[0].amplitudeFin).toBe('13:00');
  });

  it('produces no gap for two back-to-back vacations', () => {
    const days = buildAnimateurTimeline(
      [
        poste({ id: 'p1', creneau: creneau({ id: 1, jour: 1, heureDebut: '08:00', heureFin: '10:00' }), stand: stand('S1'), animateur: animateur('A') }),
        poste({ id: 'p2', creneau: creneau({ id: 2, jour: 1, heureDebut: '10:00', heureFin: '12:00' }), stand: stand('S2'), animateur: animateur('A') })
      ],
      'A'
    );

    expect(days[0].gaps).toHaveLength(0);
  });

  it('treats a "00:00" end of a vacation as midnight (end of this festival day), not the start of the next', () => {
    const days = buildAnimateurTimeline(
      [poste({ id: 'p1', creneau: creneau({ id: 1, jour: 1, heureDebut: '22:00', heureFin: '00:00' }), stand: stand('S1'), animateur: animateur('A') })],
      'A'
    );

    expect(days[0].amplitudeFin).toBe('00:00');
    expect(days[0].blocks[0]).toMatchObject({ widthPercent: 100 });
  });

  it('prefers heureDebutEffective/heureFinEffective over the créneau window (issue #60 partial closures)', () => {
    const days = buildAnimateurTimeline(
      [
        poste({
          id: 'p1',
          creneau: creneau({ id: 1, jour: 1, heureDebut: '08:00', heureFin: '20:00' }),
          stand: stand('S1'),
          animateur: animateur('A'),
          heureDebutEffective: '14:00',
          heureFinEffective: '16:00'
        })
      ],
      'A'
    );

    expect(days[0].blocks[0]).toMatchObject({ heureDebut: '14:00', heureFin: '16:00' });
  });

  it('groups vacations by festival day and orders days chronologically', () => {
    const days = buildAnimateurTimeline(
      [
        poste({ id: 'p1', creneau: creneau({ id: 1, jour: 2 }), stand: stand('S1'), animateur: animateur('A') }),
        poste({ id: 'p2', creneau: creneau({ id: 2, jour: 1 }), stand: stand('S2'), animateur: animateur('A') })
      ],
      'A'
    );

    expect(days.map((day) => day.jour)).toEqual([1, 2]);
  });

  it('excludes other animateurs and unassigned postes', () => {
    const days = buildAnimateurTimeline(
      [
        poste({ id: 'p1', creneau: creneau({ id: 1, jour: 1 }), stand: stand('S1'), animateur: animateur('B') }),
        poste({ id: 'p2', creneau: creneau({ id: 2, jour: 1 }), stand: stand('S2') })
      ],
      'A'
    );

    expect(days).toHaveLength(0);
  });
});

describe('buildAnimateurOptions', () => {
  it('lists each animateur once, sorted by display name', () => {
    const options = buildAnimateurOptions([
      poste({ id: 'p1', creneau: creneau({ id: 1, jour: 1 }), stand: stand('S1'), animateur: animateur('B', 'Bob', 'Zed') }),
      poste({ id: 'p2', creneau: creneau({ id: 2, jour: 1 }), stand: stand('S2'), animateur: animateur('A', 'Alice', 'Young') }),
      poste({ id: 'p3', creneau: creneau({ id: 3, jour: 2 }), stand: stand('S1'), animateur: animateur('B', 'Bob', 'Zed') })
    ]);

    expect(options).toEqual([
      { id: 'A', label: 'Alice Young' },
      { id: 'B', label: 'Bob Zed' }
    ]);
  });

  it('disambiguates two animateurs sharing the same display name by appending their id', () => {
    const options = buildAnimateurOptions([
      poste({ id: 'p1', creneau: creneau({ id: 1, jour: 1 }), stand: stand('S1'), animateur: animateur('id-1', 'Jean', 'Dupont') }),
      poste({ id: 'p2', creneau: creneau({ id: 2, jour: 1 }), stand: stand('S2'), animateur: animateur('id-2', 'Jean', 'Dupont') })
    ]);

    expect(options.map((option) => option.label)).toEqual(['Jean Dupont (id-1)', 'Jean Dupont (id-2)']);
  });
});

describe('exportFilename', () => {
  it('builds a readable filename from the animateur display name', () => {
    expect(exportFilename([{ id: 'id-1', label: 'Jeanne Dupont' }], 'id-1', 'pdf')).toBe('planning-Jeanne-Dupont.pdf');
    expect(exportFilename([{ id: 'id-1', label: 'Jeanne Dupont' }], 'id-1', 'ics')).toBe('planning-Jeanne-Dupont.ics');
  });

  it('falls back on the id and strips path separators when the label is unusable', () => {
    expect(exportFilename([], 'a/b', 'ics')).toBe('planning-a-b.ics');
    expect(exportFilename([{ id: 'id-1', label: '///' }], 'id-1', 'pdf')).toBe('planning-animateur.pdf');
  });
});

describe('buildStandsSummary', () => {
  it('counts each stand once even when the animateur returns to it on several days', () => {
    const days = buildAnimateurTimeline(
      [
        poste({ id: 'p1', creneau: creneau({ id: 1, jour: 1 }), stand: stand('Zebre'), animateur: animateur('id-1', 'Jean', 'Dupont') }),
        poste({ id: 'p2', creneau: creneau({ id: 2, jour: 2 }), stand: stand('Alpha'), animateur: animateur('id-1', 'Jean', 'Dupont') }),
        poste({ id: 'p3', creneau: creneau({ id: 3, jour: 3 }), stand: stand('Alpha'), animateur: animateur('id-1', 'Jean', 'Dupont') })
      ],
      'id-1'
    );

    expect(buildStandsSummary(days).count).toBe(2);
    expect(buildStandsSummary(days).stands.map((stand) => stand.nom)).toEqual(['Alpha', 'Zebre']);
  });

  it('returns an empty summary when the animateur has no day at all', () => {
    expect(buildStandsSummary([])).toEqual({ count: 0, typologieCount: 0, stands: [], legend: [] });
  });

  it('counts each game typologie once across every stand covered', () => {
    const days = buildAnimateurTimeline(
      [
        poste({ id: 'p1', creneau: creneau({ id: 1, jour: 1 }), stand: stand('Zebre', ['AMBIANCE']), animateur: animateur('id-1') }),
        poste({ id: 'p2', creneau: creneau({ id: 2, jour: 2 }), stand: stand('Alpha', ['AMBIANCE', 'STRATEGIE']), animateur: animateur('id-1') })
      ],
      'id-1'
    );

    const summary = buildStandsSummary(days, new Map([['AMBIANCE', 'Ambiance'], ['STRATEGIE', 'Stratégie']]));

    expect(summary.count).toBe(2);
    expect(summary.typologieCount).toBe(2);
    expect(summary.legend.map((item) => item.label)).toEqual(['Ambiance', 'Stratégie']);
    expect(summary.stands[0].typologies).toEqual(['Ambiance', 'Stratégie']);
    expect(summary.stands[0].tooltip).toContain('Ambiance, Stratégie');
  });

  it('gives every stand of the same typologie the same colour, and a stand without typologie the neutral one', () => {
    const days = buildAnimateurTimeline(
      [
        poste({ id: 'p1', creneau: creneau({ id: 1, jour: 1 }), stand: stand('Alpha', ['AMBIANCE']), animateur: animateur('id-1') }),
        poste({ id: 'p2', creneau: creneau({ id: 2, jour: 2 }), stand: stand('Beta', ['AMBIANCE']), animateur: animateur('id-1') }),
        poste({ id: 'p3', creneau: creneau({ id: 3, jour: 3 }), stand: stand('Gamma'), animateur: animateur('id-1') })
      ],
      'id-1'
    );

    const summary = buildStandsSummary(days);

    expect(summary.stands[0].colorClass).toBe(summary.stands[1].colorClass);
    expect(summary.stands[2].colorClass).toBe('typologie-color-none');
  });
});
