import { describe, expect, it } from 'vitest';
import { actionsOfRule, compterProblemes, construireProblemes, causeLinks } from './problemes';
import type {
  CauseInfaisabilite,
  ConstraintView,
  ContributionAdHoc,
  FeasibilityReport,
} from './models';

function cause(overrides: Partial<CauseInfaisabilite> = {}): CauseInfaisabilite {
  return {
    type: 'CRENEAU_SOUS_EFFECTIF',
    severite: 'ELEVE',
    message: 'Il manque 2 animateurs.',
    creneauId: '12',
    date: '2026-08-01',
    heureDebut: '12:30',
    heureFin: '15:30',
    standIds: ['tir-a-l-arc'],
    contrainteIds: [],
    demande: 6,
    capacite: 4,
    manque: 2,
    ...overrides,
  };
}

function report(
  causes: CauseInfaisabilite[],
  overrides: Partial<FeasibilityReport> = {},
): FeasibilityReport {
  return {
    feasible: causes.length === 0,
    manqueAnimateurs: causes.reduce((max, current) => Math.max(max, current.manque), 0),
    causes,
    totalCauses: causes.length,
    causesCritiques: 0,
    causesElevees: 0,
    message: 'Planning non réalisable en l’état.',
    ...overrides,
  };
}

function contrainte(overrides: Partial<ConstraintView> = {}): ConstraintView {
  return {
    name: 'dureeHebdomadaireMax',
    niveau: 'HARD',
    categorie: 'Légal',
    description: 'Durée hebdomadaire maximale.',
    actif: true,
    protegee: true,
    legale: true,
    dosable: false,
    poids: 1,
    score: '-2hard/0medium/0soft',
    matchCount: 2,
    violations: ['Alice : 52 h semaine 2026-W28'],
    postesEvalues: null,
    plancher: null,
    references: [],
    ...overrides,
  };
}

function contribution(overrides: Partial<ContributionAdHoc> = {}): ContributionAdHoc {
  return {
    contrainteId: 'C1',
    type: 'AFFECTATION_FORCEE',
    raison: 'Formation',
    violations: 1,
    contraintes: ['affectationForcee'],
    ...overrides,
  };
}

describe('construireProblemes', () => {
  it('returns nothing when there is no report and no constraint', () => {
    expect(construireProblemes(null)).toEqual([]);
  });

  it('returns nothing for a feasible report with no matched constraint', () => {
    expect(
      construireProblemes(report([]), [contrainte({ matchCount: 0, violations: [] })]),
    ).toEqual([]);
  });

  it('maps a CRITIQUE cause to BLOQUANT and an ELEVE one to AVERTISSEMENT', () => {
    const problemes = construireProblemes(
      report([cause({ severite: 'CRITIQUE' }), cause({ severite: 'ELEVE' })]),
    );
    expect(problemes.map((probleme) => probleme.niveau)).toEqual(['BLOQUANT', 'AVERTISSEMENT']);
    expect(problemes.every((probleme) => probleme.source === 'FAISABILITE')).toBe(true);
  });

  it('maps constraint levels HARD/MEDIUM/SOFT to BLOQUANT/AVERTISSEMENT/MINEUR', () => {
    const problemes = construireProblemes(null, [
      contrainte({ name: 'soft', niveau: 'SOFT', violations: [] }),
      contrainte({ name: 'medium', niveau: 'MEDIUM', violations: [] }),
      contrainte({ name: 'hard', niveau: 'HARD' }),
    ]);
    expect(problemes.map((probleme) => probleme.titre)).toEqual(['hard', 'medium', 'soft']);
    expect(problemes.map((probleme) => probleme.niveau)).toEqual([
      'BLOQUANT',
      'AVERTISSEMENT',
      'MINEUR',
    ]);
  });

  it('drops constraints without any match', () => {
    const problemes = construireProblemes(null, [
      contrainte({ name: 'satisfaite', matchCount: 0, violations: [] }),
      contrainte({ name: 'jamais analysée', matchCount: null, violations: [] }),
    ]);
    expect(problemes).toEqual([]);
  });

  it('sorts by severity, feasibility causes before constraints inside a tier', () => {
    const problemes = construireProblemes(
      report([cause({ severite: 'CRITIQUE', message: 'critique' })]),
      [
        contrainte({ name: 'soft', niveau: 'SOFT', violations: [] }),
        contrainte({ name: 'hard', niveau: 'HARD' }),
        contrainte({ name: 'medium', niveau: 'MEDIUM', violations: [] }),
      ],
    );
    expect(problemes.map((probleme) => [probleme.niveau, probleme.source])).toEqual([
      ['BLOQUANT', 'FAISABILITE'],
      ['BLOQUANT', 'CONTRAINTE'],
      ['AVERTISSEMENT', 'CONTRAINTE'],
      ['MINEUR', 'CONTRAINTE'],
    ]);
  });

  it('adds a warning for the relay-less breaks, after the constraints of its tier, linking to the breaks screen', () => {
    const pauses = {
      journeesAnalysees: 2,
      pausesDues: 2,
      relaisManquants: 1,
      coupuresRepasDues: 0,
      coupuresRepasManquantes: 0,
      message: '',
      journees: [
        {
          animateurId: 'alice',
          nomComplet: 'Alice Martin',
          mineur: false,
          date: '2026-07-10',
          jour: 3,
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
                  standId: 'JEUX',
                  standNom: 'Village des jeux',
                  relais: [],
                  relaisDisponible: false,
                  simultanee: false,
                },
                {
                  debut: '18:40:00',
                  fin: '19:00:00',
                  heureLimite: '19:00:00',
                  dureeMinutes: 20,
                  standId: 'JEUX',
                  standNom: 'Village des jeux',
                  relais: [{ animateurId: 'b', nomComplet: 'B' }],
                  relaisDisponible: true,
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

    const problemes = construireProblemes(null, [], [], pauses);

    expect(problemes).toHaveLength(1);
    expect(problemes[0].niveau).toBe('AVERTISSEMENT');
    expect(problemes[0].source).toBe('PAUSES');
    expect(problemes[0].details).toEqual([
      '2026-07-10 · Alice Martin · 19:00 – 19:20 · Village des jeux',
    ]);
    expect(problemes[0].liens).toEqual([
      { route: '/journee', queryParams: { vue: 'pauses' }, libelle: 'Voir les pauses' },
    ]);
    expect(construireProblemes(null, [], [], { ...pauses, relaisManquants: 0 })).toEqual([]);
    expect(construireProblemes(null, [], [], null)).toEqual([]);
  });

  it('keeps the server ranking of two causes of the same severity', () => {
    const problemes = construireProblemes(
      report([
        cause({ severite: 'ELEVE', message: 'premier' }),
        cause({ severite: 'ELEVE', message: 'second' }),
      ]),
    );
    expect(problemes.map((probleme) => probleme.message)).toEqual(['premier', 'second']);
  });

  it('details a créneau cause with its slot, stands and shortfall', () => {
    const [probleme] = construireProblemes(report([cause({ standIds: ['tir', 'quilles'] })]));
    expect(probleme.details[0]).toContain('12');
    expect(probleme.details[0]).toContain('2026-08-01');
    expect(probleme.details[0]).toContain('12:30');
    expect(probleme.details[1]).toContain('tir, quilles');
    expect(probleme.details[2]).toContain('2');
  });

  it('names the stands of a cause by their name, an unknown id kept as-is', () => {
    const noms = new Map([['S1', 'Tir à l’arc']]);
    const [probleme] = construireProblemes(
      report([cause({ standIds: ['S1', 'S9'] })]),
      [],
      [],
      null,
      noms,
    );
    expect(probleme.details[1]).toContain('Tir à l’arc, S9');
    const stands = probleme.liens.filter((lien) => lien.route === '/stands');
    expect(stands.map((lien) => [lien.libelle, lien.queryParams?.['edit']])).toEqual([
      ['Voir le stand Tir à l’arc', 'S1'],
      ['Voir le stand S9', 'S9'],
    ]);
  });

  it('opens the créneau a cause names, then each stand up to three, then the openings', () => {
    const liens = causeLinks(cause({ creneauId: '12', standIds: ['a', 'b', 'c', 'd'] }));
    expect(liens.map((lien) => [lien.route, lien.queryParams?.['edit']])).toEqual([
      ['/creneaux', '12'],
      ['/stands', 'a'],
      ['/stands', 'b'],
      ['/stands', 'c'],
      ['/ouvertures', undefined],
      ['/diagnostic', undefined],
    ]);
  });

  // The deadlock has two halves and the cause only names the exception.
  it('sends a locked-schedule cause to the locks as well as to the adjustments', () => {
    const liens = causeLinks(
      cause({
        type: 'AFFECTATION_FORCEE_SIEGE_VERROUILLE',
        severite: 'CRITIQUE',
        creneauId: null,
        standIds: [],
        contrainteIds: ['C01'],
        demande: 0,
        capacite: 0,
        manque: 0,
      }),
    );
    expect(liens.map((lien) => lien.route)).toEqual(['/ad-hoc-constraints', '/verrouillages']);
  });

  // Every other cause keeps the adjustments link alone.
  it('does not offer the locks on a day-off cause', () => {
    const liens = causeLinks(
      cause({
        type: 'AFFECTATION_FORCEE_JOUR_INDISPONIBLE',
        severite: 'CRITIQUE',
        creneauId: null,
        standIds: [],
        contrainteIds: ['C01'],
        demande: 0,
        capacite: 0,
        manque: 0,
      }),
    );
    expect(liens.map((lien) => lien.route)).toEqual(['/ad-hoc-constraints']);
  });

  it('links each match of a HARD rule to the fiches it names, in place of the bare lines', () => {
    const [probleme] = construireProblemes(null, [
      contrainte({
        violations: ['Alice : 52 h'],
        references: [{ texte: 'Alice : 52 h', animateurId: 'alice', standId: null, creneauId: 7 }],
      }),
    ]);
    // The sentence now lives with its links, not a second time among the details.
    expect(probleme.details).toEqual([]);
    expect(probleme.references.map((reference) => reference.texte)).toEqual(['Alice : 52 h']);
    expect(
      probleme.references[0].liens.map((lien) => [lien.route, lien.queryParams?.['edit']]),
    ).toEqual([
      ['/animateurs', 'alice'],
      ['/creneaux', '7'],
    ]);
  });

  it('falls back on the bare lines when an older analysis carries no reference', () => {
    const [probleme] = construireProblemes(null, [
      contrainte({ violations: ['Alice : 52 h'], references: [] }),
    ]);
    expect(probleme.details).toEqual(['Alice : 52 h']);
    expect(probleme.references).toEqual([]);
  });

  it('lists the violation lines of a HARD constraint, and the match count otherwise', () => {
    const [dur, moyen] = construireProblemes(null, [
      contrainte({ violations: ['Alice : 52 h', 'Bob : 51 h'] }),
      contrainte({ name: 'équité', niveau: 'MEDIUM', matchCount: 7, violations: [] }),
    ]);
    expect(dur.details).toEqual(['Alice : 52 h', 'Bob : 51 h']);
    expect(moyen.details[0]).toContain('7');
  });

  it('titles a contradiction between ad hoc constraints and names them', () => {
    const [probleme] = construireProblemes(
      report([
        cause({
          type: 'CONTRAINTES_AD_HOC_CONTRADICTOIRES',
          severite: 'CRITIQUE',
          message: 'Les contraintes C1 et C2 se contredisent.',
          creneauId: null,
          standIds: [],
          contrainteIds: ['C1', 'C2'],
          demande: 0,
          capacite: 0,
          manque: 0,
        }),
      ]),
    );

    expect(probleme.niveau).toBe('BLOQUANT');
    expect(probleme.titre).not.toContain('sous-effectif');
    expect(probleme.details.join(' ')).toContain('C1, C2');
    expect(probleme.liens.map((lien) => lien.route)).toEqual(['/ad-hoc-constraints']);
  });

  it('attributes an ad hoc rule violations to the exceptions that caused them', () => {
    const [probleme] = construireProblemes(
      null,
      [contrainte({ name: 'affectationForcee', violations: ['P1'] })],
      [
        contribution({ contrainteId: 'C1', violations: 3 }),
        contribution({ contrainteId: 'C2', violations: 1 }),
      ],
    );

    expect(probleme.details[0]).toContain('C1 (3)');
    expect(probleme.details[0]).toContain('C2 (1)');
    // The per-match lines stay: the attribution is prepended, not a replacement.
    expect(probleme.details).toContain('P1');
    expect(probleme.liens.map((lien) => lien.route)).toEqual([
      '/constraints',
      '/ad-hoc-constraints',
    ]);
  });

  it('leaves a rule no ad hoc exception contributed to untouched', () => {
    const [probleme] = construireProblemes(
      null,
      [contrainte({ name: 'dureeHebdomadaireMax' })],
      [contribution({ contrainteId: 'C1', contraintes: ['affectationForcee'] })],
    );

    expect(probleme.details).toEqual(['Alice : 52 h semaine 2026-W28']);
    expect(probleme.liens.map((lien) => lien.route)).toEqual(['/constraints']);
  });

  it('gives every problem a distinct track key', () => {
    const problemes = construireProblemes(report([cause(), cause()]), [contrainte()]);
    expect(new Set(problemes.map((probleme) => probleme.id)).size).toBe(problemes.length);
  });
});

describe('compterProblemes', () => {
  it('counts zero on an empty list', () => {
    expect(compterProblemes([])).toEqual({ bloquants: 0, avertissements: 0, mineurs: 0, total: 0 });
  });

  it('breaks the total down by severity', () => {
    const problemes = construireProblemes(
      report([cause({ severite: 'CRITIQUE' }), cause({ severite: 'ELEVE' })]),
      [contrainte(), contrainte({ name: 'confort', niveau: 'SOFT', violations: [] })],
    );
    expect(compterProblemes(problemes)).toEqual({
      bloquants: 2,
      avertissements: 1,
      mineurs: 1,
      total: 4,
    });
  });
});

describe('actionsOfRule', () => {
  const banc = {
    code: 'VOIR_BANC',
    libelle: 'Voir qui pourrait venir',
    explication: 'La remédiation de la règle.',
    route: '/diagnostic',
    parametres: { onglet: 'banc' },
  };

  it("keeps the server's positioning on the rule's first breach as sent", () => {
    const reparation = {
      code: 'PROPOSER_REPARATION',
      libelle: 'Proposer une réparation',
      explication: 'Sur la Journée…',
      route: '/journee',
      parametres: { date: '2026-07-12', stand: 'S1' },
    };
    const actions = actionsOfRule(
      contrainte({
        actions: [{ ...banc, parametres: { onglet: 'banc', creneau: '12' } }, reparation],
        references: [{ texte: 'avec créneau', animateurId: null, standId: 'S1', creneauId: 12 }],
      }),
      [],
    );

    expect(actions[0].queryParams).toEqual({ onglet: 'banc', creneau: '12' });
    expect(actions[0].explication).toBe('La remédiation de la règle.');
    expect(actions[1]).toMatchObject({
      route: '/journee',
      queryParams: { date: '2026-07-12', stand: 'S1' },
    });
  });

  it('leaves an aggregate rule bare, and adds the review of the exceptions it failed on', () => {
    const actions = actionsOfRule(contrainte({ actions: [banc], references: [] }), [
      contribution({ contrainteId: 'C1' }),
      contribution({ contrainteId: 'C2' }),
    ]);

    expect(actions[0].queryParams).toEqual({ onglet: 'banc' });
    expect(actions.at(-1)).toMatchObject({
      code: 'REVOIR_AJUSTEMENTS',
      route: '/ad-hoc-constraints',
      queryParams: { ids: 'C1,C2' },
    });
  });
});
