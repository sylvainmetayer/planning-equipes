// Builders of the day "rail" (issue #305): one line per animateur of the
// edition, time on the x axis, vacations as positioned blocks — the dual of
// `calendar-day-page.ts`, which is organised by stand.
//
// Pure functions, kept out of the component so the geometry and the wording of
// a line are unit-tested without rendering 150 rows.

import { Animateur, ContrainteAdHoc, PosteAffectation } from '../../core/models';
import { endMinutesOfDay, formatDuration, formatHeure, formatHourTick, minutesOfDay } from '../../core/time-of-day';
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
  /** Windows a forced ad hoc unavailability covers this day; empty in the common case. */
  blocages: RailBlocage[];
  /** What a screen reader reads for the whole line — the rail itself is decorative. */
  resume: string;
}

/**
 * A window a recorded ad hoc exception keeps an animateur off, drawn hatched
 * behind the blocks — never a verdict computed here, only the fact as entered.
 */
export interface RailBlocage {
  heureDebut: string;
  heureFin: string;
  offsetPercent: number;
  widthPercent: number;
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

/** A time window of one event day, kept with its labels so nothing has to format minutes back. */
interface Fenetre {
  debutMinutes: number;
  finMinutes: number;
  heureDebut: string;
  heureFin: string;
}

/** What the percentages of one day's rail are measured against. */
interface Echelle {
  /** Whole-hour start of the rail. */
  debutMinutes: number;
  amplitude: number;
  /** Raw first-start/last-end of the day — what "covers the whole day" is measured against, never the rounded rail. */
  journee: Fenetre;
}

/** Overlapping or touching windows merged into one, ordered — so a coverage test is a single comparison. */
function fusionner(fenetres: Fenetre[]): Fenetre[] {
  const ordonnees = [...fenetres].sort((left, right) => left.debutMinutes - right.debutMinutes);
  const fusionnees: Fenetre[] = [];
  ordonnees.forEach((fenetre) => {
    const derniere = fusionnees[fusionnees.length - 1];
    if (derniere && fenetre.debutMinutes <= derniere.finMinutes) {
      if (fenetre.finMinutes > derniere.finMinutes) {
        derniere.finMinutes = fenetre.finMinutes;
        derniere.heureFin = fenetre.heureFin;
      }
      return;
    }
    fusionnees.push({ ...fenetre });
  });
  return fusionnees;
}

/**
 * Windows on which a recorded `INDISPONIBILITE_FORCEE` keeps an animateur off
 * the plan, by animateur id — the fact as it was entered, not a feasibility
 * verdict.
 *
 * Only the exceptions carrying **no stand** are read: with one, the fact says
 * "not on that stand", which forbids one seat and leaves the person perfectly
 * callable elsewhere on the same hours. Counting that as an unavailability
 * would hide exactly the person a day of tension is looking for. A `null`
 * créneau is the whole event, hence the whole day.
 */
function blocagesParAnimateur(
  contraintes: ContrainteAdHoc[],
  creneaux: Map<number, Fenetre>,
  journee: Fenetre
): Map<string, Fenetre[]> {
  const blocages = new Map<string, Fenetre[]>();
  contraintes.forEach((contrainte) => {
    if (contrainte.type !== 'INDISPONIBILITE_FORCEE' || contrainte.stand) {
      return;
    }
    const fenetre = contrainte.creneau ? creneaux.get(contrainte.creneau.id) : journee;
    if (!fenetre) {
      // A créneau of another day, or one no poste was generated for.
      return;
    }
    (contrainte.animateursConcernes ?? []).forEach((cible) => {
      blocages.set(cible.id, [...(blocages.get(cible.id) ?? []), fenetre]);
    });
  });
  return blocages;
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
    // Trimmed once here: every label downstream — block, summary, day span —
    // reads the same `09:00` rather than the API's `09:00:00`.
    heureDebut: formatHeure(heureDebut),
    heureFin: formatHeure(heureFin),
    debutMinutes: minutesOfDay(heureDebut),
    finMinutes: endMinutesOfDay(heureFin)
  };
}

/** Everything one event day contributes to its rail, gathered in a single pass over the postes. */
interface ContenuJour {
  date: string | null;
  spans: Span[];
  spansParAnimateur: Map<string, Span[]>;
  /** Hours of every créneau of the day, by id — what an ad hoc exception scoped to a créneau names. */
  creneaux: Map<number, Fenetre>;
}

/**
 * One entry per event day found in the plan, chronologically. `animateurs` is
 * the edition's referential: every one of them gets a line on every day,
 * assigned or not. `contraintes` are the plan's ad hoc exceptions, read only to
 * hatch the hours an animateur was recorded as unavailable on.
 */
export function buildRailJours(
  postes: PosteAffectation[],
  animateurs: Animateur[],
  contraintes: ContrainteAdHoc[] = []
): RailJour[] {
  const jours = new Map<number, ContenuJour>();
  postes.forEach((poste) => {
    const creneau = poste.creneau;
    if (!creneau) {
      return;
    }
    let jour = jours.get(creneau.jour);
    if (!jour) {
      jour = { date: creneau.date ?? null, spansParAnimateur: new Map(), spans: [], creneaux: new Map() };
      jours.set(creneau.jour, jour);
    }
    // The créneau's own hours, not the poste's possibly narrowed window: an ad
    // hoc exception names the créneau, so that is the window it covers.
    jour.creneaux.set(creneau.id, {
      debutMinutes: minutesOfDay(creneau.heureDebut),
      finMinutes: endMinutesOfDay(creneau.heureFin),
      heureDebut: formatHeure(creneau.heureDebut),
      heureFin: formatHeure(creneau.heureFin)
    });
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
    .map(([jour, contenu]) => buildRailJour(jour, contenu, contraintes, effectif, noms));
}

function buildRailJour(
  jour: number,
  contenu: ContenuJour,
  contraintes: ContrainteAdHoc[],
  animateurs: Animateur[],
  noms: Map<string, string>
): RailJour {
  const { date, spans: spansDuJour, spansParAnimateur } = contenu;
  const premier = spansDuJour.reduce((tot, span) => (span.debutMinutes < tot.debutMinutes ? span : tot));
  const dernier = spansDuJour.reduce((tard, span) => (span.finMinutes > tard.finMinutes ? span : tard));
  const journee: Fenetre = {
    debutMinutes: premier.debutMinutes,
    finMinutes: dernier.finMinutes,
    heureDebut: premier.heureDebut,
    heureFin: dernier.heureFin
  };
  // Rounded outwards to whole hours so the scale's ticks are evenly spaced and
  // the gridlines can be drawn by one repeating background.
  const debutMinutes = Math.floor(journee.debutMinutes / 60) * 60;
  const finMinutes = Math.max(Math.ceil(journee.finMinutes / 60) * 60, debutMinutes + 60);
  const amplitude = finMinutes - debutMinutes;
  const echelle: Echelle = { debutMinutes, amplitude, journee };
  const blocages = blocagesParAnimateur(contraintes, contenu.creneaux, journee);

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
        fusionner(blocages.get(animateur.id) ?? []),
        echelle
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
  fenetresBloquees: Fenetre[],
  echelle: Echelle
): RailLigne {
  const { debutMinutes, amplitude } = echelle;
  const blocages: RailBlocage[] = fenetresBloquees.map((fenetre) => ({
    heureDebut: fenetre.heureDebut,
    heureFin: fenetre.heureFin,
    offsetPercent: ((fenetre.debutMinutes - debutMinutes) / amplitude) * 100,
    widthPercent: ((fenetre.finMinutes - fenetre.debutMinutes) / amplitude) * 100,
    label: $localize`:@@railJour.blocage.tooltip:Indisponibilité saisie : ${fenetre.heureDebut}:debut: – ${fenetre.heureFin}:fin:`
  }));
  const plages = fenetresBloquees.map((fenetre) => `${fenetre.heureDebut} – ${fenetre.heureFin}`).join(' ; ');
  const journeeEntiere = fenetresBloquees.some(
    (fenetre) =>
      fenetre.debutMinutes <= echelle.journee.debutMinutes && fenetre.finMinutes >= echelle.journee.finMinutes
  );

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

  // Two ways of being unavailable, and they do not say the same thing: the
  // animateur declared the day off, or an exception was recorded against them.
  // A blockage covering only part of the day leaves them callable on the rest,
  // so it hatches the hours without taking them out of the mobilisable count.
  const declareIndisponible = date !== null && (animateur.joursIndisponibles ?? []).includes(date);
  const indisponible = declareIndisponible || journeeEntiere;
  const statut: RailStatut = blocs.length > 0 ? 'affecte' : indisponible ? 'indisponible' : 'libre';
  if (blocs.length === 0) {
    const base = declareIndisponible
      ? $localize`:@@railJour.resume.indisponible:${nom}:animateur: — déclaré indisponible ce jour-là`
      : journeeEntiere
        ? $localize`:@@railJour.resume.indisponibleForcee:${nom}:animateur: — indisponibilité saisie sur toute la journée`
        : $localize`:@@railJour.resume.libre:${nom}:animateur: — aucune vacation ce jour-là, mobilisable`;
    return {
      animateurId: animateur.id,
      nom,
      statut,
      blocs,
      blocages,
      dureeLabel: '',
      amplitudeDebut: null,
      amplitudeFin: null,
      chevauchement: false,
      resume: statut === 'indisponible' ? base : mentionnerBlocages(base, plages)
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
  const chevauchement = blocs.some((bloc) => bloc.chevauchement);
  const base = $localize`:@@railJour.resume.affecte:${nom}:animateur: — ${count}:count: vacation(s), ${dureeLabel}:duree:, de ${amplitudeDebut}:debut: à ${amplitudeFin}:fin: : ${detail}:detail:`;

  return {
    animateurId: animateur.id,
    nom,
    statut,
    blocs,
    blocages,
    dureeLabel,
    amplitudeDebut,
    amplitudeFin,
    chevauchement,
    // The red outline and the warning icon are visual only; a line read out
    // loud must say the one anomaly this view exists to make visible.
    resume: mentionnerChevauchement(mentionnerBlocages(base, plages), chevauchement)
  };
}

/** Appends the recorded unavailability windows to a line's summary, when there is any. */
function mentionnerBlocages(base: string, plages: string): string {
  if (!plages) {
    return base;
  }
  return $localize`:@@railJour.resume.avecBlocage:${base}:ligne: — indisponibilité saisie : ${plages}:plages:`;
}

/** Same, for the overlap the outline alone would only tell a sighted reader. */
function mentionnerChevauchement(base: string, chevauchement: boolean): string {
  if (!chevauchement) {
    return base;
  }
  return $localize`:@@railJour.resume.chevauchement:${base}:ligne: — attention, deux vacations se chevauchent`;
}

/** How many lines each status holds — the header's at-a-glance count. */
export function compterStatuts(lignes: RailLigne[]): { affectes: number; libres: number; indisponibles: number } {
  return {
    affectes: lignes.filter((ligne) => ligne.statut === 'affecte').length,
    libres: lignes.filter((ligne) => ligne.statut === 'libre').length,
    indisponibles: lignes.filter((ligne) => ligne.statut === 'indisponible').length
  };
}
