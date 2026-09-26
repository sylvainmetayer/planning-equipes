// The pure half of the fiche 360° of one animateur: how each section reads the
// payload the server assembled. No Angular beyond `$localize`, so every rule
// here is tested without rendering — and none of them computes a figure the
// server already gave: they choose, order and word.

import {
  Animateur,
  AnimateurProfile,
  LegalRegime,
  NiveauCompetence,
  ProfileAdjustment,
  ProfileSeat,
  StatutConfirmation,
  TypeContrainteAdHoc,
  TypologieItem,
} from '../../core/models';

/** Wording of a legal regime, age included. */
export function regimeLabel(regime: LegalRegime): string {
  const age = regime.age;
  switch (regime.regime) {
    case 'MOINS_DE_16':
      return $localize`:@@fiche.regime.moinsDe16:Mineur de moins de 16 ans (${age}:age: ans)`;
    case 'MINEUR':
      return $localize`:@@fiche.regime.mineur:Mineur de 16 à 18 ans (${age}:age: ans)`;
    case 'MAJEUR':
      return $localize`:@@fiche.regime.majeur:Majeur (${age}:age: ans)`;
  }
}

/** True when the regime on the first event day is not the one on the last: a birthday falls during the event. */
export function regimeChanges(profile: AnimateurProfile): boolean {
  return (
    profile.regimeDebut !== null &&
    profile.regimeFin !== null &&
    profile.regimeDebut.regime !== profile.regimeFin.regime
  );
}

/** One day of the event on the availability strip. */
export interface AvailabilityDay {
  date: string;
  /** Declared off on the fiche. */
  declaredOff: boolean;
  /** Ruled out by an « indisponibilité forcée » adjustment on a timeslot of that day. */
  forcedOff: boolean;
}

/**
 * The event days, each with what rules the person out on it. An adjustment
 * without a timeslot covers the whole event, and says so elsewhere: it is not
 * a mark on one day.
 */
export function availabilityStrip(profile: AnimateurProfile): AvailabilityDay[] {
  const declared = new Set(profile.animateur.joursIndisponibles ?? []);
  const forced = new Set(
    profile.ajustements
      .filter((ajustement) => ajustement.type === 'INDISPONIBILITE_FORCEE' && ajustement.date)
      .map((ajustement) => ajustement.date as string),
  );
  return profile.joursEvenement.map((date) => ({
    date,
    declaredOff: declared.has(date),
    forcedOff: forced.has(date),
  }));
}

/** Declared off days that fall outside the event days: kept on the fiche, never read by a solve. */
export function daysOffOutsideEvent(profile: AnimateurProfile): string[] {
  const jours = new Set(profile.joursEvenement);
  return [...(profile.animateur.joursIndisponibles ?? [])]
    .filter((date) => !jours.has(date))
    .sort((left, right) => left.localeCompare(right));
}

/** One game category of the fiche: the appreciation and the wish side by side. */
export interface CompetenceRow {
  typologieId: string;
  label: string;
  niveau: NiveauCompetence | null;
  wished: boolean;
  /** Wished without any appreciation: what the solver can only honour with a quality penalty. */
  wishedNotAppreciated: boolean;
}

/**
 * Every game category the person is appreciated on or wishes, in label order —
 * the unappreciated wishes are the point of putting both lists side by side.
 */
export function competenceRows(
  animateur: Animateur,
  typologies: readonly TypologieItem[],
): CompetenceRow[] {
  const labels = new Map(typologies.map((typologie) => [typologie.id, typologie.label]));
  const competences = animateur.competences ?? {};
  const wishes = new Set(animateur.souhaits ?? []);
  const ids = new Set([...Object.keys(competences), ...wishes]);
  return [...ids]
    .map((typologieId) => {
      const niveau = competences[typologieId] ?? null;
      const wished = wishes.has(typologieId);
      return {
        typologieId,
        label: labels.get(typologieId) || typologieId,
        niveau,
        wished,
        wishedNotAppreciated: wished && niveau === null,
      };
    })
    .sort((left, right) => left.label.localeCompare(right.label));
}

export function niveauLabel(niveau: NiveauCompetence | null): string {
  switch (niveau) {
    case 'DEBUTANT':
      return $localize`:@@competences.niveau.debutant:Débutant`;
    case 'AUTONOME':
      return $localize`:@@competences.niveau.autonome:Autonome`;
    case 'REFERENT':
      return $localize`:@@competences.niveau.referent:Référent`;
    default:
      return $localize`:@@competences.niveau.aucun:Aucune appréciation`;
  }
}

export function adjustmentTypeLabel(type: TypeContrainteAdHoc): string {
  switch (type) {
    case 'INDISPONIBILITE_FORCEE':
      return $localize`:@@adHoc.type.indisponibiliteForcee:Indisponibilité forcée`;
    case 'INCOMPATIBILITE':
      return $localize`:@@adHoc.type.incompatibilite:Incompatibilité`;
    case 'AFFECTATION_FORCEE':
      return $localize`:@@adHoc.type.affectationForcee:Affectation forcée`;
    case 'AFFINITE':
      return $localize`:@@adHoc.type.affinite:Affinité (paire à privilégier)`;
    case 'ARRIVEE_GROUPEE':
      return $localize`:@@adHoc.type.arriveeGroupee:Arrivée groupée (covoiturage, 2 à 4 animateurs)`;
  }
}

/** Where an adjustment applies, in one line: the stand, the timeslot, or the whole event. */
export function adjustmentScope(ajustement: ProfileAdjustment): string {
  const parts: string[] = [];
  if (ajustement.standNom || ajustement.standId) {
    parts.push((ajustement.standNom || ajustement.standId) as string);
  }
  if (ajustement.date) {
    parts.push(
      `${ajustement.date} ${shortTime(ajustement.heureDebut)}→${shortTime(ajustement.heureFin)}`,
    );
  }
  return parts.length > 0
    ? parts.join(' · ')
    : $localize`:@@fiche.ajustement.toutEvenement:Tout l'événement`;
}

export function confirmationLabel(statut: StatutConfirmation): string {
  switch (statut) {
    case 'NON_VU':
      return $localize`:@@animateurs.confirmation.nonVu:Silencieux`;
    case 'CONFIRME':
      return $localize`:@@animateurs.confirmation.confirme:Confirmé`;
    case 'RELANCE':
      return $localize`:@@animateurs.confirmation.relance:Relancé`;
  }
}

/** `10:00:00` → `10:00`; nothing for nothing. */
export function shortTime(value: string | null | undefined): string {
  return value ? value.slice(0, 5) : '';
}

/** How many seats are still ahead, the ones already started being listed dimmed. */
export function upcomingCount(profile: AnimateurProfile): number {
  return profile.affectations.filter((seat) => !seat.passe).length;
}

/** The foldable sections of the fiche, in page order; `?section=` names one of them. */
export type FicheSection =
  'identite' | 'disponibilites' | 'timeline' | 'equite' | 'competences' | 'fragilite' | 'suivi';

const SECTIONS: readonly FicheSection[] = [
  'identite',
  'disponibilites',
  'timeline',
  'equite',
  'competences',
  'fragilite',
  'suivi',
];

/** Open on arrival: who, when, and where — the three questions a fiche is opened for. */
export const OPEN_BY_DEFAULT: readonly FicheSection[] = ['identite', 'disponibilites', 'timeline'];

/** The section a `?section=` or a `#fragment` names, or `null` for anything else. */
export function readSection(value: string | null | undefined): FicheSection | null {
  return SECTIONS.find((section) => section === value) ?? null;
}

/** The sections open on arrival: the three defaults, plus the one the address names. */
export function initialSections(named: FicheSection | null): Set<FicheSection> {
  const open = new Set<FicheSection>(OPEN_BY_DEFAULT);
  if (named) {
    open.add(named);
  }
  return open;
}

/** The person's seats on one day of the persisted plan, in time order as the server sent them. */
export function seatsOnDay(profile: AnimateurProfile, date: string): ProfileSeat[] {
  return profile.affectations.filter((seat) => seat.date === date);
}

/** « Stand 01 18:00–22:00 »: a seat in the words of the strip's panel. */
export function seatLabel(seat: {
  standNom: string | null;
  standId: string | null;
  heureDebut: string | null;
  heureFin: string | null;
}): string {
  const stand = seat.standNom || seat.standId || '';
  return `${stand} ${shortTime(seat.heureDebut)}–${shortTime(seat.heureFin)}`.trim();
}

/** One line of the inline competence editor: the level, and the wish. */
export interface CompetenceDraft {
  typologieId: string;
  niveau: NiveauCompetence | null;
  wished: boolean;
}

/** The editor's starting lines: every category the person is appreciated on or wishes. */
export function competenceDrafts(rows: readonly CompetenceRow[]): CompetenceDraft[] {
  return rows.map((row) => ({
    typologieId: row.typologieId,
    niveau: row.niveau,
    wished: row.wished,
  }));
}

/** What the lines say, as the fiche's two fields; a line with neither level nor wish drops out. */
export function applyCompetenceDrafts(drafts: readonly CompetenceDraft[]): {
  competences: Record<string, NiveauCompetence>;
  souhaits: string[];
} {
  const competences: Record<string, NiveauCompetence> = {};
  const souhaits: string[] = [];
  for (const draft of drafts) {
    if (draft.niveau) {
      competences[draft.typologieId] = draft.niveau;
    }
    if (draft.wished) {
      souhaits.push(draft.typologieId);
    }
  }
  return { competences, souhaits };
}

/** True when the lines no longer say what the fiche holds. */
export function competencesChanged(
  animateur: Animateur,
  drafts: readonly CompetenceDraft[],
): boolean {
  const { competences, souhaits } = applyCompetenceDrafts(drafts);
  const before = animateur.competences ?? {};
  const beforeWishes = new Set(animateur.souhaits ?? []);
  const keys = new Set([...Object.keys(before), ...Object.keys(competences)]);
  return (
    [...keys].some((key) => (before[key] ?? null) !== (competences[key] ?? null)) ||
    souhaits.length !== beforeWishes.size ||
    souhaits.some((id) => !beforeWishes.has(id))
  );
}

/** What the inline editor's lines are rebuilt from: the person, their fiche as read, and its lines. */
export interface CompetenceSource {
  animateurId: string;
  animateur: Animateur | null;
  drafts: CompetenceDraft[];
}

/** True when two sets of lines say the same thing, line for line. */
function sameDrafts(left: readonly CompetenceDraft[], right: readonly CompetenceDraft[]): boolean {
  return (
    left.length === right.length &&
    left.every(
      (line, index) =>
        line.typologieId === right[index].typologieId &&
        line.niveau === right[index].niveau &&
        line.wished === right[index].wished,
    )
  );
}

/**
 * The editor's lines after the fiche was read again: the lines as edited are
 * carried over while they hold something the fiche does not — an edit not yet
 * saved, which a reload caused by another section must not wipe —, and the
 * fiche's lines are taken otherwise: nothing was edited, the edit was just
 * saved (the fiche now says the same), or the fiche is somebody else's.
 */
export function carriedDrafts(
  source: CompetenceSource,
  previous: { source: CompetenceSource; value: CompetenceDraft[] } | undefined,
): CompetenceDraft[] {
  if (
    previous &&
    previous.source.animateurId === source.animateurId &&
    source.animateur !== null &&
    !sameDrafts(previous.value, previous.source.drafts) &&
    competencesChanged(source.animateur, previous.value)
  ) {
    return previous.value;
  }
  return source.drafts;
}
