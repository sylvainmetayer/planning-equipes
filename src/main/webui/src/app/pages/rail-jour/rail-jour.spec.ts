import { describe, expect, it } from 'vitest';
import { Animateur, ContrainteAdHoc, Creneau, PosteAffectation, Stand } from '../../core/models';
import { RailLigne, buildRailJours, compterStatuts } from './rail-jour';

function creneau(overrides: Partial<Creneau> & { id: number }): Creneau {
  return { jour: 1, date: '2026-08-01', heureDebut: '10:00', heureFin: '12:00', ...overrides };
}

function stand(id: string, typologies: string[] = []): Stand {
  return {
    id,
    nom: id,
    typologiesProposees: typologies,
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

function animateur(id: string, overrides: Partial<Animateur> = {}): Animateur {
  return {
    id,
    prenom: id,
    nom: '',
    dateNaissance: '2000-01-01',
    manager: false,
    competences: {},
    souhaits: [],
    joursIndisponibles: [],
    ...overrides
  };
}

function poste(overrides: Partial<PosteAffectation> & { id: string }): PosteAffectation {
  return { stand: null, creneau: null, animateur: null, ...overrides };
}

function indisponibiliteForcee(
  animateurIds: string[],
  overrides: Partial<ContrainteAdHoc> = {}
): ContrainteAdHoc {
  return {
    id: `c-${animateurIds.join('-')}`,
    type: 'INDISPONIBILITE_FORCEE',
    animateursConcernes: animateurIds.map((id) => ({ id })),
    creneau: null,
    stand: null,
    raison: 'test',
    ...overrides
  };
}

function ligne(lignes: RailLigne[], nom: string): RailLigne {
  const trouvee = lignes.find((candidate) => candidate.nom === nom);
  if (!trouvee) {
    throw new Error(`ligne introuvable : ${nom}`);
  }
  return trouvee;
}

describe('buildRailJours', () => {
  it('gives every animateur of the edition a line, assigned or not', () => {
    const jours = buildRailJours(
      [poste({ id: 'p1', creneau: creneau({ id: 1 }), stand: stand('S1'), animateur: animateur('Ines') })],
      [animateur('Ines'), animateur('Oscar'), animateur('Zoe')]
    );

    expect(jours).toHaveLength(1);
    expect(jours[0].lignes.map((l) => l.nom)).toEqual(['Ines', 'Oscar', 'Zoe']);
    expect(compterStatuts(jours[0].lignes)).toEqual({ affectes: 1, libres: 2, indisponibles: 0 });
  });

  it('tells a free animateur from one who declared the day unavailable', () => {
    const jours = buildRailJours(
      [poste({ id: 'p1', creneau: creneau({ id: 1, date: '2026-08-01' }), stand: stand('S1'), animateur: animateur('Ines') })],
      [animateur('Ines'), animateur('Oscar'), animateur('Zoe', { joursIndisponibles: ['2026-08-01'] })]
    );

    expect(ligne(jours[0].lignes, 'Oscar').statut).toBe('libre');
    expect(ligne(jours[0].lignes, 'Zoe').statut).toBe('indisponible');
    expect(compterStatuts(jours[0].lignes)).toEqual({ affectes: 1, libres: 1, indisponibles: 1 });
  });

  it('positions a block against the day span rounded outwards to whole hours', () => {
    // Day span 09:30-12:00 rounds to 09:00-12:00, so a 10:00-12:00 vacation
    // starts a third in and covers the last two thirds.
    const jours = buildRailJours(
      [
        poste({
          id: 'p1',
          creneau: creneau({ id: 1, heureDebut: '09:30', heureFin: '10:00' }),
          stand: stand('S0'),
          animateur: animateur('Oscar')
        }),
        poste({ id: 'p2', creneau: creneau({ id: 2 }), stand: stand('S1'), animateur: animateur('Ines') })
      ],
      [animateur('Ines'), animateur('Oscar')]
    );

    expect(jours[0].debutMinutes).toBe(9 * 60);
    expect(jours[0].finMinutes).toBe(12 * 60);
    expect(jours[0].heures.map((heure) => heure.label)).toEqual(['09:00', '10:00', '11:00', '12:00']);
    const bloc = ligne(jours[0].lignes, 'Ines').blocs[0];
    expect(bloc.offsetPercent).toBeCloseTo((1 / 3) * 100);
    expect(bloc.widthPercent).toBeCloseTo((2 / 3) * 100);
  });

  it('widens the rail to unfilled seats too', () => {
    const jours = buildRailJours(
      [
        poste({ id: 'p1', creneau: creneau({ id: 1, heureDebut: '08:00', heureFin: '10:00' }), stand: stand('S0') }),
        poste({ id: 'p2', creneau: creneau({ id: 2 }), stand: stand('S1'), animateur: animateur('Ines') })
      ],
      [animateur('Ines')]
    );

    expect(jours[0].debutMinutes).toBe(8 * 60);
  });

  it('honours the poste window when a partial stand closure narrowed the créneau', () => {
    const jours = buildRailJours(
      [
        poste({
          id: 'p1',
          creneau: creneau({ id: 1, heureDebut: '10:00', heureFin: '12:00' }),
          stand: stand('S1'),
          animateur: animateur('Ines'),
          heureDebutEffective: '11:00',
          heureFinEffective: '12:00'
        })
      ],
      [animateur('Ines')]
    );

    expect(ligne(jours[0].lignes, 'Ines').blocs[0]).toMatchObject({ heureDebut: '11:00', heureFin: '12:00' });
  });

  it('reads a vacation ending at midnight as the end of the day, not its start', () => {
    const jours = buildRailJours(
      [
        poste({
          id: 'p1',
          creneau: creneau({ id: 1, heureDebut: '22:00', heureFin: '00:00' }),
          stand: stand('S1'),
          animateur: animateur('Ines')
        })
      ],
      [animateur('Ines')]
    );

    expect(jours[0].finMinutes).toBe(24 * 60);
    expect(ligne(jours[0].lignes, 'Ines').blocs[0].widthPercent).toBeCloseTo(100);
  });

  it('flags two vacations landing on the same moment', () => {
    const jours = buildRailJours(
      [
        poste({
          id: 'p1',
          creneau: creneau({ id: 1, heureDebut: '10:00', heureFin: '12:00' }),
          stand: stand('S1'),
          animateur: animateur('Ines')
        }),
        poste({
          id: 'p2',
          creneau: creneau({ id: 2, heureDebut: '11:00', heureFin: '13:00' }),
          stand: stand('S2'),
          animateur: animateur('Ines')
        })
      ],
      [animateur('Ines')]
    );

    const ines = ligne(jours[0].lignes, 'Ines');
    expect(ines.chevauchement).toBe(true);
    expect(ines.blocs.map((bloc) => bloc.chevauchement)).toEqual([false, true]);
  });

  it('sums the worked time and spans the day, back-to-back vacations included', () => {
    const jours = buildRailJours(
      [
        poste({
          id: 'p1',
          creneau: creneau({ id: 1, heureDebut: '09:00', heureFin: '12:00' }),
          stand: stand('S1'),
          animateur: animateur('Ines')
        }),
        poste({
          id: 'p2',
          creneau: creneau({ id: 2, heureDebut: '14:00', heureFin: '16:30' }),
          stand: stand('S2'),
          animateur: animateur('Ines')
        })
      ],
      [animateur('Ines')]
    );

    const ines = ligne(jours[0].lignes, 'Ines');
    expect(ines).toMatchObject({ amplitudeDebut: '09:00', amplitudeFin: '16:30', chevauchement: false });
    // 3 h + 2 h 30 of actual work, not the 7 h 30 of the span.
    expect(ines.dureeLabel).toContain('5');
    expect(ines.resume).toContain('Ines');
    expect(ines.resume).toContain('S2 · 14:00 – 16:30');
  });

  it('colours a block after the stand main typologie and keeps the id for the legend', () => {
    const jours = buildRailJours(
      [
        poste({
          id: 'p1',
          creneau: creneau({ id: 1 }),
          stand: stand('S1', ['reflexion', 'adresse']),
          animateur: animateur('Ines')
        })
      ],
      [animateur('Ines')]
    );

    const bloc = ligne(jours[0].lignes, 'Ines').blocs[0];
    // Lowest id wins, so the colour never depends on set iteration order.
    expect(bloc.typologie).toBe('adresse');
    expect(bloc.colorClass).toMatch(/^typologie-color-/);
  });

  it('orders the days and keeps every animateur on each of them', () => {
    const jours = buildRailJours(
      [
        poste({
          id: 'p2',
          creneau: creneau({ id: 2, jour: 2, date: '2026-08-02' }),
          stand: stand('S1'),
          animateur: animateur('Oscar')
        }),
        poste({ id: 'p1', creneau: creneau({ id: 1, jour: 1 }), stand: stand('S1'), animateur: animateur('Ines') })
      ],
      [animateur('Ines'), animateur('Oscar')]
    );

    expect(jours.map((jour) => jour.jour)).toEqual([1, 2]);
    expect(jours[0].lignes).toHaveLength(2);
    expect(ligne(jours[0].lignes, 'Oscar').statut).toBe('libre');
    expect(ligne(jours[1].lignes, 'Ines').statut).toBe('libre');
  });

  it('disambiguates two animateurs sharing the same display name', () => {
    const jours = buildRailJours([], [animateur('a1', { prenom: 'Jean', nom: 'Dupont' }), animateur('a2', { prenom: 'Jean', nom: 'Dupont' })]);
    expect(jours).toEqual([]);

    const avecJour = buildRailJours(
      [poste({ id: 'p1', creneau: creneau({ id: 1 }), stand: stand('S1'), animateur: animateur('a1') })],
      [animateur('a1', { prenom: 'Jean', nom: 'Dupont' }), animateur('a2', { prenom: 'Jean', nom: 'Dupont' })]
    );
    expect(avecJour[0].lignes.map((l) => l.nom)).toEqual(['Jean Dupont (a1)', 'Jean Dupont (a2)']);
  });

  it('says out loud that two vacations overlap, which no outline can do', () => {
    const jours = buildRailJours(
      [
        poste({
          id: 'p1',
          creneau: creneau({ id: 1, heureDebut: '10:00', heureFin: '12:00' }),
          stand: stand('S1'),
          animateur: animateur('Ines')
        }),
        poste({
          id: 'p2',
          creneau: creneau({ id: 2, heureDebut: '11:00', heureFin: '13:00' }),
          stand: stand('S2'),
          animateur: animateur('Ines')
        })
      ],
      [animateur('Ines')]
    );

    expect(ligne(jours[0].lignes, 'Ines').resume).toContain('chevauchent');
  });

  it('hatches the hours a forced unavailability covers, without denying the rest of the day', () => {
    const matin = creneau({ id: 1, heureDebut: '09:00', heureFin: '12:00' });
    const apresMidi = creneau({ id: 2, heureDebut: '14:00', heureFin: '18:00' });
    const jours = buildRailJours(
      [
        poste({ id: 'p1', creneau: matin, stand: stand('S1'), animateur: animateur('Ines') }),
        poste({ id: 'p2', creneau: apresMidi, stand: stand('S1') })
      ],
      [animateur('Ines'), animateur('Zoe')],
      [indisponibiliteForcee(['Zoe'], { creneau: { id: 2 } })]
    );

    const zoe = ligne(jours[0].lignes, 'Zoe');
    // Blocked in the afternoon only: still someone to call for the morning.
    expect(zoe.statut).toBe('libre');
    expect(zoe.blocages).toHaveLength(1);
    expect(zoe.blocages[0]).toMatchObject({ heureDebut: '14:00', heureFin: '18:00' });
    expect(zoe.resume).toContain('14:00 – 18:00');
    expect(compterStatuts(jours[0].lignes)).toEqual({ affectes: 1, libres: 1, indisponibles: 0 });
  });

  it('counts an animateur blocked all day out of the mobilisable ones', () => {
    const jours = buildRailJours(
      [poste({ id: 'p1', creneau: creneau({ id: 1 }), stand: stand('S1'), animateur: animateur('Ines') })],
      [animateur('Ines'), animateur('Zoe')],
      // No créneau: the exception covers the whole event, so the whole day.
      [indisponibiliteForcee(['Zoe'])]
    );

    expect(ligne(jours[0].lignes, 'Zoe').statut).toBe('indisponible');
    expect(compterStatuts(jours[0].lignes)).toEqual({ affectes: 1, libres: 0, indisponibles: 1 });
  });

  it('leaves an animateur callable when the exception only bars them from one stand', () => {
    // « pas sur ce stand » forbids a seat, not a person: counting it as an
    // unavailability would hide exactly who a day of tension is looking for.
    const jours = buildRailJours(
      [poste({ id: 'p1', creneau: creneau({ id: 1 }), stand: stand('S1'), animateur: animateur('Ines') })],
      [animateur('Ines'), animateur('Zoe')],
      [indisponibiliteForcee(['Zoe'], { stand: { id: 'S1' } })]
    );

    const zoe = ligne(jours[0].lignes, 'Zoe');
    expect(zoe.statut).toBe('libre');
    expect(zoe.blocages).toHaveLength(0);
  });

  it('ignores an exception naming a créneau of another day', () => {
    const jours = buildRailJours(
      [
        poste({ id: 'p1', creneau: creneau({ id: 1, jour: 1 }), stand: stand('S1'), animateur: animateur('Ines') }),
        poste({
          id: 'p2',
          creneau: creneau({ id: 2, jour: 2, date: '2026-08-02' }),
          stand: stand('S1'),
          animateur: animateur('Ines')
        })
      ],
      [animateur('Ines'), animateur('Zoe')],
      [indisponibiliteForcee(['Zoe'], { creneau: { id: 2 } })]
    );

    expect(ligne(jours[0].lignes, 'Zoe').blocages).toHaveLength(0);
    expect(ligne(jours[1].lignes, 'Zoe').blocages).toHaveLength(1);
  });

  it('merges two exceptions covering back-to-back créneaux into one window', () => {
    const jours = buildRailJours(
      [
        poste({
          id: 'p1',
          creneau: creneau({ id: 1, heureDebut: '09:00', heureFin: '12:00' }),
          stand: stand('S1'),
          animateur: animateur('Ines')
        }),
        poste({ id: 'p2', creneau: creneau({ id: 2, heureDebut: '12:00', heureFin: '15:00' }), stand: stand('S1') })
      ],
      [animateur('Ines'), animateur('Zoe')],
      [
        indisponibiliteForcee(['Zoe'], { creneau: { id: 1 } }),
        { ...indisponibiliteForcee(['Zoe'], { creneau: { id: 2 } }), id: 'c2' }
      ]
    );

    const zoe = ligne(jours[0].lignes, 'Zoe');
    // 09:00-12:00 then 12:00-15:00 is one 09:00-15:00 hole, and it swallows the
    // whole day: nobody to call.
    expect(zoe.blocages).toHaveLength(1);
    expect(zoe.blocages[0]).toMatchObject({ heureDebut: '09:00', heureFin: '15:00' });
    expect(zoe.statut).toBe('indisponible');
  });

  it('still lines up an animateur holding a seat but missing from the referential', () => {
    const jours = buildRailJours(
      [poste({ id: 'p1', creneau: creneau({ id: 1 }), stand: stand('S1'), animateur: animateur('Fantome') })],
      []
    );

    expect(ligne(jours[0].lignes, 'Fantome').blocs).toHaveLength(1);
  });
});
