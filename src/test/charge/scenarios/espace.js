// The demo's espace animateur: a visitor follows the public link, asks for the
// access code (shown on screen on a demo instance), opens the session, then
// reads the planning, its ICS export and the calendar subscription feed.
// Visitors arrive over MONTEE rather than at once: on one shared espace, two
// code requests in the same second replace each other's code.
import http from 'k6/http';
import { check, sleep } from 'k6';
import { Rate } from 'k6/metrics';
import { get, post, seuils, N, DUREE } from './commun.js';

// The token of the demo dump (scripts/demo/generer-dump.sh): public by design.
const JETON = __ENV.ESPACE_TOKEN || '00000000-0000-4000-8000-0000000de305';
const ESPACE = `/api/espace-animateur/${JETON}`;
const sessionsOuvertes = new Rate('espace_sessions_ouvertes');

// A replaced code (400) or a code asked too often (429) is the instance
// answering correctly; their share is the espace_sessions_ouvertes threshold.
http.setResponseCallback(http.expectedStatuses({ min: 200, max: 399 }, 400, 429));

export const options = {
  scenarios: {
    espace: {
      executor: 'ramping-vus',
      startVUs: 0,
      stages: [
        { duration: __ENV.MONTEE || '30s', target: N },
        { duration: DUREE, target: N },
      ],
      gracefulRampDown: '10s',
    },
  },
  // Each visitor keeps the session it opened, as a browser does.
  noCookiesReset: true,
  thresholds: {
    ...seuils(),
    espace_sessions_ouvertes: [`rate>=${__ENV.TAUX_SESSIONS_MIN || 0.9}`],
  },
};

let abonnement = null;

/** Code then session, retried once: another visitor may have replaced the code. */
function ouvrirSession() {
  for (let essai = 0; essai < 2; essai++) {
    const code = post(`${ESPACE}/code`, null, {
      tags: { name: 'POST espace/code' },
    });
    const valeur = code.status === 200 ? code.json('codeDemo') : null;
    if (valeur) {
      const session = post(`${ESPACE}/session`, JSON.stringify({ code: valeur }), {
        headers: { 'Content-Type': 'application/json' },
        tags: { name: 'POST espace/session' },
      });
      if (session.status === 204) {
        return true;
      }
    }
    // Spread by VU: two visitors that collided must not retry together.
    sleep(1 + (__VU % 3));
  }
  return false;
}

export default function () {
  if (abonnement === null) {
    const ouverte = ouvrirSession();
    sessionsOuvertes.add(ouverte);
    if (!ouverte) {
      sleep(5);
      return;
    }
    abonnement = '';
  }
  const espace = get(ESPACE, { tags: { name: 'GET espace' } });
  check(espace, { 'espace lu (200)': (r) => r.status === 200 });
  abonnement = espace.status === 200 ? espace.json('abonnementToken') || '' : abonnement;
  const ics = get(`${ESPACE}/planning.ics`, {
    tags: { name: 'GET espace/planning.ics' },
  });
  check(ics, {
    'ICS de l’espace (200)': (r) => r.status === 200 && r.body.includes('BEGIN:VCALENDAR'),
  });
  if (abonnement) {
    const flux = get(`/api/abonnements/${abonnement}/planning.ics`, {
      tags: { name: 'GET abonnements/planning.ics' },
    });
    check(flux, { 'flux d’abonnement (200)': (r) => r.status === 200 });
  }
  sleep(Number(__ENV.PAUSE || 2));
}
