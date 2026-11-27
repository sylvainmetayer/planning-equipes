// The opening rush: from 0 to N visitors in MONTEE (1 min by default), then a
// plateau of DUREE at N, each visitor on the same tour as visiteur.js.
import { parcoursVisiteur, seuils, N, DUREE } from './commun.js';

export const options = {
  scenarios: {
    pic: {
      executor: 'ramping-vus',
      startVUs: 0,
      stages: [
        { duration: __ENV.MONTEE || '1m', target: N },
        { duration: DUREE, target: N },
      ],
      gracefulRampDown: '10s',
    },
  },
  thresholds: seuils(),
};

export default parcoursVisiteur;
