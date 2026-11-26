import { describe, expect, it } from 'vitest';
import { Animateur, Creneau, PosteAffectation, Stand } from '../../core/models';
import { RailBloc, RailLigne, buildRailJours, compterStatuts, shiftToOpen } from './rail-jour';

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
    horaires: [],
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
    ...overrides,
  };
}

function poste(overrides: Partial<PosteAffectation> & { id: string }): PosteAffectation {
  return { stand: null, creneau: null, animateur: null, ...overrides };
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
      [
        poste({
          id: 'p1',
          creneau: creneau({ id: 1 }),
          stand: stand('S1'),
          animateur: animateur('Ines'),
        }),
      ],
      [animateur('Ines'), animateur('Oscar'), animateur('Zoe')],
    );

    expect(jours).toHaveLength(1);
    expect(jours[0].lignes.map((l) => l.nom)).toEqual(['Ines', 'Oscar', 'Zoe']);
    expect(compterStatuts(jours[0].lignes)).toEqual({ affectes: 1, libres: 2, indisponibles: 0 });
  });

  it('tells a free animateur from one who declared the day unavailable', () => {
    const jours = buildRailJours(
      [
        poste({
          id: 'p1',
          creneau: creneau({ id: 1, date: '2026-08-01' }),
          stand: stand('S1'),
          animateur: animateur('Ines'),
        }),
      ],
      [
        animateur('Ines'),
        animateur('Oscar'),
        animateur('Zoe', { joursIndisponibles: ['2026-08-01'] }),
      ],
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
          animateur: animateur('Oscar'),
        }),
        poste({
          id: 'p2',
          creneau: creneau({ id: 2 }),
          stand: stand('S1'),
          animateur: animateur('Ines'),
        }),
      ],
      [animateur('Ines'), animateur('Oscar')],
    );

    expect(jours[0].debutMinutes).toBe(9 * 60);
    expect(jours[0].finMinutes).toBe(12 * 60);
    expect(jours[0].heures.map((heure) => heure.label)).toEqual([
      '09:00',
      '10:00',
      '11:00',
      '12:00',
    ]);
    const bloc = ligne(jours[0].lignes, 'Ines').blocs[0];
    expect(bloc.offsetPercent).toBeCloseTo((1 / 3) * 100);
    expect(bloc.widthPercent).toBeCloseTo((2 / 3) * 100);
  });

  it('widens the rail to unfilled seats too', () => {
    const jours = buildRailJours(
      [
        poste({
          id: 'p1',
          creneau: creneau({ id: 1, heureDebut: '08:00', heureFin: '10:00' }),
          stand: stand('S0'),
        }),
        poste({
          id: 'p2',
          creneau: creneau({ id: 2 }),
          stand: stand('S1'),
          animateur: animateur('Ines'),
        }),
      ],
      [animateur('Ines')],
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
          heureFinEffective: '12:00',
        }),
      ],
      [animateur('Ines')],
    );

    expect(ligne(jours[0].lignes, 'Ines').blocs[0]).toMatchObject({
      heureDebut: '11:00',
      heureFin: '12:00',
    });
  });

  it('reads a vacation ending at midnight as the end of the day, not its start', () => {
    const jours = buildRailJours(
      [
        poste({
          id: 'p1',
          creneau: creneau({ id: 1, heureDebut: '22:00', heureFin: '00:00' }),
          stand: stand('S1'),
          animateur: animateur('Ines'),
        }),
      ],
      [animateur('Ines')],
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
          animateur: animateur('Ines'),
        }),
        poste({
          id: 'p2',
          creneau: creneau({ id: 2, heureDebut: '11:00', heureFin: '13:00' }),
          stand: stand('S2'),
          animateur: animateur('Ines'),
        }),
      ],
      [animateur('Ines')],
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
          animateur: animateur('Ines'),
        }),
        poste({
          id: 'p2',
          creneau: creneau({ id: 2, heureDebut: '14:00', heureFin: '16:30' }),
          stand: stand('S2'),
          animateur: animateur('Ines'),
        }),
      ],
      [animateur('Ines')],
    );

    const ines = ligne(jours[0].lignes, 'Ines');
    expect(ines).toMatchObject({
      amplitudeDebut: '09:00',
      amplitudeFin: '16:30',
      chevauchement: false,
    });
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
          animateur: animateur('Ines'),
        }),
      ],
      [animateur('Ines')],
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
          animateur: animateur('Oscar'),
        }),
        poste({
          id: 'p1',
          creneau: creneau({ id: 1, jour: 1 }),
          stand: stand('S1'),
          animateur: animateur('Ines'),
        }),
      ],
      [animateur('Ines'), animateur('Oscar')],
    );

    expect(jours.map((jour) => jour.jour)).toEqual([1, 2]);
    expect(jours[0].lignes).toHaveLength(2);
    expect(ligne(jours[0].lignes, 'Oscar').statut).toBe('libre');
    expect(ligne(jours[1].lignes, 'Ines').statut).toBe('libre');
  });

  it('disambiguates two animateurs sharing the same display name', () => {
    const jours = buildRailJours(
      [],
      [
        animateur('a1', { prenom: 'Jean', nom: 'Dupont' }),
        animateur('a2', { prenom: 'Jean', nom: 'Dupont' }),
      ],
    );
    expect(jours).toEqual([]);

    const avecJour = buildRailJours(
      [
        poste({
          id: 'p1',
          creneau: creneau({ id: 1 }),
          stand: stand('S1'),
          animateur: animateur('a1'),
        }),
      ],
      [
        animateur('a1', { prenom: 'Jean', nom: 'Dupont' }),
        animateur('a2', { prenom: 'Jean', nom: 'Dupont' }),
      ],
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
          animateur: animateur('Ines'),
        }),
        poste({
          id: 'p2',
          creneau: creneau({ id: 2, heureDebut: '11:00', heureFin: '13:00' }),
          stand: stand('S2'),
          animateur: animateur('Ines'),
        }),
      ],
      [animateur('Ines')],
    );

    expect(ligne(jours[0].lignes, 'Ines').resume).toContain('chevauchent');
  });

  it('still lines up an animateur holding a seat but missing from the referential', () => {
    const jours = buildRailJours(
      [
        poste({
          id: 'p1',
          creneau: creneau({ id: 1 }),
          stand: stand('S1'),
          animateur: animateur('Fantome'),
        }),
      ],
      [],
    );

    expect(ligne(jours[0].lignes, 'Fantome').blocs).toHaveLength(1);
  });

  describe('buildRailJours — pauses', () => {
    it('draws the breaks of the day on the line, flags the relay-less one and says it in the summary', () => {
      const alice = animateur('alice');
      const postes = [
        poste({
          id: 'p1',
          creneau: creneau({ id: 1, date: '2026-08-01', heureDebut: '13:00', heureFin: '20:00' }),
          stand: stand('Tir'),
          animateur: alice,
        }),
      ];
      const pauses = {
        journeesAnalysees: 1,
        pausesDues: 1,
        relaisManquants: 1,
        coupuresRepasDues: 0,
        coupuresRepasManquantes: 0,
        message: '',
        journees: [
          {
            animateurId: 'alice',
            nomComplet: 'Alice',
            mineur: false,
            date: '2026-08-01',
            jour: 1,
            sequences: [
              {
                debut: '13:00:00',
                fin: '20:00:00',
                minutes: 420,
                pausesDues: [
                  {
                    debut: '19:00:00',
                    fin: '19:20:00',
                    heureLimite: '19:00:00',
                    dureeMinutes: 20,
                    standId: 'Tir',
                    standNom: 'Tir',
                    relais: [],
                    relaisDisponible: false,
                    simultanee: false,
                  },
                ],
              },
            ],
            pausesPlanifiees: [],
            coupuresRepas: [],
          },
        ],
      };

      const [jour] = buildRailJours(postes, [alice], pauses);
      const ligne = jour.lignes[0];

      expect(ligne.pauses).toHaveLength(1);
      expect(ligne.pauses[0].withoutRelais).toBe(true);
      // Scale 13:00 → 20:00, whole hours: 19:00 sits at 6/7 of the track.
      expect(ligne.pauses[0].offsetPercent).toBeCloseTo((6 / 7) * 100, 5);
      expect(ligne.resume).toContain("Pause 19:00 – 19:20 sur Tir — personne d'autre sur le stand");
    });

    it('ignores a seat without a timeslot rather than failing the day', () => {
      const alice = animateur('alice');
      const postes = [
        poste({
          id: 'p1',
          creneau: creneau({ id: 1, date: '2026-08-01' }),
          stand: stand('Tir'),
          animateur: alice,
        }),
        poste({ id: 'p2', creneau: undefined, stand: stand('Tir'), animateur: alice }),
      ];

      expect(buildRailJours(postes, [alice])).toHaveLength(1);
    });

    it('draws nothing without a report, and an empty line carries no break', () => {
      const alice = animateur('alice');
      const postes = [
        poste({
          id: 'p1',
          creneau: creneau({ id: 1, date: '2026-08-01', heureDebut: '13:00', heureFin: '20:00' }),
          stand: stand('Tir'),
          animateur: alice,
        }),
      ];

      const [without] = buildRailJours(postes, [alice, animateur('bob')]);
      expect(without.lignes.every((ligne) => ligne.pauses.length === 0)).toBe(true);
    });
  });
});

describe('buildRailJours — tight walks', () => {
  it('puts a red chevron in the gap between the two vacations, and says it', () => {
    const ines = animateur('Ines');
    const jours = buildRailJours(
      [
        poste({
          id: 'p1',
          creneau: creneau({ id: 1, heureDebut: '10:00', heureFin: '14:00' }),
          stand: stand('A'),
          animateur: ines,
        }),
        poste({
          id: 'p2',
          creneau: creneau({ id: 2, heureDebut: '14:10', heureFin: '18:00' }),
          stand: stand('B'),
          animateur: ines,
        }),
      ],
      [ines],
      null,
      {
        walkingSpeedKmH: 4,
        detourFactor: 1.3,
        toleranceMinutes: 5,
        geolocated: true,
        walks: [
          {
            animateurId: 'Ines',
            date: '2026-08-01',
            end: '14:00:00',
            start: '14:10:00',
            distanceMetres: 1000,
            walkMinutes: 20,
            gapMinutes: 10,
            missingMinutes: 5,
            walkOnBreak: false,
          },
        ],
      },
    );

    const line = ligne(jours[0].lignes, 'Ines');
    expect(line.trajets).toHaveLength(1);
    expect(line.trajets[0].tight).toBe(true);
    expect(line.trajets[0].label).toBe('14:00 → 14:10 : 20 min à pied, 10 min de battement');
    // 14:00 on a 10:00–18:00 rail is half-way.
    expect(line.trajets[0].offsetPercent).toBe(50);
    expect(line.resume).toContain('1 trajet(s) trop court(s)');
  });

  it('draws nothing without the report', () => {
    const ines = animateur('Ines');
    const jours = buildRailJours(
      [
        poste({
          id: 'p1',
          creneau: creneau({ id: 1 }),
          stand: stand('A'),
          animateur: ines,
        }),
      ],
      [ines],
    );

    expect(ligne(jours[0].lignes, 'Ines').trajets).toEqual([]);
  });
});

describe('buildRailJours — covoiturage', () => {
  it('marks the members of a group, in red on a day it does not hold', () => {
    const ines = animateur('Ines');
    const oscar = animateur('Oscar');
    const jours = buildRailJours(
      [poste({ id: 'p1', creneau: creneau({ id: 1 }), stand: stand('A'), animateur: ines })],
      [ines, oscar, animateur('Zoe')],
      null,
      null,
      {
        toleranceMinutes: 30,
        groups: [
          {
            contrainteId: 'G1',
            animateurIds: ['Ines', 'Oscar'],
            misalignedDays: 1,
            days: [
              {
                date: '2026-08-01',
                aligned: false,
                working: ['Ines'],
                absent: ['Oscar'],
                arrivalSpreadMinutes: 0,
                departureSpreadMinutes: 0,
                hours: [],
              },
            ],
          },
        ],
      },
    );

    expect(ligne(jours[0].lignes, 'Ines').covoiturage).toEqual({
      desaligne: true,
      label: 'Covoiturage avec Oscar — désaligné ce jour',
    });
    expect(ligne(jours[0].lignes, 'Ines').resume).toContain('désaligné ce jour');
    expect(ligne(jours[0].lignes, 'Zoe').covoiturage).toBeNull();
  });
});

describe('shiftToOpen', () => {
  const bloc = (posteId: string) => ({ posteId }) as RailBloc;

  it('opens the first shift, then walks the line and comes back round', () => {
    const blocs = [bloc('p1'), bloc('p2'), bloc('p3')];

    expect(shiftToOpen(blocs, null)?.posteId).toBe('p1');
    expect(shiftToOpen(blocs, 'p1')?.posteId).toBe('p2');
    expect(shiftToOpen(blocs, 'p3')?.posteId).toBe('p1');
    // The panel open on a seat of another line: this line starts from its first.
    expect(shiftToOpen(blocs, 'autre')?.posteId).toBe('p1');
  });

  it('opens nothing on a line without shifts', () => {
    expect(shiftToOpen([], null)).toBeNull();
  });
});
