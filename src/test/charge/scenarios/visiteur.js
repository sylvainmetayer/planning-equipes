// N visitors browsing the demo at once, each one logging in and touring
// Accueil, Planning, Diagnostic and Règles, for DUREE.
import { parcoursVisiteur, seuils, N, DUREE } from './commun.js';

export const options = {
  scenarios: {
    visiteur: { executor: 'constant-vus', vus: N, duration: DUREE },
  },
  thresholds: seuils(),
};

export default parcoursVisiteur;
