// What the four scenarios share: the target, the admin login, the visitor's
// journey through Accueil, Planning, Diagnostic and Règles, and the thresholds
// that fail a run. Every setting is an environment variable (see ../README.md).
import http from 'k6/http';
import { check, fail, group, sleep } from 'k6';
import { Counter } from 'k6/metrics';

export const BASE_URL = (__ENV.BASE_URL || '').replace(/\/+$/, '');
if (!BASE_URL) {
  throw new Error('BASE_URL manque : l’adresse de l’instance de démo visée');
}

/** Number of visitors (VUs) of a scenario, and how long its plateau lasts. */
export const N = Number(__ENV.N || 5);
export const DUREE = __ENV.DUREE || '1m';
/** Seconds a visitor spends on a screen before the next one. */
const PAUSE = Number(__ENV.PAUSE || 2);

/** Every answer above 499, whatever the scenario: never acceptable. */
export const erreursServeur = new Counter('erreurs_serveur');

/** The thresholds every scenario carries; a scenario adds its own. */
export function seuils() {
  return {
    http_req_failed: [`rate<${__ENV.TAUX_ERREUR_MAX || 0.01}`],
    http_req_duration: [`p(95)<${__ENV.P95_MAX_MS || 1500}`],
    erreurs_serveur: ['count==0'],
  };
}

/** Counts a 5xx and logs it once, so a run that fails says which route. */
export function surveiller(reponse) {
  if (reponse.status >= 500) {
    erreursServeur.add(1, { route: reponse.request.url });
    console.error(`${reponse.status} ${reponse.request.method} ${reponse.request.url}`);
  }
  return reponse;
}

export function get(chemin, params) {
  return surveiller(http.get(`${BASE_URL}${chemin}`, params));
}

export function post(chemin, corps, params) {
  return surveiller(http.post(`${BASE_URL}${chemin}`, corps, params));
}

/**
 * The admin form login, as the login page posts it, then the active edition:
 * every later read carries it in X-Edition-Id. Fails the iteration when the
 * login is refused, so a wrong password does not pass for a slow instance.
 */
export function connexion() {
  const motDePasse = __ENV.ADMIN_PASSWORD;
  if (!motDePasse) {
    fail('ADMIN_PASSWORD manque : le mot de passe du compte admin de la démo');
  }
  const reponse = post(
    '/j_security_check',
    { j_username: __ENV.ADMIN_USER || 'admin', j_password: motDePasse },
    { redirects: 0, tags: { name: 'connexion' } },
  );
  if (!check(reponse, { 'connexion acceptée (302)': (r) => r.status === 302 })) {
    fail(`connexion refusée : ${reponse.status}`);
  }
  const editions = get('/api/editions', {
    tags: { name: 'GET /api/editions' },
  });
  const active = (editions.json() || []).find((edition) => edition.active);
  if (!active) {
    fail('aucune édition active sur l’instance visée');
  }
  return { 'X-Edition-Id': active.id };
}

/** Reads one screen: its calls, in parallel as the browser makes them. */
function ecran(nom, entete, chemins) {
  group(nom, () => {
    const reponses = http.batch(
      chemins.map((chemin) => {
        const [methode, url] = chemin.includes(' ') ? chemin.split(' ') : ['GET', chemin];
        return {
          method: methode,
          url: `${BASE_URL}${url}`,
          body: methode === 'POST' ? '{}' : null,
          params: {
            headers: { ...entete, 'Content-Type': 'application/json' },
            tags: { name: `${methode} ${url}` },
          },
        };
      }),
    );
    reponses.forEach(surveiller);
    check(reponses, {
      [`${nom} : tout en 2xx`]: (rs) => rs.every((r) => r.status >= 200 && r.status < 300),
    });
  });
  sleep(PAUSE);
}

/**
 * One visitor: log in, then the four screens of the demo tour. The calls are
 * the ones the frontend makes when each screen opens (the shell's own first).
 */
export function parcoursVisiteur() {
  const entete = connexion();
  ecran('Accueil', entete, [
    '/api/config',
    '/api/auth/me',
    '/api/editions/courant',
    '/api/editions/courant/etat',
    '/api/editions/courant/coherence',
    '/api/alertes',
    '/api/constraints',
    '/api/jobs/file',
  ]);
  ecran('Planning', entete, [
    '/api/planning/persisted',
    '/api/stands',
    '/api/animateurs',
    '/api/creneaux',
    '/api/verrouillages',
  ]);
  ecran('Diagnostic', entete, [
    '/api/feasibility',
    '/api/constraints',
    '/api/pauses',
    '/api/planning/enchainements',
    '/api/planning/arrivees-groupees',
  ]);
  ecran('Règles', entete, ['/api/constraints', 'POST /api/constraints/diagnostic']);
}
