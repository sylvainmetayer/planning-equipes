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

/** `J3 · 2026-07-16 · 10:00-13:00` — enough to tell two same-hour vacations apart. */
export function libelleCreneau(creneau: Creneau): string {
  const famille = creneau.famille === undefined ? '' : ` (F${creneau.famille + 1})`;
  return `J${creneau.jour} · ${creneau.date} · ${creneau.heureDebut}-${creneau.heureFin}${famille}`;
}

export function libelleStand(stands: Stand[], standId: string | null): string {
  if (!standId) {
    return '';
  }
  return stands.find((stand) => stand.id === standId)?.nom ?? standId;
}
