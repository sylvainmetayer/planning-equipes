import { APIRequestContext, expect, request } from '@playwright/test';
import { createHash } from 'node:crypto';
import { existsSync, mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import { dirname, join } from 'node:path';

import { MOT_DE_PASSE_ADMIN } from './support';

/**
 * L'état de référence de la base, et le moyen d'y revenir entre deux specs.
 *
 * Les specs ne s'isolaient pas : chacune ne supprimait que ses propres lignes,
 * repérées par un préfixe d'identifiant, alors que les écrans listent **toute**
 * l'édition. Les animateurs d'une spec apparaissaient donc dans le rail d'une
 * autre, et `seedPlanning` avait fini par supprimer les préfixes de ses
 * voisines — `SOLV-%`, `FUZZ-%` — chacun ajouté après une panne.
 *
 * Le résultat se mesurait : selon ce que la passe précédente avait laissé, ce
 * n'étaient pas les mêmes tests qui tombaient.
 *
 * La sortie n'est pas un préfixe de plus. `GET /api/database/export` produit un
 * script qui commence par un `DELETE FROM` de chaque table : une restauration
 * autonome. On la capture une fois, chaque spec y revient. Mesuré : 4,3 Kio,
 * 33 ms à l'export, 42 ms à la restauration.
 *
 * <h2>Ce mécanisme efface la base qu'il vise</h2>
 *
 * C'est sa raison d'être, et c'est aussi son danger. Le cas qui fait mal n'est
 * pas de viser sa propre base — la référence serait alors *son* contenu, que
 * chaque restauration remet en place — mais de **rejouer sur une base la
 * référence d'une autre** : prise sur une pile jetable, puis un `npm run e2e`
 * sans variables, donc sur le `quarkus:dev` du développeur, qui se ferait
 * remplacer par le contenu de la pile.
 *
 * D'où l'unique garde-fou qui compte : la référence est **indexée par l'URL
 * visée**. Celle d'une pile n'est jamais rejouée sur une autre.
 *
 * <p>J'avais d'abord ajouté un second contrôle — refuser toute base portant une
 * ligne absente de la référence et sans préfixe de test. Il ne tient pas : les
 * `creneau` et les `poste_affectation` que l'application crée elle-même
 * pendant les tests ont des identifiants générés, qu'aucun préfixe ne
 * distingue d'une vraie donnée. Il refusait donc la seconde exécution.</p>
 *
 * <h2>La base n'est pas le seul état partagé</h2>
 *
 * Le serveur de mail non plus ne s'efface pas tout seul. `dernierCodeMailpit`
 * attend « plus de zéro message » puis prend le plus récent : une boîte qui
 * porte encore les courriels de la passe précédente satisfait la condition
 * immédiatement, avec un code **périmé**, et l'ouverture de l'espace animateur
 * échoue. C'est ce qui rendait `espace-animateur.spec.ts` instable, sur `main`
 * comme ici. La boîte est donc vidée avec la base.
 *
 * <p>Reste que la référence est un dump en clair de la base visée, écrit sous
 * `node_modules/.cache/`. Sur une pile jetable il ne contient que les données
 * des migrations ; visez une pile jetable, comme `docs/developpement.md` le
 * demande déjà.</p>
 */

/** Les préfixes d'identifiant que les specs se réservent. */
const PREFIXES_DE_TEST = ['E2E-', 'E2EIMP-', 'SOLV-', 'FUZZ-', 'CJ-', 'ENE-', 'ADH-', 'CSV-'];

/**
 * Ce que la restauration ne couvre pas, et pourquoi ce n'est pas un trou ici.
 *
 * <p>`horloge_jour_j` (pas de colonne d'édition) et `kpi_historique` (une
 * colonne sans clé étrangère) sont absentes de `DatabaseDumpService.TABLES` :
 * ni exportées, ni emportées par le `DELETE FROM edition` du dump. Elles
 * survivent donc à chaque restauration, et l'import les refuserait — sa liste
 * d'instructions autorisées dérive de la même constante.</p>
 *
 * <p>Sans conséquence pour la suite : la seule spec qui touche à l'horloge
 * vérifie que la figer est **refusée** (l'application packagée tourne en mode
 * production), et aucune n'observe l'historique des KPI. Ce serait à revoir le
 * jour où l'une des deux devient un fait sur lequel un test s'appuie.</p>
 */

function fichierDeReference(baseURL: string): string {
  // Indexé par l'URL : une référence prise sur une pile n'est jamais rejouée
  // sur une autre. Hors de `test-results/`, que Playwright vide à chaque
  // exécution — sinon la seconde passe photographierait la première.
  const empreinte = createHash('sha256').update(baseURL).digest('hex').slice(0, 12);
  return join(process.cwd(), 'node_modules', '.cache', `e2e-reference-${empreinte}.sql`);
}

/** Les instructions d'insertion d'un dump, seule partie qui décrit des données. */
const insertions = (dump: string) =>
  dump
    .split('\n')
    .map((ligne) => ligne.trim())
    .filter((ligne) => ligne.toUpperCase().startsWith('INSERT INTO'));

const estDeTest = (ligne: string) => PREFIXES_DE_TEST.some((prefixe) => ligne.includes(`'${prefixe}`));

async function contexte(baseURL: string): Promise<APIRequestContext> {
  const requeteur = await request.newContext({ baseURL });
  await requeteur.post('/j_security_check', {
    form: { j_username: 'admin', j_password: MOT_DE_PASSE_ADMIN },
    maxRedirects: 0
  });
  return requeteur;
}

async function exporter(requeteur: APIRequestContext): Promise<string> {
  const reponse = await requeteur.get('/api/database/export');
  expect(reponse.ok(), `l'export de la base a répondu ${reponse.status()}`).toBe(true);
  return reponse.text();
}

async function restaurer(requeteur: APIRequestContext, dump: string): Promise<boolean> {
  const reponse = await requeteur.post('/api/database/import', {
    headers: { 'Content-Type': 'text/plain' },
    data: dump
  });
  await viderLaBoiteMail(requeteur);
  return reponse.ok();
}

/**
 * Vide Mailpit. Sans échec si la boîte est injoignable : toutes les specs n'en
 * ont pas besoin, et celles qui en ont besoin échoueront d'elles-mêmes, en
 * disant mieux pourquoi.
 */
async function viderLaBoiteMail(requeteur: APIRequestContext): Promise<void> {
  const mailpit = process.env['E2E_MAILPIT_URL'] ?? 'http://localhost:8025';
  try {
    await requeteur.delete(`${mailpit}/api/v1/messages`);
  } catch {
    // Boîte absente : rien à vider.
  }
}

function refuser(details: string): never {
  throw new Error(
    `${details}\n\n` +
      "La suite e2e efface la base qu'elle vise, à chaque spec : elle ne doit viser qu'une pile\n" +
      'jetable. Vérifiez E2E_BASE_URL, ou recréez la base si celle-ci est bien jetable :\n' +
      '  podman rm -f e2e-postgres && podman run -d --rm --name e2e-postgres … postgres:18'
  );
}

/**
 * Établit l'état de référence, avant la première spec.
 *
 * <p>Si une référence existe pour cette URL, elle est <b>restaurée</b> : la base
 * redevient propre quel que soit ce que la passe précédente a laissé, et la
 * suite se relance sans rien recréer. Sinon elle est capturée.</p>
 *
 * <p>Une référence qui ne se rejoue plus — une migration a changé le schéma —
 * est recapturée : l'échec de la restauration est le signal.</p>
 */
export default async function etablirLaReference(): Promise<void> {
  const baseURL = process.env['E2E_BASE_URL'] ?? 'http://localhost:8080';
  const fichier = fichierDeReference(baseURL);
  const requeteur = await contexte(baseURL);
  try {
    const vivant = await exporter(requeteur);

    if (existsSync(fichier)) {
      const reference = readFileSync(fichier, 'utf8');
      if (await restaurer(requeteur, reference)) {
        return;
      }
      // Le schéma a bougé sous la référence : on la refait ci-dessous.
    }

    const deTest = insertions(vivant).filter(estDeTest);
    if (deTest.length > 0) {
      refuser(
        `Aucune référence pour ${baseURL}, et la base porte déjà ${deTest.length} ligne(s) de test.\n` +
          "Elle ne peut donc pas en servir : la référence figerait l'état laissé par une exécution\n" +
          'précédente, et chaque spec repartirait de là — la panne même que ce mécanisme supprime.'
      );
    }

    mkdirSync(dirname(fichier), { recursive: true });
    writeFileSync(fichier, vivant);
  } finally {
    await requeteur.dispose();
  }
}

/**
 * Ramène la base à l'état de référence. À appeler dans le `beforeAll` d'une
 * spec, avant son propre amorçage.
 *
 * <p>Relue du disque à chaque fois : `globalSetup` tourne dans le processus
 * principal et les specs dans des processus ouvriers, donc aucune variable ne
 * se partage entre les deux.</p>
 */
export async function repartirDeLaReference(admin: APIRequestContext): Promise<void> {
  const baseURL = process.env['E2E_BASE_URL'] ?? 'http://localhost:8080';
  const reference = readFileSync(fichierDeReference(baseURL), 'utf8');
  expect(await restaurer(admin, reference), 'la restauration de la base de référence a échoué').toBe(true);
}
