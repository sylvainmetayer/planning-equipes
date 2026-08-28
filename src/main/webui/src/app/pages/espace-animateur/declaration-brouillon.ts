// Draft of the availability declaration (issue #291): what the animateur has
// ticked before sending it. Pure functions, so the rules — where the form
// opens from, what counts as a change, what actually leaves — are unit-tested
// without rendering a calendar.

import { DeclarationEspaceView, NouvelleDeclaration } from '../../core/models';

/** What the form holds while it is being filled in. */
export interface BrouillonDeclaration {
  /** ISO dates the animateur says they cannot come. */
  joursIndisponibles: string[];
  /** Typologie ids they would like to animate. */
  souhaits: string[];
  commentaire: string;
}

/** One month of the day picker: a heading and the event days it holds. */
export interface MoisCollecte {
  /** ISO date of the first day shown, used as the month's label source. */
  premierJour: string;
  jours: string[];
}

/**
 * Where the form opens from: the pending proposal if there is one — correcting
 * a declaration must not start from a blank page — otherwise what the
 * organisation currently holds. A blank form would read as « I am available
 * every day », which is a statement nobody meant to make.
 */
export function brouillonInitial(vue: DeclarationEspaceView | null): BrouillonDeclaration {
  if (!vue) {
    return { joursIndisponibles: [], souhaits: [], commentaire: '' };
  }
  if (vue.enAttente) {
    return {
      joursIndisponibles: [...vue.enAttente.joursIndisponibles],
      souhaits: [...vue.enAttente.souhaits],
      commentaire: vue.enAttente.commentaire ?? ''
    };
  }
  return {
    joursIndisponibles: [...vue.joursActuels],
    souhaits: [...vue.souhaitsActuels],
    commentaire: ''
  };
}

/** Groups the event days by calendar month, in chronological order. */
export function moisDeCollecte(joursEvenement: readonly string[]): MoisCollecte[] {
  const mois: MoisCollecte[] = [];
  for (const jour of [...joursEvenement].sort()) {
    const dernier = mois.at(-1);
    if (dernier && dernier.premierJour.slice(0, 7) === jour.slice(0, 7)) {
      dernier.jours.push(jour);
    } else {
      mois.push({ premierJour: jour, jours: [jour] });
    }
  }
  return mois;
}

/** Toggles one value in a list, keeping it sorted so two equal drafts compare equal. */
export function basculer(valeurs: readonly string[], valeur: string): string[] {
  return valeurs.includes(valeur)
    ? valeurs.filter((candidat) => candidat !== valeur)
    : [...valeurs, valeur].sort();
}

/**
 * True when the draft says something other than what is already on record —
 * the pending proposal if there is one, the fiche otherwise. Resending an
 * identical declaration would replace a pending row with a copy of itself and
 * tell the admin nothing, so the button stays disabled.
 */
export function declarationModifiee(
  vue: DeclarationEspaceView | null,
  brouillon: BrouillonDeclaration
): boolean {
  if (!vue) {
    return false;
  }
  const reference = vue.enAttente
    ? {
        jours: vue.enAttente.joursIndisponibles,
        souhaits: vue.enAttente.souhaits,
        commentaire: vue.enAttente.commentaire ?? ''
      }
    : { jours: vue.joursActuels, souhaits: vue.souhaitsActuels, commentaire: '' };
  return (
    !memesValeurs(reference.jours, brouillon.joursIndisponibles) ||
    !memesValeurs(reference.souhaits, brouillon.souhaits) ||
    reference.commentaire.trim() !== brouillon.commentaire.trim()
  );
}

/** What actually leaves for the backend, normalised the way the server stores it. */
export function versNouvelleDeclaration(brouillon: BrouillonDeclaration): NouvelleDeclaration {
  return {
    joursIndisponibles: [...brouillon.joursIndisponibles].sort(),
    souhaits: [...brouillon.souhaits].sort(),
    commentaire: brouillon.commentaire.trim() ? brouillon.commentaire.trim() : null
  };
}

/** Order-insensitive comparison: the picker's order is not part of the meaning. */
function memesValeurs(gauche: readonly string[], droite: readonly string[]): boolean {
  if (gauche.length !== droite.length) {
    return false;
  }
  const trie = [...gauche].sort();
  return [...droite].sort().every((valeur, index) => valeur === trie[index]);
}
