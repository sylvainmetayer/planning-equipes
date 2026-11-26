// Builders of the day "rail" (issue #305): one line per animateur of the
// edition, time on the x axis, vacations as positioned blocks — the dual of
// `calendar-day-page.ts`, which is organised by stand.
//
// Pure functions, kept out of the component so the geometry and the wording of
// a line are unit-tested without rendering 150 rows.

import {
  Animateur,
  WalkView,
  GroupView,
  PauseDueView,
  PosteAffectation,
  GroupedArrivalReport,
  WalkSequenceReport,
  RapportPauses,
} from '../../core/models';
import {
  IndexPauses,
  indexerPauses,
  pausesDe,
  SegmentPause,
  segmentsPause,
} from '../../core/pauses-index';
import {
  endMinutesOfDay,
  formatDuration,
  formatHeure,
  formatHourTick,
  minutesOfDay,
} from '../../core/time-of-day';
import { indexWalks, WalkSegment, walkSegments, WalksIndex, walksOf } from '../../core/walks-index';
import {
  standTypologies,
  typologieColorClass,
  typologiePrincipale,
} from '../../core/typologie-colors';

/**
 * Why a line is empty, which is the whole point of showing empty lines: on a
 * day of tension, `libre` is the list of people who can still be called, and
 * `indisponible` is the list of people who cannot.
 */
export type RailStatut = 'affecte' | 'libre' | 'indisponible';

/** One vacation of one animateur, positioned within the day's rail. */
export interface RailBloc {
  posteId: string;
  standId: string;
  standNom: string;
  heureDebut: string;
  heureFin: string;
  /** Position within the day's rail, as a 0-100 percentage of its whole-hour span. */
  offsetPercent: number;
  widthPercent: number;
  /** Main typologie of the stand, null when it proposes none — drives the colour and the legend. */
  typologie: string | null;
  /** Palette class of {@link typologie} — same colouring as the timeline and the heatmap. */
  colorClass: string;
  /** True when this block starts before the previous one of the same line ends. */
  chevauchement: boolean;
  /** `Stand · 09:00 – 12:00`, shown on hover and folded into the line's summary. */
  label: string;
}

/** One animateur, one day. Present even with no block at all — that is the information. */
export interface RailLigne {
  animateurId: string;
  nom: string;
  statut: RailStatut;
  blocs: RailBloc[];
  /** Sum of the blocks' durations, formatted; empty when there is none. */
  dureeLabel: string;
  /** First start / last end of the day, null on an empty line. */
  amplitudeDebut: string | null;
  amplitudeFin: string | null;
  /** True when any block overlaps the previous one: the animateur is in two places at once. */
  chevauchement: boolean;
  /** The legal breaks of the day, as the rotation places them, drawn over the vacations. */
  pauses: SegmentPause[];
  /** Tight walks between two consecutive vacations, drawn as a chevron in the gap. */
  trajets: WalkSegment[];
  /** The animateur rides in a grouped arrival (covoiturage); `null` otherwise. */
  covoiturage: RailCovoiturage | null;
  /** What a screen reader reads for the whole line — the rail itself is decorative. */
  resume: string;
}

/** A member of a grouped arrival, on one day: who they ride with, and whether the car holds. */
export interface RailCovoiturage {
  /** True when a member works this day without the others, or too far from their hours. */
  desaligne: boolean;
  /** « Covoiturage avec Ines, Oscar — horaires alignés ». */
  label: string;
}

/** A whole-hour tick of the rail's scale. */
export interface RailHeure {
  /** Minutes since midnight, also the `@for` track key. */
  minutes: number;
  label: string;
  offsetPercent: number;
}

export interface RailJour {
  jour: number;
  date: string | null;
  title: string;
  /** Whole-hour bounds the blocks are positioned against. */
  debutMinutes: number;
  finMinutes: number;
  heures: RailHeure[];
  lignes: RailLigne[];
}

/** Display label of an animateur, disambiguated by id when two share a name. */
function libelles(animateurs: Animateur[]): Map<string, string> {
  const bruts = new Map<string, string>();
  animateurs.forEach((animateur) =>
    bruts.set(
      animateur.id,
      `${animateur.prenom ?? ''} ${animateur.nom ?? ''}`.trim() || animateur.id,
    ),
  );
  const compte = new Map<string, number>();
  bruts.forEach((label) => compte.set(label, (compte.get(label) ?? 0) + 1));
  const libelle = new Map<string, string>();
  bruts.forEach((label, id) =>
    libelle.set(id, (compte.get(label) ?? 0) > 1 ? `${label} (${id})` : label),
  );
  return libelle;
}

/**
 * Every animateur the rail has a line for: the edition's referential first —
 * an animateur with no seat at all still has to appear — plus, defensively,
 * anyone holding a seat who is missing from it.
 */
function tousLesAnimateurs(postes: PosteAffectation[], animateurs: Animateur[]): Animateur[] {
  const connus = new Map(animateurs.map((animateur) => [animateur.id, animateur]));
  postes.forEach((poste) => {
    if (poste.animateur && !connus.has(poste.animateur.id)) {
      connus.set(poste.animateur.id, poste.animateur);
    }
  });
  return Array.from(connus.values());
}

interface Span {
  posteId: string;
  standId: string;
  standNom: string;
  typologie: string | null;
  colorClass: string;
  heureDebut: string;
  heureFin: string;
  debutMinutes: number;
  finMinutes: number;
}

/** What the percentages of one day's rail are measured against. */
interface Echelle {
  /** Whole-hour start of the rail. */
  debutMinutes: number;
  amplitude: number;
}

function spanDuPoste(poste: PosteAffectation): Span {
  const creneau = poste.creneau!;
  // The poste's own window when a partial stand closure (issue #60) narrowed
  // it, the créneau's hours otherwise — same rule as the day calendar.
  const heureDebut = poste.heureDebutEffective ?? creneau.heureDebut;
  const heureFin = poste.heureFinEffective ?? creneau.heureFin;
  const standNom = poste.stand?.nom || poste.stand?.id || '';
  const typologie = typologiePrincipale(standTypologies(poste.stand));
  return {
    posteId: poste.id,
    standId: poste.stand?.id ?? '',
    standNom,
    typologie,
    colorClass: typologieColorClass(typologie),
    // Trimmed once here: every label downstream — block, summary, day span —
    // reads the same `09:00` rather than the API's `09:00:00`.
    heureDebut: formatHeure(heureDebut),
    heureFin: formatHeure(heureFin),
    debutMinutes: minutesOfDay(heureDebut),
    finMinutes: endMinutesOfDay(heureFin),
  };
}

/** Everything one event day contributes to its rail, gathered in a single pass over the postes. */
interface ContenuJour {
  date: string | null;
  spans: Span[];
  spansParAnimateur: Map<string, Span[]>;
}

/**
 * One entry per event day found in the plan, chronologically. `animateurs` is
 * the edition's referential: every one of them gets a line on every day,
 * assigned or not.
 */
export function buildRailJours(
  postes: PosteAffectation[],
  animateurs: Animateur[],
  pauses: RapportPauses | null = null,
  walks: WalkSequenceReport | null = null,
  groupedArrivals: GroupedArrivalReport | null = null,
): RailJour[] {
  const indexPauses = indexerPauses(pauses);
  const walksIndex = indexWalks(walks);
  const groupsByMember = new Map<string, GroupView[]>();
  (groupedArrivals?.groups ?? []).forEach((groupe) =>
    groupe.animateurIds.forEach((id) =>
      groupsByMember.set(id, [...(groupsByMember.get(id) ?? []), groupe]),
    ),
  );
  const jours = new Map<number, ContenuJour>();
  postes.forEach((poste) => {
    const creneau = poste.creneau;
    if (!creneau) {
      return;
    }
    let jour = jours.get(creneau.jour);
    if (!jour) {
      jour = {
        date: creneau.date ?? null,
        spansParAnimateur: new Map(),
        spans: [],
      };
      jours.set(creneau.jour, jour);
    }
    const span = spanDuPoste(poste);
    // Unfilled seats still widen the rail: an unstaffed 08:00 opening is part
    // of the day the reader is looking at.
    jour.spans.push(span);
    if (poste.animateur) {
      const spans = jour.spansParAnimateur.get(poste.animateur.id) ?? [];
      spans.push(span);
      jour.spansParAnimateur.set(poste.animateur.id, spans);
    }
  });

  const effectif = tousLesAnimateurs(postes, animateurs);
  const noms = libelles(effectif);

  return Array.from(jours.entries())
    .sort((left, right) => left[0] - right[0])
    .map(([jour, contenu]) =>
      buildRailJour(jour, contenu, {
        indexPauses,
        walksIndex,
        groupsByMember,
        animateurs: effectif,
        noms,
      }),
    );
}

/** What every day of the rail reads besides its own seats: the event-wide indexes. */
interface RailSources {
  indexPauses: IndexPauses;
  walksIndex: WalksIndex;
  groupsByMember: Map<string, GroupView[]>;
  animateurs: Animateur[];
  noms: Map<string, string>;
}

function buildRailJour(jour: number, contenu: ContenuJour, sources: RailSources): RailJour {
  const { indexPauses, walksIndex, groupsByMember, animateurs, noms } = sources;
  const { date, spans: spansDuJour, spansParAnimateur } = contenu;
  const premier = spansDuJour.reduce(
    (tot, span) => (span.debutMinutes < tot.debutMinutes ? span : tot),
    spansDuJour[0],
  );
  const dernier = spansDuJour.reduce(
    (tard, span) => (span.finMinutes > tard.finMinutes ? span : tard),
    spansDuJour[0],
  );
  // Rounded outwards to whole hours so the scale's ticks are evenly spaced and
  // the gridlines can be drawn by one repeating background.
  const debutMinutes = Math.floor(premier.debutMinutes / 60) * 60;
  const finMinutes = Math.max(Math.ceil(dernier.finMinutes / 60) * 60, debutMinutes + 60);
  const amplitude = finMinutes - debutMinutes;
  const echelle: Echelle = { debutMinutes, amplitude };

  const heures: RailHeure[] = [];
  for (let minutes = debutMinutes; minutes <= finMinutes; minutes += 60) {
    heures.push({
      minutes,
      label: formatHourTick(minutes / 60),
      offsetPercent: ((minutes - debutMinutes) / amplitude) * 100,
    });
  }

  const lignes = animateurs
    .map((animateur) =>
      buildRailLigne(
        animateur,
        noms.get(animateur.id) ?? animateur.id,
        date,
        spansParAnimateur.get(animateur.id) ?? [],
        echelle,
        {
          pausesDuJour: pausesDe(indexPauses, date, animateur.id),
          walks: walksOf(walksIndex, date, animateur.id),
          covoiturage: carpoolOn(groupsByMember.get(animateur.id) ?? [], animateur.id, date, noms),
        },
      ),
    )
    .sort((left, right) => left.nom.localeCompare(right.nom));

  return {
    jour,
    date,
    title: date
      ? $localize`:@@calendarDay.dayTitleWithDate:Jour ${jour}:jour: — ${date}:date:`
      : $localize`:@@calendarDay.dayTitle:Jour ${jour}:jour:`,
    debutMinutes,
    finMinutes,
    heures,
    lignes,
  };
}

/** One person's day besides their seats: the breaks due, the walks, the shared ride. */
interface RailLigneExtras {
  pausesDuJour: PauseDueView[];
  walks: WalkView[];
  covoiturage: RailCovoiturage | null;
}

function buildRailLigne(
  animateur: Animateur,
  nom: string,
  date: string | null,
  spans: Span[],
  echelle: Echelle,
  extras: RailLigneExtras,
): RailLigne {
  const { pausesDuJour, walks, covoiturage } = extras;
  const { debutMinutes, amplitude } = echelle;
  const pauses = segmentsPause(pausesDuJour, debutMinutes, amplitude);
  const ordonnes = [...spans].sort((left, right) => left.debutMinutes - right.debutMinutes);
  let finPrecedente = -1;
  const blocs: RailBloc[] = ordonnes.map((span) => {
    const overlap = span.debutMinutes < finPrecedente;
    finPrecedente = Math.max(finPrecedente, span.finMinutes);
    return {
      posteId: span.posteId,
      standId: span.standId,
      standNom: span.standNom,
      heureDebut: span.heureDebut,
      heureFin: span.heureFin,
      offsetPercent: ((span.debutMinutes - debutMinutes) / amplitude) * 100,
      widthPercent: ((span.finMinutes - span.debutMinutes) / amplitude) * 100,
      typologie: span.typologie,
      colorClass: span.colorClass,
      chevauchement: overlap,
      label: `${span.standNom} · ${span.heureDebut} – ${span.heureFin}`,
    };
  });

  const declareIndisponible = date !== null && (animateur.joursIndisponibles ?? []).includes(date);
  let statut: RailStatut = declareIndisponible ? 'indisponible' : 'libre';
  if (blocs.length > 0) {
    statut = 'affecte';
  }
  if (blocs.length === 0) {
    let base = $localize`:@@railJour.resume.libre:${nom}:animateur: — aucune vacation ce jour-là, mobilisable`;
    if (declareIndisponible) {
      base = $localize`:@@railJour.resume.indisponible:${nom}:animateur: — déclaré indisponible ce jour-là`;
    }
    return {
      animateurId: animateur.id,
      nom,
      statut,
      blocs,
      dureeLabel: '',
      amplitudeDebut: null,
      amplitudeFin: null,
      chevauchement: false,
      pauses: [],
      trajets: [],
      covoiturage,
      resume: base,
    };
  }

  const minutesTravaillees = ordonnes.reduce(
    (total, span) => total + (span.finMinutes - span.debutMinutes),
    0,
  );
  const dureeLabel = formatDuration(minutesTravaillees);
  const amplitudeDebut = ordonnes[0].heureDebut;
  const amplitudeFin = ordonnes.reduce(
    (last, span) => (span.finMinutes >= last.finMinutes ? span : last),
    ordonnes[0],
  ).heureFin;
  const detail = blocs.map((bloc) => bloc.label).join(' ; ');
  const count = blocs.length;
  const overlap = blocs.some((bloc) => bloc.chevauchement);
  const base = $localize`:@@railJour.resume.affecte:${nom}:animateur: — ${count}:count: vacation(s), ${dureeLabel}:duree:, de ${amplitudeDebut}:debut: à ${amplitudeFin}:fin: : ${detail}:detail:`;

  return {
    animateurId: animateur.id,
    nom,
    statut,
    blocs,
    dureeLabel,
    amplitudeDebut,
    amplitudeFin,
    chevauchement: overlap,
    // The red outline and the warning icon are visual only; a line read out
    // loud must say the one anomaly this view exists to make visible.
    pauses,
    trajets: walkSegments(walks, echelle.debutMinutes, echelle.amplitude),
    covoiturage,
    resume: mentionCarpool(
      mentionWalks(mentionnerPauses(mentionOverlap(base, overlap), pauses), walks),
      covoiturage,
    ),
  };
}

/**
 * The covoiturage of one member on one day, read from the server's report:
 * misaligned when that day of one of their groups is.
 */
function carpoolOn(
  groupes: GroupView[],
  animateurId: string,
  date: string | null,
  noms: Map<string, string>,
): RailCovoiturage | null {
  if (groupes.length === 0) {
    return null;
  }
  const coequipiers = [
    ...new Set(groupes.flatMap((groupe) => groupe.animateurIds).filter((id) => id !== animateurId)),
  ]
    .map((id) => noms.get(id) ?? id)
    .join(', ');
  const desaligne = groupes.some((groupe) =>
    groupe.days.some((jour) => jour.date === date && !jour.aligned),
  );
  return {
    desaligne,
    label: desaligne
      ? $localize`:@@railJour.covoiturage.desaligne:Covoiturage avec ${coequipiers}:coequipiers: — désaligné ce jour`
      : $localize`:@@railJour.covoiturage.aligne:Covoiturage avec ${coequipiers}:coequipiers:`,
  };
}

/** Same, for a car that does not hold this day. */
function mentionCarpool(base: string, covoiturage: RailCovoiturage | null): string {
  if (!covoiturage?.desaligne) {
    return base;
  }
  return $localize`:@@railJour.resume.covoiturage:${base}:ligne: — ${covoiturage.label}:covoiturage:`;
}

/** Same, for the tight walks the chevron alone would only show. */
function mentionWalks(base: string, walks: WalkView[]): string {
  const tight = walks.filter((walk) => walk.missingMinutes > 0).length;
  if (tight === 0) {
    return base;
  }
  return $localize`:@@railJour.resume.trajets:${base}:ligne: — ${tight}:count: trajet(s) trop court(s) entre deux vacations`;
}

/** Same, for the breaks — a relay-less one is the thing to hear first. */
function mentionnerPauses(base: string, pauses: SegmentPause[]): string {
  if (pauses.length === 0) {
    return base;
  }
  const liste = pauses.map((pause) => pause.label).join(' ; ');
  return $localize`:@@railJour.resume.pauses:${base}:ligne: — ${liste}:pauses:`;
}

/** Same, for the overlap the outline alone would only tell a sighted reader. */
function mentionOverlap(base: string, overlap: boolean): string {
  if (!overlap) {
    return base;
  }
  return $localize`:@@railJour.resume.chevauchement:${base}:ligne: — attention, deux vacations se chevauchent`;
}

/**
 * The shift Space opens the Siège panel on, the keyboard's way into it: the
 * line's first shift, or the one after the shift the panel is open on — a
 * line of three shifts is walked by pressing again. Null when the line holds
 * none.
 */
export function shiftToOpen(
  blocs: readonly RailBloc[],
  openSeatId: string | null,
): RailBloc | null {
  if (blocs.length === 0) {
    return null;
  }
  const current = blocs.findIndex((bloc) => bloc.posteId === openSeatId);
  return blocs[(current + 1) % blocs.length];
}

/** How many lines each status holds — the header's at-a-glance count. */
export function compterStatuts(lignes: RailLigne[]): {
  affectes: number;
  libres: number;
  indisponibles: number;
} {
  return {
    affectes: lignes.filter((ligne) => ligne.statut === 'affecte').length,
    libres: lignes.filter((ligne) => ligne.statut === 'libre').length,
    indisponibles: lignes.filter((ligne) => ligne.statut === 'indisponible').length,
  };
}
