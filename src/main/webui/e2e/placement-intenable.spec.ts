// Un placement posé à la main qu'aucune résolution ne pourra tenir (ADR 0046),
// vu de bout en bout : l'exception est **écrite** et dite, la faisabilité la
// reprend avant tout calcul, le bouton Calculer demande confirmation, et le
// seul geste qui n'attend plus rien — écrire un siège à la main — est refusé.
//
// Ce que cette suite prouve et qu'aucun test unitaire ne peut prouver : que le
// point d'entrée lit bien le référentiel enregistré, que l'avertissement
// voyage dans le corps du succès au lieu de devenir un refus déguisé, et que
// les écrans le montrent. Les règles elles-mêmes sont tenues ailleurs
// (ForcedAssignmentOn*Test, ReparationHardRulesTest, VerrouillageSurViolationTest).
//
// Le référentiel est construit ici plutôt que repris d'un scénario livré : il
// faut un **mineur**, une **nuit** et un **stand réservé aux majeurs**, et
// aucun des scénarios du dépôt n'en porte.
//
// Deux choses restent délibérément hors de cette suite :
// - le passé figé : les fixtures sont datées dans l'avenir (`shiftDate`), donc
//   aucun de leurs créneaux n'est commencé ; son silence sur une exception
//   passée est tenu par les trois ForcedAssignmentOn*Test ;
// - la phrase du verrou posé sur une violation dure, qui se lit dans la
//   dernière analyse : elle demanderait une résolution dont l'écart soit
//   certain, là où VerrouillageSurViolationTest la couvre en sept cas. Ce qui
//   est vérifié ici du verrou, c'est la forme de sa réponse — la rupture de
//   contrat que cette PR introduit.
//
// Le contre-test de la confirmation — un sous-effectif ne demande rien —
// reste dans solver-page.spec.ts : le prouver ici lancerait une vraie
// résolution pour observer qu'aucune fenêtre ne s'ouvre.

import { APIRequestContext, expect, test } from '@playwright/test';
import {
  AnimateurSeed,
  CreneauSeed,
  StandSeed,
  contexteAdmin,
  shiftDate,
  dialogueOuvert,
  pageAdmin,
  seedReferentielSolveur,
} from './support';
import { repartirDeLaReference } from './reference';

/** Quatorze ans sur les dates jouées : la nuit commence à 20 h pour lui. */
const MINEUR = 'E2E-INTEN-MINEUR';
const MAJEUR = 'E2E-INTEN-MAJEUR';
/** Majeur, mais indisponible le jour de la nuit : la lecture « jour off » lui est réservée. */
const ABSENT = 'E2E-INTEN-ABSENT';

const STAND_TOUS = 'E2E-INTEN-S1';
const STAND_MAJEURS = 'E2E-INTEN-S2';

const NUIT = 987401;
const JOURNEE = 987402;
const JOUR_NUIT = shiftDate('2026-07-20');
const JOUR_JOURNEE = shiftDate('2026-07-21');

/** Le siège de nuit, écrit en base : c'est lui que l'écriture directe vise. */
const SIEGE_NUIT = 'E2E-INTEN-POSTE-NUIT';

const ANIMATEURS: AnimateurSeed[] = [
  { id: MINEUR, prenom: 'Mina', nom: 'Intenable', dateNaissance: shiftDate('2012-01-01') },
  { id: MAJEUR, prenom: 'Marc', nom: 'Intenable', dateNaissance: '1990-01-01' },
  {
    id: ABSENT,
    prenom: 'Alix',
    nom: 'Intenable',
    dateNaissance: '1991-02-02',
    joursIndisponibles: [JOUR_NUIT],
  },
];
const STANDS: StandSeed[] = [
  { id: STAND_TOUS, nom: 'Stand ouvert à tous', effectif: 1 },
  { id: STAND_MAJEURS, nom: 'Stand réservé aux majeurs', effectif: 1, reserveMajeurs: true },
];
const CRENEAUX: CreneauSeed[] = [
  { id: NUIT, date: JOUR_NUIT, debut: '20:00', fin: '23:00' },
  { id: JOURNEE, date: JOUR_JOURNEE, debut: '10:00', fin: '12:00' },
];

const EXCEPTION = 'E2E-INTEN-FORCEE';

interface Avertissement {
  type: string;
  message: string;
}

interface Cause {
  type: string;
  severite: string;
  message: string;
  contrainteIds: string[] | null;
}

let admin: APIRequestContext;

test.beforeAll(async ({ playwright }, testInfo) => {
  admin = await contexteAdmin(playwright, testInfo.project.use.baseURL as string);
  await repartirDeLaReference(admin);
  await seedReferentielSolveur(admin, ANIMATEURS, STANDS, CRENEAUX);
});

test.afterAll(async () => {
  await admin.dispose();
});

test.afterEach(async () => {
  await admin.post('/api/database/import', {
    headers: { 'Content-Type': 'text/plain' },
    data: [
      `delete from contrainte_animateur where contrainte_id like 'E2E-INTEN-%';`,
      `delete from contrainte_ad_hoc where id like 'E2E-INTEN-%';`,
      `delete from verrouillage_planning where animateur_id like 'E2E-INTEN-%' or stand_id like 'E2E-INTEN-%';`,
    ].join('\n'),
  });
});

/** Une affectation forcée, telle que l'écran et l'API l'écrivent toutes deux. */
function affectationForcee(options: {
  animateurs: string[];
  stand?: string | null;
  creneau?: number | null;
}) {
  return {
    id: EXCEPTION,
    type: 'AFFECTATION_FORCEE',
    animateursConcernes: options.animateurs.map((id) => ({ id })),
    creneau: options.creneau == null ? null : { id: options.creneau },
    stand: options.stand == null ? null : { id: options.stand },
    raison: 'E2E : placement imposé',
  };
}

/** Écrit l'exception et rend les avertissements du corps de succès. */
async function ecrire(contrainte: object): Promise<Avertissement[]> {
  const reponse = await admin.post('/api/contraintes-ad-hoc', { data: contrainte });
  expect(reponse.ok(), await reponse.text()).toBe(true);
  return ((await reponse.json()) as { avertissements: Avertissement[] }).avertissements;
}

async function causes(): Promise<Cause[]> {
  const reponse = await admin.get('/api/feasibility');
  expect(reponse.ok()).toBe(true);
  return ((await reponse.json()) as { causes: Cause[] }).causes;
}

test.describe('piste 1 — la raison légale', () => {
  test('un mineur forcé sur une nuit est écrit, dit, et repris avant le calcul', async ({
    browser,
  }) => {
    const avertissements = await ecrire(
      affectationForcee({ animateurs: [MINEUR], stand: STAND_TOUS, creneau: NUIT }),
    );

    const legal = avertissements.find((a) => a.type === 'AFFECTATION_FORCEE_MOTIF_LEGAL');
    expect(legal, JSON.stringify(avertissements)).toBeDefined();
    expect(legal?.message).toContain(EXCEPTION);
    expect(legal?.message).toContain('travailDeNuitInterditPourMineur');
    // La phrase est recopiée dans un journal que le navigateur garde après la
    // déconnexion : elle nomme l'exception, jamais la personne (docs/rgpd.md §7).
    expect(legal?.message).not.toContain(MINEUR);

    // Averti, jamais refusé : la ligne est bien là.
    const ecrites = (await (await admin.get('/api/contraintes-ad-hoc')).json()) as { id: string }[];
    expect(ecrites.map((c) => c.id)).toContain(EXCEPTION);

    // Et la faisabilité la reprend, sans qu'aucune résolution ait tourné.
    const bloquante = (await causes()).find((cause) => cause.contrainteIds?.includes(EXCEPTION));
    expect(bloquante, 'la faisabilité doit nommer l’exception').toBeDefined();
    expect(bloquante?.type).toBe('AFFECTATION_FORCEE_MOTIF_LEGAL');
    expect(bloquante?.severite).toBe('CRITIQUE');

    const page = await pageAdmin(browser, admin);
    await page.goto('/diagnostic');
    await expect(page.locator('#contenu')).toContainText(EXCEPTION);
    await page.context().close();
  });

  test('un stand réservé aux majeurs est le second motif que la lecture nomme', async () => {
    const avertissements = await ecrire(
      affectationForcee({ animateurs: [MINEUR], stand: STAND_MAJEURS, creneau: JOURNEE }),
    );

    const legal = avertissements.find((a) => a.type === 'AFFECTATION_FORCEE_MOTIF_LEGAL');
    expect(legal?.message, JSON.stringify(avertissements)).toContain('standReserveAuxMajeurs');
  });

  test('une seule place acceptable dans la portée suffit à tout taire', async () => {
    // Même personne, même stand, mais de jour : la place existe, donc rien à dire.
    const avertissements = await ecrire(
      affectationForcee({ animateurs: [MINEUR], stand: STAND_TOUS, creneau: JOURNEE }),
    );
    expect(avertissements.map((a) => a.type)).not.toContain('AFFECTATION_FORCEE_MOTIF_LEGAL');

    expect((await causes()).filter((cause) => cause.contrainteIds?.includes(EXCEPTION))).toEqual(
      [],
    );
  });

  test('le jour déclaré indisponible garde le cas pour lui', async () => {
    // Le jour off est aussi un motif d'exclusion dur : les deux lectures ne se
    // doublent jamais, et c'est celle-ci que l'organisateur sait traiter.
    const avertissements = await ecrire(
      affectationForcee({ animateurs: [ABSENT], stand: STAND_TOUS, creneau: NUIT }),
    );

    const types = avertissements.map((a) => a.type);
    expect(types, JSON.stringify(avertissements)).toContain('AFFECTATION_FORCEE_JOUR_INDISPONIBLE');
    expect(types).not.toContain('AFFECTATION_FORCEE_MOTIF_LEGAL');
  });
});

test.describe('piste 2 — l’emploi du temps verrouillé', () => {
  /** Pose un verrou et rend la réponse, dans sa forme `{ verrouillage, avertissements }`. */
  async function verrouiller(
    data: Record<string, unknown>,
  ): Promise<{ verrouillage: { id: string }; avertissements: Avertissement[] }> {
    const reponse = await admin.post('/api/verrouillages', { data });
    expect(reponse.ok(), await reponse.text()).toBe(true);
    return (await reponse.json()) as {
      verrouillage: { id: string };
      avertissements: Avertissement[];
    };
  }

  test('une affectation forcée sur un emploi du temps gelé est un blocage dit', async () => {
    const { verrouillage, avertissements } = await verrouiller({
      type: 'ANIMATEUR',
      animateurId: MAJEUR,
      raison: 'E2E : Marc validé',
    });
    // La rupture de contrat de cette PR : le verrou n'est plus rendu nu.
    expect(verrouillage.id, 'le POST doit rendre le verrou sous `verrouillage`').toBeTruthy();
    expect(Array.isArray(avertissements)).toBe(true);

    const surExceptions = await ecrire(
      affectationForcee({ animateurs: [MAJEUR], stand: STAND_TOUS, creneau: JOURNEE }),
    );
    const verrouille = surExceptions.find((a) => a.type === 'AFFECTATION_FORCEE_SIEGE_VERROUILLE');
    expect(verrouille, JSON.stringify(surExceptions)).toBeDefined();
    expect(verrouille?.message).toContain(EXCEPTION);
    expect(verrouille?.message).not.toContain(MAJEUR);

    const bloquante = (await causes()).find(
      (cause) =>
        cause.contrainteIds?.includes(EXCEPTION) &&
        cause.type === 'AFFECTATION_FORCEE_SIEGE_VERROUILLE',
    );
    expect(bloquante?.severite, 'la faisabilité doit reprendre le blocage').toBe('CRITIQUE');
  });

  test('un verrou de stand n’a jamais bloqué personne', async () => {
    // STAND, JOUR et CRENEAU n'épinglent que les places occupées : la place
    // vide que l'exception vise reste prenable.
    await verrouiller({ type: 'STAND', standId: STAND_TOUS, raison: 'E2E : stand figé' });

    const avertissements = await ecrire(
      affectationForcee({ animateurs: [MAJEUR], stand: STAND_TOUS, creneau: JOURNEE }),
    );
    expect(avertissements.map((a) => a.type)).not.toContain('AFFECTATION_FORCEE_SIEGE_VERROUILLE');
  });
});

test.describe('piste 3 — le siège écrit à la main', () => {
  test.beforeEach(async () => {
    // Le siège de nuit, écrit droit en base : c'est désormais la seule façon
    // d'en poser un sans passer par la porte que ce test éprouve.
    const insertion = await admin.post('/api/database/import', {
      headers: { 'Content-Type': 'text/plain' },
      data: [
        `delete from poste_affectation where id = '${SIEGE_NUIT}';`,
        `insert into poste_affectation (edition_id, id, stand_id, creneau_id, animateur_id) values ('DEFAUT', '${SIEGE_NUIT}', '${STAND_TOUS}', ${NUIT}, '${MAJEUR}');`,
      ].join('\n'),
    });
    expect(insertion.ok(), await insertion.text()).toBe(true);
  });

  test.afterEach(async () => {
    await admin.post('/api/database/import', {
      headers: { 'Content-Type': 'text/plain' },
      data: `delete from poste_affectation where id = '${SIEGE_NUIT}';`,
    });
  });

  test('y asseoir un mineur est refusé, en nommant la règle', async () => {
    const refus = await admin.post(`/api/postes/${SIEGE_NUIT}/affectation?animateurId=${MINEUR}`);
    expect(refus.status(), await refus.text()).toBe(400);
    const message = await refus.text();
    // La règle est nommée par sa description du catalogue, pas par son
    // identifiant technique : c'est la phrase que l'organisateur peut lire, et
    // elle cite l'article qui la fonde.
    expect(message).toContain('Affectation refusée');
    expect(message).toContain('nuit légale');
    expect(message).toContain('L3163-1');
    // Le message revient tel quel côté MCP : il ne nomme personne.
    expect(message).not.toContain('Mina');
    expect(message).not.toContain(MINEUR);

    // Refusé veut dire non écrit : la place n'a pas changé de main.
    const plan = (await (await admin.get('/api/planning/persisted')).json()) as {
      postes: { id: string; animateur: { id: string } | null }[];
    };
    expect(plan.postes.find((poste) => poste.id === SIEGE_NUIT)?.animateur?.id).toBe(MAJEUR);
  });

  test('libérer la place passe toujours, même sur un plan en mauvais état', async () => {
    // Marquer une absence le jour J est le geste qui doit passer précisément
    // quand le plan va mal : libérer n'est jamais scoré.
    const liberation = await admin.post(`/api/postes/${SIEGE_NUIT}/affectation`);
    expect(liberation.status(), await liberation.text()).toBe(204);

    const plan = (await (await admin.get('/api/planning/persisted')).json()) as {
      postes: { id: string; animateur: { id: string } | null }[];
    };
    expect(plan.postes.find((poste) => poste.id === SIEGE_NUIT)?.animateur).toBeNull();
  });
});

test.describe('piste 5 — la confirmation avant le calcul', () => {
  test('Calculer demande confirmation sur une cause bloquante, et renoncer n’entame rien', async ({
    browser,
  }) => {
    await ecrire(affectationForcee({ animateurs: [MINEUR], stand: STAND_TOUS, creneau: NUIT }));

    const page = await pageAdmin(browser, admin);
    await page.goto('/solveur');
    await page.getByRole('button', { name: 'Calculer le planning' }).click();

    const dialog = await dialogueOuvert(page);
    await expect(dialog).toContainText('Lancer malgré un problème bloquant');
    // La cause est citée telle que le serveur l'a écrite, pas reformulée.
    await expect(dialog).toContainText(EXCEPTION);

    // La confirmation demande, elle ne refuse pas — et renoncer ne lance rien.
    await dialog.getByRole('button', { name: 'Annuler' }).click();
    await expect(page.getByRole('dialog')).toHaveCount(0);
    const jobs = (await (await admin.get('/api/jobs')).json()) as { status: string }[];
    expect(jobs.filter((job) => job.status === 'RUNNING' || job.status === 'QUEUED')).toEqual([]);

    await page.context().close();
  });
});
