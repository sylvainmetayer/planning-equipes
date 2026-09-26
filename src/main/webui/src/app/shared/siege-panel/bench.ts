// Pure view logic of « Qui peut tenir ce siège ? » — the bench of one seat,
// once a screen of its own (Banc de touche), now the dialog of the Siège
// panel. Nothing here decides whether an animateur may take a seat: the server
// already did, from the constraints themselves. This only sorts, groups and
// words the answer.

import {
  Animateur,
  AnimateurBanc,
  BancDeTouche,
  MotifExclusion,
  VerrouillagePlanning,
} from '../../core/models';
import { animateurName } from '../affectation-explanation-rules';

/**
 * The three states a line can be in. Deliberately three and not two: the
 * backend carries two verdicts that do not coincide, and flattening them would
 * either call someone available while a rule refuses them, or hide a candidate
 * the repair assistant would happily propose.
 */
export type BenchState = 'disponible' | 'sousReserve' | 'impossible';

export interface BenchLine {
  animateurId: string;
  nom: string;
  state: BenchState;
  /** Reasons the plan's rules give, hard ones first — see {@link sortReasons}. */
  motifs: MotifExclusion[];
  /** Hard-score cost of the assignment, negative when it would break something. */
  hardCost: number | null;
  /** Medium-score cost: what « Placer » would warn about. */
  mediumCost: number | null;
}

/**
 * `impossible` — the plan itself would get worse.
 * `sousReserve` — it would not, but a hard rule is strained all the same:
 * « il peut le prendre, mais il sera sur deux stands à la fois ».
 */
export function stateOf(line: AnimateurBanc): BenchState {
  if (line.degradeLePlan) {
    return 'impossible';
  }
  return line.disponible ? 'disponible' : 'sousReserve';
}

/** Hard reasons first: they are the ones that answer « pourquoi pas lui ». */
export function sortReasons(motifs: MotifExclusion[]): MotifExclusion[] {
  const rank = (motif: MotifExclusion): number => {
    if (motif.niveau === 'HARD') {
      return 0;
    }
    return motif.niveau === 'MEDIUM' ? 1 : 2;
  };
  return [...motifs].sort(
    (left, right) => rank(left) - rank(right) || left.contrainte.localeCompare(right.contrainte),
  );
}

/**
 * Rows in the order the server sent them — available first — with each
 * animateur named rather than reduced to their id. An id with no matching
 * animateur keeps the id: the bench is read from a persisted plan, which can
 * legitimately be older than a since-deleted animateur, and dropping the row
 * would silently shorten the list.
 */
export function benchLines(bench: BancDeTouche | null, animateurs: Animateur[]): BenchLine[] {
  if (!bench) {
    return [];
  }
  const byId = new Map(animateurs.map((animateur) => [animateur.id, animateur]));
  return bench.animateurs.map((line) => {
    const animateur = byId.get(line.animateurId);
    return {
      animateurId: line.animateurId,
      nom: animateur ? animateurName(animateur) : line.animateurId,
      state: stateOf(line),
      motifs: sortReasons(line.motifs),
      hardCost: line.delta ? line.delta.hardScore : null,
      mediumCost: line.delta ? line.delta.mediumScore : null,
    };
  });
}

/** What the placement write reads besides the rules: the clock, and the locks on the people. */
export interface PlacementContext {
  /** The seat's timeslot. */
  creneauId: number;
  /** The seat's timeslot has started under the frozen past: no seat of it is written by hand. */
  seatStarted: boolean;
  locks: readonly VerrouillagePlanning[];
}

/**
 * Why an available line still gets no « Placer »: the write refuses it for a
 * reason the rules do not carry. `started` — the timeslot has begun, and the
 * past is not rewritten by hand (ADR 0044); `locked` — a lock on the person
 * forbids them a new seat then: their whole schedule (`ANIMATEUR`), or this
 * very timeslot (`ANIMATEUR_CRENEAU`, which an accepted échange or « Libérer »
 * lays to keep them off it). Null when nothing does.
 */
export type PlacementBlock = 'started' | 'locked';

export function placementBlock(line: BenchLine, context: PlacementContext): PlacementBlock | null {
  if (context.seatStarted) {
    return 'started';
  }
  const locked = context.locks.some(
    (lock) =>
      lock.animateurId === line.animateurId &&
      (lock.type === 'ANIMATEUR' ||
        (lock.type === 'ANIMATEUR_CRENEAU' && lock.creneauId === context.creneauId)),
  );
  return locked ? 'locked' : null;
}

/**
 * Only an available line is offered « Placer », and only when the write would
 * take it: the server refuses a seating that breaks a hard rule — a line
 * « sous réserve » strains one — and one {@link placementBlock} names. The
 * click would only meet the refusal.
 */
export function placeable(line: BenchLine, context: PlacementContext): boolean {
  return line.state === 'disponible' && placementBlock(line, context) === null;
}

/**
 * The lines shown before « Voir les autres »: every line somebody could take
 * the seat from. An edition holds a hundred people off duty who cannot, and
 * listing them in the same breath buried the three who can.
 */
export function splitLines(lines: readonly BenchLine[]): {
  shown: BenchLine[];
  folded: BenchLine[];
} {
  return {
    shown: lines.filter((line) => line.state !== 'impossible'),
    folded: lines.filter((line) => line.state === 'impossible'),
  };
}
