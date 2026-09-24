// Property-based solve testing: random (but reproducible) referentials are
// generated, solved for real, and the persisted planning is checked against
// the invariants the hard rules promise — whatever the data looked like.
//
// The seed is printed on every run; re-run a failure identically with
//   E2E_FUZZ_SEED=<seed> npm run e2e -- e2e/solveur-fuzz.spec.ts

import { APIRequestContext, expect, test } from '@playwright/test';
import {
  AnimateurSeed,
  CreneauSeed,
  PlanningPersiste,
  StandSeed,
  contexteAdmin,
  decaler,
  lancerSolve,
  planningPersiste,
  seedReferentielSolveur,
} from './support';
import { repartirDeLaReference } from './reference';

/** Deterministic PRNG (mulberry32), so a printed seed replays the exact run. */
function mulberry32(seed: number): () => number {
  let etat = seed >>> 0;
  return () => {
    etat = (etat + 0x6d2b79f5) >>> 0;
    let t = etat;
    t = Math.imul(t ^ (t >>> 15), t | 1);
    t ^= t + Math.imul(t ^ (t >>> 7), t | 61);
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}

interface Genere {
  animateurs: AnimateurSeed[];
  stands: StandSeed[];
  creneaux: CreneauSeed[];
  adultes: string[];
  mineurs: string[];
  indispoForcee: { animateurId: string; creneauId: number };
  incompatibles: [string, string];
  affectationForcee: { animateurId: string; standId: string; creneauId: number };
}

/**
 * A random, feasible-by-construction problem: plenty of adults for the seats,
 * daytime créneaux only, and the three random ad hoc constraints picked so
 * they can never contradict each other or an unavailability.
 */
function genererProbleme(alea: () => number, iteration: number): Genere {
  const entier = (max: number) => Math.floor(alea() * max);
  const jours = ['2026-07-15', '2026-07-16'].map(decaler);
  const base = 987400 + iteration * 100;

  const creneaux: CreneauSeed[] = jours.flatMap((date, indexJour) => [
    { id: base + indexJour * 10, date, debut: '10:00', fin: '12:00' },
    { id: base + indexJour * 10 + 1, date, debut: '14:00', fin: '16:00' },
  ]);

  const stands: StandSeed[] = [
    { id: 'FUZZ-S1', nom: 'Stand Fuzz un', effectif: 1 + entier(2) },
    { id: 'FUZZ-S2', nom: 'Stand Fuzz deux', effectif: 1 + entier(2) },
    { id: 'FUZZ-S3', nom: 'Stand Fuzz majeurs', effectif: 1, reserveMajeurs: true },
  ];

  // 14 adults: the worst roll (10 seats a day, 3 adults on a personal off
  // day, hard same-day break rule limiting everyone to one créneau per day)
  // still leaves enough people for a zero-hard plan, whatever the graine.
  const adultes = Array.from({ length: 14 }, (ignore, index) => `FUZZ-A${index}`);
  const mineurs = ['FUZZ-M0', 'FUZZ-M1'];
  const animateurs: AnimateurSeed[] = [
    ...adultes.map((id, index) => ({
      id,
      prenom: `Adulte${index}`,
      nom: 'Fuzz',
      dateNaissance: `${1980 + entier(20)}-06-15`,
      // A few random personal off days, never enough to starve the seats.
      joursIndisponibles: index < 3 && alea() < 0.6 ? [jours[entier(jours.length)]] : [],
    })),
    ...mineurs.map((id, index) => ({
      id,
      prenom: `Mineur${index}`,
      nom: 'Fuzz',
      // Sixteen on the seeded days, whichever year the suite runs in.
      dateNaissance: decaler('2010-06-15'),
      joursIndisponibles: [],
    })),
  ];

  // Constraint targets: three DISTINCT adults with no personal off day, so
  // the forced assignment can never collide with an unavailability, and the
  // pair stays satisfiable.
  const candidats = animateurs
    .filter(
      (animateur) => adultes.includes(animateur.id) && animateur.joursIndisponibles!.length === 0,
    )
    .map((animateur) => animateur.id);
  const indispoCible = candidats[entier(candidats.length)];
  let force = candidats[entier(candidats.length)];
  while (force === indispoCible) {
    force = candidats[entier(candidats.length)];
  }
  const restants = candidats.filter((id) => id !== indispoCible && id !== force);
  const incompatibles: [string, string] = [restants[0], restants[1]];

  const indispoCreneau = creneaux[entier(creneaux.length)].id;
  const forceCreneau = creneaux[entier(creneaux.length)].id;
  const forceStand = stands[entier(stands.length)].id;

  return {
    animateurs,
    stands,
    creneaux,
    adultes,
    mineurs,
    indispoForcee: { animateurId: indispoCible, creneauId: indispoCreneau },
    incompatibles,
    affectationForcee: { animateurId: force, standId: forceStand, creneauId: forceCreneau },
  };
}

/** Every structural promise the hard rules make, checked on the raw planning. */
function verifierInvariants(planning: PlanningPersiste, probleme: Genere): void {
  const postesFuzz = planning.postes.filter((poste) => poste.stand?.id.startsWith('FUZZ-'));
  const sieges =
    probleme.stands.reduce((somme, stand) => somme + stand.effectif, 0) * probleme.creneaux.length;
  expect(postesFuzz, 'un poste par siège requis').toHaveLength(sieges);
  expect(
    postesFuzz.every((poste) => poste.animateur !== null),
    'tous les postes pourvus',
  ).toBe(true);

  const indisposParAnimateur = new Map(
    probleme.animateurs.map((animateur) => [
      animateur.id,
      new Set(animateur.joursIndisponibles ?? []),
    ]),
  );
  const parCreneau = new Map<number, string[]>();
  for (const poste of postesFuzz) {
    const animateurId = poste.animateur!.id;
    const creneauId = poste.creneau!.id;
    parCreneau.set(creneauId, [...(parCreneau.get(creneauId) ?? []), animateurId]);

    // Personal unavailability (opt-out availability).
    expect(
      indisposParAnimateur.get(animateurId)?.has(poste.creneau!.date ?? ''),
      `${animateurId} affecté un jour déclaré indisponible`,
    ).toBe(false);
    // Reserved-to-adults stand never hosts a minor.
    if (poste.stand!.id === 'FUZZ-S3') {
      expect(
        probleme.mineurs,
        `mineur ${animateurId} sur le stand réservé aux majeurs`,
      ).not.toContain(animateurId);
    }
  }
  for (const [creneauId, occupants] of parCreneau) {
    // One seat per animateur per créneau.
    expect(new Set(occupants).size, `chevauchement sur le créneau ${creneauId}`).toBe(
      occupants.length,
    );
    // Ad hoc: forced unavailability and incompatibility.
    if (creneauId === probleme.indispoForcee.creneauId) {
      expect(occupants, 'indisponibilité forcée violée').not.toContain(
        probleme.indispoForcee.animateurId,
      );
    }
    const [gauche, droite] = probleme.incompatibles;
    expect(
      occupants.includes(gauche) && occupants.includes(droite),
      `incompatibles réunis sur le créneau ${creneauId}`,
    ).toBe(false);
  }
  // Ad hoc: forced assignment honoured on the exact seat.
  const siegeForce = postesFuzz.find(
    (poste) =>
      poste.stand!.id === probleme.affectationForcee.standId &&
      poste.creneau!.id === probleme.affectationForcee.creneauId &&
      poste.animateur!.id === probleme.affectationForcee.animateurId,
  );
  expect(siegeForce, 'affectation forcée absente du planning').toBeTruthy();
}

const graine = process.env['E2E_FUZZ_SEED']
  ? Number(process.env['E2E_FUZZ_SEED'])
  : Math.floor(Math.random() * 2 ** 31);

let admin: APIRequestContext;

test.beforeAll(async ({ playwright }, testInfo) => {
  admin = await contexteAdmin(playwright, testInfo.project.use.baseURL as string);
  await repartirDeLaReference(admin);
});

test.afterAll(async () => {
  await admin.dispose();
});

for (const iteration of [0, 1]) {
  // The graine stays OUT of the title: a random title would differ between the
  // runner and the worker process ("Test not found in the worker process").
  test(`fuzz #${iteration} : les règles dures tiennent sur un problème aléatoire`, async ({}, testInfo) => {
    test.slow();
    testInfo.annotations.push({ type: 'graine', description: String(graine) });
    console.log(`solveur-fuzz : graine ${graine}, itération ${iteration}`);

    const alea = mulberry32(graine + iteration);
    const probleme = genererProbleme(alea, iteration);
    await seedReferentielSolveur(admin, probleme.animateurs, probleme.stands, probleme.creneaux);

    for (const contrainte of [
      {
        id: `FUZZ-ADHOC-INDISPO-${iteration}`,
        type: 'INDISPONIBILITE_FORCEE',
        animateursConcernes: [{ id: probleme.indispoForcee.animateurId }],
        creneau: { id: probleme.indispoForcee.creneauId },
        stand: null,
        raison: `Fuzz ${graine}/${iteration} : indisponibilité forcée`,
      },
      {
        id: `FUZZ-ADHOC-FORCEE-${iteration}`,
        type: 'AFFECTATION_FORCEE',
        animateursConcernes: [{ id: probleme.affectationForcee.animateurId }],
        creneau: { id: probleme.affectationForcee.creneauId },
        stand: { id: probleme.affectationForcee.standId },
        raison: `Fuzz ${graine}/${iteration} : affectation forcée`,
      },
      {
        id: `FUZZ-ADHOC-INCOMPAT-${iteration}`,
        type: 'INCOMPATIBILITE',
        animateursConcernes: probleme.incompatibles.map((id) => ({ id })),
        creneau: null,
        stand: null,
        raison: `Fuzz ${graine}/${iteration} : incompatibilité`,
      },
    ]) {
      const reponse = await admin.post('/api/contraintes-ad-hoc', { data: contrainte });
      expect(reponse.ok(), await reponse.text()).toBe(true);
    }

    const job = await lancerSolve(admin, 8);
    expect(
      job.result?.diagnostic.hardScore,
      `problème infaisable (graine ${graine}, itération ${iteration})`,
    ).toBe(0);
    verifierInvariants(await planningPersiste(admin), probleme);
  });
}
