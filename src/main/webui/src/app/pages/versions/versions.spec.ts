import { describe, expect, it } from 'vitest';
import { KpiHistoriqueEntry, PlanningKpi, PlanSnapshot } from '../../core/models';
import {
  CURRENT_PLAN,
  comparisonSides,
  eventLabel,
  kpiSentence,
  lastPublishedId,
  readComparison,
  versionRows,
} from './versions';

function kpi(overrides: Partial<PlanningKpi> = {}): PlanningKpi {
  return {
    score: '0hard/-12medium/-40soft',
    scoreHard: 0,
    scoreMedium: -12,
    scoreSoft: -40,
    postesTotal: 200,
    postesPourvus: 190,
    animateursAffectes: 120,
    standsDistincts: 40,
    creneauxDistincts: 60,
    heuresTotal: 1200,
    heuresMoyenne: 10,
    heuresEcartType: 2,
    heuresMin: 4,
    heuresMax: 18,
    heuresIncompletes: false,
    modificationsManuelles: 0,
    tauxModificationsManuelles: 0,
    dureeSolveSecondes: 600,
    violationsParContrainte: {},
    scoreMediumHorsPlancher: null,
    plancherMedium: null,
    journeesSousConsigne: null,
    heuresFermeesParConsigne: null,
    ...overrides,
  };
}

function entry(id: number, creeLe: string, editionId = 'E1'): KpiHistoriqueEntry {
  return { id, editionId, editionNom: null, kpi: kpi(), creeLe };
}

function snapshot(id: number, creeLe: string | null, overrides: Partial<PlanSnapshot> = {}) {
  return {
    id,
    libelle: `Instantané ${id}`,
    automatique: false,
    score: null,
    nombreAffectations: 190,
    creeLe,
    editionId: 'E1',
    editionNom: null,
    kpi: null,
    referenceModifieLe: null,
    perime: false,
    publieLe: null,
    ...overrides,
  } satisfies PlanSnapshot;
}

describe('versionRows', () => {
  it('merges the solves and the snapshots of the edition, newest first', () => {
    const rows = versionRows(
      [
        entry(1, '2026-08-01T10:00:00Z'),
        entry(2, '2026-08-02T10:00:00Z'),
        entry(3, '2026-08-03T10:00:00Z', 'E2'),
      ],
      [snapshot(7, '2026-08-01T12:00:00Z'), snapshot(8, null)],
      'E1',
    );

    expect(rows.map((row) => row.key)).toEqual(['r2', 's7', 'r1', 's8']);
  });

  it('puts the solve before a snapshot written in the same instant', () => {
    const rows = versionRows(
      [entry(1, '2026-08-01T10:00:00Z')],
      [snapshot(7, '2026-08-01T10:00:00Z')],
      null,
    );

    expect(rows.map((row) => row.key)).toEqual(['r1', 's7']);
  });
});

describe('kpiSentence', () => {
  it('reads the server’s verdict when the plan was read, and the seats staffed', () => {
    const sentence = kpiSentence(
      kpi({ lecture: [{ sujet: 'VERDICT', niveau: 'OK', texte: 'Tout est tenu.', liens: [] }] }),
    );

    expect(sentence).toContain('Tout est tenu.');
    expect(sentence).toMatch(/190 places pourvues sur 200\./);
    expect(sentence).not.toMatch(/hard|medium|soft/);
  });

  it('words the hard score when no reading was stored', () => {
    expect(kpiSentence(kpi())).toContain('Règles obligatoires tenues.');
    expect(kpiSentence(kpi({ scoreHard: -3 }))).toContain(
      'Des règles obligatoires ne sont pas tenues.',
    );
    expect(kpiSentence(null)).toBe('');
  });
});

describe('eventLabel', () => {
  it('names a solve by its duration, and a snapshot by its label', () => {
    const [solve, capture] = versionRows(
      [entry(1, '2026-08-02T10:00:00Z')],
      [snapshot(7, '2026-08-01T10:00:00Z', { libelle: 'Avant canicule' })],
      null,
    );

    expect(eventLabel(solve)).toBe('Résolution terminée en 10 min');
    expect(eventLabel(capture)).toBe('Avant canicule');
  });
});

describe('the comparison', () => {
  const snapshots = [snapshot(7, '2026-08-01T10:00:00Z'), snapshot(8, '2026-08-02T10:00:00Z')];

  it('takes the older side as the reference, the plan in place being the newest', () => {
    expect(comparisonSides(['8', '7'], snapshots)).toEqual({ base: '7', variante: '8' });
    expect(comparisonSides([CURRENT_PLAN, '8'], snapshots)).toEqual({
      base: '8',
      variante: CURRENT_PLAN,
    });
    expect(comparisonSides(['8'], snapshots)).toBeNull();
  });

  it('reads `?comparer=` as two distinct selectors, or nothing', () => {
    expect(readComparison('7,courant')).toEqual(['7', 'courant']);
    expect(readComparison('7,7')).toEqual([]);
    expect(readComparison('7')).toEqual([]);
    expect(readComparison('7,abc')).toEqual([]);
    expect(readComparison(null)).toEqual([]);
  });
});

describe('lastPublishedId', () => {
  it('names the last publication, the id breaking a tie, and nothing without one', () => {
    expect(
      lastPublishedId([
        snapshot(7, null, { publieLe: '2026-08-01T10:00:00.5Z' }),
        snapshot(8, null, { publieLe: '2026-08-01T10:00:00Z' }),
        snapshot(9, null, { publieLe: '2026-08-01T10:00:00.5Z' }),
      ]),
    ).toBe(9);
    expect(lastPublishedId([snapshot(7, null)])).toBeNull();
  });
});
