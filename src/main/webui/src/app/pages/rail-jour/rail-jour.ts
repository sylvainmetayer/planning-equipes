// Builders of the day "rail" (issue #305): one line per animateur of the
// edition, time on the x axis, vacations as positioned blocks — the dual of
// `calendar-day-page.ts`, which is organised by stand.
//
// Pure functions, kept out of the component so the geometry and the wording of
// a line are unit-tested without rendering 150 rows.

import { Animateur, PosteAffectation } from '../../core/models';
import { endMinutesOfDay, formatDuration, formatHourTick, minutesOfDay } from '../../core/time-of-day';
import { standTypologies, typologieColorClass, typologiePrincipale } from '../../core/typologie-colors';

/**
 * Why a line is empty, which is the whole point of showing empty lines: on a
 * day of tension, `libre` is the list of people who can still be called, and
 * `indisponible` is the list of people who cannot.
 */
export type RailStatut = 'affecte' | 'libre' | 'indisponible';

/** One vacation of one animateur, positioned within the day's rail. */
export interface RailBloc {
  posteId: string;
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
  /** What a screen reader reads for the whole line — the rail itself is decorative. */
  resume: string;
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
    bruts.set(animateur.id, `${animateur.prenom ?? ''} ${animateur.nom ?? ''}`.trim() || animateur.id)
  );
  const compte = new Map<string, number>();
  bruts.forEach((label) => compte.set(label, (compte.get(label) ?? 0) + 1));
  const libelle = new Map<string, string>();
  bruts.forEach((label, id) => libelle.set(id, (compte.get(label) ?? 0) > 1 ? `${label} (${id})` : label));
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
  standNom: string;
  typologie: string | null;
  colorClass: string;
  heureDebut: string;
  heureFin: string;
  debutMinutes: number;
  finMinutes: number;
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
    standNom,
    typologie,
    colorClass: typologieColorClass(typologie),
    heureDebut,
    heureFin,
    debutMinutes: minutesOfDay(heureDebut),
    finMinutes: endMinutesOfDay(heureFin)
  };
}

/**
 * One entry per event day found in the plan, chronologically. `animateurs` is
 * the edition's referential: every one of them gets a line on every day,
 * assigned or not.
 */
export function buildRailJours(postes: PosteAffectation[], animateurs: Animateur[]): RailJour[] {
  const jours = new Map<number, { date: string | null; spansParAnimateur: Map<string, Span[]>; spans: Span[] }>();
  postes.forEach((poste) => {
    const creneau = poste.creneau;
    if (!creneau) {
      return;
    }
    let jour = jours.get(creneau.jour);
    if (!jour) {
      jour = { date: creneau.date ?? null, spansParAnimateur: new Map(), spans: [] };
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
    .map(([jour, contenu]) => buildRailJour(jour, contenu.date, contenu.spans, contenu.spansParAnimateur, effectif, noms));
}

function buildRailJour(
  jour: number,
  date: string | null,
  spansDuJour: Span[],
  spansParAnimateur: Map<string, Span[]>,
  animateurs: Animateur[],
  noms: Map<string, string>
): RailJour {
  // Rounded outwards to whole hours so the scale's ticks are evenly spaced and
  // the gridlines can be drawn by one repeating background.
  const debutMinutes = Math.floor(Math.min(...spansDuJour.map((span) => span.debutMinutes)) / 60) * 60;
  const finBrute = Math.max(...spansDuJour.map((span) => span.finMinutes));
  const finMinutes = Math.max(Math.ceil(finBrute / 60) * 60, debutMinutes + 60);
  const amplitude = finMinutes - debutMinutes;

  const heures: RailHeure[] = [];
  for (let minutes = debutMinutes; minutes <= finMinutes; minutes += 60) {
    heures.push({
      minutes,
      label: formatHourTick(minutes / 60),
      offsetPercent: ((minutes - debutMinutes) / amplitude) * 100
    });
  }

  const lignes = animateurs
    .map((animateur) =>
      buildRailLigne(
        animateur,
        noms.get(animateur.id) ?? animateur.id,
        date,
        spansParAnimateur.get(animateur.id) ?? [],
        debutMinutes,
        amplitude
      )
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
    lignes
  };
}

function buildRailLigne(
  animateur: Animateur,
  nom: string,
  date: string | null,
  spans: Span[],
  debutMinutes: number,
  amplitude: number
): RailLigne {
  const ordonnes = [...spans].sort((left, right) => left.debutMinutes - right.debutMinutes);
  let finPrecedente = -1;
  const blocs: RailBloc[] = ordonnes.map((span) => {
    const chevauchement = span.debutMinutes < finPrecedente;
    finPrecedente = Math.max(finPrecedente, span.finMinutes);
    return {
      posteId: span.posteId,
      standNom: span.standNom,
      heureDebut: span.heureDebut,
      heureFin: span.heureFin,
      offsetPercent: ((span.debutMinutes - debutMinutes) / amplitude) * 100,
      widthPercent: ((span.finMinutes - span.debutMinutes) / amplitude) * 100,
      typologie: span.typologie,
      colorClass: span.colorClass,
      chevauchement,
      label: `${span.standNom} · ${span.heureDebut} – ${span.heureFin}`
    };
  });

  const indisponible = date !== null && (animateur.joursIndisponibles ?? []).includes(date);
  const statut: RailStatut = blocs.length > 0 ? 'affecte' : indisponible ? 'indisponible' : 'libre';
  if (blocs.length === 0) {
    return {
      animateurId: animateur.id,
      nom,
      statut,
      blocs,
      dureeLabel: '',
      amplitudeDebut: null,
      amplitudeFin: null,
      chevauchement: false,
      resume:
        statut === 'indisponible'
          ? $localize`:@@railJour.resume.indisponible:${nom}:animateur: — déclaré indisponible ce jour-là`
          : $localize`:@@railJour.resume.libre:${nom}:animateur: — aucune vacation ce jour-là, mobilisable`
    };
  }

  const minutesTravaillees = ordonnes.reduce((total, span) => total + (span.finMinutes - span.debutMinutes), 0);
  const dureeLabel = formatDuration(minutesTravaillees);
  const amplitudeDebut = ordonnes[0].heureDebut;
  const amplitudeFin = ordonnes.reduce((derniere, span) =>
    span.finMinutes >= derniere.finMinutes ? span : derniere
  ).heureFin;
  const detail = blocs.map((bloc) => bloc.label).join(' ; ');
  const count = blocs.length;

  return {
    animateurId: animateur.id,
    nom,
    statut,
    blocs,
    dureeLabel,
    amplitudeDebut,
    amplitudeFin,
    chevauchement: blocs.some((bloc) => bloc.chevauchement),
    resume: $localize`:@@railJour.resume.affecte:${nom}:animateur: — ${count}:count: vacation(s), ${dureeLabel}:duree:, de ${amplitudeDebut}:debut: à ${amplitudeFin}:fin: : ${detail}:detail:`
  };
}

/** How many lines each status holds — the header's at-a-glance count. */
export function compterStatuts(lignes: RailLigne[]): { affectes: number; libres: number; indisponibles: number } {
  return {
    affectes: lignes.filter((ligne) => ligne.statut === 'affecte').length,
    libres: lignes.filter((ligne) => ligne.statut === 'libre').length,
    indisponibles: lignes.filter((ligne) => ligne.statut === 'indisponible').length
  };
}
