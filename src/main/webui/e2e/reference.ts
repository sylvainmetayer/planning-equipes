import { APIRequestContext, expect, request } from '@playwright/test';
import { existsSync, mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import { dirname, join } from 'node:path';

import { MOT_DE_PASSE_ADMIN } from './support';

/**
 * L'état de référence de la base, et le moyen d'y revenir entre deux specs.
 *
 * Les specs ne s'isolaient pas : chacune ne supprimait que ses propres lignes,
 * repérées par un préfixe d'identifiant, alors que les écrans listent **toute**
 * l'édition. Les animateurs d'une spec apparaissaient donc dans le rail d'une
 * autre, et `seedPlanning` avait fini par supprimer les préfixes des voisines
 * — `SOLV-%`, `FUZZ-%` — chacun ajouté après une panne, chacun commenté par
 * l'incident qui l'avait fait ajouter.
 *
 * Le résultat se voyait : selon ce que la passe précédente avait laissé, ce
 * n'étaient pas les mêmes tests qui tombaient. Mesuré sur `main` — 1 échec sur
 * base résiduelle, 2 sur base neuve, chacun vert isolément.
 *
 * La sortie n'est pas un préfixe de plus. L'application sait déjà exporter et
 * réimporter sa base : `GET /api/database/export` produit un script qui
 * commence par un `DELETE FROM` de chaque table, donc une **restauration
 * autonome**. On capture cet état une fois, et chaque spec y revient. Coût
 * mesuré : 4,3 Kio, 33 ms à l'export, 42 ms à la restauration — moins qu'un
 * chargement de page.
 *
 * Effet de bord assumé et souhaitable : la suite exerce désormais l'export et
 * l'import à chaque spec, deux fonctions d'exploitation qu'un seul test
 * couvrait.
 */

/**
 * Hors de `test-results/`, que Playwright vide à chaque exécution : la
 * référence doit survivre d'une passe à l'autre, sans quoi la seconde
 * photographierait ce que la première a laissé.
 */
const FICHIER = join(process.cwd(), 'node_modules', '.cache', 'e2e-base-de-reference.sql');

/** Les préfixes d'identifiant que les specs se réservent. */
const PREFIXES_DE_TEST = ['E2E-', 'SOLV-', 'FUZZ-', 'CJ-', 'ENE-', 'ADH-', 'CSV-'];

let enMemoire: string | null = null;

const porteDesDonneesDeTest = (dump: string) =>
  PREFIXES_DE_TEST.filter((prefixe) => dump.includes(`'${prefixe}`));

async function contexte(baseURL: string): Promise<APIRequestContext> {
  const requeteur = await request.newContext({ baseURL });
  await requeteur.post('/j_security_check', {
    form: { j_username: 'admin', j_password: MOT_DE_PASSE_ADMIN },
    maxRedirects: 0
  });
  return requeteur;
}

async function restaurer(requeteur: APIRequestContext, dump: string): Promise<boolean> {
  const reponse = await requeteur.post('/api/database/import', {
    headers: { 'Content-Type': 'text/plain' },
    data: dump
  });
  return reponse.ok();
}

/**
 * Établit l'état de référence, avant la première spec.
 *
 * <p>Deux cas, et le premier est celui de tous les jours. Si une référence est
 * déjà en cache, elle est <b>restaurée</b> : la base redevient propre quel que
 * soit ce que la passe précédente a laissé, et la suite se relance sans rien
 * recréer. Sinon elle est capturée — et refusée si la base porte déjà des
 * données de test, parce qu'elle figerait alors l'état qu'on cherche à
 * effacer.</p>
 *
 * <p>Une référence en cache qui ne se rejoue plus (une migration a changé le
 * schéma depuis) est simplement recapturée : l'échec de la restauration est le
 * signal, pas une panne.</p>
 */
export default async function etablirLaReference(): Promise<void> {
  const baseURL = process.env['E2E_BASE_URL'] ?? 'http://localhost:8080';
  const requeteur = await contexte(baseURL);
  try {
    if (existsSync(FICHIER)) {
      const cache = readFileSync(FICHIER, 'utf8');
      if (await restaurer(requeteur, cache)) {
        enMemoire = cache;
        return;
      }
      // Le schéma a bougé sous la référence : on la refait.
    }

    const reponse = await requeteur.get('/api/database/export');
    expect(reponse.ok(), `l'export de la base a répondu ${reponse.status()}`).toBe(true);
    const dump = await reponse.text();

    const trouves = porteDesDonneesDeTest(dump);
    if (trouves.length > 0) {
      throw new Error(
        `Aucune référence en cache, et la base porte déjà des données de test (${trouves.join(', ')}).\n` +
          "Elle ne peut donc pas en servir : la référence figerait l'état laissé par une exécution\n" +
          "précédente, et chaque spec repartirait de là — exactement la panne que ce mécanisme supprime.\n" +
          'Recréez la base une fois, la référence sera capturée et réutilisée ensuite :\n' +
          '  podman rm -f e2e-postgres && podman run -d --rm --name e2e-postgres … postgres:18'
      );
    }

    mkdirSync(dirname(FICHIER), { recursive: true });
    writeFileSync(FICHIER, dump);
    enMemoire = dump;
  } finally {
    await requeteur.dispose();
  }
}

/**
 * Ramène la base à l'état de référence. À appeler dans le `beforeAll` d'une
 * spec, avant son propre amorçage.
 */
export async function repartirDeLaReference(admin: APIRequestContext): Promise<void> {
  const dump = (enMemoire ??= readFileSync(FICHIER, 'utf8'));
  expect(await restaurer(admin, dump), 'la restauration de la base de référence a échoué').toBe(true);
}
