// N visitors pressing « Calculer » at the same instant. The instance runs one
// solve, queues FILE_MAX more (SOLVER_MAX_QUEUED_JOBS) and counts each one
// against its hourly quota: everything else must be a clean refusal, a 409 or
// a 429 carrying a sentence, and never a 5xx.
import http from 'k6/http';
import { check } from 'k6';
import { Counter } from 'k6/metrics';
import { BASE_URL, connexion, post, seuils, N } from './commun.js';

const FILE_MAX = Number(__ENV.FILE_MAX || 3);
const acceptes = new Counter('calculs_acceptes');
const refuses = new Counter('calculs_refuses');

// The refusals are the expected answer here, not failures.
http.setResponseCallback(http.expectedStatuses({ min: 200, max: 399 }, 409, 429));

export const options = {
  scenarios: {
    calculs: {
      executor: 'per-vu-iterations',
      vus: N,
      iterations: 1,
      maxDuration: '2m',
    },
  },
  thresholds: {
    ...seuils(),
    // One running, FILE_MAX waiting: nothing more may be accepted at once.
    calculs_acceptes: [`count<=${FILE_MAX + 1}`],
    checks: ['rate==1'],
  },
};

/** One login for all: the VUs then fire together instead of one login apart. */
export function setup() {
  const entete = connexion();
  const jar = http.cookieJar();
  return { entete, cookies: jar.cookiesForURL(`${BASE_URL}/`) };
}

export default function ({ entete, cookies }) {
  const jar = http.cookieJar();
  Object.entries(cookies).forEach(([nom, valeurs]) => jar.set(BASE_URL, nom, valeurs[0]));
  const secondes = __ENV.SECONDES ? `&seconds=${__ENV.SECONDES}` : '';
  const reponse = post(`/api/solve/async/reference-data?enFile=true${secondes}`, null, {
    headers: entete,
    tags: { name: 'POST /api/solve/async/reference-data' },
  });
  if (reponse.status === 202) {
    acceptes.add(1);
  } else {
    refuses.add(1, { statut: String(reponse.status) });
  }
  check(reponse, {
    'accepté (202) ou refusé proprement (409, 429)': (r) => [202, 409, 429].includes(r.status),
    'un refus porte une phrase': (r) => r.status === 202 || Boolean(message(r)),
  });
}

function message(reponse) {
  try {
    return reponse.json('message');
  } catch {
    return null;
  }
}
