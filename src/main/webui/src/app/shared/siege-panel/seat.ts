// The pure half of the Siège panel: which seat a click or an address names,
// which locks hold it, what a rule is called on screen, and what is left to do
// after a gesture. The component holds the signals and the calls; this file
// holds what they mean, and is what the unit tests read.

import {
  ConstraintView,
  HardMediumSoftScore,
  NiveauContrainte,
  PosteAffectation,
  VerrouillagePlanning,
} from '../../core/models';

/**
 * What names a seat. A cell of the calendar or a block of the rail names it
 * outright; the map, the breaks and the changes name a stand at a time; an
 * old bench address names a timeslot, and a stand when it had one.
 */
export type SeatRequest =
  | { posteId: string }
  | { creneauId: number; standId?: string | null; animateurId?: string | null }
  | {
      date: string;
      standId: string;
      heureDebut: string;
      heureFin?: string | null;
      animateurId?: string | null;
    };

/** `HH:mm`, whatever precision the API sent. */
export function shortTime(value: string | null | undefined): string {
  return (value ?? '').slice(0, 5);
}

/** The hours the seat is actually held: its own window when a partial closure narrowed it. */
export function seatHours(poste: PosteAffectation): { start: string; end: string } {
  return {
    start: shortTime(poste.heureDebutEffective ?? poste.creneau?.heureDebut),
    end: shortTime(poste.heureFinEffective ?? poste.creneau?.heureFin),
  };
}

const byId = (left: PosteAffectation, right: PosteAffectation): number =>
  left.id.localeCompare(right.id, undefined, { numeric: true });

/**
 * The seat among `candidates` a stand-level request means: the named person's,
 * else the first free one — the question « qui peut tenir ce siège ? » is
 * about a hole — else the first one. Ordered by id so the same address opens
 * the same seat twice, as the server's bench does.
 */
function pickSeat(
  candidates: readonly PosteAffectation[],
  animateurId: string | null | undefined,
): PosteAffectation | null {
  if (animateurId) {
    const held = candidates.find((poste) => poste.animateur?.id === animateurId);
    if (held) {
      return held;
    }
  }
  const sorted = [...candidates].sort(byId);
  return sorted.find((poste) => !poste.animateur) ?? sorted[0] ?? null;
}

/** The seat a request names in the plan on screen; null when the plan holds none. */
export function resolveSeat(
  postes: readonly PosteAffectation[],
  request: SeatRequest,
): PosteAffectation | null {
  if ('posteId' in request) {
    return postes.find((poste) => poste.id === request.posteId) ?? null;
  }
  if ('creneauId' in request) {
    return pickSeat(
      postes.filter(
        (poste) =>
          poste.creneau?.id === request.creneauId &&
          (!request.standId || poste.stand?.id === request.standId),
      ),
      request.animateurId,
    );
  }
  return pickSeat(
    postes.filter((poste) => {
      if (poste.creneau?.date !== request.date || poste.stand?.id !== request.standId) {
        return false;
      }
      const { start, end } = seatHours(poste);
      return (
        start === shortTime(request.heureDebut) &&
        (!request.heureFin || end === shortTime(request.heureFin))
      );
    }),
    request.animateurId,
  );
}

/** How much a lock holding this seat freezes — what the panel says, and whether it can undo it here. */
export type LockReach = 'seat' | 'person' | 'stand' | 'creneau' | 'day';

export interface SeatLock {
  lock: VerrouillagePlanning;
  reach: LockReach;
  /**
   * Only the lock on this very seat — this person on this timeslot — is lifted
   * from the panel. The others freeze a whole stand, day, timeslot or
   * person's schedule: lifting one from a single seat would unfreeze far more
   * than the reader is looking at, so they are lifted on the Verrouillages
   * screen, where their whole reach is shown.
   */
  removable: boolean;
}

function reachOf(lock: VerrouillagePlanning, poste: PosteAffectation): LockReach | null {
  const holder = poste.animateur?.id ?? null;
  const creneau = poste.creneau;
  switch (lock.type) {
    case 'ANIMATEUR_CRENEAU':
      return holder !== null &&
        lock.animateurId === holder &&
        creneau !== null &&
        lock.creneauId === creneau.id
        ? 'seat'
        : null;
    case 'ANIMATEUR':
      return holder !== null && lock.animateurId === holder ? 'person' : null;
    case 'STAND':
      return poste.stand !== null && lock.standId === poste.stand.id ? 'stand' : null;
    case 'CRENEAU':
      return creneau !== null && lock.creneauId === creneau.id ? 'creneau' : null;
    case 'JOUR':
      return creneau?.date && lock.jour === creneau.date ? 'day' : null;
    default:
      return null;
  }
}

/** Every lock the next solve applies to this seat, the one on the seat itself first. */
export function seatLocks(
  locks: readonly VerrouillagePlanning[],
  poste: PosteAffectation,
): SeatLock[] {
  const result: SeatLock[] = [];
  for (const lock of locks) {
    const reach = reachOf(lock, poste);
    if (reach) {
      result.push({ lock, reach, removable: reach === 'seat' });
    }
  }
  return result.sort((left, right) => Number(right.removable) - Number(left.removable));
}

/** One sentence per lock, in the words of the reach it has. */
export function lockLabel(lock: SeatLock): string {
  switch (lock.reach) {
    case 'seat':
      return $localize`:@@siege.verrou.siege:Verrouillé : cette personne reste sur ce créneau au prochain calcul.`;
    case 'person':
      return $localize`:@@siege.verrou.personne:Tout l'emploi du temps de cette personne est verrouillé.`;
    case 'stand':
      return $localize`:@@siege.verrou.stand:Tout le stand est verrouillé.`;
    case 'creneau':
      return $localize`:@@siege.verrou.creneau:Tout le créneau est verrouillé.`;
    default:
      return $localize`:@@siege.verrou.jour:Toute la journée est verrouillée.`;
  }
}

/**
 * What a rule is called on screen: its short label from the catalogue, else
 * the description the answer carried, else its name taken apart. Never the
 * Java identifier as it stands — `equilibrerCharge` tells an organiser
 * nothing.
 */
export function ruleLabel(
  name: string,
  catalogue: ReadonlyMap<string, ConstraintView> | null,
  description: string | null = null,
): string {
  const rule = catalogue?.get(name);
  return rule?.libelleCourt || description || rule?.description || readableName(name);
}

/** `equilibrerCharge` → `equilibrer charge`: the last resort when the catalogue does not know the rule. */
export function readableName(name: string): string {
  return name
    .replace(/([a-z0-9])([A-Z])/g, '$1 $2')
    .replace(/[_-]+/g, ' ')
    .toLowerCase()
    .trim();
}

/** The catalogue indexed by rule name, what {@link ruleLabel} reads. */
export function catalogueByName(
  contraintes: readonly ConstraintView[],
): Map<string, ConstraintView> {
  return new Map(contraintes.map((contrainte) => [contrainte.name, contrainte]));
}

/** A rule's level in words, never HARD / MEDIUM / SOFT. */
export function levelLabel(niveau: NiveauContrainte | null): string {
  switch (niveau) {
    case 'HARD':
      return $localize`:@@siege.niveau.dure:règle dure`;
    case 'MEDIUM':
      return $localize`:@@siege.niveau.moyenne:qualité`;
    case 'SOFT':
      return $localize`:@@siege.niveau.souple:préférence`;
    default:
      return '';
  }
}

/** What the panel did last, and therefore what it proposes next. */
export type SeatGesture = 'place' | 'replace' | 'move' | 'free' | 'lock' | 'unlock';

export interface NextSteps {
  /** The plan now differs from what the animateurs were sent: « non publié : Prévenir ». */
  notify: boolean;
  /** The gesture left a seat empty: « Corriger le reste » (incremental solve). */
  repair: boolean;
}

/**
 * A lock changes what the next solve may do, not the plan the animateurs
 * read: nothing to announce. Every other gesture rewrote a seat. Only a
 * gesture that emptied one has a rest to correct.
 */
export function nextSteps(gesture: SeatGesture, holeOpened: boolean): NextSteps {
  const written = gesture !== 'lock' && gesture !== 'unlock';
  return { notify: written, repair: written && holeOpened };
}

/**
 * The warning a placement carries when it was accepted but costs quality: the
 * medium level lost points. Null when it did not.
 */
export function qualityWarning(delta: HardMediumSoftScore | null | undefined): string | null {
  if (!delta || delta.mediumScore >= 0) {
    return null;
  }
  const points = -delta.mediumScore;
  return $localize`:@@siege.avertissement.qualite:Accepté, mais la qualité d'organisation perd ${points}:points: point(s) : une règle de qualité est moins bien tenue.`;
}

/**
 * What a gesture did to the plan's score, in words: the levels that moved,
 * signed, and nothing when none did. `+1 règle dure` is a hole filled.
 */
export function scoreEffect(delta: HardMediumSoftScore): string {
  const signed = (value: number): string => (value > 0 ? `+${value}` : `${value}`);
  const parts: string[] = [];
  if (delta.hardScore !== 0) {
    const value = signed(delta.hardScore);
    parts.push($localize`:@@siege.effet.dur:${value}:value: règle dure`);
  }
  if (delta.mediumScore !== 0) {
    const value = signed(delta.mediumScore);
    parts.push($localize`:@@siege.effet.moyen:${value}:value: qualité`);
  }
  if (delta.softScore !== 0) {
    const value = signed(delta.softScore);
    parts.push($localize`:@@siege.effet.souple:${value}:value: préférences`);
  }
  return parts.length > 0
    ? parts.join(' · ')
    : $localize`:@@siege.effet.aucun:sans effet sur le score`;
}
