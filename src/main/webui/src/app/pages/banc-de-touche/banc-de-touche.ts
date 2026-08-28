// Pure view logic of the banc de touche, kept out of the component so it is
// unit-tested without rendering anything. Nothing here decides whether an
// animateur may take a seat: the server already did, from the constraints
// themselves (issue #303). This only sorts, groups and words the answer.

import { Animateur, AnimateurBanc, BancDeTouche, Creneau, MotifExclusion, Stand } from '../../core/models';

/**
 * The three states a line can be in. Deliberately three and not two: the
 * backend carries two verdicts that do not coincide, and flattening them would
 * either call someone available while a rule refuses them, or hide a candidate
 * the repair assistant would happily propose.
 */
export type EtatBanc = 'disponible' | 'sousReserve' | 'impossible';

export interface LigneBanc {
  animateurId: string;
  nom: string;
  etat: EtatBanc;
  /** Reasons the plan's rules give, hard ones first — see {@link ordreMotifs}. */
  motifs: MotifExclusion[];
  /** Hard-score cost of the assignment, negative when it would break something. */
  coutDur: number | null;
}

/**
 * `impossible` — the repair assistant would not propose them either.
 * `sousReserve` — it would, but a hard rule is strained: « il peut le prendre,
 * mais il sera sur deux stands à la fois ».
 */
export function etatDe(ligne: AnimateurBanc): EtatBanc {
  if (!ligne.envisageable) {
    return 'impossible';
  }
  return ligne.disponible ? 'disponible' : 'sousReserve';
}

/** Hard reasons first: they are the ones that answer « pourquoi pas lui ». */
export function ordreMotifs(motifs: MotifExclusion[]): MotifExclusion[] {
  const rang = (motif: MotifExclusion): number =>
    motif.niveau === 'HARD' ? 0 : motif.niveau === 'MEDIUM' ? 1 : 2;
  return [...motifs].sort((a, b) => rang(a) - rang(b) || a.contrainte.localeCompare(b.contrainte));
}

/**
 * Rows in the order the server sent them — available first — with each
 * animateur named rather than reduced to their id. An id with no matching
 * animateur keeps the id: the bench is read from a persisted plan, which can
 * legitimately be older than a since-deleted animateur, and dropping the row
 * would silently shorten the list.
 */
export function lignes(banc: BancDeTouche | null, animateurs: Animateur[]): LigneBanc[] {
  if (!banc) {
    return [];
  }
  const parId = new Map(animateurs.map((animateur) => [animateur.id, animateur]));
  return banc.animateurs.map((ligne) => {
    const animateur = parId.get(ligne.animateurId);
    return {
      animateurId: ligne.animateurId,
      nom: animateur ? `${animateur.prenom} ${animateur.nom}` : ligne.animateurId,
      etat: etatDe(ligne),
      motifs: ordreMotifs(ligne.motifs),
      coutDur: ligne.delta ? ligne.delta.hardScore : null
    };
  });
}

/**
 * `J3 · 2026-07-16 · 10:00-13:00`, plus the stagger family when there is one.
 *
 * `avecFamille` is decided over the whole list, like the créneaux screen does:
 * every créneau carries a family, but it only means something once a découpage
 * has generated several variants of the same hours. Tagging every line `(F1)`
 * when there is only one family is noise on top of the one thing this label is
 * for — telling two otherwise identical vacations apart.
 */
export function libelleCreneau(creneau: Creneau, avecFamille = false): string {
  const famille = avecFamille ? ` (F${(creneau.famille ?? 0) + 1})` : '';
  return `J${creneau.jour} · ${creneau.date} · ${heure(creneau.heureDebut)}-${heure(creneau.heureFin)}${famille}`;
}

/** Hours arrive as `HH:mm:ss` from the API; the seconds are always zero and never read. */
function heure(valeur: string): string {
  return valeur.length > 5 ? valeur.slice(0, 5) : valeur;
}

/** True once a découpage has produced more than one stagger family, so the tag carries information. */
export function familleUtile(creneaux: readonly Creneau[]): boolean {
  return creneaux.some((creneau) => (creneau.famille ?? 0) > 0);
}

/**
 * The timeslots the saved plan actually holds a seat on, as a lookup.
 *
 * The selector lists the referential's timeslots, which are legitimately more
 * numerous — nothing is scheduled on a slot where no stand is open, and a
 * découpage can add slots after the last solve. Marking the difference in the
 * list is what turns « that one is empty, and so is that one » into a choice.
 * An empty set means the answer carries no such information (no saved plan at
 * all), and nothing is marked rather than everything.
 */
export function creneauxUtiles(banc: BancDeTouche | null): ReadonlySet<number> {
  return new Set(banc?.creneauxAvecSieges ?? []);
}

export function libelleStand(stands: Stand[], standId: string | null): string {
  if (!standId) {
    return '';
  }
  return stands.find((stand) => stand.id === standId)?.nom ?? standId;
}
