// The pure side of the review table (issue #503): sorting, filtering and
// wording the list of people a publication would write to.
//
// Pure and outside the component for the reason the rest of `core/` is: what
// the table says about somebody — « 2 ajouts, 1 retrait », « reporté depuis la
// dernière publication » — is the sentence an organiser makes a decision on,
// and it deserves a test that does not render anything.

import { DestinatairePublication } from '../../core/models';

/** The two orders the table offers, and the values of the `tri` query param. */
export type TriDestinataires = 'nom' | 'ampleur';

const TRIS: readonly TriDestinataires[] = ['nom', 'ampleur'];

/**
 * Reads the `tri` query param. Anything unknown is the name order, the one
 * the server already sends and the only one that does not move between two
 * readings of an unchanged plan.
 */
export function readTri(value: string | null): TriDestinataires {
  return (TRIS as readonly string[]).includes(value ?? '') ? (value as TriDestinataires) : 'nom';
}

/** How much moves for somebody: the number of sentences their mail would carry. */
export function changeCount(destinataire: DestinatairePublication): number {
  return destinataire.ajouts + destinataire.retraits + destinataire.deplacements;
}

/**
 * The rows in the chosen order. « Par ampleur » puts the biggest change first
 * and falls back on the name, so two people with as much to read are in an
 * order that does not move between two openings of the screen.
 */
export function sortRecipients(
  destinataires: readonly DestinatairePublication[],
  tri: TriDestinataires,
): DestinatairePublication[] {
  const rows = [...destinataires];
  if (tri === 'nom') {
    return rows;
  }
  return rows.sort(
    (left, right) =>
      changeCount(right) - changeCount(left) || left.nomAffiche.localeCompare(right.nomAffiche),
  );
}

/**
 * The rows left once the minor changes are folded away — a view filter, never
 * a decision: hiding somebody does not exclude them, and the count above the
 * table keeps saying how many people the publication would write to.
 */
export function filterRecipients(
  destinataires: readonly DestinatairePublication[],
  masquerMineurs: boolean,
): DestinatairePublication[] {
  return masquerMineurs ? destinataires.filter((each) => !each.mineur) : [...destinataires];
}

/**
 * « 2 ajouts · 1 déplacement » — what moves, counted by kind. A first delivery
 * says so instead: every seat is new to them, and « 12 ajouts » would read as
 * a correction of a planning they never received.
 */
export function changeSummary(destinataire: DestinatairePublication): string {
  if (destinataire.premiereDiffusion) {
    return $localize`:@@publication.table.premiere:Première diffusion`;
  }
  const morceaux: string[] = [];
  if (destinataire.ajouts > 0) {
    morceaux.push($localize`:@@publication.table.ajouts:${destinataire.ajouts}:count: ajout(s)`);
  }
  if (destinataire.retraits > 0) {
    morceaux.push(
      $localize`:@@publication.table.retraits:${destinataire.retraits}:count: retrait(s)`,
    );
  }
  if (destinataire.deplacements > 0) {
    morceaux.push(
      $localize`:@@publication.table.deplacements:${destinataire.deplacements}:count: déplacement(s)`,
    );
  }
  if (morceaux.length === 0) {
    return $localize`:@@publication.table.demandeSeule:Décision d'échange à annoncer`;
  }
  return morceaux.join(' · ');
}

/**
 * Where somebody's « j'ai lu » stands on the plan they were last sent —
 * the question an organiser asks before deciding to call rather than to write.
 */
export function confirmationLabel(destinataire: DestinatairePublication, locale: string): string {
  if (destinataire.confirmation === 'CONFIRME' && destinataire.confirmeLe) {
    const quand = new Date(destinataire.confirmeLe).toLocaleDateString(locale, {
      day: 'numeric',
      month: 'short',
    });
    return $localize`:@@publication.table.confirmeLe:Confirmé le ${quand}:date:`;
  }
  if (destinataire.confirmation === 'RELANCE') {
    return $localize`:@@publication.table.relance:Relancé, sans réponse`;
  }
  return $localize`:@@publication.table.jamaisConfirme:Pas de réponse`;
}
