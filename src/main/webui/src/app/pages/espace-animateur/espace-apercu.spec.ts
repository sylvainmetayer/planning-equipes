import { describe, expect, it } from 'vitest';
import { PosteAnimateurView } from '../../core/models';
import { JourPlanning } from './espace-maintenant';
import {
  axeFrise,
  finSurLAxe,
  heuresDuJour,
  legendeTypologies,
  lignesFrise,
  minutesDeLHeure,
  statsPlanning,
} from './espace-apercu';

function poste(overrides: Partial<PosteAnimateurView> = {}): PosteAnimateurView {
  return {
    date: '2026-02-16',
    standId: 'stand-1',
    standNom: 'Construction',
    creneauId: 1,
    heureDebut: '10:00:00',
    heureFin: '12:00:00',
    coequipiers: [],
    emplacementNom: null,
    emplacementLatitude: null,
    emplacementLongitude: null,
    typologieId: 'CONSTRUCTION',
    typologieLibelle: 'Jeux de construction',
    ...overrides,
  };
}

function jour(date: string, postes: PosteAnimateurView[], repos = false): JourPlanning {
  return { date, postes, repos, pauses: [] };
}

describe('minutesDeLHeure', () => {
  it('reads the hours the API sends, seconds included', () => {
    expect(minutesDeLHeure('10:00:00')).toBe(600);
    expect(minutesDeLHeure('09:30')).toBe(570);
    expect(minutesDeLHeure(null)).toBeNull();
    expect(minutesDeLHeure('plus tard')).toBeNull();
  });
});

describe('finSurLAxe', () => {
  /** « 22:00–02:00 » is four hours after its start, not twenty hours before it. */
  it('puts a shift running past midnight after its own start', () => {
    expect(finSurLAxe(22 * 60, 2 * 60)).toBe(26 * 60);
    expect(finSurLAxe(10 * 60, 12 * 60)).toBe(12 * 60);
  });
});

describe('axeFrise', () => {
  it('spans from the first start to the last end, each on its own hour', () => {
    const axe = axeFrise([
      jour('2026-02-16', [poste({ heureDebut: '09:30:00', heureFin: '12:00:00' })]),
      jour('2026-02-17', [poste({ heureDebut: '14:00:00', heureFin: '19:45:00' })]),
    ]);

    expect(axe.debut).toBe(9 * 60);
    expect(axe.fin).toBe(20 * 60);
  });

  /** A night shift drawn on a daytime axis falls off it. */
  it('stretches past midnight for a night shift', () => {
    const axe = axeFrise([jour('2026-02-16', [poste({ heureDebut: '22:00', heureFin: '02:00' })])]);

    expect(axe.debut).toBe(22 * 60);
    expect(axe.fin).toBe(26 * 60);
  });

  it('falls back on an ordinary event day when no hour can be read', () => {
    expect(axeFrise([])).toMatchObject({ debut: 9 * 60, fin: 20 * 60 });
    expect(axeFrise([jour('2026-02-16', [], true)])).toMatchObject({ debut: 9 * 60, fin: 20 * 60 });
  });

  /** One short shift on a two-hour axis would fill the whole strip and say nothing. */
  it('keeps a readable span for a planning of one short shift', () => {
    const axe = axeFrise([jour('2026-02-16', [poste({ heureDebut: '10:00', heureFin: '11:00' })])]);

    expect(axe.fin - axe.debut).toBeGreaterThanOrEqual(4 * 60);
  });

  it('labels both edges and no more than six hours', () => {
    const axe = axeFrise([jour('2026-02-16', [poste({ heureDebut: '08:00', heureFin: '23:00' })])]);

    expect(axe.graduations[0]).toBe(8 * 60);
    expect(axe.graduations.at(-1)).toBe(23 * 60);
    expect(axe.graduations.length).toBeLessThanOrEqual(6);
  });
});

describe('lignesFrise', () => {
  const jours = [
    jour('2026-02-15', [poste({ date: '2026-02-15', heureDebut: '10:00', heureFin: '12:00' })]),
    jour('2026-02-16', [], true),
    jour('2026-02-17', [poste({ date: '2026-02-17', heureDebut: '14:00', heureFin: '18:00' })]),
  ];

  it('draws one row per day, rest days included', () => {
    const lignes = lignesFrise(jours, axeFrise(jours), '2026-02-17', new Set());

    expect(lignes.map((ligne) => ligne.date)).toEqual(['2026-02-15', '2026-02-16', '2026-02-17']);
    expect(lignes[1].repos).toBe(true);
    expect(lignes[1].barres).toEqual([]);
    expect(lignes[2].aujourdhui).toBe(true);
  });

  it('places a bar on the axis in percentages of its width', () => {
    const axe = { debut: 10 * 60, fin: 18 * 60, graduations: [] };
    const [ligne] = lignesFrise([jours[0]], axe, null, new Set());

    expect(ligne.barres[0].gauchePct).toBe(0);
    expect(ligne.barres[0].largeurPct).toBe(25);
    expect(ligne.heures).toBe(2);
  });

  /** A shift shorter than a pixel still happened. */
  it('never draws a bar of no width', () => {
    const axe = { debut: 8 * 60, fin: 23 * 60, graduations: [] };
    const court = jour('2026-02-15', [poste({ heureDebut: '10:00', heureFin: '10:05' })]);

    expect(lignesFrise([court], axe, null, new Set())[0].barres[0].largeurPct).toBeGreaterThan(0);
  });

  it('marks the days a consigne governs, and the weeks', () => {
    const lignes = lignesFrise(jours, axeFrise(jours), null, new Set(['2026-02-16']));

    expect(lignes[1].consigne).toBe(true);
    // Monday 16 February opens a week, and the first row separates nothing.
    expect(lignes[0].debutDeSemaine).toBe(false);
    expect(lignes[1].debutDeSemaine).toBe(true);
  });

  it('carries the typologie of the seat, never a guess on the stand name', () => {
    const [ligne] = lignesFrise([jours[0]], axeFrise(jours), null, new Set());

    expect(ligne.barres[0].typologieId).toBe('CONSTRUCTION');
    expect(ligne.barres[0].typologieLibelle).toBe('Jeux de construction');
  });
});

describe('statsPlanning', () => {
  it('counts the worked days, their seats, their stands and their hours', () => {
    const stats = statsPlanning([
      jour('2026-02-15', [
        poste({ heureDebut: '10:00', heureFin: '12:00' }),
        poste({ standId: 'stand-2', heureDebut: '14:00', heureFin: '17:00' }),
      ]),
      jour('2026-02-16', [], true),
      jour('2026-02-17', [poste({ heureDebut: '09:00', heureFin: '12:00' })]),
    ]);

    expect(stats).toEqual({ heures: 8, creneaux: 3, stands: 2, jours: 2 });
  });
});

describe('heuresDuJour', () => {
  it('counts a night shift on the day that opens it', () => {
    expect(
      heuresDuJour(jour('2026-02-15', [poste({ heureDebut: '22:00', heureFin: '02:00' })])),
    ).toBe(4);
  });
});

describe('legendeTypologies', () => {
  it('names each typologie once, in label order', () => {
    const legende = legendeTypologies([
      jour('2026-02-15', [
        poste({ typologieId: 'STRATEGIE', typologieLibelle: 'Stratégie' }),
        poste({ typologieId: 'AMBIANCE', typologieLibelle: 'Ambiance' }),
        poste({ typologieId: 'STRATEGIE', typologieLibelle: 'Stratégie' }),
      ]),
      jour('2026-02-16', [poste({ typologieId: null, typologieLibelle: null })]),
    ]);

    expect(legende).toEqual([
      { id: 'AMBIANCE', libelle: 'Ambiance' },
      { id: 'STRATEGIE', libelle: 'Stratégie' },
    ]);
  });
});
